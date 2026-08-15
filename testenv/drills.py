#!/usr/bin/env python3
"""Operational drills against a running Wake server.

Forces a database outage and confirms the journal replays, and checks that a change reaches the
other backend. Every step prints what it did and what it saw.

    python testenv/drills.py            # boot + reloads + outage
    python testenv/drills.py --sync     # also the cross-server drill (needs the mariadb stack)
    python testenv/drills.py --lifecycle  # also the plugin lifecycle -- STOPS THE SERVER, run it last

`--lifecycle` covers what only a boot and a shutdown can show, so it ends the session: it stops the
running server over RCON and reads its shutdown back, then boots two more servers of its own from the
jar run-paper already downloaded -- one whose database never answers, to watch Wake disable itself
against a half-built object, and one on the restored config, to prove the first left nothing behind.
Start `./gradlew runServer` again afterwards. It needs a current `./gradlew shadowJar`, since it adds
the same `build/libs` jar runServer does.

Needs a server up with RCON (./gradlew runServer). Exits non-zero if a drill fails.
"""

import argparse
import io
import json
import os
import re
import socket
import sqlite3
import struct
import subprocess
import sys
import threading
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Optional

if hasattr(sys.stdout, "buffer"):
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parents[1]
# drills_versions.py points this at testenv/matrix/<version>, so every path below follows whichever
# server is under drill rather than the one runServer happens to own
RUN = Path(os.environ.get("WAKE_RUN") or ROOT / "run")
WAKE = RUN / "plugins" / "wake"
LOG = RUN / "logs" / "latest.log"
JOURNAL = WAKE / "outage-journal.jsonl"
SERVER_JARS = Path.home() / ".gradle" / "caches" / "run-task-jars" / "paper" / "jars"
PLUGIN_JARS = ROOT / "build" / "libs"
# trailing §x: rcon-cli rewrites the six hex digits as ANSI escapes but leaves the marker behind
CODES = re.compile(r"§x(?:§[0-9a-fA-F]){6}|§[0-9a-fk-orA-FK-OR]|\x1b\[[0-9;]*m|§x")
STATUS = re.compile(r"Status:\s*(\w+)")
# a stack trace carries no plugin tag of its own, so the frames are read as part of whatever logged them
TRACE = re.compile(r"^(?:\s+at |\s*Caused by:|[\w.$]+(?:Exception|Error)(?::|$))")
# the console writes "[12:00:00 ERROR]:" where the log file writes "[Server thread/ERROR]:"
LEVEL = re.compile(r"[/ ](ERROR|WARN)\]")
OUTCOMES = [
    ("enabled", re.compile(r"(?<!Dis)Enabled module: (\w+)")),
    ("disabled", re.compile(r"Disabled module: (\w+)")),
    ("reloaded", re.compile(r"Reloaded module: (\w+)")),
    ("incompatible", re.compile(r"incompatible: (\w+)")),
    ("failed", re.compile(r"Failed to sync module: (\w+)")),
]

failures = []


def step(text):
    print(f"  {text}")


def ok(text):
    print(f"  ok    {text}")


def bad(text):
    print(f"  FAIL  {text}")
    failures.append(text)


class Rcon:
    def __init__(self, host, port, password):
        self.sock = socket.create_connection((host, port), timeout=20)
        self.sock.settimeout(20)
        self.id = 0
        if self._send(3, password) is None:
            sys.exit("RCON authentication failed")

    def _send(self, kind, body):
        self.id += 1
        payload = struct.pack("<ii", self.id, kind) + body.encode() + b"\x00\x00"
        self.sock.sendall(struct.pack("<i", len(payload)) + payload)
        size = struct.unpack("<i", self._read(4))[0]
        data = self._read(size)
        return None if struct.unpack("<i", data[:4])[0] == -1 else data[8:-2].decode("utf-8", "replace")

    def _read(self, count):
        buf = b""
        while len(buf) < count:
            chunk = self.sock.recv(count - len(buf))
            if not chunk:
                sys.exit("RCON connection closed")
            buf += chunk
        return buf

    def raw(self, command):
        """The reply with the colour codes left in -- the only place a rendered colour is visible."""
        return self._send(2, command) or ""

    def run(self, command):
        return CODES.sub("", self.raw(command))


class Log:
    def __init__(self):
        self.mark = LOG.stat().st_size if LOG.is_file() else 0

    def reset(self):
        self.mark = LOG.stat().st_size if LOG.is_file() else 0

    def read(self):
        if not LOG.is_file():
            return ""
        with LOG.open(encoding="utf-8", errors="replace") as handle:
            handle.seek(min(self.mark, LOG.stat().st_size))
            text = handle.read()
        return CODES.sub("", text)

    def await_line(self, needle, timeout):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if needle in self.read():
                return True
            time.sleep(0.5)
        return False


