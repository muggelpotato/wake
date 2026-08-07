#!/usr/bin/env python3
"""Client drills against the compiled classes -- no server, no client, no world.

Covers the two things `features/obu/clients` decides before anything else can run: whether a payload
on the mod's channels is a version handshake, and what the registry does with the verdict it yields.
Both are pure logic over bytes and a map, so TESTPLAN.md used to leave them to a human with a modded
client -- but a human cannot send a 0.4.8 handshake, a truncated one, or two at once.

The drill compiles a probe into `dev.muggel.wake.features.obu.clients`, runs it against
`build/classes/java/main` and judges what comes back here. Python builds every payload: the current
one is written the way `OpenBoatUtilsClient` writes it, from the `VERSION` and `UNSTABLE` constants
read straight out of `OBUSOURCE/OpenBoatUtils`, and the legacy one the way the mod's own
`developers/settings.md` says builds before 0.5.0 wrote it -- a version id with no unstable flag
behind it. Nothing in the probe decides whether a payload is well-formed.

    python testenv/drills_clients.py        # needs ./gradlew compileJava first

What is left to TESTPLAN.md: everything downstream of the verdict. That the log line appears once,
that the outdated/ahead/unsupported message reaches a real client, that a rejected build is left in
game, and that a module toggle re-asks a connected client -- all of which need a client that answers.

Exits non-zero if a check fails.
"""

import argparse
import os
import re
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from drills import ROOT  # noqa: E402

CLASSES = ROOT / "build" / "classes" / "java" / "main"
OBU_SOURCE = ROOT / "OBUSOURCE" / "OpenBoatUtils" / "src" / "main" / "java" / "dev" / "o7moon" / "openboatutils"

