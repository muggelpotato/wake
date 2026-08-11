#!/usr/bin/env python3
"""Drills for the drydock module's commands.

Covers what a console can judge about `/dd boostpad`: the values `add` stores and hands back, that
adding a block twice edits the one pad instead of undoing the switch an admin threw, the order the
listing comes out in, that a row stored under a key no command would have written is still one every
command can reach, where each early-out switch lands, what the global cooldown stores and prints,
that both switches survive a reload and a module cycle, and that the pad flag, the axes and the
cooldown ride the export round trip -- the state sweep carries the settings, but a pad's own
`enabled` lives in the module's table and has to be checked from the other end.

Two of them watch the export and the import rather than a command. The export is written straight out
of `BoostpadConfig`, so one reads the record's field names out of the source and holds the export to
them -- a component added to the record and left out of its export would go missing from every backup
without a command ever misbehaving. The other drives the values only an edited backup can deliver: a
delay past the ceiling the `add` argument enforces, a key that parses fine and names no block on this
server, and a name under `boostpads` whose value is not a block of settings at all. A third writes
straight into the table, which is the one door a row in a shape the columns do not declare comes in
through -- and the read that trips over such a row is the read that loads every other pad.

One of them asks Paper rather than Wake: what it costs a server to have the feature switched on is
whether the move listener is registered, and only Paper can say. The switch's own half of that
decision is drills_module.py, which cycles it; what is here is the other half, a pad that could fire.

Which spellings of a block key parse and which are refused is drills_arguments.py, propagation to a
second backend is drills_changelog.py, and the outage half -- a pad written while the database is
unreachable, the listing a module re-enabled mid-outage prints, and the add it refuses -- is
drills_database.py.

Values are read back out of the listing and out of a fresh export, so what is checked is the cache
rather than the file the export happens to be written into: a write that never reached the cache
fails here even when the table took it.

    python testenv/drills_drydock.py       # needs a server up (./gradlew runServer)

Runs against sqlite and mariadb alike, bar the one row only sqlite will hold. Exits non-zero if a
drill fails.
"""

import argparse
import os
import re
import sqlite3
import sys
import time
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from drills import (WAKE, Rcon, bad, detect_backend, failures, ok, set_module_enabled, state, step,  # noqa: E402
                    switch, write_state_raw)

ROOT = Path(__file__).resolve().parents[1]
EXPORT = WAKE / "exports" / "drydock_data.yml"
RECORD = ROOT / "src" / "main" / "java" / "dev" / "muggel" / "wake" / "features" / "drydock" / "boostpads" / "BoostpadConfig.java"
SETTLE = 1.5
PAD = "minecraft:smooth_stone"  # a block the shipped defaults leave without a pad
SHORT = "smooth_stone"
# added in an order the sort has to undo, so an ordering that only mirrors insertion is caught
ORDERED = ["minecraft:sandstone", "minecraft:bookshelf", "minecraft:netherrack"]
# stored bare, so it sorts after every "minecraft:" key while printing before "sandstone"
FOREIGN = "prismarine"
FOREIGN_BODY = "    enabled: true\n    force_x: 0.0\n    force_y: 0.0\n    force_z: 0.7\n    delay_ms: 250\n    padding: 1.0\n"
# a key that parses like any other and names no block on any server, carrying a delay no argument would take
UNKNOWN = "wake:not_a_block"
UNKNOWN_SHORT = UNKNOWN  # only "minecraft:" is stripped for display, so a foreign namespace prints whole
UNKNOWN_BODY = ("    enabled: true\n    force_x: 0.0\n    force_y: 0.0\n    force_z: 0.5\n"
                "    delay_ms: 99999999999\n    padding: 4.0\n")
CAPPED = str(2 ** 31 - 1)  # BoostpadConfig.MAX_DELAY_MS, which is the add argument's own ceiling
# a name under boostpads whose value is not a block of settings at all
SCALAR = "minecraft:andesite"
SCALAR_SHORT = "andesite"
# a row only a hand-run INSERT leaves, and only on sqlite
MALFORMED = "wake:malformed_row"
MOVE_EVENT = "org.bukkit.event.player.PlayerMoveEvent"
# one listing line: the block, then the six values it is printed with
ITEM = re.compile(r"●\s+(\S+)\s+\S+\s+(-?[\d.]+)x\s+(-?[\d.]+)y\s+(-?[\d.]+)z,\s+(\d+)ms,\s+(-?[\d.]+)")
PARSE_ERROR = ("Incorrect argument", "Unknown or incomplete", "Expected", "Invalid block",
               "not in the boostpad list", "<--[HERE]")
