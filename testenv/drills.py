#!/usr/bin/env python3
"""Operational drills against a running Wake server.

Forces a database outage and confirms the journal replays, and checks that a change reaches the
other backend. Every step prints what it did and what it saw.

    python testenv/drills.py            # boot + outage
    python testenv/drills.py --sync     # also the cross-server drill (needs the mariadb stack)

Needs a server up with RCON (./gradlew runServer). Exits non-zero if a drill fails.
"""

import argparse
import io
import os
import re
import socket
import sqlite3
import struct
import subprocess
import sys
import time
from contextlib import contextmanager
from pathlib import Path
from typing import Optional

if hasattr(sys.stdout, "buffer"):
    sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")

ROOT = Path(__file__).resolve().parents[1]
WAKE = ROOT / "run" / "plugins" / "wake"
LOG = ROOT / "run" / "logs" / "latest.log"
JOURNAL = WAKE / "outage-journal.jsonl"
# trailing §x: rcon-cli rewrites the six hex digits as ANSI escapes but leaves the marker behind
CODES = re.compile(r"§x(?:§[0-9a-fA-F]){6}|§[0-9a-fk-orA-FK-OR]|\x1b\[[0-9;]*m|§x")
STATUS = re.compile(r"Status:\s*(\w+)")

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

    if "Unknown or incomplete" in rcon.run("wake help"):
        bad("/wake help did not resolve")
    else:
        ok("commands respond over RCON")


def drill_outage(rcon: Rcon, log: Log, mariadb: Optional[tuple]):
    """Cuts the database off, confirms writes are journaled, restores it, confirms they replay."""
    print("\ndatabase outage")
    original = state("base.show_hints", mariadb) or "true"
    target = "false" if original == "true" else "true"
    log.reset()

    with outage(mariadb):
        step(f"changing a setting while the database is unreachable (hints -> {target})")
        rcon.run(f"wake hints {target}")
        if await_file(JOURNAL, True, 45):
            lines = [ln for ln in JOURNAL.read_text(encoding="utf-8").splitlines() if ln.strip()]
            if all(ln.lstrip().startswith("{") for ln in lines):
                ok(f"journaled {len(lines)} write(s), one JSON object per line")
            else:
                bad("journal is not one JSON object per line")
        else:
            bad("no outage journal appeared within 45s")

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
    if state("base.show_hints", mariadb) == target:
        ok(f"replayed value landed in the database ({target})")
    else:
        bad(f"database holds {state('base.show_hints', mariadb)!r}, expected {target!r}")

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
    hints = state("base.show_hints", mariadb) or "true"

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


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25575)
    parser.add_argument("--password", default="wake-dev")
    parser.add_argument("--sync", action="store_true", help="also run the cross-server drill")
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
        drill_outage(rcon, log, mariadb)
        if args.sync:
            if mariadb:
                drill_sync(rcon, log, args.backend)
                drill_boot_replay(rcon, log, args.backend, mariadb)
            else:
                print("\ncross-server sync\n  skipped: needs database.type: mariadb")
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