PROBE = r"""
package dev.muggel.wake.features.obu.clients;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

public final class ClientsProbe {
    private static Method parse;
    private static Method versionId;
    private static Method unstable;

    public static void main(String[] args) throws Exception {
        parse = HandshakeListener.class.getDeclaredMethod("parseHandshakeData", byte[].class);
        parse.setAccessible(true);
        Class<?> data = Class.forName("dev.muggel.wake.features.obu.clients.HandshakeListener$HandshakeData");
        versionId = data.getDeclaredMethod("versionId");
        versionId.setAccessible(true);
        unstable = data.getDeclaredMethod("isUnstable");
        unstable.setAccessible(true);
        payloads();
        fuzz();
        latch();
        race();
    }

    /** Every payload is named and spelled by the caller; all this does is report what the parser made of it */
    private static void payloads() throws Exception {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(System.in))) {
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                if (line.isBlank()) continue;
                String[] split = line.split("=", 2);
                byte[] bytes = "null".equals(split[1]) ? null : HexFormat.of().parseHex(split[1]);
                print(split[0], read(bytes));
            }
        }
    }

    private static String read(byte[] bytes) throws Exception {
        Object handshake = parse.invoke(null, (Object) bytes);
        return handshake == null ? "null" : versionId.invoke(handshake) + " " + unstable.invoke(handshake);
    }

    /** Nothing a hostile client can put on the channel may cost more than a null: every length, every fill */
    private static void fuzz() throws Exception {
        int calls = 0;
        int threw = 0;
        int accepted = 0;
        for (int length = 0; length <= 64; length++) {
            for (int fill = 0; fill < 256; fill += 17) {
                // the same rubbish twice, the second time behind a zero short, so half the sweep gets past the id
                for (boolean framed : new boolean[]{false, true}) {
                    byte[] bytes = new byte[length];
                    for (int i = 0; i < length; i++) {
                        bytes[i] = (byte) (framed && i < 2 ? 0 : fill + i * 31);
                    }
                    calls++;
                    try {
                        if (!"null".equals(read(bytes))) accepted++;
                    } catch (Exception e) {
                        threw++;
                    }
                }
            }
        }
        print("fuzz", calls + " " + threw + " " + accepted);
    }

    private static void latch() {
        ClientRegistry registry = new ClientRegistry();
        UUID uuid = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");
        print("absent", registry.state(uuid) + " " + registry.isDriven(uuid));
        print("first", registry.claim(uuid, ClientRegistry.ClientState.DRIVEN) + " " + registry.state(uuid));
        print("second", registry.claim(uuid, ClientRegistry.ClientState.UNSUPPORTED) + " " + registry.state(uuid));
        print("repeat", registry.claim(uuid, ClientRegistry.ClientState.DRIVEN) + " " + registry.state(uuid));
        print("driven", String.valueOf(registry.isDriven(uuid)));

        registry.forget(uuid);
        print("forgotten", registry.state(uuid) + " " + registry.isDriven(uuid));

        registry.claim(uuid, ClientRegistry.ClientState.UNSUPPORTED);
        print("rejected_latches", registry.claim(uuid, ClientRegistry.ClientState.DRIVEN) + " " + registry.state(uuid));
        print("rejected_not_driven", String.valueOf(registry.isDriven(uuid)));

        registry.reopen(uuid);
        print("reopened", registry.state(uuid) + " " + registry.isDriven(uuid));
        print("after_reopen", registry.claim(uuid, ClientRegistry.ClientState.DRIVEN) + " " + registry.state(uuid));
    }

    /** Two handshakes arriving on two netty threads at once: one verdict may land, and it is the one that is kept */
    private static void race() throws Exception {
        int rounds = 500;
        int racers = 8;
        int freshWinners = 0;
        int reopenWinners = 0;
        int disagreed = 0;
        for (int round = 0; round < rounds; round++) {
            ClientRegistry registry = new ClientRegistry();
            UUID uuid = UUID.randomUUID();
            if (round % 2 == 1) registry.reopen(uuid);
            AtomicInteger won = new AtomicInteger();
            CyclicBarrier gate = new CyclicBarrier(racers);
            Thread[] threads = new Thread[racers];
            for (int i = 0; i < racers; i++) {
                threads[i] = new Thread(() -> {
                    try {
                        gate.await();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                    if (registry.claim(uuid, ClientRegistry.ClientState.DRIVEN)) won.incrementAndGet();
                });
                threads[i].start();
            }
            for (Thread thread : threads) thread.join();
            if (registry.state(uuid) != ClientRegistry.ClientState.DRIVEN) disagreed++;
            if (round % 2 == 1) {
                reopenWinners += won.get();
            } else {
                freshWinners += won.get();
            }
        }
        print("race_fresh", (rounds / 2) + " " + freshWinners);
        print("race_reopened", (rounds / 2) + " " + reopenWinners);
        print("race_state", String.valueOf(disagreed));
    }

    private static void print(String name, String value) {
        System.out.println(name + "\t" + value);
    }
}
"""

failures = []


def ok(label):
    print(f"  ok    {label}")


def bad(label, detail):
    print(f"  FAIL  {label}: {detail}")
    failures.append(label)


def equals(facts, name, expected, note=None):
    got = facts.get(name)
    if got == expected:
        ok(f"{name} -> {note or expected}")
    else:
        bad(name, f"{got!r}, expected {expected!r}")


def constant(name, kind):
    """A `public static final` out of the mod's own source, so the drill never restates one itself."""
    source = (OBU_SOURCE / "OpenBoatUtils.java").read_text(encoding="utf-8")
    found = re.search(rf"public static final {kind} {name} = ([^;]+);", source)
    if not found:
        raise SystemExit(f"no `{kind} {name}` in the mod's OpenBoatUtils.java -- OBUSOURCE moved on.")
    return found.group(1).strip()


def transaction_marker():
    """The short the mod wraps a transaction in, taken off its own reply path so the drill cannot drift."""
    source = (OBU_SOURCE / "network" / "ClientboundSettingsPacket.java").read_text(encoding="utf-8")
    if "packet.writeShort(Short.MAX_VALUE);" not in source:
        raise SystemExit("the mod no longer answers a transaction with Short.MAX_VALUE -- OBUSOURCE moved on.")
    return 0x7FFF