AXES = ["x", "y", "z"]
DEFAULT_AXES = {"x": "false", "y": "true", "z": "false"}
COOLDOWN_KEY = "drydock.boostpads_global_cooldown_ms"
COOLDOWN = re.compile(r"Global cooldown:\s*(\S+)")


def truthy(label, condition, detail=""):
    (ok if condition else bad)(label if condition else f"{label} -- {detail}")


def accepts(rcon: Rcon, label, command):
    reply = rcon.run(command)
    truthy(label, not any(marker in reply for marker in PARSE_ERROR), reply.strip()[:160])


def refuses(rcon: Rcon, label, command):
    reply = rcon.run(command)
    truthy(label, any(marker in reply for marker in PARSE_ERROR), f"{command!r} was accepted -> {reply.strip()[:160]}")


def pads(rcon: Rcon):
    """Every pad the listing prints, as block -> the values beside it."""
    return {found[0]: found[1:] for found in ITEM.findall(rcon.run("dd boostpad list"))}


def order(rcon: Rcon):
    return [found[0] for found in ITEM.findall(rcon.run("dd boostpad list"))]


def registrations(rcon: Rcon):
    """How often VehiclePath sits on the move event, read off Paper rather than off Wake."""
    return rcon.run(f"paper dumplisteners {MOVE_EVENT}").count("VehiclePath")


def export(rcon: Rcon, timeout=20):
    """Writes the module's cache out and answers with the file, so a per-pad flag can be read back."""
    before = EXPORT.stat().st_mtime_ns if EXPORT.is_file() else 0
    rcon.run("wake database export drydock")
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if EXPORT.is_file() and EXPORT.stat().st_mtime_ns != before:
            return EXPORT.read_text(encoding="utf-8", errors="replace")
        time.sleep(0.5)
    return ""


def record(text, block):
    """One exported pad, as a dict of the fields under it."""
    found = re.search(rf"(?m)^  {re.escape(block)}:\n((?:    \w+: .*\n)+)", text)
    return dict(re.findall(r"    (\w+): (.*)", found.group(1))) if found else {}


def record_fields():
    """The record's own component names, snake_cased the way the export writes them.

    Read out of the source rather than restated here, so a field added to BoostpadConfig and left out
    of its export fails this instead of quietly going missing from every backup.
    """
    header = re.search(r"record BoostpadConfig\(\s*(.*?)\n\)", RECORD.read_text(encoding="utf-8"), re.S)
    names = [part.strip().split()[-1] for part in header.group(1).split(",")] if header else []
    return [re.sub(r"(?<!^)(?=[A-Z])", "_", name).lower() for name in names if name != "blockKey"]


def graft(rcon: Rcon, addition):
    """Writes `addition` into the boostpads section of a fresh export and imports it back."""
    text = export(rcon)
    if "boostpads:\n" not in text:
        bad("the export carries no boostpads section to graft onto")
        return False
    EXPORT.write_text(text.replace("boostpads:\n", f"boostpads:\n{addition}", 1), encoding="utf-8")
    rcon.run("wake database import drydock confirm")
    time.sleep(3)
    return True


def write_pad_raw(block_key, force_x):
    """Puts a boostpad row into sqlite behind the server's back, with force_x stored exactly as given."""
    connection = sqlite3.connect(str(WAKE / "wake.db"), timeout=10)
    try:
        connection.execute("REPLACE INTO wake_drydock_boostpads "
                           "(block_key, enabled, force_x, force_y, force_z, delay_ms, padding) "
                           "VALUES (?, 1, ?, 0, 0.3, 250, 1.0)", (block_key, force_x))
        connection.commit()
    finally:
        connection.close()


