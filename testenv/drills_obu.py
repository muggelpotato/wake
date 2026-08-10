#!/usr/bin/env python3
"""OBU protocol drills: the bytes on the wire, then the commands that produce them.

The first half needs no server. `features/obu/protocol` is the one package whose mistakes are
invisible here and only show up as wrong physics on someone's client, so the bytes it writes are
judged directly: a probe compiled into the package prints every packet as hex and Python compares it
against the ids, framing and defaults read out of `OBUSOURCE/OpenBoatUtils` -- the mod's own
`ClientboundSettingsPacket`, `ClientboundContextPacket` and `ISettingContext.VANILLA`. Nothing in the
probe decides whether a byte is right.

The second half covers what `SettingType` owns at the command surface: every semantic argument type
still parses, still refuses what it should, still stores and displays, and still gates a value the
wire cannot carry out of the database.

    python testenv/drills_obu.py                # both halves (needs ./gradlew compileJava, then runServer)
    python testenv/drills_obu.py --encoding     # the wire half alone, no server

Console has no entity, so a setting command that parses still reports an invalid target -- which is
the signal we want: a Brigadier parse error means the argument type is wrong, anything else means it
was accepted. Storage and display go through the sandbox commands, which run as the console sender.

Block and entity lists are absent from the probe on purpose: canonicalising one reads the Bukkit
registry, which needs a live server, so those stay with the command drills below.

Exits non-zero if a check fails.
"""

import argparse
import base64
import gzip
import os
import re
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
# also installs the utf-8 stdout wrapper the section-sign colour codes need
from drills import Rcon, CODES, ROOT  # noqa: E402

# Brigadier's own rejections, including the range messages the byte type relies on
PARSE_ERROR = ("Incorrect argument", "Unknown or incomplete", "Expected", "must not be",
               "Invalid option", "Invalid block", "Invalid entity", "<--[HERE]")

CLASSES = ROOT / "build" / "classes" / "java" / "main"
# the paper-api those classes were compiled against, never the newest cached: checkVersions fills the same
# cache with every version Wake claims, and the newest of those is built for a JVM this probe cannot run on
COMPILED_AGAINST = re.compile(r'compileOnly\("io\.papermc\.paper:paper-api:([^"]+)"')

failures = []
rcon = None


def run(command):
    return CODES.sub("", rcon.run(command))


def ok(label):
    print(f"  ok    {label}")


def bad(label, detail):
    print(f"  FAIL  {label}: {detail.strip()[:200]}")
    failures.append(label)


def parses(label, command):
    reply = run(command)
    if any(marker in reply for marker in PARSE_ERROR):
        bad(label, f"{command!r} -> {reply}")
    else:
        ok(label)


def rejects(label, command):
    reply = run(command)
    if any(marker in reply for marker in PARSE_ERROR):
        ok(label)
    else:
        bad(label, f"{command!r} was accepted -> {reply}")


def expect(label, command, needle):
    reply = run(command)
    if needle.lower() in reply.lower():
        ok(label)
    else:
        bad(label, f"{command!r} -> {reply}")
    return reply


def caret(label, command, pointed_at):
    """The console prints ten characters of lead-in, then everything from the error cursor on, then
    the marker -- so what the cursor points at is that line past its first ten characters"""
    reply = run(command)
    line = reply.split("\n")[-1].strip()
    if not line.startswith("...") or not line.endswith("<--[HERE]"):
        bad(label, f"{command!r} -> {reply}")
    elif line[len("...") + 10:-len("<--[HERE]")] == pointed_at:
        ok(label)
    else:
        bad(label, f"the cursor sits at {line!r}, not at {pointed_at!r}")


def settings_of(view):
    """The setting lines a `-sandbox view` reply carries, as (name, value) pairs in the order they arrive."""
    held = []
    for line in view.splitlines():
        if "→" not in line:
            continue
        name, _, value = line.partition("→")
        held.append((name.rsplit("○", 1)[-1].strip(), value.strip()))
    return held


def share(*entries):
    """A share code is gzipped, then url-safe base64 without padding. See SandboxCommandHelper"""
    payload = (";".join(entries) + ";").encode()
    return base64.urlsafe_b64encode(gzip.compress(payload)).decode().rstrip("=")