def handshake(version, unstable=None, packet_id=0):
    """The bytes `sendVersionPacket` writes: a short packet id, an int version, and the flag 0.5.0 added."""
    payload = struct.pack(">hi", packet_id, version)
    return (payload if unstable is None else payload + bytes([1 if unstable else 0])).hex()


def probe(payloads):
    """Compiles the probe against the built classes and returns everything it printed, as name -> value."""
    if not CLASSES.is_dir():
        raise SystemExit(f"{CLASSES} is missing -- run ./gradlew compileJava first.")
    classpath = os.pathsep.join([str(CLASSES)] + [str(jar) for jar in jars()])
    with tempfile.TemporaryDirectory() as work:
        source = Path(work) / "ClientsProbe.java"
        source.write_text(PROBE, encoding="utf-8")
        built = subprocess.run([toolchain("javac"), "--release", "21", "-cp", classpath, "-d", work, str(source)],
                               capture_output=True, text=True, encoding="utf-8", errors="replace")
        if built.returncode != 0:
            raise SystemExit(f"the probe did not compile against {CLASSES}:\n{built.stderr}")
        ran = subprocess.run([toolchain("java"), "-cp", os.pathsep.join([work, classpath]),
                              "dev.muggel.wake.features.obu.clients.ClientsProbe"],
                             input="\n".join(f"{name}={hexed}" for name, hexed in payloads.items()),
                             capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=120)
        if ran.returncode != 0:
            raise SystemExit(f"the probe failed to run:\n{ran.stdout}\n{ran.stderr}")
    return dict(line.split("\t", 1) for line in ran.stdout.splitlines() if "\t" in line)


def toolchain(name):
    home = os.environ.get("JAVA_HOME")
    for candidate in (name, f"{name}.exe"):
        if home and (Path(home) / "bin" / candidate).exists():
            return str(Path(home) / "bin" / candidate)
    return name


def jars():
    """What the listener needs to load: packetevents for its superclass, paper-api for the events it declares.

    Reading a method off it resolves every signature on the class, so paper-api's own adventure types have
    to be there too -- whatever of them the build pulled in, which is why that half is a sweep and not a list.
    """
    modules = Path(os.environ.get("GRADLE_USER_HOME") or (Path.home() / ".gradle")) / "caches/modules-2/files-2.1"
    for module in ("io.papermc.paper/paper-api", "com.github.retrooper/packetevents-api"):
        jar = newest(modules / module)
        if jar is None:
            raise SystemExit(f"no jar for {module} -- run ./gradlew compileJava once.")
        yield jar
    for module in sorted((modules / "net.kyori").glob("*")):
        jar = newest(module)
        if jar is not None:
            yield jar


def newest(module):
    found = [jar for jar in module.glob("*/*/*.jar") if "sources" not in jar.name]
    return sorted(found)[-1] if found else None


def payloads():
    """Every shape that has ever come down the two handshake channels, plus what never should."""
    version = int(constant("VERSION", "int"))
    unstable = constant("UNSTABLE", "boolean") == "true"
    return version, unstable, {
        # what the mod ships today, written the way OpenBoatUtilsClient writes it
        "current": handshake(version, unstable),
        "unstable_build": handshake(version, True),
        # settings.md: the flag only exists from 0.5.0 (id 19), so 0.4.8 sent six bytes and no more
        "legacy": handshake(16),
        "legacy_oldest": handshake(0),
        # a build that grows the packet must not cost the version we can already read
        "trailing": handshake(version, unstable) + "ff",
        # every version the parse itself must stay out of the way of
        "rejected": handshake(20, False),
        "ahead": handshake(version + 1, False),
        "negative": handshake(-1, False),
        "huge": handshake(2147483647, False),
        # and everything that is not a version packet
        "wrong_packet_id": handshake(version, unstable, packet_id=1),
        # the transaction reply the mod sends back on this same channel: a legacy-length payload that is not a version
        "transaction_ack": handshake(7, packet_id=transaction_marker()),
        "five_bytes": struct.pack(">ib", version, 0).hex(),
        "id_only": "0000",
        "truncated": "000000000016"[:-2],
        "empty": "",
        "no_data": "null",
    }