def await_file(path, present, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if path.is_file() == present:
            return True
        time.sleep(0.5)
    return False


def state(key, mariadb=None):
    """Reads a state row from whichever backend the server is actually writing to."""
    if mariadb:
        container, user, password, database = mariadb
        value = docker("exec", container, "mariadb", f"-u{user}", f"-p{password}", database, "-N", "-B",
                       "-e", f"SELECT state_value FROM wake_state WHERE state_key = '{key}'").strip()
        return value.strip('"') if value else None
    uri = "file:" + (WAKE / "wake.db").resolve().as_posix() + "?mode=ro"
    connection = sqlite3.connect(uri, uri=True, timeout=5)
    try:
        row = connection.execute("SELECT state_value FROM wake_state WHERE state_key = ?", (key,)).fetchone()
    finally:
        connection.close()
    return row[0].strip('"') if row else None


def write_state_raw(key, raw_value, mariadb=None):
    """Puts raw text into wake_state behind the server's back, or drops the row when raw_value is None."""
    if mariadb:
        container, user, password, database = mariadb
        sql = (f"DELETE FROM wake_state WHERE state_key = '{key}'" if raw_value is None
               else f"REPLACE INTO wake_state (state_key, state_value) VALUES ('{key}', '{raw_value}')")
        docker("exec", container, "mariadb", f"-u{user}", f"-p{password}", database, "-N", "-B", "-e", sql)
        return
    connection = sqlite3.connect(str(WAKE / "wake.db"), timeout=10)
    try:
        if raw_value is None:
            connection.execute("DELETE FROM wake_state WHERE state_key = ?", (key,))
        else:
            connection.execute("REPLACE INTO wake_state (state_key, state_value) VALUES (?, ?)", (key, raw_value))
        connection.commit()
    finally:
        connection.close()


def schema_version(module, mariadb=None):
    """The schema version stamped for a module, or None when it has never been stamped."""
    if mariadb:
        container, user, password, database = mariadb
        value = docker("exec", container, "mariadb", f"-u{user}", f"-p{password}", database, "-N", "-B",
                       "-e", f"SELECT version FROM wake_schema_version WHERE module = '{module}'").strip()
        return int(value) if value else None
    uri = "file:" + (WAKE / "wake.db").resolve().as_posix() + "?mode=ro"
    connection = sqlite3.connect(uri, uri=True, timeout=5)
    try:
        row = connection.execute("SELECT version FROM wake_schema_version WHERE module = ?", (module,)).fetchone()
    finally:
        connection.close()
    return row[0] if row else None


def write_schema_version(module, version, mariadb=None):
    """Stamps a schema version behind the server's back: a DAO that reads one it cannot support throws."""
    if mariadb:
        container, user, password, database = mariadb
        docker("exec", container, "mariadb", f"-u{user}", f"-p{password}", database, "-N", "-B",
               "-e", f"REPLACE INTO wake_schema_version (module, version) VALUES ('{module}', {version})")
        return
    connection = sqlite3.connect(str(WAKE / "wake.db"), timeout=10)
    try:
        connection.execute("REPLACE INTO wake_schema_version (module, version) VALUES (?, ?)", (module, version))
        connection.commit()
    finally:
        connection.close()


def set_module_enabled(module, enabled):
    """Flips `modules.<module>.enabled` in the live config, which is what a `/wake reload` acts on."""
    config = WAKE / "config.yml"
    text = config.read_text(encoding="utf-8")
    swapped, count = re.subn(rf"(?ms)(^  {module}:\n    enabled: )(?:true|false)",
                             rf"\g<1>{'true' if enabled else 'false'}", text, count=1)
    if count != 1:
        raise RuntimeError(f"could not find modules.{module}.enabled in {config}")
    config.write_text(swapped, encoding="utf-8")


def switch(text):
    """The Status: line a listing prints, which is read out of the cache rather than the table."""
    found = STATUS.search(CODES.sub("", text))
    return found.group(1).lower() if found else None


def reload_outcomes(rcon: Rcon):
    """What /wake reload reported for each module, as a list per module so a doubled line shows."""
    reply = rcon.run("wake reload")
    seen = {}
    for name, pattern in OUTCOMES:
        for module in pattern.findall(reply):
            seen.setdefault(module, []).append(name)
    return seen


def state_keys(prefix, mariadb=None):
    """Every state key under `prefix`, out of whichever backend the server is writing to."""
    if mariadb:
        container, user, password, database = mariadb
        out = docker("exec", container, "mariadb", f"-u{user}", f"-p{password}", database, "-N", "-B",
                     "-e", f"SELECT state_key FROM wake_state WHERE state_key LIKE '{prefix}%'")
        return sorted(line.strip() for line in out.splitlines() if line.strip())
    uri = "file:" + (WAKE / "wake.db").resolve().as_posix() + "?mode=ro"
    connection = sqlite3.connect(uri, uri=True, timeout=5)
    try:
        rows = connection.execute("SELECT state_key FROM wake_state WHERE state_key LIKE ?", (prefix + "%",)).fetchall()
    finally:
        connection.close()
    return sorted(row[0] for row in rows)


def docker(*args):
    # rcon-cli echoes section-sign colour codes: decoding with the console default fails on Windows
    result = subprocess.run([os.environ.get("DOCKER", "docker"), *args], capture_output=True,
                            text=True, encoding="utf-8", errors="replace", timeout=120)
    if result.returncode != 0:
        raise RuntimeError(f"docker {' '.join(args)}: {(result.stderr or result.stdout).strip()}")
    return result.stdout


def wake_errors(text, level=None):
    """Wake's own ERROR/WARN lines and the stack traces under them, in log order."""
    found = []
    tagged = False
    for line in text.splitlines():
        if "[wake]" in line:
            seen = LEVEL.search(line)
            tagged = bool(seen) and (level is None or seen.group(1) == level)
            if tagged:
                found.append(line.strip())
        elif tagged and TRACE.match(line):
            found.append(line.strip())
        elif line.strip():
            tagged = False
    return found


def port_answers(port, host="127.0.0.1"):
    try:
        socket.create_connection((host, port), timeout=2).close()
        return True
    except OSError:
        return False


def await_port(port, answering, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if port_answers(port) == answering:
            return True
        time.sleep(1)
    return False


def server_jar():
    """The paper jar run-paper downloaded for the version this run directory last booted, newest build.

    Never simply the newest cached: the matrix tasks fill that cache with every version Wake claims, and
    booting a 1.21 world on the 26.2 jar upgrades the world instead of drilling the lifecycle.
    """
    booted = [home for home in (RUN / "versions").glob("*") if home.is_dir()]
    version = max(booted, key=lambda home: home.stat().st_mtime).name if booted else None
    builds = [jar for jar in SERVER_JARS.glob(f"{version}/*.jar") if jar.stem.isdigit()] if version else []
    return max(builds, key=lambda jar: int(jar.stem)) if builds else None


def plugin_jar():
    """The shadow jar runServer adds, which is what a lifecycle drill has to boot."""
    built = [jar for jar in PLUGIN_JARS.glob("wake-*.jar") if not jar.name.endswith("-sources.jar")]
    return max(built, key=lambda jar: jar.stat().st_mtime) if built else None


class Headless:
    """A server of the drill's own, booted from the jar rather than through gradle, and driven over its console."""

    def __init__(self, jar, plugin):
        self.lines = []
        self.process = subprocess.Popen(
            ["java", "-Xms1G", "-Xmx2G", "-jar", str(jar), "--nogui", f"-add-plugin={plugin}"],
            cwd=str(RUN), stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace", bufsize=1)
        self.reader = threading.Thread(target=self._pump, daemon=True)
        self.reader.start()

    def _pump(self):
        for line in self.process.stdout:
            self.lines.append(CODES.sub("", line.rstrip("\r\n")))

    def log(self):
        return "\n".join(self.lines)

    def await_line(self, needle, timeout):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if needle in self.log():
                return True
            if self.process.poll() is not None:
                return needle in self.log()
            time.sleep(0.5)
        return False

    def stop(self, timeout=180):
        """`stop` down the console, never a kill: a killed JVM proves nothing about what Wake wound down."""
        if self.process.poll() is None:
            try:
                self.process.stdin.write("stop\n")
                self.process.stdin.flush()
            except OSError:
                pass
        try:
            self.process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            self.process.kill()
            self.process.wait(timeout=30)
            return False
        self.reader.join(timeout=10)
        return True


@contextmanager
def outage(mariadb: Optional[tuple]):
    """Cuts the database off for the body: stops the container, or holds the sqlite write lock."""
    holder = None
    if mariadb:
        step(f"stopping {mariadb[0]}")
        docker("stop", mariadb[0])
    else:
        # SQLite has no service to stop: hold the write lock so the plugin's writes fail busy
        step("taking the sqlite write lock")
        holder = sqlite3.connect(str(WAKE / "wake.db"), timeout=5, isolation_level=None)
        holder.execute("BEGIN IMMEDIATE")
    try:
        yield
    finally:
        if holder is None:
            step(f"starting {mariadb[0]}")
            docker("start", mariadb[0])
        else:
            step("releasing the sqlite write lock")
            holder.execute("ROLLBACK")
            holder.close()


def detect_backend(container) -> Optional[tuple]:
    """The mariadb connection the server is configured for, or None when it is on sqlite."""
    config = (WAKE / "config.yml").read_text(encoding="utf-8")
    block = re.search(r"(?ms)^database:\n(?:[ \t]+.*\n?)*", config)
    # match the setting, not the comment listing the options next to it
    if not block or not re.search(r"""(?m)^\s*type:\s*["']?(mariadb|mysql)""", block.group(0)):
        return None

    def field(name, fallback):
        found = re.search(rf"""(?m)^\s+{name}:\s*["']?([^"'#\r\n]+)""", block.group(0))
        return found.group(1).strip() if found else fallback

    return container, field("username", "root"), field("password", "password"), field("database", "wake")


def drill_boot(rcon: Rcon):
    print("\nboot")
    text = LOG.read_text(encoding="utf-8", errors="replace")
    # a drill run earlier in the same server session is not boot: reloads re-enable modules and the drills
    # that feed the layer bad input log about it on purpose
    done = text.find("]: Done (")
    if done != -1:
        text = text[:text.index("\n", done) + 1]
    if "Database ready" in text:
        ok("database came up")
    else:
        bad("no 'Database ready' line in the log")

    enabled = re.findall(r"Module '(\w+)' has been enabled", text)
    if enabled:
        ok(f"modules enabled: {', '.join(enabled)}")
    else:
        bad("no module reported enabling")

    errors = [line for line in text.splitlines()
              if "[wake]" in line and ("SEVERE" in line or "Exception" in line)]
    if errors:
        bad(f"wake logged {len(errors)} error(s) at boot, first: {errors[0].strip()}")
    else:
        ok("no wake errors or stack traces")

    # Bukkit warns at registration for a listener on a deprecated event. EntityRemoveEvent is one Wake carries
    # knowingly -- 1.21 through 1.21.3 deprecated it and 1.21.4 took that back -- so only the rest is news
    warned = [line for line in text.splitlines() if "[wake]" in line and "is Deprecated" in line
              and "EntityRemoveEvent" not in line]
    if warned:
        bad(f"a listener registered on a deprecated event: {warned[0].strip()[:150]}")
    else:
        ok("no listener on an event this version deprecated, beyond the one Wake carries")

    if "Unknown or incomplete" in rcon.run("wake help"):
        bad("/wake help did not resolve")
    else:
        ok("commands respond over RCON")


def drill_repeated_reload(rcon: Rcon, log: Log):
    """`/wake reload` has to be able to run all day.

    Every step of it reads what the one before settled -- the file, the language, the caches, then the
    modules -- so a reload that half-applies shows as an outcome changing between rounds. What the
    console cannot see is the registration side, and that is where a reload would leak: Paper is asked
    for the listener count instead, because a re-registered listener answers every event twice.
    """
    print("\nrepeated reload")
    tick = "com.destroystokyo.paper.event.server.ServerTickStartEvent"

    def clock_registrations():
        return rcon.run(f"paper dumplisteners {tick}").count("TickClock")

    log.reset()
    before = clock_registrations()
    if before != 1:
        bad(f"the tick clock is on {tick.rsplit('.', 1)[1]} {before} time(s) before the drill starts")
        return
    rounds = [reload_outcomes(rcon) for _ in range(5)]
    step(f"reloaded 5 times, first round said {rounds[0]}")
    drifted = [index for index, outcome in enumerate(rounds, 1) if outcome != rounds[0]]
    if drifted:
        bad(f"round(s) {drifted} answered differently: {[rounds[index - 1] for index in drifted]}")
    elif any(len(names) != 1 for names in rounds[0].values()):
        bad(f"a module was named more than once in one reload: {rounds[0]}")
    else:
        ok(f"every round named {', '.join(sorted(rounds[0]))} exactly once, with the same outcome")

    after = clock_registrations()
    if after == before:
        ok(f"the core listener is still registered once, not {before} + 5")
    else:
        bad(f"the tick clock is registered {after} time(s) after five reloads, was {before}")

    noise = wake_errors(log.read())
    if noise:
        bad(f"a reload logged {len(noise)} error line(s), first: {noise[0][:140]}")
    else:
        ok("no reload logged an error")

    if "Unknown or incomplete" in rcon.run("wake help"):
        bad("the command tree stopped resolving after the reloads")
    else:
        ok("the command tree still resolves")


def drill_outage(rcon: Rcon, log: Log, mariadb: Optional[tuple]):
    """Cuts the database off, confirms writes are journaled, restores it, confirms they replay."""
    print("\ndatabase outage")
    original = state("core.show_hints", mariadb) or "true"
    target = "false" if original == "true" else "true"
    log.reset()

    with outage(mariadb):
        step(f"changing a setting while the database is unreachable (hints -> {target})")
        rcon.run(f"wake hints {target}")
        if await_file(JOURNAL, True, 45):
            lines = [ln for ln in JOURNAL.read_text(encoding="utf-8").splitlines() if ln.strip()]
            try:
                groups = [json.loads(ln).get("s") for ln in lines]
            except ValueError:
                groups = []
            if groups and all(group and all("q" in statement for statement in group) for group in groups):
                ok(f"journaled {len(lines)} write(s), one line each with its statements")
            else:
                bad("a journal line is not a write with the statements it queued")
        else:
            bad("no outage journal appeared within 45s")

        # a write of several statements is one line or it is a torn transaction: journal a rename's shape
        # by hand, because nothing the console can reach queues more than one statement
        step("appending a two-statement write by hand")
        with JOURNAL.open("a", encoding="utf-8") as out:
            out.write(json.dumps({"s": [
                {"q": "REPLACE INTO wake_state (state_key, state_value) VALUES (?, ?)",
                 "p": [{"t": "s", "v": "core.journal_pair_a"}, {"t": "s", "v": '"one"'}]},
                {"q": "REPLACE INTO wake_state (state_key, state_value) VALUES (?, ?)",
                 "p": [{"t": "s", "v": "core.journal_pair_b"}, {"t": "s", "v": '"two"'}]}]}) + "\n")

        # a reload reads the state table back before the modules re-derive from it, and that read must not
        # sit on a database that is not answering: an admin reloading during an outage would hang the server
        step("reloading while it is still unreachable")
        started = time.monotonic()
        reply = rcon.run("wake reload")
        took = time.monotonic() - started
        if took < 5 and "Reloaded configuration" in reply:
            ok(f"the reload answered without waiting on the database ({took:.1f}s)")
        else:
            bad(f"reload took {took:.1f}s and answered {reply.strip()[:120]!r}")

    if log.await_line("Database recovered", 90):
        ok("recovery detected and journal replayed")
    else:
        bad("no 'Database recovered' line within 90s")

    if await_file(JOURNAL, False, 30):
        ok("journal deleted after replay")
    else:
        bad("journal still on disk after replay")

    time.sleep(2)
    if state("core.show_hints", mariadb) == target:
        ok(f"replayed value landed in the database ({target})")
    else:
        bad(f"database holds {state('core.show_hints', mariadb)!r}, expected {target!r}")

    pair = (state("core.journal_pair_a", mariadb), state("core.journal_pair_b", mariadb))
    if pair == ("one", "two"):
        ok("the two-statement write replayed whole")
    else:
        bad(f"the two-statement write replayed as {pair}, expected ('one', 'two')")
    write_state_raw("core.journal_pair_a", None, mariadb)
    write_state_raw("core.journal_pair_b", None, mariadb)

    rcon.run(f"wake hints {original}")


def drill_sync(rcon: Rcon, log: Log, backend: str):
    """A change on this server must reach the other backend over the pub-sub bus."""
    print("\ncross-server sync")
    try:
        # rcon-cli exits 0 even for a command the server does not have, so the reply is what has to be read:
        # a backend whose Wake failed to enable would otherwise be reported as a broken sync bus
        probe = CODES.sub("", docker("exec", backend, "rcon-cli", "wake help"))
    except RuntimeError as error:
        bad(str(error))
        return
    if "Unknown or incomplete" in probe or not probe.strip():
        bad(f"{backend} has no Wake command tree, so there is nothing to sync to. Its plugin did not enable "
            f"-- `docker logs {backend}` will say why (a database that was not accepting connections when the "
            f"container started is the usual cause; `docker restart {backend}` fixes that one)")
        return

    def remote_switch():
        return switch(docker("exec", backend, "rcon-cli", "dd boostpad list"))

    def await_remote(expected, timeout=30):
        """Polls rather than sleeping a fixed span: propagation is normally under a second, but the drill that runs
        before this one restarts the database container, and the first write afterwards waits on a fresh pool
        connection. A fixed sleep turns that into a false failure."""
        deadline = time.monotonic() + timeout
        seen = remote_switch()
        while seen != expected and time.monotonic() < deadline:
            time.sleep(1)
            seen = remote_switch()
        return seen

    # compare against what the switch was, not an assumed starting position
    before = remote_switch()
    step(f"toggling a setting on the primary (both backends read {before})")
    log.reset()
    rcon.run("dd boostpad toggle")
    local = switch(rcon.run("dd boostpad list"))
    remote = await_remote(local)
    if local and local != before and remote == local:
        ok(f"the other backend observed the change ({before} -> {remote})")
    else:
        bad(f"the other backend reports {remote!r}, the primary reports {local!r} (was {before!r})")
    rcon.run("dd boostpad toggle")
    # settle before the next step, so a run of this drill always starts from an agreed position
    await_remote(switch(rcon.run("dd boostpad list")))

    step("stopping the sync bus")
    docker("stop", "wake-testenv-valkey-1")
    try:
        time.sleep(5)
        if "Unknown or incomplete" in rcon.run("wake help"):
            bad("the server stopped answering with the bus down")
        else:
            ok("both servers keep running with the bus down")
    finally:
        step("starting the sync bus")
        docker("start", "wake-testenv-valkey-1")

    if log.await_line("resync", 60):
        ok("subscriber reconnected and resynced")
    else:
        bad("no resync line within 60s of the bus returning")

    # the resync line only says the subscriber came back. What proves the bus works again is the next change
    # riding it -- a publish connection left broken by the outage would still let the resync line print
    before = remote_switch()
    step(f"changing a setting again now the bus is back (both backends read {before})")
    rcon.run("dd boostpad toggle")
    local = switch(rcon.run("dd boostpad list"))
    remote = await_remote(local, 60)
    if local and local != before and remote == local:
        ok(f"the change after the resync reaches the other backend too ({before} -> {remote})")
    else:
        bad(f"after the resync the other backend reports {remote!r}, the primary reports {local!r} (was {before!r})")
    rcon.run("dd boostpad toggle")
    await_remote(switch(rcon.run("dd boostpad list")), 60)


def drill_boot_replay(rcon: Rcon, log: Log, backend: str, mariadb: tuple):
    """A journal replayed at boot has to reach the other server as well.

    Nothing announced the writes the journal holds, because they never reached the table. The peer's own
    recovery reads that table back, and at that point it still says what it said before the outage -- so
    unless the replaying server announces what it just pushed in, the peer stays wrong indefinitely.
    """
    print("\nboot replay reaches the other server")
    container = mariadb[0]

    def remote_switch():
        return switch(docker("exec", backend, "rcon-cli", "dd boostpad list"))

    def await_local(target, timeout):
        deadline = time.monotonic() + timeout
        current = switch(rcon.run("dd boostpad list"))
        while current != target and time.monotonic() < deadline:
            time.sleep(2)
            current = switch(rcon.run("dd boostpad list"))
        return current

    before = switch(rcon.run("dd boostpad list"))
    if before is None or remote_switch() != before:
        bad(f"the servers disagree before the drill starts ({before!r} vs {remote_switch()!r})")
        return
    hints = state("core.show_hints", mariadb) or "true"

    step(f"stopping {container}")
    docker("stop", container)
    stopped_backend = False
    try:
        # the primary has to journal something of its own, or it never notices the outage and never recovers
        rcon.run(f"wake hints {'false' if hints == 'true' else 'true'}")
        step("flipping the switch on the other server, where it can only reach its journal")
        docker("exec", backend, "rcon-cli", "dd boostpad toggle")
        time.sleep(3)
        expected = remote_switch()
        if expected == before:
            bad("the other server did not flip its own cache while the database was down")
            return
        step(f"stopping {backend} with that write still in its journal")
        docker("stop", backend)
        stopped_backend = True
    finally:
        step(f"starting {container}")
        docker("start", container)
        if not stopped_backend:
            docker("start", backend)

    if not log.await_line("Database recovered", 120):
        bad("the primary never recovered, so the rest of this drill would prove nothing")
        return
    time.sleep(3)
    settled = switch(rcon.run("dd boostpad list"))
    if settled == before:
        ok(f"the primary read the table back and still holds the pre-outage value ({before})")
    else:
        bad(f"the primary reports {settled!r} before the replay, expected {before!r}")

    step(f"starting {backend}, which replays its journal before anything else")
    docker("start", backend)
    seen = await_local(expected, 240)
    if seen == expected:
        ok(f"the boot replay was announced and the primary took it ({before} -> {expected})")
    else:
        bad(f"the primary still reports {seen!r} after the replay, expected {expected!r}")

    rcon.run(f"wake hints {hints}")
    if switch(rcon.run("dd boostpad list")) != before:
        rcon.run("dd boostpad toggle")


def drill_clean_stop(rcon: Rcon, host, port):
    """`stop` over RCON: everything Wake registered has to come back off before the JVM does.

    A leak has no line of its own, so it is read off what comes after the teardown. A task or listener
    that outlived the disable either logs past Wake's own last line or trips Bukkit's refusal to serve a
    disabled plugin, and a writer thread that never drained says so itself.
    """
    print("\nclean stop")
    step("queueing a burst of writes, then stopping the server over RCON in the same breath")
    for _ in range(10):
        rcon.run("wake hints true")
        rcon.run("wake hints false")
    try:
        rcon.run("stop")
    except (OSError, SystemExit):
        pass  # the server is entitled to drop the connection rather than answer
    if not await_port(port, False, 240):
        bad("the server was still answering 240s after stop")
        return False
    ok("the server process let go of its port")

    whole = CODES.sub("", LOG.read_text(encoding="utf-8", errors="replace"))
    if "Stopping server" not in whole:
        bad("no 'Stopping server' line in the log, so the shutdown is not the one being read")
        return False
    tail = whole[whole.rindex("Stopping server"):]

    if "Disabling wake" in tail and "Wake has been disabled" in tail:
        ok("the disable ran through to its last line")
    else:
        bad("the log has no complete Wake teardown between 'Stopping server' and the end")
        return False

    noise = wake_errors(tail)
    if noise:
        bad(f"the teardown logged {len(noise)} error line(s), first: {noise[0][:140]}")
    else:
        ok("nothing failed on the way down")

    after = [line for line in tail[tail.index("Wake has been disabled"):].splitlines()[1:] if "[wake]" in line]
    if after:
        bad(f"{len(after)} wake line(s) after its own teardown finished, first: {after[0][:140]}")
    else:
        ok("nothing of Wake's ran once it said it was done")

    refused = [line for line in tail.splitlines()
               if "IllegalPluginAccess" in line or "while not enabled" in line or "attempted to register" in line]
    if refused:
        bad(f"something of Wake's outlived the disable and Bukkit refused it: {refused[0][:140]}")
    elif "Timed out flushing pending database writes" in tail:
        bad("the writer thread did not drain within its shutdown window")
    elif "queued after the database was shut down" in tail:
        bad("a write was queued behind the pool closing, so the burst outlived the layer that had to take it")
    else:
        ok("no task, listener or write was left behind, and every write in the burst landed before the pool closed")
    return True


def boot_headless(jar, plugin, why, timeout=300):
    """Boots a server of the drill's own and answers with it once it is up, or None."""
    server = Headless(jar, plugin)
    if server.await_line("Done (", timeout):
        return server
    bad(f"the server booted {why} never finished starting within {timeout}s")
    server.stop()
    return None


def half_built_teardown(text):
    """What a disable running against a half-built plugin owes, whatever stage it failed at."""
    if "NullPointerException" in text:
        frame = next(line for line in text.splitlines() if "NullPointerException" in line)
        bad(f"the half-built teardown dereferenced something: {frame.strip()[:140]}")
    elif "Error occurred while disabling" in text:
        bad("Bukkit reported an error out of onDisable")
    else:
        ok("the teardown against a half-built plugin threw nothing")

    if "Wake has been disabled" in text:
        ok("the teardown still ran to its last line")
    else:
        bad("Wake never logged that it finished disabling")

    if "has been enabled" in text:
        bad("a module enabled even though the boot had already failed")
    else:
        ok("no module was enabled behind the failure")

    # a disable that lands mid-onEnable has to be the end of it. Bukkit closes the plugin's classloader
    # on the way out, so a boot that carries on dies on the first class it has not loaded yet -- read off
    # anything Wake logs past its own teardown, a registration Bukkit refuses, or an enable error behind it
    after = text[text.index("Wake has been disabled"):].splitlines()[1:] if "Wake has been disabled" in text else []
    carried_on = [line for line in after if "[wake]" in line or "IllegalPluginAccess" in line
                  or "Error occurred while enabling" in line]
    if carried_on:
        bad(f"the boot carried on after Wake was disabled underneath it: {carried_on[0][:140]}")
    else:
        ok("the teardown was the end of the boot, so onEnable did not carry on past it")


def tree_is_gone(host, port, password):
    """The server has to still be up, and the disabled plugin's commands gone with it."""
    try:
        probe = Rcon(host, port, password)
    except (OSError, SystemExit) as error:
        bad(f"the server did not stay up to answer RCON ({error})")
        return
    reply = probe.run("wake help")
    if "Unknown or incomplete" in reply:
        ok("the server is up and the command tree is gone with the plugin")
    else:
        bad(f"the disabled plugin still answers /wake help: {reply.strip()[:120]!r}")


def drill_boot_without_database(jar, plugin, host, port, password):
    """A database that never answers has to take Wake down and leave the server standing.

    `onEnable` disables the plugin from inside itself, so `onDisable` runs against an object that only
    got half built: no state store, no modules, no command tree. What proves that path is not the error
    Wake logs on purpose, it is that nothing follows it -- one error, no second trace, and a teardown
    that still reaches its last line.
    """
    print("\nboot with the database unreachable")
    config = WAKE / "config.yml"
    saved = config.read_text(encoding="utf-8")
    # port 1 rather than a stopped container: the connection is refused at once, so the drill watches the
    # pool's connection test fail rather than a socket timeout
    broken, types = re.subn(r'(?m)^(\s+type:\s*)"?\w+"?', r'\1"mariadb"', saved, count=1)
    broken, ports = re.subn(r"(?m)^(\s+port:\s*)\d+", r"\g<1>1", broken, count=1)
    if types != 1 or ports != 1:
        bad(f"could not point {config.name} at an unreachable database")
        return
    # a stand-in for what the last run left behind: nothing replays it, so what it holds does not matter
    left_behind = json.dumps({"s": [{"q": "REPLACE INTO wake_state (state_key, state_value) VALUES (?, ?)",
                                     "p": [{"t": "s", "v": "core.journal_drill"}, {"t": "s", "v": "kept"}]}]}) + "\n"
    server = None
    try:
        config.write_text(broken, encoding="utf-8")
        JOURNAL.write_text(left_behind, encoding="utf-8")
        step("booting with database.type: mariadb on a port nothing listens on, a journal left on disk")
        server = boot_headless(jar, plugin, "without a database")
        if server is None:
            return
        text = server.log()

        errors = [line for line in wake_errors(text, "ERROR") if "[wake]" in line]
        if len(errors) == 1 and "Database initialization failed" in errors[0]:
            ok("Wake reported the failure once and named it")
        else:
            bad(f"expected one wake error naming the failed init, saw {len(errors)}: {errors[:2]}")

        half_built_teardown(text)
        tree_is_gone(host, port, password)

        if JOURNAL.is_file() and JOURNAL.read_text(encoding="utf-8") == left_behind:
            ok("the journal the last run left is still on disk, byte for byte")
        else:
            bad("the failed boot took the outage journal with it")
    finally:
        if server is not None and not server.stop():
            bad("the server booted without a database had to be killed instead of stopping")
        JOURNAL.unlink(missing_ok=True)
        config.write_text(saved, encoding="utf-8")
        step("restored config.yml and cleared the drill's journal")


def drill_boot_with_unreadable_state(jar, plugin, host, port, password, mariadb):
    """A state store this build cannot read has to take Wake down the same way an unreachable one does.

    It fails a step further in than the pool does -- the database is open and the sync service is up by
    the time the store refuses -- so it is the other half of the same question. Whatever `onEnable` got
    as far as building is what `onDisable` is handed, and it has to reverse exactly that much.
    """
    print("\nboot on a state schema this build cannot read")
    stamped = schema_version("state", mariadb)
    if stamped is None:
        bad("the state schema has never been stamped, so there is nothing to raise")
        return
    server = None
    try:
        write_schema_version("state", stamped + 98, mariadb)
        step(f"stamping wake_schema_version for state at v{stamped + 98}, which this build cannot support")
        server = boot_headless(jar, plugin, "on an unsupported state schema")
        if server is None:
            return
        text = server.log()

        errors = [line for line in wake_errors(text, "ERROR") if "[wake]" in line]
        named = "update the Wake jar" in text
        if len(errors) == 1 and named:
            ok("Wake reported the failure once and the trace names the version it refused")
        else:
            bad(f"expected one wake error and a named cause, saw {len(errors)} error(s), cause named: {named}")

        half_built_teardown(text)
        tree_is_gone(host, port, password)
    finally:
        if server is not None and not server.stop():
            bad("the server booted on a bad schema had to be killed instead of stopping")
        write_schema_version("state", stamped, mariadb)
        step(f"restored the state schema stamp to v{stamped}")


def drill_boot_after_failure(jar, plugin):
    """The failed boot must have left nothing behind: the next one on the real config has to be clean."""
    print("\nboot again on the restored config")
    server = boot_headless(jar, plugin, "on the restored config")
    if server is None:
        return
    text = server.log()

    enabled = re.findall(r"Module '(\w+)' has been enabled", text)
    if enabled:
        ok(f"modules enabled: {', '.join(enabled)}")
    else:
        bad("no module reported enabling")

    noise = wake_errors(text[:text.index("Done (")] if "Done (" in text else text)
    if noise:
        bad(f"the boot logged {len(noise)} error line(s), first: {noise[0][:140]}")
    else:
        ok("no errors or stack traces at boot")

    if not server.stop():
        bad("the server had to be killed instead of stopping")
        return
    if "Wake has been disabled" in server.log():
        ok("and it stopped as cleanly as it started")
    else:
        bad("the second server never finished its teardown")


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25575)
    parser.add_argument("--password", default="wake-dev")
    parser.add_argument("--sync", action="store_true", help="also run the cross-server drill")
    parser.add_argument("--lifecycle", action="store_true",
                        help="also the boot and shutdown drills -- STOPS THE SERVER, so run it last")
    parser.add_argument("--mariadb-container", default="wake-testenv-mariadb-1")
    parser.add_argument("--backend", default="wake-testenv-paper2-1")
    args = parser.parse_args()

    try:
        rcon = Rcon(args.host, args.port, args.password)
    except OSError as error:
        raise SystemExit(f"cannot reach RCON at {args.host}:{args.port} ({error}). "
                         f"Start ./gradlew runServer first.")

    mariadb = detect_backend(args.mariadb_container)
    print(f"backend: {'mariadb' if mariadb else 'sqlite'}")
    log = Log()

    try:
        drill_boot(rcon)
        drill_repeated_reload(rcon, log)
        drill_outage(rcon, log, mariadb)
        if args.sync:
            if mariadb:
                drill_sync(rcon, log, args.backend)
                drill_boot_replay(rcon, log, args.backend, mariadb)
            else:
                print("\ncross-server sync\n  skipped: needs database.type: mariadb")
        if args.lifecycle:
            jar, plugin = server_jar(), plugin_jar()
            if jar is None or plugin is None:
                print(f"\nlifecycle\n  skipped: no {'paper jar under ' + str(SERVER_JARS) if jar is None else 'wake jar under ' + str(PLUGIN_JARS)}")
            elif drill_clean_stop(rcon, args.host, args.port):
                drill_boot_without_database(jar, plugin, args.host, args.port, args.password)
                drill_boot_with_unreadable_state(jar, plugin, args.host, args.port, args.password, mariadb)
                drill_boot_after_failure(jar, plugin)
                print("\n  the server is down: start ./gradlew runServer again")
    except RuntimeError as error:
        bad(str(error))

    print()
    if failures:
        print(f"{len(failures)} drill step(s) failed")
        return 1
    print("all drills passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())