def drill_parsing():
    print("\nevery semantic type still parses")
    parses("float", "wo stepsize 0.6")
    parses("double", "wo boatgravity -0.04")
    parses("int", "wo coyotetime 3")
    parses("byte", "wo setcollisionresolution 5")
    parses("boolean", "wo falldamage true")
    parses("block_list", "wo removeblockslipperiness ice")
    parses("entity_list", "wo addcollisionfilter minecraft:pig")
    parses("collision_enum", "wo collisionmode VANILLA")
    parses("setting_enum + float + block_list", "wo setblocksetting JUMP_FORCE 1.0 ice")
    parses("float + block_list", "wo blockslipperiness 0.9 ice,packed_ice")
    parses("three doubles", "wo applyimpulse 0.1 0.2 0.3")
    parses("no arguments", "wo clearslipperiness")

    print("\nand a list argument takes every shape of the same list")
    parses("uppercase", "wo removeblockslipperiness ICE")
    parses("bare and namespaced mixed", "wo removeblockslipperiness minecraft:ice,packed_ice")
    parses("repeated and mixed separators", "wo removeblockslipperiness ice,,  packed_ice")
    parses("a leading separator", "wo removeblockslipperiness ,ice")
    parses("duplicates", "wo removeblockslipperiness ice,ice")
    parses("an entry that prefixes another", "wo removeblockslipperiness sand,sandstone")
    parses("an entry whose text appears earlier", "wo removeblockslipperiness sandstone,sand")
    parses("a trailing separator", "wo removeblockslipperiness ice,")
    parses("an empty namespace", "wo removeblockslipperiness :ice")
    parses("a short-form uuid", "wo addcollisionfilter 1-1-1-1-1")
    parses("a list far longer than any real one", "wo removeblockslipperiness " + ",".join(["ice"] * 200))  # rcon caps the packet at ~1.4kB

    print("\nand still refuses what it should")
    # the client runs a plain move outside 1..50, so a value it would ignore never leaves the command
    rejects("resolution above the client's cap", "wo setcollisionresolution 51")
    rejects("resolution below the client's floor", "wo setcollisionresolution 0")
    rejects("negative resolution", "wo setcollisionresolution -1")
    rejects("unknown collision mode", "wo collisionmode NONSENSE")
    rejects("unknown per-block setting", "wo setblocksetting NONSENSE 1.0 ice")
    rejects("non-numeric float", "wo stepsize abc")
    rejects("unknown block", "wo removeblockslipperiness not_a_block")
    rejects("unknown entity", "wo addcollisionfilter not_an_entity")
    rejects("separators only", "wo removeblockslipperiness ,")
    rejects("a bad entry behind good ones", "wo removeblockslipperiness ice,,  not_a_block")
    rejects("an item that is not a block", "wo removeblockslipperiness stick")
    rejects("two colons", "wo removeblockslipperiness a:b:c")
    rejects("a namespace with no key", "wo addcollisionfilter minecraft:")
    rejects("a non-ascii letter", "wo removeblockslipperiness İce")

    print("\nand points at the entry that failed, not at the separators before it")
    caret("a bad entry behind good ones", "wo removeblockslipperiness ice,,  not_a_block", "not_a_block")
    caret("a bad entry with good ones behind it", "wo blockslipperiness 0.9 ice,not_a_block,stone", "not_a_block,stone")


def drill_storage():
    print("\nstorage, display and export")
    rcon.run("wo -sandbox delete codectest")
    # 1=stepsize(float) 27=collisionmode(collision_enum) 30=setcollisionresolution(byte)
    # 3=blockslipperiness(float, block_list), whose namespace the door takes off, so nothing below carries one
    code = share("1:0.6", "27:NO_ENTITIES", "30:3", "3:0.9 minecraft:ice")
    expect("a share code of every stored type imports", f'wo -sandbox import "{code}" codectest', "imported")
    if "skip" in run(f'wo -sandbox import "{code}" codectest2').lower():
        bad("phantom skips", "a code whose entries are all sound still reported skipped settings")
    else:
        ok("nothing is reported skipped when every entry is sound")
    rcon.run("wo -sandbox delete codectest2")
    view = expect("the sandbox lists its settings", "wo -sandbox view codectest", "stepsize")
    for needle in ("0.6", "NO_ENTITIES", "blockslipperiness", "0.9"):
        if needle.lower() in view.lower():
            ok(f"display shows {needle}")
        else:
            bad(f"display {needle}", view)
    if "minecraft:ice" in view:
        bad("namespace stripping", f"block list still shown namespaced: {view}")
    elif "ice" in view:
        ok("block list shown as 'ice', without the namespace")
    else:
        bad("namespace stripping", f"the block list is missing entirely: {view}")

    # the code itself rides in the copy button's click event, so a console reply only shows the wrapper
    exported = run("wo -sandbox export codectest")
    if "exported sandbox" in exported.lower() and "share code" in exported.lower():
        ok("export renders the sandbox as a share code")
    else:
        bad("export", exported)
    rcon.run("wo -sandbox delete codectest")


def drill_block_canonicalisation():
    """The half of the canonical form the probe cannot reach: a block name needs the live registry."""
    print("\nan imported block list lands in one spelling")
    rcon.run("wo -sandbox delete spellingtest")
    # the same block, three ways a share code could spell it. All three are one entry, not three,
    # or the client is sent the same block twice and whichever arrives last wins
    code = share("3:0.9 ICE", "3:0.95 ice", "3:0.98 minecraft:ice", "22:PACKED_ICE")
    expect("a share code of one block spelled three ways imports",
           f'wo -sandbox import "{code}" spellingtest', "imported")
    view = expect("the sandbox lists its settings", "wo -sandbox view spellingtest", "blockslipperiness")
    if view.lower().count("blockslipperiness") != 2:  # blockslipperiness plus removeblockslipperiness
        bad("one entry per block", f"the same block landed under more than one key: {view}")
    elif "0.9 " in view or "0.95" in view:
        bad("last spelling wins", f"an earlier spelling survived alongside the last: {view}")
    else:
        ok("three spellings of one block collapsed to a single entry")
    if "ICE" in view or "PACKED_ICE" in view:
        bad("stored casing", f"a block was stored as typed rather than canonically: {view}")
    else:
        ok("the stored spelling is the canonical one")
    rcon.run("wo -sandbox delete spellingtest")

    print("\nand the same set typed in two orders is one setting, not two")
    rcon.run("wo -sandbox delete ordertest")
    code = share("3:0.9 ice,packed_ice", "3:0.4 packed_ice,ice")
    expect("a share code holding one set twice imports", f'wo -sandbox import "{code}" ordertest', "imported")
    view = run("wo -sandbox view ordertest")
    if view.lower().count("blockslipperiness") != 1:
        bad("one entry per set", f"one named set landed under two keys: {view}")
    elif "0.9" in view:
        bad("last order wins", f"the earlier order survived alongside the later: {view}")
    else:
        ok("two orders of one set collapsed to a single entry")
    rcon.run("wo -sandbox delete ordertest")

    print("\nand a share code spells a list with spaces, the way an invocation does")
    rcon.run("wo -sandbox delete spacedtest")
    code = share("3:0.9 ice stone")
    expect("a spaced list imports", f'wo -sandbox import "{code}" spacedtest', "imported")
    # the second block rides in the hover, which no console reply carries, so what the line can show
    # is that the list holds more than the one entry it names
    held = settings_of(run("wo -sandbox view spacedtest"))
    if held == [("blockslipperiness", "0.9, [2]")]:
        ok("a spaced list is one setting naming more than one block, not one block")
    else:
        bad("spaced list", f"a block went missing between the code and storage: {held}")
    rcon.run("wo -sandbox delete spacedtest")