def drill_handshake(facts, version, unstable):
    print("\nthe handshake the mod actually sends")
    equals(facts, "current", f"{version} {str(unstable).lower()}",
           f"version {version}, unstable={unstable} -- as OpenBoatUtils {version} writes it")
    equals(facts, "unstable_build", f"{version} true")
    equals(facts, "trailing", f"{version} {str(unstable).lower()}", "a longer packet still reads its version")

    print("\nand the one every build before 0.5.0 sent, with no unstable flag behind the id")
    equals(facts, "legacy", "16 false", "0.4.8 (id 16) is driven, not ignored")
    equals(facts, "legacy_oldest", "0 false", "0.1.2 (id 0), the oldest that sends a version at all")

    print("\nthe parse reports the version and judges nothing else")
    equals(facts, "rejected", "20 false", "a rejected build still parses -- the verdict is not the parser's")
    equals(facts, "ahead", f"{version + 1} false")
    equals(facts, "negative", "-1 false")
    equals(facts, "huge", "2147483647 false")

    print("\nand refuses everything that is not a version packet")
    equals(facts, "wrong_packet_id", "null", "a non-zero packet id is not a handshake")
    equals(facts, "transaction_ack", "null",
           "the mod's transaction reply is six bytes too -- the packet id refuses it, not the length")
    equals(facts, "five_bytes", "null", "no build ever sent a bare int -- reading one invents a version")
    equals(facts, "id_only", "null", "a packet id with no version behind it")
    equals(facts, "truncated", "null", "a version cut short")
    equals(facts, "empty", "null")
    equals(facts, "no_data", "null", "a plugin message with no data at all")

    print("\nand a hostile payload costs no more than that null")
    calls, threw, accepted = facts.get("fuzz", "0 1 0").split()
    if int(threw):
        bad("fuzz", f"{threw} of {calls} payloads threw out of a netty thread")
    elif int(accepted) == 0:
        bad("fuzz", f"none of {calls} payloads parsed -- the sweep is not reaching the parser")
    else:
        ok(f"fuzz -> {calls} payloads of every length and fill, {threw} threw, {accepted} were well-formed")


def drill_latch(facts):
    print("\nthe verdict is a latch")
    equals(facts, "absent", "null false", "a client nobody asked reads as null, not as UNKNOWN")
    equals(facts, "first", "true DRIVEN")
    equals(facts, "second", "false DRIVEN", "a second handshake cannot downgrade an accepted client")
    equals(facts, "repeat", "false DRIVEN", "nor can a repeat of the same one")
    equals(facts, "driven", "true")
    equals(facts, "forgotten", "null false", "and quit puts it back to never-asked")
    equals(facts, "rejected_latches", "false UNSUPPORTED", "a rejected client cannot be talked up either")
    equals(facts, "rejected_not_driven", "false")

    print("\nand only a reopen re-arms it")
    equals(facts, "reopened", "UNKNOWN false", "asked and unanswered is its own state, and drives nothing")
    equals(facts, "after_reopen", "true DRIVEN")

    print("\nunder two handshakes at once, on two threads")
    for name, note in (("race_fresh", "on a client nobody had claimed"),
                       ("race_reopened", "on a client a module toggle had just re-armed")):
        rounds, winners = facts.get(name, "1 0").split()
        if rounds == winners:
            ok(f"{name} -> exactly one of eight racers won each of {rounds} rounds, {note}")
        else:
            bad(name, f"{winners} winners over {rounds} rounds -- the claim is not a compare-and-set")
    equals(facts, "race_state", "0", "and the verdict left behind is the winner's, every round")


def main():
    argparse.ArgumentParser(description=__doc__.split("\n")[0]).parse_args()
    version, unstable, wire = payloads()
    facts = probe(wire)
    drill_handshake(facts, version, unstable)
    drill_latch(facts)

    print()
    if failures:
        print(f"{len(failures)} check(s) failed: {', '.join(failures)}")
        return 1
    print("all client drills passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