def drop_pad_raw(block_key):
    connection = sqlite3.connect(str(WAKE / "wake.db"), timeout=10)
    try:
        connection.execute("DELETE FROM wake_drydock_boostpads WHERE block_key = ?", (block_key,))
        connection.commit()
    finally:
        connection.close()


def reread_table(rcon: Rcon):
    """Cycles the module, which is what makes the server read a row nothing announced to it.

    A reload on its own will not: the store goes back to the table for the keys it was told moved, and a row
    written behind its back moved none. Disabling drops the store, and enabling builds one that has never read.
    """
    set_module_enabled("drydock", False)
    try:
        rcon.run("wake reload")
        time.sleep(2)
    finally:
        set_module_enabled("drydock", True)
        rcon.run("wake reload")
        time.sleep(3)


def axis_key(axis):
    return f"drydock.boostpads_early_out_{axis}"


def cooldown_line(rcon: Rcon):
    """What the listing prints for the global cooldown, which is read out of the cache like the pads are."""
    found = COOLDOWN.search(rcon.run("dd boostpad list"))
    return found.group(1) if found else None


def restore_axes(rcon: Rcon):
    for axis, value in DEFAULT_AXES.items():
        rcon.run(f"dd boostpad config early-out-{axis} {value}")
    time.sleep(SETTLE)


def drill_add_round_trip(rcon: Rcon, mariadb):
    """An admin types six numbers and reads them back off the listing, so they have to be the same six."""
    step("what add stores is what list prints")
    rcon.run(f"dd boostpad remove {PAD}")
    rcon.run(f"dd boostpad add {PAD} -0.25 0 0.4 250 1.5")
    time.sleep(SETTLE)
    values = pads(rcon).get(SHORT)
    truthy("every value landed exactly", values == ("-0.25", "0.00", "0.40", "250", "1.50"), str(values))

    step("padding is optional, and left out it is 1")
    rcon.run(f"dd boostpad add {PAD} 0 0 0.4 250")
    time.sleep(SETTLE)
    values = pads(rcon).get(SHORT)
    truthy("it came back at 1.00", values and values[4] == "1.00", str(values))

    step("a signed zero is stored and printed like the zero it behaves as")
    rcon.run(f"dd boostpad add {PAD} -0.0 0 0.4 250 1")
    time.sleep(SETTLE)
    values = pads(rcon).get(SHORT)
    truthy("the row still reads back whole", values and float(values[0]) == 0.0 and values[2] == "0.40", str(values))

    step("and the range is the argument's, so no executor ever sees a bad one")
    accepts(rcon, "padding at the floor", f"dd boostpad add {PAD} 0 0 .4 0 0")
    accepts(rcon, "padding at the cap", f"dd boostpad add {PAD} 0 0 .4 0 4")
    accepts(rcon, "no delay at all", f"dd boostpad add {PAD} 0 0 .4 0")
    refuses(rcon, "padding over the cap", f"dd boostpad add {PAD} 0 0 .4 0 4.1")
    refuses(rcon, "negative padding", f"dd boostpad add {PAD} 0 0 .4 0 -0.1")
    refuses(rcon, "a negative delay", f"dd boostpad add {PAD} 0 0 .4 -1")
    rcon.run(f"dd boostpad remove {PAD}")
    time.sleep(SETTLE)


def drill_add_edits(rcon: Rcon, mariadb):
    """add is the only way to change a pad's numbers, so it must not undo the switch toggle threw."""
    step("adding a block that already has a pad edits the one pad")
    rcon.run(f"dd boostpad remove {PAD}")
    time.sleep(SETTLE)
    first = rcon.run(f"dd boostpad add {PAD} 0 0 .4 250 1").strip()
    second = rcon.run(f"dd boostpad add {PAD} 0 0 .9 250 2").strip()
    time.sleep(SETTLE)
    truthy("the second answer is not the first", first and second and first != second, f"{first!r} twice")
    truthy("there is still one row for the block", order(rcon).count(SHORT) == 1, str(order(rcon)))
    truthy("holding the values it was edited to",
           pads(rcon).get(SHORT) == ("0.00", "0.00", "0.90", "250", "2.00"), str(pads(rcon).get(SHORT)))

    step("and a pad switched off stays off through an edit")
    rcon.run(f"dd boostpad toggle {PAD}")
    time.sleep(SETTLE)
    truthy("the pad is off to begin with", record(export(rcon), PAD).get("enabled") == "false",
           str(record(export(rcon), PAD)))
    rcon.run(f"dd boostpad add {PAD} 0 0 .5 250 1")
    time.sleep(SETTLE)
    edited = record(export(rcon), PAD)
    truthy("the edit left it off", edited.get("enabled") == "false", str(edited))
    truthy("while taking the new numbers", edited.get("force_z") == "0.5", str(edited))
    rcon.run(f"dd boostpad remove {PAD}")
    time.sleep(SETTLE)