def drill_setting_folding():
    """A list entry belongs to exactly one setting. A second invocation carrying the same value joins
    the entry that already holds one; one carrying another value takes the entry off it. Two settings
    naming one block would be two packet entries for that block, and the wire order between them is a
    HashMap's, so the client would apply whichever arrived last."""
    print("\nsettings that name a list fold into one entry per block")
    for label, code, expected in (
            ("two blocks at one value are one setting",
             ("3:0.9 ice", "3:0.9 packed_ice"),
             [("blockslipperiness", "0.9, [2]")]),
            ("and two values stay two settings",
             ("3:0.9 ice", "3:0.4 stone"),
             [("blockslipperiness", "0.9, ice"), ("blockslipperiness", "0.4, stone")]),
            ("a later value takes the block off the entry that held it",
             ("3:0.9 ice", "3:0.9 packed_ice", "3:0.4 ice"),
             [("blockslipperiness", "0.9, packed_ice"), ("blockslipperiness", "0.4, ice")]),
            ("and an entry that loses every block it named disappears",
             ("3:0.9 ice", "3:0.4 ice"),
             [("blockslipperiness", "0.4, ice")]),
            ("a setting that is nothing but a list is always one entry",
             ("22:ice", "22:packed_ice", "22:stone"),
             [("removeblockslipperiness", "[3]")]),
            ("setblocksetting folds inside one per-block setting",
             ("26:JUMPS 2 stone", "26:JUMPS 2 ice"),
             [("setblocksetting", "JUMPS, 2.0, [2]")]),
            ("and never across two",
             ("26:JUMPS 2 stone", "26:COYOTE_TIME 2 stone"),
             [("setblocksetting", "JUMPS, 2.0, stone"), ("setblocksetting", "COYOTE_TIME, 2.0, stone")]),
    ):
        rcon.run("wo -sandbox delete foldtest")
        run(f'wo -sandbox import "{share(*code)}" foldtest')
        held = settings_of(run("wo -sandbox view foldtest"))
        if held == expected:
            ok(label)
        else:
            bad(label, f"{list(code)} landed as {held}, not as {expected}")
    rcon.run("wo -sandbox delete foldtest")


def drill_unwritable_lists():
    """A list entry the client cannot build an Identifier from throws inside its handler, and its
    handler wraps the whole packet -- so one bad block costs every other setting in the context."""
    print("\na list the wire cannot carry never reaches storage")
    for label, entry in (("an unknown block spelled with a capital", "STONEE"),
                         ("a path with a second colon", "a:b:c"),
                         ("separators only", ","),
                         ("a non-ascii letter", "İce")):
        rcon.run("wo -sandbox delete badlisttest")
        # the sound setting rides in the same code: it must survive the entry that does not
        code = share(f"22:{entry}", "1:0.6")
        reply = run(f'wo -sandbox import "{code}" badlisttest')
        view = run("wo -sandbox view badlisttest")
        if "removeblockslipperiness" in view.lower():
            bad(label, f"{entry!r} reached storage: {view}")
        elif "skip" not in reply.lower():
            bad(label, f"{entry!r} was dropped without telling the importer: {reply}")
        elif "0.6" not in view:
            bad(label, f"the sound setting alongside {entry!r} was lost too: {view}")
        else:
            ok(f"{label} is refused, and the setting beside it still lands")
    rcon.run("wo -sandbox delete badlisttest")

    print("\nbut a key this server has never heard of still travels")
    rcon.run("wo -sandbox delete foreigntest")
    # another server's block: legal on the wire, absent from this registry, and refusing it would
    # quietly drop settings every time a share code crosses between servers
    code = share("22:othermod:turbo_ice")
    expect("a foreign namespaced key imports", f'wo -sandbox import "{code}" foreigntest', "imported")
    view = run("wo -sandbox view foreigntest")
    if "turbo_ice" in view:
        ok("a block only the other server knows survived the trip")
    else:
        bad("foreign key", f"a legal key was refused for not being in this registry: {view}")
    rcon.run("wo -sandbox delete foreigntest")


