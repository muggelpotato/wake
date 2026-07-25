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

    def run(self, command):
        return CODES.sub("", self._send(2, command) or "")


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


def docker(*args):
    # rcon-cli echoes section-sign colour codes: decoding with the console default fails on Windows
    result = subprocess.run([os.environ.get("DOCKER", "docker"), *args], capture_output=True,
                            text=True, encoding="utf-8", errors="replace", timeout=120)
    if result.returncode != 0:
        raise RuntimeError(f"docker {' '.join(args)}: {(result.stderr or result.stdout).strip()}")
    return result.stdout


def drill_boot(rcon: Rcon):
    print("\nboot")
    text = LOG.read_text(encoding="utf-8", errors="replace")
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

    if mariadb:
        container = mariadb[0]
        step(f"stopping {container}")
        docker("stop", container)
        release = lambda: docker("start", container)
        step_back = f"starting {container}"
    else:
        # SQLite has no service to stop: hold the write lock so the plugin's writes fail busy
        step("taking the sqlite write lock")
        holder = sqlite3.connect(str(WAKE / "wake.db"), timeout=5, isolation_level=None)
        holder.execute("BEGIN IMMEDIATE")
        release = lambda: (holder.execute("ROLLBACK"), holder.close())
        step_back = "releasing the sqlite write lock"

    try:
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
    finally:
        step(step_back)
        release()

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
        docker("exec", backend, "rcon-cli", "wake help")
    except RuntimeError as error:
        bad(str(error))
        return

    def switch(text):
        found = STATUS.search(CODES.sub("", text))
        return found.group(1).lower() if found else None

    def remote_switch():
        return switch(docker("exec", backend, "rcon-cli", "dd boostpad list"))

    # compare against what the switch was, not an assumed starting position
    before = remote_switch()
    step(f"toggling a setting on the primary (both backends read {before})")
    log.reset()
    rcon.run("dd boostpad toggle")
    time.sleep(3)
    local, remote = switch(rcon.run("dd boostpad list")), remote_switch()
    if local and local != before and remote == local:
        ok(f"the other backend observed the change ({before} -> {remote})")
    else:
        bad(f"the other backend reports {remote!r}, the primary reports {local!r} (was {before!r})")
    rcon.run("dd boostpad toggle")

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

    config = (WAKE / "config.yml").read_text(encoding="utf-8")
    block = re.search(r"(?ms)^database:\n(?:[ \t]+.*\n?)*", config)
    # match the setting, not the comment listing the options next to it
    mariadb = None
    if block and re.search(r"""(?m)^\s*type:\s*["']?(mariadb|mysql)""", block.group(0)):
        def field(name, fallback):
            found = re.search(rf"""(?m)^\s+{name}:\s*["']?([^"'#\r\n]+)""", block.group(0))
            return found.group(1).strip() if found else fallback

        mariadb = (args.mariadb_container, field("username", "root"),
                   field("password", "password"), field("database", "wake"))
    print(f"backend: {'mariadb' if mariadb else 'sqlite'}")
    log = Log()

    try:
        drill_boot(rcon)
        drill_outage(rcon, log, mariadb)
        if args.sync:
            if mariadb:
                drill_sync(rcon, log, args.backend)
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