def drill_listing(rcon: Rcon, mariadb):
    """A listing read out of a hash map is a different listing every time it is asked."""
    step("the listing is ordered, and says the same thing twice")
    for block in ORDERED:
        rcon.run(f"dd boostpad add {block} 0 0 .1 0")
    time.sleep(SETTLE)
    try:
        once, twice = order(rcon), order(rcon)
        truthy("two runs agree", once == twice, f"{once} vs {twice}")
        truthy("and it comes out sorted", once == sorted(once), str(once))
        truthy("with one line per pad", len(once) == len(set(once)), str(once))
    finally:
        for block in ORDERED:
            rcon.run(f"dd boostpad remove {block}")
        time.sleep(SETTLE)

    step("with none configured it says so, rather than printing nothing")
    seeded = order(rcon)
    try:
        for block in seeded:
            rcon.run(f"dd boostpad remove minecraft:{block}")
        time.sleep(SETTLE)
        listing = rcon.run("dd boostpad list")
        truthy("the listing survives an empty set", switch(listing) is not None, listing[:200])
        truthy("and answers with none configured", not ITEM.search(listing) and "none" in listing.lower(),
               listing[:200])
    finally:
        rcon.run("wake database setdefaults drydock confirm")
        time.sleep(3)
    truthy("the shipped pads are back", set(order(rcon)) == set(seeded), f"{order(rcon)} != {seeded}")


def drill_foreign_key_row(rcon: Rcon, mariadb):
    """A key another writer stored is still a pad that fires, so every command has to be able to reach it.

    Imports go straight to the table, so an edited export is the console's way of writing the row a
    second server or a hand-edited backup would leave: the same block, under a name no argument parses to.
    """
    step("a pad stored under a key no command would have written")
    if not graft(rcon, f"  {FOREIGN}:\n{FOREIGN_BODY}"):
        return
    try:
        stored = export(rcon)
        truthy("it is in the table under the bare name", record(stored, FOREIGN).get("force_z") == "0.7",
               str(record(stored, FOREIGN)))
        truthy("and not under the namespaced one", not record(stored, f"minecraft:{FOREIGN}"),
               str(record(stored, f"minecraft:{FOREIGN}")))
        truthy("the listing prints it", FOREIGN in pads(rcon), str(order(rcon)))

        step("and the listing stays sorted by the name it prints, not by the key it is stored under")
        rcon.run("dd boostpad add minecraft:sandstone 0 0 .1 0")
        time.sleep(SETTLE)
        listed = order(rcon)
        truthy("it comes out sorted with a bare key among namespaced ones", listed == sorted(listed), str(listed))

        step("toggle reaches it under the name the argument parses to")
        reply = rcon.run(f"dd boostpad toggle {FOREIGN}")
        time.sleep(SETTLE)
        truthy("it was not refused as unknown", "not in the boostpad list" not in reply, reply.strip()[:160])
        stored = export(rcon)
        truthy("and the switch landed on the row that is there", record(stored, FOREIGN).get("enabled") == "false",
               str(record(stored, FOREIGN)))
        truthy("without leaving a second row beside it", not record(stored, f"minecraft:{FOREIGN}"),
               str(record(stored, f"minecraft:{FOREIGN}")))

        step("and add edits that row rather than opening a rival one")
        rcon.run(f"dd boostpad add {FOREIGN} 0 0 .9 250 1")
        time.sleep(SETTLE)
        stored = export(rcon)
        truthy("the numbers went to the row that was already there", record(stored, FOREIGN).get("force_z") == "0.9",
               str(record(stored, FOREIGN)))
        truthy("the switch it was left on survived the edit", record(stored, FOREIGN).get("enabled") == "false",
               str(record(stored, FOREIGN)))
        truthy("and the block still has exactly one line", order(rcon).count(FOREIGN) == 1, str(order(rcon)))

        step("and remove clears it, so it is not a row only the table can be edited to lose")
        rcon.run(f"dd boostpad remove {FOREIGN}")
        time.sleep(SETTLE)
        truthy("it is gone from the listing", FOREIGN not in pads(rcon), str(order(rcon)))
        truthy("and gone from the table", not record(export(rcon), FOREIGN), str(record(export(rcon), FOREIGN)))
    finally:
        rcon.run(f"dd boostpad remove {FOREIGN}")
        rcon.run("dd boostpad remove minecraft:sandstone")
        time.sleep(SETTLE)