def drill_encodability_gate():
    print("\nthe encodability gate")
    rcon.run("wo -sandbox delete gatetest")
    # every second entry is one the wire cannot carry: an unknown enum, a float that is not a number,
    # and a resolution outside the range the client acts on. Each must be dropped rather than stored,
    # because a setting that throws on encode would be missing from every later sync for whoever holds it
    code = share("1:0.5", "27:NOT_A_MODE", "1:abc", "30:900", "27:VANILLA")
    reply = expect("a mixed share code still imports", f'wo -sandbox import "{code}" gatetest', "imported")
    if "skip" in reply.lower():
        ok("the unencodable entries were reported as skipped")
    else:
        bad("skip reporting", reply)
    stored = run("wo -sandbox view gatetest")
    if "not_a_mode" in stored.lower() or "abc" in stored.lower():
        bad("gate leaked", f"an unencodable setting was stored: {stored}")
    else:
        ok("no unencodable setting reached storage")
    rcon.run("wo -sandbox delete gatetest")


PROBE = r"""
package dev.muggel.wake.features.obu.protocol;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;

public final class WireProbe {
    private static final UUID BOAT = UUID.fromString("00112233-4455-6677-8899-aabbccddeeff");

    /** Every shape a value can arrive in that is not a number, a boolean or an enum constant */
    private static final List<String> HOSTILE = List.of(
            "", " ", "abc", "NaN", "Infinity", "-Infinity", "1e400", "0x10", "99999999999999999999",
            "-", "+", "1,2", "true ", "VANILLA;", "İce");

    public static void main(String[] args) {
        table();
        buffer();
        packets();
        keys();
        canonical();
        splits();
        defaults();
        throwSurface();
    }

    private static void table() {
        for (OBUDefinition def : OBUDefinition.values()) {
            StringBuilder types = new StringBuilder();
            for (SettingType type : def.types()) {
                if (!types.isEmpty()) types.append(',');
                types.append(type.name());
            }
            say("def_" + def.name(), def.id() + " " + types + " " + def.defaultValue());
        }
        say("channels", OBUDefinition.CHANNEL_SETTINGS + " " + OBUDefinition.CHANNEL_CONTEXT
                + " " + OBUDefinition.CHANNEL_CONFIGURATION);
        say("versions", OBUDefinition.PACKET_RESEND_VERSION + " " + OBUDefinition.LATEST_SUPPORTED_VERSION
                + " " + new TreeSet<>(OBUDefinition.REJECTED_VERSIONS));
        say("personal", OBUDefinition.CONTEXT_PERSONAL);
    }

    private static void buffer() {
        PacketByteBuf buf = new PacketByteBuf();
        buf.writeBoolean(true);
        buf.writeBoolean(false);
        buf.writeByte((byte) 5);
        buf.writeShort((short) 0x1234);
        buf.writeShort((short) -2);
        buf.writeInt(0x01020304);
        buf.writeInt(-1);
        buf.writeFloat(0.6f);
        buf.writeDouble(-0.03999999910593033);
        say("buf_scalars", hex(buf.toBytes()));

        say("str_ascii", hex(string("wake:personal")));
        say("str_empty", hex(string("")));
        say("str_varint2", hex(string("x".repeat(200))).substring(0, 4));
        say("str_utf8", hex(string("é")));
        say("str_at_cap", refused(() -> string("x".repeat(32767))));
        say("str_over_cap", refused(() -> string("x".repeat(32768))));
        say("str_3byte", hex(string("€")));
        say("str_surrogate", hex(string("🚀")));
        // the client caps characters and bytes both; a character is at most three bytes, so the
        // widest string the character cap allows is exactly the byte cap and never more
        say("str_3byte_at_cap", refused(() -> string("€".repeat(32767))));
        say("str_surrogate_at_cap", refused(() -> string("🚀".repeat(16383) + "x")));
        say("str_surrogate_over_cap", refused(() -> string("🚀".repeat(16384))));
    }

    private static void packets() {
        say("pkt_reset", hex(PacketWriter.resetContext()));
        say("pkt_drop", hex(PacketWriter.dropContext(OBUDefinition.CONTEXT_PERSONAL)));
        say("pkt_switch", hex(PacketWriter.switchContext(OBUDefinition.CONTEXT_PERSONAL)));
        say("pkt_version", hex(PacketWriter.versionRequest()));
        say("pkt_store_empty", hex(PacketWriter.storeContext(OBUDefinition.CONTEXT_PERSONAL, List.of())));
        say("pkt_entity_empty", hex(PacketWriter.entityContext(BOAT, List.of())));
        say("pkt_store_scalars", hex(PacketWriter.storeContext(OBUDefinition.CONTEXT_PERSONAL, scalars())));
        say("pkt_store_skips", hex(PacketWriter.storeContext(OBUDefinition.CONTEXT_PERSONAL, withUnwritableRows())));
        say("pkt_raw_impulse", hex(PacketWriter.rawSetting(of(OBUDefinition.applyimpulse, "1", "2", "3"))));
    }

    /** One of every scalar shape the wire carries, in the order they are handed over */
    private static List<OBUSetting> scalars() {
        List<OBUSetting> settings = new ArrayList<>();
        settings.add(of(OBUDefinition.stepsize, "0.6"));
        settings.add(of(OBUDefinition.falldamage, "TRUE"));
        settings.add(of(OBUDefinition.coyotetime, "3"));
        settings.add(of(OBUDefinition.boatgravity, "-0.04"));
        settings.add(of(OBUDefinition.setcollisionresolution, "5"));
        settings.add(of(OBUDefinition.collisionmode, "no_entities"));
        settings.add(of(OBUDefinition.clearslipperiness));
        return settings;
    }

    /** Rows an older build could have left behind, or another server still writes: stepped over, not thrown on */
    private static List<OBUSetting> withUnwritableRows() {
        List<OBUSetting> settings = new ArrayList<>();
        settings.add(of(OBUDefinition.stepsize, "0.6"));
        settings.add(new OBUSetting(OBUDefinition.stepsize, List.of("not-a-number")));
        settings.add(new OBUSetting(OBUDefinition.blockslipperiness, List.of("0.9")));
        settings.add(of(OBUDefinition.coyotetime, "3"));
        return settings;
    }

    private static void keys() {
        say("key_singular", of(OBUDefinition.stepsize, "0.6").uniqueKey());
        say("key_enum_only", of(OBUDefinition.collisionmode, "vanilla").uniqueKey());
        say("key_blocks", key(OBUDefinition.blockslipperiness, "0.9", "ice"));
        say("key_blocks_other_value", key(OBUDefinition.blockslipperiness, "0.98", "ice"));
        say("key_blocks_other_set", key(OBUDefinition.blockslipperiness, "0.9", "stone"));
        say("key_remove_blocks", key(OBUDefinition.removeblockslipperiness, "ice"));
        say("key_per_block", key(OBUDefinition.setblocksetting, "JUMP_FORCE", "0.36", "ice"));
        say("key_per_block_other_setting", key(OBUDefinition.setblocksetting, "MAX_SPEED", "0.36", "ice"));
        say("key_filter", key(OBUDefinition.addcollisionfilter, "pig"));
        say("key_blocks_two", key(OBUDefinition.blockslipperiness, "0.9", "ice,stone"));
        say("key_blocks_reordered", key(OBUDefinition.blockslipperiness, "0.9", "stone,ice"));
        say("key_per_block_reordered", key(OBUDefinition.setblocksetting, "JUMP_FORCE", "0.36", "stone,ice"));
        say("key_short_args", key(OBUDefinition.blockslipperiness, "0.9"));
        say("key_no_args", key(OBUDefinition.clearslipperiness));
        say("key_uncanonical", key(OBUDefinition.blockslipperiness, "0.9", "ICE"));
    }

    private static void canonical() {
        say("of_bool_case", arg(OBUDefinition.falldamage, "TrUe"));
        say("of_bool_bad", arg(OBUDefinition.falldamage, "yes"));
        say("of_float_whole", arg(OBUDefinition.stepsize, "1"));
        say("of_float_bad", arg(OBUDefinition.stepsize, "abc"));
        say("of_float_nan", arg(OBUDefinition.stepsize, "NaN"));
        say("of_float_infinite", arg(OBUDefinition.stepsize, "Infinity"));
        say("of_int_padded", arg(OBUDefinition.coyotetime, "007"));
        say("of_int_fractional", arg(OBUDefinition.coyotetime, "1.5"));
        say("of_enum_case", arg(OBUDefinition.collisionmode, "no_entities"));
        say("of_enum_bad", arg(OBUDefinition.collisionmode, "NONSENSE"));
        say("of_resolution_floor", arg(OBUDefinition.setcollisionresolution, "0"));
        say("of_resolution_ceiling", arg(OBUDefinition.setcollisionresolution, "50"));
        say("of_resolution_over", arg(OBUDefinition.setcollisionresolution, "51"));
        say("of_too_few", arg(OBUDefinition.applyimpulse, "1", "2"));
        say("of_extra_dropped", arg(OBUDefinition.stepsize, "0.6", "0.7"));
        say("of_none_needed", arg(OBUDefinition.clearslipperiness));
    }

    private static void splits() {
        say("split_none", OBUDefinition.clearslipperiness.splitInvocation(""));
        say("split_one", OBUDefinition.stepsize.splitInvocation("  1.25  "));
        say("split_one_empty", OBUDefinition.stepsize.splitInvocation(""));
        say("split_two", OBUDefinition.blockslipperiness.splitInvocation("0.9 ice, packed_ice"));
        say("split_three", OBUDefinition.setblocksetting.splitInvocation("JUMP_FORCE 0.36 ice stone"));
        say("split_short", OBUDefinition.blockslipperiness.splitInvocation("0.9"));
        // the shape a share code carries: both import doors fold trailing tokens into the last argument
        say("split_spaced_list", OBUDefinition.blockslipperiness.splitInvocation("0.9 ice stone"));
    }

    /** A stored default and a fresh one must be the same string, or one setting lives under two spellings */
    private static void defaults() {
        List<String> drifted = new ArrayList<>();
        for (OBUDefinition def : OBUDefinition.values()) {
            String value = def.defaultValue();
            if (value == null) continue;
            OBUSetting setting = OBUSetting.of(def, def.splitInvocation(value));
            String back = setting == null ? "refused" : String.join(" ", setting.args());
            if (!back.equals(value)) drifted.add(def.name() + " " + value + " -> " + back);
        }
        say("defaults_roundtrip", drifted.isEmpty() ? "stable" : String.join(", ", drifted));
    }

    /** OBUSetting.of and PacketWriter.writeSettings both catch IllegalArgumentException and nothing else */
    private static void throwSurface() {
        List<String> escaped = new ArrayList<>();
        for (OBUDefinition def : OBUDefinition.values()) {
            for (SettingType type : def.types()) {
                if (type.isList()) continue;  // needs the block registry, so it is drilled over RCON
                for (String hostile : HOSTILE) {
                    try {
                        type.canonical(hostile);
                    } catch (IllegalArgumentException refused) {
                        // the one hierarchy both callers catch
                    } catch (Throwable escapee) {
                        escaped.add(type + "(" + hostile + ") -> " + escapee.getClass().getName());
                    }
                }
            }
        }
        say("throw_surface", escaped.isEmpty() ? "IllegalArgumentException only" : String.join(", ", escaped));
    }

    private static String key(OBUDefinition def, String... args) {
        return new OBUSetting(def, List.of(args)).uniqueKey();
    }

    private static String arg(OBUDefinition def, String... args) {
        OBUSetting setting = OBUSetting.of(def, List.of(args));
        return setting == null ? "refused" : String.join(" ", setting.args());
    }

    private static OBUSetting of(OBUDefinition def, String... args) {
        OBUSetting setting = OBUSetting.of(def, List.of(args));
        if (setting == null) {
            throw new IllegalStateException("the probe built an unwritable " + def);
        }
        return setting;
    }

    private static String refused(Runnable body) {
        try {
            body.run();
            return "accepted";
        } catch (IllegalArgumentException tooLong) {
            return "refused";
        }
    }

    private static byte[] string(String value) {
        PacketByteBuf buf = new PacketByteBuf();
        buf.writeString(value);
        return buf.toBytes();
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }

    private static void say(String name, List<String> value) {
        say(name, value.size() + ":" + String.join("|", value));
    }

    private static void say(String name, String value) {
        System.out.println(name + "\t" + value);
    }
}
"""

