#!/usr/bin/env python3
"""Join drills: what a client is actually told on join, driven by a fake one.

`drills_clients.py` ends at the verdict. This is everything after it: the console line, the nag, the
refusal, and the selection that rides along with them -- the half of TESTPLAN §1 that until now
needed four builds of the mod, a human, and four chat lines read off a screen.

The handshake is written the way `OpenBoatUtilsClient` writes it, and sent where that build sends it:
in the configuration phase, which is where every build from 0.5.0 announces itself, and again in play,
which is where the same build repeats it. Both matter, because the phase decides which thread answers
and whether a player is in the world at all -- on 1.21 PacketEvents resolves one during configuration
where later builds resolve none, so a verdict acted on there lands before the join and is thrown away.
A client that joins in 40ms hides that; `--config-delay` holds the phase open the way registries and
resource packs do on a real one.

The ids come out of `OBUVersions.java` and are never restated here: the floor Wake drives, one below
it, one the mod marks broken, the top of the table and one past it.

    python testenv/drills_join.py --port 25585      # against ./gradlew run1.21

Speaks protocol 767 (1.21, 1.21.1) and cannot authenticate, so that server has to be in offline mode.
What is still left to TESTPLAN.md: a client that computes physics, which is the only thing that can
say a delivered context is the right one.

Exits non-zero if a check fails.
"""

import argparse
import os
import re
import socket
import struct
import sys
import time
import uuid
import zlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from drills import Log, Rcon, ROOT, bad, failures, ok, step, wake_errors  # noqa: E402

PROTOCOL = 767
SETTLE = 1.5
VERSIONS = ROOT.joinpath("src", "main", "java", "dev", "muggel", "wake",
                         "features", "obu", "protocol", "OBUVersions.java")

# judged on the wire, so the language file's own wording is what has to arrive
NAG = b"outdated version of OpenBoatUtils"
AHEAD = b"newer than the server's supported version"
REFUSED = b"bugged or no longer supported"
CHANNELS = b"openboatutils"
CONTEXT_CHANNEL = b"openboatutils:context"
# the two the mod applies to itself rather than to a context: reset-on-world-load pinned off, and the
# interpolation flag whichever way the switch is set. Raw settings, so the id follows the channel name
WORLD_LOAD = b"openboatutils:settings\x00\x26\x00"
INTERPOLATION = b"openboatutils:settings\x00\x1d"

CB_DISCONNECT, CB_FINISH, CB_KEEPALIVE, CB_KNOWN_PACKS = 0x02, 0x03, 0x04, 0x0E
SB_CLIENT_INFO, SB_PLUGIN, SB_FINISH_ACK, SB_KEEPALIVE, SB_KNOWN_PACKS = 0x00, 0x02, 0x03, 0x04, 0x07
SB_PLAY_PLUGIN = 0x12

rcon = None
game = ("127.0.0.1", 25565)
name = "wakejoindrill"


def truthy(label, condition, detail=""):
    (ok if condition else bad)(label if condition else f"{label} -- {detail}")


def varint(value):
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        out.append(byte | (0x80 if value else 0))
        if not value:
            return bytes(out)


def take_varint(data):
    value = shift = index = 0
    while True:
        byte = data[index]
        index += 1
        value |= (byte & 0x7F) << shift
        if not byte & 0x80:
            return value, data[index:]
        shift += 7


def text(value):
    raw = value.encode("utf-8")
    return varint(len(raw)) + raw