def drill_export_carries_the_record(rcon: Rcon, mariadb):
    """The export is written out of BoostpadConfig, so what the record declares is what a backup holds.

    The first half reads the field names out of the source: a component added to the record and left
    out of its export would go missing from every backup silently, which is the one way this can break
    without anybody noticing. The second half drives the round trip that carries them.
    """
    step("the export writes exactly the fields the record declares")
    rcon.run(f"dd boostpad add {PAD} -0.25 0.75 0.4 250 1.5")
    time.sleep(SETTLE)
    try:
        declared = record_fields()
        before = record(export(rcon), PAD)
        truthy("the record has fields to check", len(declared) > 1, str(declared))
        truthy("and the export carries every one of them and nothing else", set(before) == set(declared),
               f"{sorted(before)} != {sorted(declared)}")

        step("and every one of them survives a round trip, not just the ones a listing prints")
        rcon.run(f"dd boostpad add {PAD} 0 0 0 0 0")
        rcon.run(f"dd boostpad toggle {PAD}")
        time.sleep(SETTLE)
        truthy("the pad really was changed first",
               pads(rcon).get(SHORT) == ("0.00", "0.00", "0.00", "0", "0.00"), str(pads(rcon).get(SHORT)))
        rcon.run("wake database import drydock confirm")
        time.sleep(3)
        after = record(export(rcon), PAD)
        truthy("it came back field for field", after == before, f"{after} != {before}")
    finally:
        rcon.run(f"dd boostpad remove {PAD}")
        time.sleep(SETTLE)


def drill_imported_row_repair(rcon: Rcon, mariadb):
    """Import is the one door a value no argument would have taken can come in through.

    An edited backup is where a delay past the argument's ceiling, a key that names no block on this
    server, and an entry that is not a block of settings at all all arrive from. The record repairs the
    first, the registry has to tolerate the second, and the import has to refuse the third rather than
    invent a pad with no force from it.
    """
    step("a delay past what the argument allows comes back at the cap")
    if not graft(rcon, f"  {UNKNOWN}:\n{UNKNOWN_BODY}  {SCALAR}: nonsense\n"):
        return
    try:
        stored = record(export(rcon), UNKNOWN)
        truthy("the delay was clamped rather than stored whole", stored.get("delay_ms") == CAPPED, str(stored))
        listed = pads(rcon).get(UNKNOWN_SHORT)
        truthy("and the listing prints the clamped number", listed and listed[3] == CAPPED, str(listed))

        step("a key that names no block here is still a row every command can reach")
        truthy("the rest of it survived the import", stored.get("force_z") == "0.5", str(stored))
        reply = rcon.run(f"dd boostpad toggle {UNKNOWN}")
        time.sleep(SETTLE)
        truthy("toggle reaches it", "not in the boostpad list" not in reply, reply.strip()[:160])
        truthy("and the switch landed on it", record(export(rcon), UNKNOWN).get("enabled") == "false",
               str(record(export(rcon), UNKNOWN)))

        step("while an entry that is not a block of settings is skipped rather than made into a pad")
        truthy("no row was invented for it", not record(export(rcon), SCALAR), str(record(export(rcon), SCALAR)))
        truthy("and the listing does not print one", SCALAR_SHORT not in order(rcon), str(order(rcon)))
    finally:
        rcon.run(f"dd boostpad remove {UNKNOWN}")
        rcon.run(f"dd boostpad remove {SCALAR}")
        time.sleep(SETTLE)