# Read out of OBUSOURCE: the ordinal in ClientboundSettingsPacket, the argument order in
# docs/developers/settings.md, and the vanilla value in ISettingContext.VANILLA. The gaps -- 8, 15,
# 18, 24, 25, 33 -- are SET_MODE, RESEND_VERSION, SET_EXCLUSIVE_MODE, the two MODE_SERIES and
# COMPOUND: packets Wake does not expose as settings.
DEFINITIONS = [
    (0, "reset", "", None),
    (1, "stepsize", "FLOAT", "0.0"),
    (2, "defaultslipperiness", "FLOAT", "0.6"),
    (3, "blockslipperiness", "FLOAT,BLOCK_LIST", None),
    (4, "falldamage", "BOOLEAN", "true"),
    (5, "waterelevation", "BOOLEAN", "false"),
    (6, "aircontrol", "BOOLEAN", "false"),
    (7, "jumpforce", "FLOAT", "0.0"),
    (9, "boatgravity", "DOUBLE", "-0.03999999910593033"),
    (10, "setyawaccel", "FLOAT", "1.0"),
    (11, "setforwardaccel", "FLOAT", "0.04"),
    (12, "setbackwardaccel", "FLOAT", "0.005"),
    (13, "setturnforwardaccel", "FLOAT", "0.005"),
    (14, "allowaccelstacking", "BOOLEAN", "false"),
    (16, "underwatercontrol", "BOOLEAN", "false"),
    (17, "surfacewatercontrol", "BOOLEAN", "false"),
    (19, "coyotetime", "INT", "0"),
    (20, "waterjumping", "BOOLEAN", "false"),
    (21, "swimforce", "FLOAT", "0.0"),
    (22, "removeblockslipperiness", "BLOCK_LIST", None),
    (23, "clearslipperiness", "", None),
    (26, "setblocksetting", "SETTING_ENUM,FLOAT,BLOCK_LIST", None),
    (27, "collisionmode", "COLLISION_ENUM", "VANILLA"),
    (28, "stepwhilefalling", "BOOLEAN", "false"),
    (29, "setinterpolationten", "BOOLEAN", "false"),
    (30, "setcollisionresolution", "BYTE", "1"),
    (31, "addcollisionfilter", "ENTITY_LIST", None),
    (32, "clearcollisionfilter", "", None),
    (34, "setwalltapmultiplier", "FLOAT", "0.0"),
    (35, "setjumps", "INT", "1"),
    (36, "setscale", "FLOAT", "1.0"),
    (37, "setstepupslipperiness", "FLOAT", "1.0"),
    (38, "setresetonworldload", "BOOLEAN", "true"),
    (39, "fixdoublewaterelevation", "BOOLEAN", "false"),
    (40, "setlateralslipperiness", "FLOAT", "1.0"),
    (41, "setbrakeslipperiness", "FLOAT", "1.0"),
    (42, "applyimpulse", "DOUBLE,DOUBLE,DOUBLE", None),
    (43, "applyimpulserelative", "DOUBLE,DOUBLE,DOUBLE", None),
    (44, "setmultistepping", "BOOLEAN", "false"),
    (45, "setmaxspeed", "FLOAT", "-1.0"),
    (46, "setmaxspeedresistance", "FLOAT", "0.0"),
    (47, "sethoneycompat", "BOOLEAN", "false"),
]