class Connection:
    """Just enough of the protocol to be a client: framing, compression, and the packets a join needs."""

    def __init__(self, host, port):
        self.sock = socket.create_connection((host, port), timeout=15)
        self.buf = b""
        self.threshold = -1

    def _fill(self, size):
        while len(self.buf) < size:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise EOFError("the server closed the connection")
            self.buf += chunk

    def _varint(self):
        value = shift = 0
        while True:
            self._fill(1)
            byte, self.buf = self.buf[0], self.buf[1:]
            value |= (byte & 0x7F) << shift
            if not byte & 0x80:
                return value
            shift += 7

    def send(self, packet_id, payload=b""):
        body = varint(packet_id) + payload
        if self.threshold < 0:
            frame = varint(len(body)) + body
        elif len(body) < self.threshold:
            frame = varint(len(body) + 1) + b"\x00" + body
        else:
            packed = zlib.compress(body)
            frame = varint(len(varint(len(body)) + packed)) + varint(len(body)) + packed
        self.sock.sendall(frame)

    def read(self):
        length = self._varint()
        self._fill(length)
        body, self.buf = self.buf[:length], self.buf[length:]
        if self.threshold >= 0:
            size, body = take_varint(body)
            if size:
                body = zlib.decompress(body)
        return take_varint(body)

    def close(self):
        try:
            self.sock.close()
        except OSError:
            pass


class Seen:
    """What came back down the play stream, and whether the connection survived it."""

    def __init__(self, stream, lines, connected):
        self.nags = stream.count(NAG)
        self.ahead = AHEAD in stream
        self.refused = REFUSED in stream
        self.payloads = stream.count(CHANNELS)
        # a world load between the two would drop a context the server still thinks the client holds,
        # so the flag that switches that off has to be on the wire ahead of the context, not behind it
        pinned, context = stream.find(WORLD_LOAD), stream.find(CONTEXT_CHANNEL)
        self.globals_first = 0 <= pinned < context and INTERPOLATION in stream
        # every interpolation flag that went out, in order, so a push nothing made is as visible as one twice
        self.interpolation = ",".join("on" if stream[at.end():at.end() + 1] == b"\x01" else "off"
                                      for at in re.finditer(re.escape(INTERPOLATION), stream))
        self.lines = lines
        self.connected = connected

    def __str__(self):
        return (f"{self.lines} console line(s), {self.nags} nag(s), ahead={self.ahead}, "
                f"refused={self.refused}, {self.payloads} channel mention(s), "
                f"globals_first={self.globals_first}, interpolation=[{self.interpolation}], "
                f"connected={self.connected}")


def handshake(version, unstable=False):
    """Packet 0 on the mod's channels: the version id, and the flag every build from 0.5.0 puts behind it."""
    return struct.pack(">hi?", 0, version, unstable)


def protocol_of(host, port):
    """The version the server answers a status ping with, so a mismatch is named rather than parsed into."""
    probe = Connection(host, port)
    try:
        probe.send(0x00, varint(PROTOCOL) + text(host) + struct.pack(">H", port) + varint(1))
        probe.send(0x00)
        _, payload = probe.read()
        _, payload = take_varint(payload)
        found = re.search(rb'"protocol"\s*:\s*(\d+)', payload)
        return int(found.group(1)) if found else 0
    finally:
        probe.close()