def drill_malformed_row(rcon: Rcon, mariadb):
    """One row in a shape the columns do not declare must cost that pad and no other.

    SQLite keeps whatever was written into a column whatever the column says, so a hand-run INSERT can leave
    text where a force belongs -- and the read that trips over it is the read that loads every pad there is.
    MariaDB refuses the same row outright, so there is nothing to drill on that backend.
    """
    if mariadb:
        print("  skipped: mariadb refuses a row in the wrong shape, so there is none to read back")
        return
    step("a force column holding text leaves that pad inert and every other one alone")
    rcon.run(f"dd boostpad add {PAD} 0 0 .4 250 1")
    write_pad_raw(MALFORMED, "nonsense")
    reread_table(rcon)
    try:
        listing = rcon.run("dd boostpad list")
        truthy("the table was still read", "has not been read" not in listing, listing[:200])
        listed = pads(rcon)
        truthy("the pad beside it came back whole",
               listed.get(SHORT) == ("0.00", "0.00", "0.40", "250", "1.00"), str(listed.get(SHORT)))
        truthy("and the malformed row is listed with no force rather than dropped",
               listed.get(MALFORMED) == ("0.00", "0.00", "0.30", "250", "1.00"), str(listed.get(MALFORMED)))
        truthy("the export reads it the same way",
               record(export(rcon), MALFORMED).get("force_x") == "0.0", str(record(export(rcon), MALFORMED)))
    finally:
        rcon.run(f"dd boostpad remove {PAD}")
        drop_pad_raw(MALFORMED)
        reread_table(rcon)


def drill_registration_needs_a_pad(rcon: Rcon, mariadb):
    """The switch being on is not enough to be worth listening for -- a pad has to be able to fire.

    A server with none configured, or with nothing but pads naming a block it does not have, must not sit on
    the busiest event in the game for something that can never happen. Paper is asked rather than Wake,
    because the cost this avoids is Paper's to report. The switch's own half of the same decision, and the
    module cycle around it, are drills_module.py.
    """
    step("a pad naming a block this server does not have does not make the listener worth registering")
    was_on = switch(rcon.run("dd boostpad list")) == "enabled"
    # grafts onto a fresh export, which doubles as the file every pad removed below is put back from
    if not graft(rcon, f"  {UNKNOWN}:\n{UNKNOWN_BODY}"):
        return
    try:
        if not was_on:
            rcon.run("dd boostpad toggle")
        time.sleep(SETTLE)
        truthy("a real pad and the switch on registers it once", registrations(rcon) == 1,
               f"{registrations(rcon)} registration(s)")
        for block in order(rcon):
            if block != UNKNOWN_SHORT:
                rcon.run(f"dd boostpad remove minecraft:{block}")
        time.sleep(SETTLE)
        truthy("the unknown key is all that is left", order(rcon) == [UNKNOWN_SHORT], str(order(rcon)))
        truthy("and nothing is registered for it", registrations(rcon) == 0,
               f"{registrations(rcon)} registration(s)")

        step("the first pad that names a real block claims it back, the last one gone gives it up again")
        rcon.run(f"dd boostpad add {PAD} 0 0 .4 250 1")
        time.sleep(SETTLE)
        truthy("one registration once a pad can fire", registrations(rcon) == 1,
               f"{registrations(rcon)} registration(s)")
        rcon.run(f"dd boostpad remove {PAD}")
        time.sleep(SETTLE)
        truthy("and none once it is gone", registrations(rcon) == 0, f"{registrations(rcon)} registration(s)")
    finally:
        rcon.run(f"dd boostpad remove {PAD}")
        rcon.run("wake database import drydock confirm")
        time.sleep(3)
        rcon.run(f"dd boostpad remove {UNKNOWN}")
        if (switch(rcon.run("dd boostpad list")) == "enabled") != was_on:
            rcon.run("dd boostpad toggle")
        time.sleep(SETTLE)