PERSONAL = "wake:personal"


def i16(value):
    return struct.pack(">h", value).hex()


def i32(value):
    return struct.pack(">i", value).hex()


def f32(value):
    return struct.pack(">f", value).hex()


def f64(value):
    return struct.pack(">d", value).hex()


def mcstring(value):
    """A varint byte-length prefix and then the utf-8 bytes, the way the client's PacketByteBuf reads one"""
    raw = value.encode("utf-8")
    prefix, length = b"", len(raw)
    while length & ~0x7F:
        prefix += bytes([(length & 0x7F) | 0x80])
        length >>= 7
    return (prefix + bytes([length]) + raw).hex()


def toolchain(name):
    home = os.environ.get("JAVA_HOME")
    for candidate in (name, f"{name}.exe"):
        if home and (Path(home) / "bin" / candidate).exists():
            return str(Path(home) / "bin" / candidate)
    return name


def compile_classpath():
    """The two jars the protocol package needs to load: paper-api for the argument types, brigadier for their base"""
    gradle = Path(os.environ.get("GRADLE_USER_HOME") or (Path.home() / ".gradle"))
    declared = COMPILED_AGAINST.search((ROOT / "build.gradle.kts").read_text(encoding="utf-8"))
    if not declared:
        raise SystemExit("no compileOnly paper-api line in build.gradle.kts -- nothing says which api the classes hold")
    jars = []
    for pattern in (f"caches/modules-2/files-2.1/io.papermc.paper/paper-api/{declared.group(1)}/*/paper-api-*.jar",
                    "caches/modules-2/files-2.1/com.mojang/brigadier/*/*/brigadier-*.jar"):
        found = [jar for jar in gradle.glob(pattern) if "sources" not in jar.name]
        if not found:
            raise SystemExit(f"no jar matching {pattern} -- run ./gradlew compileJava once.")
        jars.append(str(sorted(found)[-1]))
    return os.pathsep.join([str(CLASSES)] + jars)