def join(version, play_copy=False, config_delay=0.0, listen=6.0, during=()):
    """One full join announcing `version`, reported by what the server said back.

    `during` is run on the console one command at a time while the client is still listening, which is
    the only place a packet the server sends of its own accord can be watched arriving."""
    log = Log()
    host, port = game
    client = Connection(host, port)
    try:
        client.send(0x00, varint(PROTOCOL) + text(host) + struct.pack(">H", port) + varint(2))
        client.send(0x00, text(name) + uuid.uuid5(uuid.NAMESPACE_OID, name).bytes)
        while True:
            packet_id, payload = client.read()
            if packet_id == 0x03:  # set compression
                client.threshold, _ = take_varint(payload)
            elif packet_id == 0x02:  # login success
                client.send(0x03)
                break
            elif packet_id == 0x01:  # encryption request
                raise SystemExit("the server is in online mode: this drill cannot authenticate. "
                                 "Set online-mode=false in that server's server.properties")
            elif packet_id == 0x00:  # disconnect
                raise SystemExit(f"login refused: {payload[:200]!r}")

        # the configuration phase, where 0.5.0 and newer announce themselves
        client.send(SB_CLIENT_INFO, text("en_us") + struct.pack(">b", 8) + varint(0)
                    + b"\x01" + b"\x7f" + varint(1) + b"\x00\x01")
        client.send(SB_PLUGIN, text("openboatutils:configuration") + handshake(version))
        while True:
            packet_id, payload = client.read()
            if packet_id == CB_KNOWN_PACKS:
                client.send(SB_KNOWN_PACKS, varint(0))
            elif packet_id == CB_KEEPALIVE:
                client.send(SB_KEEPALIVE, payload)
            elif packet_id == CB_DISCONNECT:
                raise SystemExit(f"kicked in configuration: {payload[:200]!r}")
            elif packet_id == CB_FINISH:
                time.sleep(config_delay)
                client.send(SB_FINISH_ACK)
                break

        if play_copy:
            client.send(SB_PLAY_PLUGIN, text("openboatutils:settings") + handshake(version))

        stream, connected = bytearray(), True
        client.sock.settimeout(1.0)
        pending, due = list(during), time.monotonic() + SETTLE
        deadline = time.monotonic() + listen
        while time.monotonic() < deadline:
            if pending and time.monotonic() >= due:
                rcon.run(pending.pop(0))
                due = time.monotonic() + SETTLE
            try:
                _, payload = client.read()
                stream += payload
            except socket.timeout:
                continue
            except (EOFError, ConnectionError, zlib.error):
                connected = False
                break
    finally:
        client.close()
        time.sleep(SETTLE)

    needle = f"is running OpenBoatUtils version {version}"
    log.await_line(needle, 5)
    return Seen(bytes(stream), log.read().count(needle), connected)


def table():
    """The ids to judge against, read out of `OBUVersions.java` so the drill never restates one."""
    source = VERSIONS.read_text(encoding="utf-8")

    def number(constant):
        found = re.search(rf"{constant} = (\d+);", source)
        if not found:
            raise SystemExit(f"OBUVersions.java no longer spells {constant}")
        return int(found.group(1))

    broken = re.search(r"BROKEN = Set\.of\(([^)]*)\)", source)
    if not broken:
        raise SystemExit("OBUVersions.java no longer spells BROKEN")
    return number("MINIMUM_SUPPORTED"), number("LATEST_SUPPORTED"), [int(id) for id in re.findall(r"\d+", broken.group(1))]


def drill_floor_build(floor):
    """The oldest build Wake drives is the one every gate has to let through and still name."""
    seen = join(floor)
    truthy("one console line names the version", seen.lines == 1, str(seen))
    truthy("one outdated line, not two", seen.nags == 1, str(seen))
    truthy("and the join carried a context with it", seen.payloads > 0, str(seen))
    truthy("behind the two flags the mod applies to itself", seen.globals_first, str(seen))
    truthy("and the client is driven, not dropped", seen.connected, str(seen))


def drill_slow_configuration(floor):
    """Held open, the phase is long over before the join -- which is where a verdict acted on there is lost."""
    seen = join(floor, config_delay=3.0)
    truthy("the outdated line survives a slow configuration phase", seen.nags == 1, str(seen))
    truthy("and so does the context", seen.payloads > 0, str(seen))


def drill_both_handshakes(floor):
    """0.5.0 announces itself twice, in both phases. The second is a repeat, and a repeat drives nothing."""
    seen = join(floor, play_copy=True)
    truthy("two handshakes still log one line", seen.lines == 1, str(seen))
    truthy("and still cost one outdated line", seen.nags == 1, str(seen))


def drill_refused_builds(floor, broken):
    """A build Wake will not drive is told so and left in game: kicking one only hands it to the next backend."""
    for version, why in ((floor - 1, "below the floor"), (broken, "marked broken by the mod")):
        step(f"{version} is {why}")
        seen = join(version)
        truthy("it is told the build is refused", seen.refused, str(seen))
        truthy("no outdated line beside it", seen.nags == 0, str(seen))
        truthy("nothing is sent to it", seen.payloads == 0, str(seen))
        truthy("and it stays in game", seen.connected, str(seen))