def drill_config_switches(rcon: Rcon, mariadb):
    """Three switches that differ by one letter, so each has to reach its own key and only its own."""
    step("each early-out axis confirms what it set and lands under its own key")
    try:
        for axis in AXES:
            reply = rcon.run(f"dd boostpad config early-out-{axis} true")
            time.sleep(SETTLE)
            truthy(f"early-out-{axis} says enabled", "enabled" in reply, reply.strip()[:160])
            truthy("and the database holds it", state(axis_key(axis), mariadb) == "true",
                   repr(state(axis_key(axis), mariadb)))
            reply = rcon.run(f"dd boostpad config early-out-{axis} false")
            time.sleep(SETTLE)
            truthy(f"early-out-{axis} says disabled", "disabled" in reply, reply.strip()[:160])
            truthy("and the database holds that too", state(axis_key(axis), mariadb) == "false",
                   repr(state(axis_key(axis), mariadb)))

        step("and one axis does not move another")
        rcon.run("dd boostpad config early-out-y true")
        time.sleep(SETTLE)
        settled = {axis: state(axis_key(axis), mariadb) for axis in AXES}
        truthy("only the axis that was set changed", settled == {"x": "false", "y": "true", "z": "false"},
               str(settled))
        refuses(rcon, "a value that is not a boolean", "dd boostpad config early-out-x maybe")
    finally:
        restore_axes(rcon)


def drill_global_cooldown(rcon: Rcon, mariadb):
    """A number rather than a switch, and the one value whose off state is a number the listing has to word."""
    step("global-cooldown stores what it took and the listing prints it")
    try:
        rcon.run("dd boostpad config global-cooldown 50")
        time.sleep(SETTLE)
        truthy("the database holds it", state(COOLDOWN_KEY, mariadb) == "50", repr(state(COOLDOWN_KEY, mariadb)))
        truthy("and the listing prints it", cooldown_line(rcon) == "50ms", repr(cooldown_line(rcon)))

        step("zero is off rather than a cooldown of nothing")
        truthy("setting it says disabled", "disabled" in rcon.run("dd boostpad config global-cooldown 0"))
        time.sleep(SETTLE)
        truthy("and the listing says off", cooldown_line(rcon) == "off", repr(cooldown_line(rcon)))

        step("a value no argument type would have taken is held to one that could have been typed")
        refuses(rcon, "the command turns a negative away", "dd boostpad config global-cooldown -5")
        write_state_raw(COOLDOWN_KEY, "-5", mariadb)
        rcon.run("wake reload")
        time.sleep(3)
        truthy("a row carrying a negative is off", cooldown_line(rcon) == "off", repr(cooldown_line(rcon)))
        # the same cooldown written in nanoseconds: uncapped it wraps the nanosecond arithmetic to no cooldown at all
        write_state_raw(COOLDOWN_KEY, "50000000000000", mariadb)
        rcon.run("wake reload")
        time.sleep(3)
        truthy("and one past the ceiling comes back at the cap", cooldown_line(rcon) == f"{CAPPED}ms",
               repr(cooldown_line(rcon)))
    finally:
        rcon.run("dd boostpad config global-cooldown 0")
        time.sleep(SETTLE)


def drill_switches_survive_reload(rcon: Rcon, mariadb):
    """Both switches are read out of a cache a reload rebuilds, so both have to be there afterwards."""
    step("the global switch and a pad's own survive /wake reload")
    rcon.run(f"dd boostpad add {PAD} 0 0 .4 250")
    rcon.run(f"dd boostpad toggle {PAD}")
    before = switch(rcon.run("dd boostpad list"))
    rcon.run("dd boostpad toggle")
    time.sleep(SETTLE)
    flipped = switch(rcon.run("dd boostpad list"))
    try:
        truthy("the global switch flipped", flipped and flipped != before, f"{before!r} -> {flipped!r}")
        rcon.run("wake reload")
        time.sleep(3)
        truthy("it is where it was left after the reload", switch(rcon.run("dd boostpad list")) == flipped,
               f"want {flipped!r}")
        truthy("and the pad is still off", record(export(rcon), PAD).get("enabled") == "false",
               str(record(export(rcon), PAD)))
    finally:
        if switch(rcon.run("dd boostpad list")) != before:
            rcon.run("dd boostpad toggle")
        rcon.run(f"dd boostpad remove {PAD}")
        time.sleep(SETTLE)