def probe():
    """Compiles the probe against the built classes and returns everything it printed, as name -> value."""
    if not CLASSES.is_dir():
        raise SystemExit(f"{CLASSES} is missing -- run ./gradlew compileJava first.")
    classpath = compile_classpath()
    with tempfile.TemporaryDirectory() as work:
        source = Path(work) / "WireProbe.java"
        source.write_text(PROBE, encoding="utf-8")
        built = subprocess.run([toolchain("javac"), "--release", "21", "-cp", classpath, "-d", work, str(source)],
                               capture_output=True, text=True, encoding="utf-8", errors="replace")
        if built.returncode != 0:
            raise SystemExit(f"the probe did not compile against {CLASSES}:\n{built.stderr}")
        ran = subprocess.run([toolchain("java"), "-cp", os.pathsep.join([work, classpath]),
                              "dev.muggel.wake.features.obu.protocol.WireProbe"],
                             capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=120)
        if ran.returncode != 0:
            raise SystemExit(f"the probe failed to run:\n{ran.stdout}\n{ran.stderr}")
    return dict(line.split("\t", 1) for line in ran.stdout.splitlines() if "\t" in line)


def fact(facts, name, expected):
    got = facts.get(name)
    if got == expected:
        ok(f"{name} -> {expected if len(expected) < 60 else expected[:57] + '...'}")
    else:
        bad(name, f"{got!r}, expected {expected!r}")


def drill_definitions(facts):
    print("\nevery definition still carries the id and arguments the mod reads")
    for ident, name, types, default in DEFINITIONS:
        fact(facts, f"def_{name}", f"{ident} {types} {default if default is not None else 'null'}")
    declared = {key[len("def_"):] for key in facts if key.startswith("def_")}
    unexpected = declared - {name for _, name, _, _ in DEFINITIONS}
    if unexpected:
        bad("definition table", f"settings the drill does not know: {sorted(unexpected)}")
    else:
        ok("no setting exists that this table has not checked")
    fact(facts, "channels", "openboatutils:settings openboatutils:context openboatutils:configuration")
    # 15 is RESEND_VERSION's ordinal, 22 is OpenBoatUtils.VERSION, and the rejects are every row
    # docs/developers/versions.md marks Bugged or Reject
    fact(facts, "versions", "15 22 [8, 12, 15, 20, 21]")
    fact(facts, "personal", PERSONAL)


def drill_buffer(facts):
    print("\nthe buffer writes what minecraft reads")
    fact(facts, "buf_scalars", "01" + "00" + "05" + i16(0x1234) + i16(-2)
         + i32(0x01020304) + i32(-1) + f32(0.6) + f64(-0.03999999910593033))
    fact(facts, "str_ascii", mcstring(PERSONAL))
    fact(facts, "str_empty", "00")
    fact(facts, "str_varint2", "c801")
    # the length prefix counts bytes, not characters -- the snippet in the mod's own docs counts characters
    fact(facts, "str_utf8", mcstring("é"))
    fact(facts, "str_at_cap", "accepted")
    fact(facts, "str_over_cap", "refused")
    fact(facts, "str_3byte", mcstring("€"))
    fact(facts, "str_surrogate", mcstring("🚀"))
    # readString caps characters at 32767 and bytes at 3x that. A character is at most three utf-8
    # bytes and a surrogate pair spends four across two characters, so the character guard alone can
    # never let a string past the byte guard -- 32767 three-byte characters is the byte cap exactly
    fact(facts, "str_3byte_at_cap", "accepted")
    fact(facts, "str_surrogate_at_cap", "accepted")
    fact(facts, "str_surrogate_over_cap", "refused")


def drill_packets(facts):
    print("\nand every packet is framed the way ClientboundContextPacket reads it")
    stepsize = i16(1) + f32(0.6)
    coyote = i16(19) + i32(3)
    scalars = (stepsize + i16(4) + "01" + coyote + i16(9) + f64(-0.04)
               + i16(30) + "05" + i16(27) + i16(2) + i16(23))
    fact(facts, "pkt_reset", i16(0))
    fact(facts, "pkt_switch", i16(1) + mcstring(PERSONAL))
    fact(facts, "pkt_drop", i16(2) + mcstring(PERSONAL))
    fact(facts, "pkt_version", i16(15))
    fact(facts, "pkt_store_empty", i16(3) + mcstring(PERSONAL) + i32(0))
    fact(facts, "pkt_entity_empty", i16(4) + mcstring("00112233-4455-6677-8899-aabbccddeeff") + i32(0))
    fact(facts, "pkt_store_scalars", i16(3) + mcstring(PERSONAL) + i32(7) + scalars)
    # the two rows in the middle cannot be written; the count and the payload must both step over them
    fact(facts, "pkt_store_skips", i16(3) + mcstring(PERSONAL) + i32(2) + stepsize + coyote)
    fact(facts, "pkt_raw_impulse", i16(42) + f64(1) + f64(2) + f64(3))