def drill_table_edges(latest):
    """The top of the table says nothing at all; one id past it is the server admitting it is behind."""
    step(f"{latest} is the top of the table")
    seen = join(latest)
    truthy("no outdated line", seen.nags == 0, str(seen))
    truthy("no ahead line either", not seen.ahead, str(seen))
    truthy("and it is driven", seen.payloads > 0, str(seen))

    step(f"{latest + 1} is past it")
    seen = join(latest + 1)
    truthy("it is told the server is behind", seen.ahead, str(seen))
    truthy("no outdated line beside it", seen.nags == 0, str(seen))
    truthy("and it is still driven", seen.payloads > 0, str(seen))


def drill_globals_pushed(floor):
    """A client-wide flag is pushed and never asked for, so every way of moving one owes a connected
    client a packet: the switch in both directions, and an import, which writes the row behind it."""
    log = Log()
    rcon.run("wobu -settings setinterpolationten true")
    rcon.run("wake database export obu")
    if not log.await_line("Database export completed for module obu", 30):
        bad("the export never finished, so no import could hand the flag back")
        return
    try:
        rcon.run("wobu -settings setinterpolationten false")
        seen = join(floor, listen=12.0, during=("wobu -settings setinterpolationten true",
                                                "wobu -settings setinterpolationten false",
                                                "wake database import obu confirm"))
        truthy("the join, both switches and the import each put the flag on the wire",
               seen.interpolation == "off,on,off,on", str(seen))
    finally:
        rcon.run("wobu -settings setinterpolationten false")


def drill_quiet_console(log):
    """Ten joins and ten quits behind us: a listener that leaks per-player state says so here."""
    noise = wake_errors(log.read())
    truthy("no Wake error or warning behind any of the joins", not noise, "; ".join(noise[:3]))


def drill_nag_switch(floor):
    """The switch owes exactly one thing: the line stops. Everything the join delivers has to stay."""
    try:
        rcon.run("wobu -settings update-nag false")
        seen = join(floor, config_delay=3.0)
        truthy("switched off, no outdated line", seen.nags == 0, str(seen))
        truthy("the version is still logged", seen.lines == 1, str(seen))
        truthy("and the context still arrives", seen.payloads > 0, str(seen))
    finally:
        rcon.run("wobu -settings update-nag true")
    seen = join(floor)
    truthy("switched back on, the line is back", seen.nags == 1, str(seen))


def main():
    global rcon, game, name
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25575, help="RCON, not the game port")
    parser.add_argument("--game-port", type=int, default=25565)
    parser.add_argument("--password", default="wake-dev")
    parser.add_argument("--name", default=name)
    args = parser.parse_args()
    game, name = (args.host, args.game_port), args.name

    spoken = protocol_of(*game)
    if spoken != PROTOCOL:
        raise SystemExit(f"the server at {game[0]}:{game[1]} speaks protocol {spoken}, this drill speaks "
                         f"{PROTOCOL} (1.21, 1.21.1). Point it at ./gradlew run1.21 with --port 25585")
    try:
        rcon = Rcon(args.host, args.port, args.password)
    except OSError as error:
        raise SystemExit(f"cannot reach RCON at {args.host}:{args.port} ({error})")

    floor, latest, broken = table()
    print(f"floor {floor}, latest {latest}, broken {', '.join(str(id) for id in broken)}")
    log = Log()
    for drill, arguments in ((drill_floor_build, (floor,)),
                             (drill_slow_configuration, (floor,)),
                             (drill_both_handshakes, (floor,)),
                             (drill_refused_builds, (floor, broken[0])),
                             (drill_table_edges, (latest,)),
                             (drill_nag_switch, (floor,)),
                             (drill_globals_pushed, (floor,)),
                             (drill_quiet_console, (log,))):
        print(f"\n{drill.__name__.removeprefix('drill_').replace('_', ' ')}")
        drill(*arguments)

    print()
    if failures:
        print(f"{len(failures)} drill step(s) failed")
        return 1
    print("all join drills passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