def drill_module_cycle(rcon: Rcon, mariadb):
    """The tree is declared once at boot, so a module cycle may only change what answers, not what exists."""
    step("the drydock commands go with the module and come back with it")
    before = order(rcon)
    set_module_enabled("drydock", False)
    try:
        rcon.run("wake reload")
        time.sleep(2)
        listing = rcon.run("dd boostpad list")
        truthy("its commands stop resolving", any(marker in listing for marker in PARSE_ERROR), listing[:160])
        truthy("while the rest of the tree answers", "Unknown or incomplete" not in rcon.run("wake help"))
    finally:
        set_module_enabled("drydock", True)
        rcon.run("wake reload")
        time.sleep(3)
    truthy("the listing comes back unchanged", order(rcon) == before, f"{order(rcon)} != {before}")


def drill_export_round_trip(rcon: Rcon, mariadb):
    """A pad's own switch lives in the module's table rather than its state prefix, so it needs its own check."""
    step("a pad's switch, every early-out axis and the global cooldown reach the export")
    rcon.run(f"dd boostpad add {PAD} 0 0 .4 250 2")
    rcon.run(f"dd boostpad toggle {PAD}")
    rcon.run("dd boostpad config early-out-x true")
    rcon.run("dd boostpad config early-out-y false")
    rcon.run("dd boostpad config global-cooldown 75")
    time.sleep(SETTLE)
    try:
        text = export(rcon)
        truthy("the export carries the pad's own switch", record(text, PAD).get("enabled") == "false",
               str(record(text, PAD)))
        truthy("and both axes as they were set",
               "boostpads_early_out_x: true" in text and "boostpads_early_out_y: false" in text,
               text[:200])
        truthy("and the cooldown too", "boostpads_global_cooldown_ms: 75" in text, text[:200])

        step("changing all of it back, then importing")
        rcon.run(f"dd boostpad toggle {PAD}")
        rcon.run("dd boostpad config early-out-x false")
        rcon.run("dd boostpad config early-out-y true")
        rcon.run("dd boostpad config global-cooldown 0")
        time.sleep(SETTLE)
        rcon.run("wake database import drydock confirm")
        time.sleep(3)
        truthy("the pad came back switched off", record(export(rcon), PAD).get("enabled") == "false",
               str(record(export(rcon), PAD)))
        truthy("and the axes came back with it", state(axis_key("x"), mariadb) == "true",
               repr(state(axis_key("x"), mariadb)))
        truthy("both of them", state(axis_key("y"), mariadb) == "false", repr(state(axis_key("y"), mariadb)))
        truthy("and the cooldown is back where it was", cooldown_line(rcon) == "75ms", repr(cooldown_line(rcon)))
    finally:
        restore_axes(rcon)
        rcon.run("dd boostpad config global-cooldown 0")
        rcon.run(f"dd boostpad remove {PAD}")
        time.sleep(SETTLE)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25575)
    parser.add_argument("--password", default="wake-dev")
    parser.add_argument("--mariadb-container", default="wake-testenv-mariadb-1")
    args = parser.parse_args()

    try:
        rcon = Rcon(args.host, args.port, args.password)
    except OSError as error:
        raise SystemExit(f"cannot reach RCON at {args.host}:{args.port} ({error}). "
                         f"Start ./gradlew runServer first.")

    mariadb = detect_backend(args.mariadb_container)
    print(f"backend: {'mariadb' if mariadb else 'sqlite'}")

    try:
        for drill in [drill_add_round_trip, drill_add_edits, drill_listing, drill_foreign_key_row,
                      drill_export_carries_the_record, drill_imported_row_repair, drill_malformed_row,
                      drill_registration_needs_a_pad, drill_config_switches, drill_global_cooldown,
                      drill_switches_survive_reload, drill_module_cycle, drill_export_round_trip]:
            print(f"\n{drill.__name__.removeprefix('drill_').replace('_', ' ')}")
            drill(rcon, mariadb)
    except RuntimeError as error:
        bad(str(error))

    print()
    if failures:
        print(f"{len(failures)} drill step(s) failed")
        return 1
    print("all drydock drills passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