def drill_keys(facts):
    print("\nand a setting's identity is its blocks, never its value")
    fact(facts, "key_singular", "1")
    fact(facts, "key_enum_only", "27")
    fact(facts, "key_blocks", "3:ice")
    fact(facts, "key_blocks_other_value", "3:ice")
    fact(facts, "key_blocks_other_set", "3:stone")
    fact(facts, "key_remove_blocks", "22:ice")
    fact(facts, "key_per_block", "26:JUMP_FORCE:ice")
    fact(facts, "key_per_block_other_setting", "26:MAX_SPEED:ice")
    fact(facts, "key_filter", "31:pig")
    # a named set is the same set whichever order it was typed in, or setting it again in another
    # order stores a second row and which of the two the client ends up applying is arbitrary
    fact(facts, "key_blocks_two", "3:ice,stone")
    fact(facts, "key_blocks_reordered", "3:ice,stone")
    fact(facts, "key_per_block_reordered", "26:JUMP_FORCE:ice,stone")
    fact(facts, "key_short_args", "3")
    fact(facts, "key_no_args", "23")
    # a key is built from the argument as stored, so an uncanonical one is a *different* block as far
    # as the key is concerned -- which is why nothing uncanonical may reach storage. The import paths
    # that used to let one through are drilled against a live registry further down
    fact(facts, "key_uncanonical", "3:ICE")
    if facts.get("key_uncanonical") == facts.get("key_blocks"):
        bad("key_uncanonical", "uniqueKey canonicalises, so storage no longer has to")
    else:
        ok("one block under two spellings is two keys, so storage must canonicalise first")


def drill_canonical(facts):
    print("\nand a value that did not come from a command argument arrives in one spelling or not at all")
    fact(facts, "of_bool_case", "true")
    fact(facts, "of_bool_bad", "refused")
    fact(facts, "of_float_whole", "1.0")
    fact(facts, "of_float_bad", "refused")
    fact(facts, "of_float_nan", "refused")
    fact(facts, "of_float_infinite", "refused")
    fact(facts, "of_int_padded", "7")
    fact(facts, "of_int_fractional", "refused")
    fact(facts, "of_enum_case", "NO_ENTITIES")
    fact(facts, "of_enum_bad", "refused")
    # the client divides its movement step by this and ignores anything outside 1..50
    fact(facts, "of_resolution_floor", "refused")
    fact(facts, "of_resolution_ceiling", "50")
    fact(facts, "of_resolution_over", "refused")
    fact(facts, "of_too_few", "refused")
    fact(facts, "of_extra_dropped", "0.6")
    fact(facts, "of_none_needed", "")

    print("\nand an exported invocation splits back into the arguments it came from")
    fact(facts, "split_none", "0:")
    fact(facts, "split_one", "1:1.25")
    fact(facts, "split_one_empty", "1:")
    fact(facts, "split_two", "2:0.9|ice, packed_ice")
    fact(facts, "split_three", "3:JUMP_FORCE|0.36|ice stone")
    fact(facts, "split_short", "1:0.9")
    fact(facts, "split_spaced_list", "2:0.9|ice stone")

    print("\nand nothing else escapes the seam the callers guard")
    # a default that does not survive its own canonicalisation would store under one spelling and
    # arrive under another, so -defaults and a stored row would disagree about the same setting
    fact(facts, "defaults_roundtrip", "stable")
    fact(facts, "throw_surface", "IllegalArgumentException only")


def main():
    global rcon
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25575)
    parser.add_argument("--password", default="wake-dev")
    parser.add_argument("--encoding", action="store_true", help="the wire half alone, without a server")
    args = parser.parse_args()

    facts = probe()
    drill_definitions(facts)
    drill_buffer(facts)
    drill_packets(facts)
    drill_keys(facts)
    drill_canonical(facts)

    if not args.encoding:
        try:
            rcon = Rcon(args.host, args.port, args.password)
        except OSError as error:
            raise SystemExit(f"cannot reach RCON at {args.host}:{args.port} ({error}). "
                             f"Start ./gradlew runServer first, or pass --encoding.")

        drill_parsing()
        drill_storage()
        drill_block_canonicalisation()
        drill_setting_folding()
        drill_unwritable_lists()
        drill_encodability_gate()

        print("\nthe vanilla defaults the probe checked, as the command answers them")
        expect("a setting with a default", "wo -defaults stepsize", "0.0")
        expect("the gravity double, in full", "wo -defaults boatgravity", "-0.03999999910593033")
        expect("a setting that only adds to a set", "wo -defaults blockslipperiness", "no ")
        expect("a name no setting carries", "wo -defaults nonsense", "no ")

        print("\nadmin utility")
        expect("query-context-quantity", "wo -settings query-context-quantity", "total")

    print()
    if failures:
        print(f"{len(failures)} check(s) failed: {', '.join(failures)}")
        return 1
    print("all OBU protocol drills passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
