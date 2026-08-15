#!/usr/bin/env python3
"""Delivery drills: what a player and a boat are actually running.

`features/obu/delivery` resolves the active selection and ships it over the plugin-message channels.
Half of that needs an OBU client to observe, but the other half does not: a boat is an entity, so
`/execute as` puts one behind every command the package exposes, and what it ends up holding is
readable straight off the world -- its pin lives in its persistent data container, and `-clear`
answers whether a temporary override is there or not. That is enough to judge pin storage and its
spelling, override add/clear/reset, the impulses that must never be stored, eviction when a boat
leaves, and the sweeps that run over pinned boats behind an admin's edit, without a single line of
"print and trust".

Eviction is drilled by keeping the boat's UUID fixed across the removal: a leaked override would
still be attached to that id, and `-clear` would find it. Both ways out are drilled -- the boat taken
out from under Wake, and the chunk unloading with it still in there.

The sandbox purge is here too: with the keep window cut to seconds a real sweep runs while the drill
watches, so what it takes, what a recent access spares, and what an off switch stops are all read
back off the listing rather than reasoned about.

So is the store the whole surface stands on: what an empty one seeds, the names and the settings the
import door has to refuse because no command would ever have written them, and the same rows arriving
through the other door -- the table itself, where a hand-run INSERT, an older build or a server sharing
the database can leave a row nothing validated. Each of those must cost that row and no other, because
the read that trips over one is the read that loads every context there is.

The export is drilled as the backup it claims to be: a sandbox key with its `@uuid` and owner, a context
holding nothing, and a repeatable setting whose argument is a list, all through export -> drop -> import
-> export and compared line for line. An import carrying no contexts at all is beside it, because the
two halves of an import are counted apart, and an export taken over a store that never managed to read
the table is beside that, because a file that looks like a full backup and is an empty one is the one
failure an export must never have.

What a client *does* with any of it is not here, because it needs a client. That half is TESTPLAN §2.

    python testenv/drills_delivery.py       # needs a server up (./gradlew runServer)

Runs against sqlite and mariadb alike. Exits non-zero if a drill fails.
"""

import argparse
import os
import re
import sqlite3
import sys
import time
from collections.abc import Callable

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from drills import (CODES, Log, Rcon, WAKE, bad, detect_backend, docker, failures, ok,  # noqa: E402
                    set_module_enabled, state, step)

SETTLE = 1.0
TAG = "wakedelivery"
EXPORT = WAKE / "exports" / "obu_data.yml"
# a boat summoned twice under this id is, to everything keyed by UUID, the same boat
FIXED_UUID = "[I;16,32,48,64]"
# far enough out that dropping the forceload really unloads the chunk. Inside the spawn radius it does not:
# the entity stops answering a selector while the chunk stays resident, so nothing is ever removed and an
# eviction drill run there passes or fails on where the world spawn happens to sit
FAR_CHUNK = (4000, 4000)
# where CraftBukkit keeps an entity's persistent data container inside its nbt
PDC = "BukkitValues"
PIN = re.compile(r'"?wake:obu_context"?\s*:\s*"([^"]*)"')
# one row of a rendered panel: the bullet, its name, and the value behind the colon
PANEL_ROW = re.compile(r"●\s*([^:●\n]+?)\s*:\s*([^●\n]+)")
# a context every install has, seeded from the jar, and one nothing will ever seed
KNOWN_CONTEXT = "harbour"
UNKNOWN_CONTEXT = "nosuchcontextanywhere"
# two players who were never here: a sandbox key is only ever built from its owner's id
GRAFT_OWNER = "0f1e2d3c-4b5a-4968-8778-695a4b3c2d1e"
OTHER_OWNER = "1a2b3c4d-5e6f-4708-8192-a3b4c5d6e7f8"
# rows only a hand-run INSERT, an older build or another server leaves behind
RAW_NO_TYPE = "deliverynotype"
RAW_UNADDRESSABLE = "delivery.dotted"
RAW_SETTINGS = "deliveryrawsettings"
RAW_MIXED_CASE = "DeliveryMixedCase"
RAW_STALE_SANDBOX = "DeliveryStaleBox"
# a name and an owner that only ever meet in an export file
ROUND_SANDBOX = f"roundtrip@{GRAFT_OWNER}"
ROUND_EMPTY = "roundempty"

rcon = None
mariadb = None


def run(command):
    return CODES.sub("", rcon.run(command))


def truthy(label, condition, detail=""):
    (ok if condition else bad)(label if condition else f"{label} -- {detail}")


def says(label, command, needle):
    reply = run(command)
    truthy(label, needle.lower() in reply.lower(), f"{command!r} -> {reply.strip()[:200]}")
    return reply


def boat_says(label, command, needle):
    """The same judgement, with the boat behind the command rather than the console."""
    reply = as_boat(command)
    truthy(label, needle.lower() in reply.lower(), f"as the boat: {command!r} -> {reply.strip()[:200]}")
    return reply


def traces(log):
    """Wake's own exceptions in whatever the console has printed since the mark."""
    return [line for line in log.read().splitlines() if "wake" in line.lower() and "xception" in line]


def wait_until(condition: Callable[[], bool], timeout=40.0):
    """Chunk work lands on the server's own schedule, so a drill that needs it waits for the state, not a clock."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if condition():
            return True
        time.sleep(0.5)
    return False


def write_rows_raw(statements):
    """Runs SQL straight at the obu tables, behind the server's back, on whichever backend it is using."""
    if mariadb:
        container, user, password, database = mariadb
        docker("exec", container, "mariadb", f"-u{user}", f"-p{password}", database, "-N", "-B",
               "-e", "; ".join(statements))
        return
    connection = sqlite3.connect(str(WAKE / "wake.db"), timeout=10)
    try:
        for sql in statements:
            connection.execute(sql)
        connection.commit()
    finally:
        connection.close()


def context_names_raw():
    """Every name in wake_obu_contexts as the table actually spells it, read past the server's cache."""
    if mariadb:
        container, user, password, database = mariadb
        out = docker("exec", container, "mariadb", f"-u{user}", f"-p{password}", database, "-N", "-B",
                     "-e", "SELECT name FROM wake_obu_contexts")
        return [line.strip() for line in out.splitlines() if line.strip()]
    uri = "file:" + (WAKE / "wake.db").resolve().as_posix() + "?mode=ro"
    connection = sqlite3.connect(uri, uri=True, timeout=10)
    try:
        return [row[0] for row in connection.execute("SELECT name FROM wake_obu_contexts")]
    finally:
        connection.close()


def reread_store():
    """Cycles the module, which is what makes the server read a row nothing announced to it.

    A reload will not: the store goes back to the table for the keys it was told moved, and a row written
    behind its back moved none. Disabling drops the store, and enabling builds one that has never read."""
    set_module_enabled("obu", False)
    try:
        rcon.run("wake reload")
        time.sleep(SETTLE)
    finally:
        set_module_enabled("obu", True)
        rcon.run("wake reload")
        time.sleep(SETTLE * 2)


def boat_present():
    return "passed" in run(f"execute if entity @e[tag={TAG},limit=1]").lower()


def boat_unloaded(settle=6.0):
    """A chunk stops answering well before it finishes unloading, and asking for it back in that window
    resurrects it without ever removing the entity. An entity in a loaded chunk always answers a selector,
    so staying away for the whole settle is what says the chunk really went."""
    if not wait_until(lambda: not boat_present()):
        return False
    deadline = time.monotonic() + settle
    while time.monotonic() < deadline:
        if boat_present():
            return False
        time.sleep(0.5)
    return True


def boat(uuid=None, at=(0, 0)):
    """Puts one tagged boat in the world, optionally under a fixed id, and answers whether it is there."""
    rcon.run(f"kill @e[tag={TAG}]")
    nbt = f'{{Tags:["{TAG}"],NoGravity:1b' + (f",UUID:{uuid}}}" if uuid else "}")
    rcon.run(f"summon minecraft:oak_boat {at[0]} 100 {at[1]} {nbt}")
    return boat_present()


def as_boat(command):
    return run(f"execute as @e[tag={TAG},limit=1] run {command}")


def pinned_on_boat():
    """The context name in the boat's persistent data container, or None when it carries no pin."""
    found = PIN.search(run(f"data get entity @e[tag={TAG},limit=1] {PDC}"))
    return found.group(1) if found else None


def boat_uuid():
    reply = run(f"data get entity @e[tag={TAG},limit=1] UUID")
    found = re.search(r"\[I;\s*(-?\d+),\s*(-?\d+),\s*(-?\d+),\s*(-?\d+)\]", reply)
    return found.groups() if found else None


def drill_pin_storage():
    """A pin is the boat's own selection, so it has to land in the container in one spelling."""
    if not boat():
        bad("could not summon the boat this drill needs")
        return
    boat_says("a context applies to the boat behind /execute as",
              f"wobu -context {KNOWN_CONTEXT}", "applied context")
    truthy("and the pin is in the boat's container", pinned_on_boat() == KNOWN_CONTEXT, repr(pinned_on_boat()))

    step("the same context typed in another case is the same context")
    as_boat(f"wobu -context {KNOWN_CONTEXT.upper()}")
    truthy("the stored pin is still the canonical spelling", pinned_on_boat() == KNOWN_CONTEXT,
           f"stored as {pinned_on_boat()!r}, so one context reached two ways would be two")

    step("a name nothing answers to leaves the pin alone")
    boat_says("an unknown context is refused", f"wobu -context {UNKNOWN_CONTEXT}", "does not exist")
    truthy("and the boat keeps the pin it had", pinned_on_boat() == KNOWN_CONTEXT, repr(pinned_on_boat()))

    step("and default is how a boat is unpinned")
    boat_says("applying default answers", "wobu -context default", "applied context")
    truthy("the pin is gone from the container", pinned_on_boat() is None, repr(pinned_on_boat()))

    step("a pin written behind Wake's back -- another build, another server -- is read the same way")
    as_boat(f"wobu -context {KNOWN_CONTEXT}")
    rcon.run(f'data merge entity @e[tag={TAG},limit=1] '
             f'{{{PDC}:{{"wake:obu_context":"{KNOWN_CONTEXT.upper()}"}}}}')
    if pinned_on_boat() != KNOWN_CONTEXT.upper():
        bad(f"the uncanonical pin never reached the container ({pinned_on_boat()!r}), so nothing below would prove anything")
        return
    log = Log()
    reply = as_boat("wobu -context default")
    truthy("a boat carrying an uncanonical pin still answers", "applied context" in reply.lower(), reply.strip()[:200])
    truthy("and unpins cleanly", pinned_on_boat() is None, repr(pinned_on_boat()))
    truthy("with nothing on the console", not traces(log), str(traces(log)[:2]))
    rcon.run(f"kill @e[tag={TAG}]")


def drill_boat_overrides():
    """A setting aimed at a boat is a temporary override on that boat, and -clear is what reads it back."""
    if not boat():
        bad("could not summon the boat this drill needs")
        return
    boat_says("a setting applies to the boat", "wobu stepsize 1.5", "set stepsize")
    boat_says("and -clear finds it", "wobu -clear stepsize", "cleared stepsize")
    boat_says("a second -clear reports nothing left", "wobu -clear stepsize", "is not active")

    step("-reset drops every override the boat holds, not only the last one")
    as_boat("wobu stepsize 1.5")
    as_boat("wobu jumpforce 0.75")
    boat_says("-reset answers for the boat", "wobu -reset", "the boat")
    boat_says("the first override is gone", "wobu -clear stepsize", "is not active")
    boat_says("and so is the second", "wobu -clear jumpforce", "is not active")

    step("an impulse is an action, so it is delivered and never stored")
    boat_says("the impulse is accepted", "wobu applyimpulse 0 1 0", "set applyimpulse")
    boat_says("and nothing was written to the boat", "wobu -clear applyimpulse", "is not active")

    # the client applies these to whatever it already holds, and Wake ships a context as a fresh
    # compound -- so stored, they would be a negation riding above the entry they cancel, and the
    # fold that reorders that entry would decide whether the block came back
    step("a subtractive setting edits the overrides the boat holds rather than joining them")
    as_boat("wobu blockslipperiness 0.9 ice,stone")
    boat_says("removing one block names what it took", "wobu removeblockslipperiness ice", "removed")
    boat_says("and never lands as a setting of its own", "wobu -clear removeblockslipperiness", "is not active")
    boat_says("the block beside it is the whole of what is left", "wobu -clear 3:stone", "cleared blockslipperiness")
    boat_says("so the entry it edited is gone with it", "wobu -clear blockslipperiness", "is not active")

    step("and one with nothing to edit says so rather than storing itself")
    boat_says("a block the boat never had", "wobu removeblockslipperiness ice", "no blockslipperiness")
    boat_says("a slipperiness list the boat never had", "wobu clearslipperiness", "no blockslipperiness")
    boat_says("a collision filter the boat never had", "wobu clearcollisionfilter", "no addcollisionfilter")

    # by definition alone a -clear takes every per-block setting at once, which is the one place the
    # command was coarser than the rows it reads: two of them differ only by the enum they pin
    step("-clear narrows to the identity arguments the setting was given, not to the definition")
    as_boat("wobu setblocksetting JUMPS 2 ice,stone")
    as_boat("wobu setblocksetting WALLTAP_MULTIPLIER 2 ice")
    boat_says("naming one block of one per-block setting takes only that block",
              "wobu -clear setblocksetting JUMPS ice", "cleared")
    boat_says("the block beside it is still under the same one", "wobu -clear 26:JUMPS:stone", "cleared")
    boat_says("and the per-block setting beside them was never touched",
              "wobu -clear setblocksetting WALLTAP_MULTIPLIER", "cleared")
    boat_says("so nothing of either is left", "wobu -clear setblocksetting", "is not active")

    # -clear mirrors each setting's own node, so its arguments are the setting's own argument types and
    # every refusal below comes from the same door the set command is turned back at
    step("and a word that setting could never carry is refused by the argument, not read as nothing to clear")
    as_boat("wobu blockslipperiness 0.9 ice,stone")
    boat_says("an enum no per-block setting names", "wobu -clear setblocksetting NOT_A_SETTING", "invalid option")
    boat_says("a block this server does not have", "wobu -clear blockslipperiness notablock", "invalid block")
    boat_says("an argument a setting that takes none cannot have", "wobu -clear stepsize 1.5", "incorrect argument")
    boat_says("a name no setting carries at all", "wobu -clear notasetting", "unknown obu setting")
    boat_says("and the entry none of them reached is still there",
              "wobu -clear blockslipperiness ice", "cleared")
    boat_says("beside the one they never named", "wobu -clear blockslipperiness stone", "cleared")

    step("and a list is typed the way the setting's own command takes one")
    as_boat("wobu blockslipperiness 0.9 ice,stone,packed_ice")
    boat_says("blocks separated by spaces rather than commas", "wobu -clear blockslipperiness ice stone", "cleared")
    boat_says("leaving the one neither named", "wobu -clear 3:packed_ice", "cleared blockslipperiness")

    # a fold rebuilds the override map whole, and losing the last entry drops the boat's uuid with it:
    # a rebuild that loses the settings beside the one it folded, or a boat that empties and can never
    # take another, would both read as an override that is simply not there
    step("a second setting on the same list folds into the entry already there, and the rest stand")
    as_boat("wobu blockslipperiness 0.9 ice")
    as_boat("wobu blockslipperiness 0.9 packed_ice")
    as_boat("wobu stepsize 1.5")
    boat_says("the folded list clears in one go", "wobu -clear blockslipperiness", "cleared blockslipperiness")
    boat_says("with nothing of it left behind", "wobu -clear blockslipperiness", "is not active")
    boat_says("and the setting beside it survived the rebuild", "wobu -clear stepsize", "cleared stepsize")
    boat_says("clearing the last one leaves the boat holding nothing", "wobu -clear stepsize", "is not active")
    boat_says("and it takes an override again afterwards", "wobu stepsize 1.5", "set stepsize")

    step("and a block moved to another value leaves the entry it came from under a new key")
    as_boat("wobu blockslipperiness 0.9 ice,stone")
    as_boat("wobu blockslipperiness 0.4 ice")
    boat_says("the set it was typed as answers to nothing", "wobu -clear 3:ice,stone", "unknown obu setting")
    boat_says("the block it kept is the whole of the first entry", "wobu -clear 3:stone", "cleared blockslipperiness")
    boat_says("and the block that moved is the whole of the second", "wobu -clear 3:ice", "cleared blockslipperiness")
    as_boat("wobu -reset")
    rcon.run(f"kill @e[tag={TAG}]")


def drill_context_replaces_overrides():
    """A context is the boat's whole selection, so applying one has to take the temporary settings with it."""
    if not boat():
        bad("could not summon the boat this drill needs")
        return
    as_boat("wobu stepsize 1.5")
    as_boat("wobu jumpforce 0.75")
    boat_says("a context applies over them", f"wobu -context {KNOWN_CONTEXT}", "applied context")
    boat_says("the first override went with it", "wobu -clear stepsize", "is not active")
    boat_says("and so did the second", "wobu -clear jumpforce", "is not active")

    step("unpinning is the same selection, so it clears them too")
    as_boat("wobu stepsize 1.5")
    boat_says("default applies", "wobu -context default", "applied context")
    boat_says("and the override is gone", "wobu -clear stepsize", "is not active")

    step("a name nothing answers to is not a selection, and changes nothing")
    as_boat(f"wobu -context {KNOWN_CONTEXT}")
    as_boat("wobu stepsize 1.5")
    boat_says("the name is refused", f"wobu -context {UNKNOWN_CONTEXT}", "does not exist")
    truthy("the pin is untouched", pinned_on_boat() == KNOWN_CONTEXT, repr(pinned_on_boat()))
    boat_says("and the override is still there", "wobu -clear stepsize", "cleared stepsize")
    rcon.run(f"kill @e[tag={TAG}]")


def drill_chunk_unload_eviction():
    """A chunk going out is a boat leaving the world: what Wake held for it goes, what the boat carries comes back.

    Driven far from spawn, where dropping the forceload really unloads the chunk. Nearer in, the spawn radius
    keeps it resident: the boat stops answering a selector, nothing is ever removed, and the drill would be
    reporting on where the world spawn sits rather than on eviction."""
    far = FAR_CHUNK
    rcon.run(f"forceload add {far[0]} {far[1]}")
    if not boat(FIXED_UUID, far):
        bad("could not summon the boat this drill needs")
        return
    as_boat(f"wobu -context {KNOWN_CONTEXT}")
    as_boat("wobu stepsize 1.5")
    boat_says("the override is on the boat while its chunk is loaded", "wobu -clear stepsize", "cleared stepsize")
    as_boat("wobu stepsize 1.5")
    first = boat_uuid()

    step("let the chunk go")
    rcon.run(f"forceload remove {far[0]} {far[1]}")
    if not boat_unloaded():
        bad("the chunk never finished unloading, so nothing below would prove anything")
        rcon.run(f"forceload add {far[0]} {far[1]}")
        return
    ok("the boat left the world with its chunk")

    step("and bring it back")
    rcon.run(f"forceload add {far[0]} {far[1]}")
    if not wait_until(boat_present):
        bad("the boat did not come back with its chunk")
        return
    truthy("it is the same boat by id", boat_uuid() == first and first is not None,
           f"{first} vs {boat_uuid()}")
    truthy("carrying the pin it was given", pinned_on_boat() == KNOWN_CONTEXT, repr(pinned_on_boat()))
    boat_says("but not the override Wake was holding for it", "wobu -clear stepsize", "is not active")
    rcon.run(f"kill @e[tag={TAG}]")
    rcon.run(f"forceload remove {far[0]} {far[1]}")


def drill_override_eviction():
    """Every insertion keyed by a boat's UUID needs a removal, or the next boat to hold that id inherits it."""
    if not boat(FIXED_UUID):
        bad("could not summon the boat this drill needs")
        return
    first = boat_uuid()
    as_boat("wobu stepsize 1.5")
    boat_says("the override is on the boat before it is removed", "wobu -clear stepsize", "cleared stepsize")

    step("set it again, then take the boat out of the world")
    as_boat("wobu stepsize 1.5")
    if not boat(FIXED_UUID):
        bad("could not summon the replacement boat")
        return
    time.sleep(SETTLE)
    if boat_uuid() != first or first is None:
        bad(f"the replacement boat did not take the same id ({first} vs {boat_uuid()}), so nothing here would prove anything")
        return
    ok("the replacement boat holds the same id as the one that was removed")
    boat_says("and it starts clean", "wobu -clear stepsize", "is not active")

    step("a pin, on the other hand, is the boat's own data and goes with it")
    as_boat(f"wobu -context {KNOWN_CONTEXT}")
    truthy("the replacement carries its own pin", pinned_on_boat() == KNOWN_CONTEXT, repr(pinned_on_boat()))
    boat(FIXED_UUID)
    truthy("and a boat summoned fresh under that id carries none", pinned_on_boat() is None, repr(pinned_on_boat()))
    rcon.run(f"kill @e[tag={TAG}]")


def drill_module_cycle():
    """A module toggled off and on again keeps nothing: the overrides go, the boat's own data stays."""
    if not boat(FIXED_UUID):
        bad("could not summon the boat this drill needs")
        return
    as_boat(f"wobu -context {KNOWN_CONTEXT}")
    as_boat("wobu stepsize 1.5")
    try:
        step("obu off")
        set_module_enabled("obu", False)
        rcon.run("wake reload")
        time.sleep(SETTLE)
        truthy("the command tree is gone with the module", "Unknown or incomplete" in run("wobu -context"),
               run("wobu -context").strip()[:120])
    finally:
        step("obu back on")
        set_module_enabled("obu", True)
        rcon.run("wake reload")
        time.sleep(SETTLE)

    truthy("the tree answers again", "Unknown or incomplete" not in run("wobu -context"))
    boat_says("the override the old module held is gone", "wobu -clear stepsize", "is not active")
    truthy("but the pin, which lives on the boat, survived", pinned_on_boat() == KNOWN_CONTEXT, repr(pinned_on_boat()))
    rcon.run(f"kill @e[tag={TAG}]")


def drill_seeds_an_empty_store():
    """The jar's contexts are all an install starts with, and the module only reaches for them when it
    finds the table empty. So nothing may write a row of its own before that question is asked -- a
    module that stocks the store on the way up answers it for itself, and the bundled contexts then
    never land at all, on the one install that had none. The fallback context has to answer anyway."""
    log = Log()
    rcon.run("wake database drop obu confirm")
    if not log.await_line("Database drop completed for module obu", 30):
        bad("the drop never finished, so the store was never empty")
        return
    listing = run("wobu -context")
    truthy("the store is empty", KNOWN_CONTEXT not in listing.lower(), listing.strip()[:300])
    truthy("but the context every selection falls back to still answers for itself",
           "default" in listing.lower(), listing.strip()[:300])
    says("and it is still protected with no row behind it", "wobu -context -delete default", "cannot delete")

    step("and the module comes back up on it")
    set_module_enabled("obu", False)
    rcon.run("wake reload")
    time.sleep(SETTLE)
    log = Log()
    set_module_enabled("obu", True)
    rcon.run("wake reload")
    time.sleep(SETTLE)
    truthy("the console says it seeded the jar's contexts", log.await_line("Auto-seeded", 15), log.read()[-300:])
    listing = run("wobu -context")
    truthy("and they are live without a setdefaults", KNOWN_CONTEXT in listing.lower(), listing.strip()[:300])


def drill_reserved_contexts():
    """A protected context has to be refused whatever case it is typed in, or the reply lies about deleting it."""
    for spelling in ("default", "DEFAULT", "Default"):
        says(f"-context -delete {spelling} is refused", f"wobu -context -delete {spelling}", "cannot delete")
    for spelling in ("wake:empty", "WAKE:EMPTY"):
        says(f"-context -delete {spelling} is refused", f"wobu -context -delete {spelling}", "cannot delete")
    listing = run("wobu -context")
    truthy("default is still in the listing afterwards", "default" in listing.lower(), listing.strip()[:200])
    says("a sandbox cannot take a reserved name either", "wobu -sandbox create default", "reserved")


def graft_export(sections):
    """Exports, writes hand-authored entries into the file, imports it back and hands over the console.

    An export is the one file an admin edits by hand, so it is the only door a name that no command
    would have produced can come through."""
    log = Log()
    rcon.run("wake database export obu")
    if not log.await_line("Database export completed for module obu", 30):
        return None
    good = EXPORT.read_text(encoding="utf-8", errors="replace")
    edited = good
    for heading, body in sections.items():
        edited = (edited.replace(f"{heading}:\n", f"{heading}:\n{body}", 1) if f"{heading}:\n" in edited
                  else edited + f"{heading}:\n{body}")
    EXPORT.write_text(edited, encoding="utf-8")
    try:
        log = Log()
        rcon.run("wake database import obu confirm")
        if not log.await_line("Database import completed for module obu", 30):
            return None
        return log.read()
    finally:
        EXPORT.write_text(good, encoding="utf-8")


def drill_imported_names():
    """Every name a command refuses has to be refused at the import door too, and for the same reason.

    A sandbox called `default` shadows the context every selection falls back to, and the reserved
    guard would then make it undeletable. A sandbox keyed to a uuid that is not its owner's, or a
    server context carrying the `@` that splits a sandbox key, is worse than wrong: it is a row no
    command can ever name again, so it only ever surfaces as a number in the count and as a purge
    much later. An owner that is not a uuid at all is the third kind, and the one a typo produces.
    A name in the wrong case is the fourth, and the one an admin is likeliest to write: it would be
    stored under a spelling every lookup and every delete folds away from.

    A name carrying a `.` never survives to be judged -- yaml reads it as a path, so the file's
    `graftdot.child` reaches the importer as an ordinary `graftdot` and lands as one. That is worth
    nothing more than knowing: what matters is that no dot can reach the store, because that is the row
    an export would silently fold away next time. The one well-formed graft in the same file is what
    says this is a door, not a wall."""
    console = graft_export({"sandbox":
                            "  default:\n    settings:\n      stepsize: '9.0'\n"
                            f"  default@{GRAFT_OWNER}:\n    owner_uuid: {GRAFT_OWNER}\n"
                            f"  graftstray@{GRAFT_OWNER}:\n    owner_uuid: {OTHER_OWNER}\n"
                            "  graftbadowner:\n    owner_uuid: not-a-uuid\n"
                            f"  graftok@{GRAFT_OWNER}:\n    owner_uuid: {GRAFT_OWNER}\n"
                            "    settings:\n      stepsize: '1.5'\n",
                            "server": "  graftat@server:\n    settings:\n      stepsize: '9.0'\n"
                                      "  GraftMixedCase:\n    settings:\n      stepsize: '9.0'\n"
                                      "  graftdot.child: {}\n"})
    if console is None:
        bad("the export/import round trip never finished, so there is nothing to judge")
        return
    truthy("the import named both reserved names it refused, keyed and bare",
           console.count("that name is reserved") == 2, console.strip()[-600:])
    truthy("and named the three nothing could have addressed, for the other reason",
           console.count("no command could reach a context stored under that name") == 3, console.strip()[-600:])
    truthy("and the owner that is not a uuid, for a third",
           "invalid owner_uuid 'not-a-uuid'" in console, console.strip()[-600:])
    listing = run("wobu -context").lower()
    for refused in (f"default@{GRAFT_OWNER}".lower(), f"graftstray@{GRAFT_OWNER}".lower(),
                    "graftat@server", "graftbadowner", "graftmixedcase"):
        truthy(f"{refused} is not in the store", refused not in listing, listing.strip()[:400])
    truthy("and nothing carrying a dot reached it either", ".child" not in listing, listing.strip()[:400])
    rcon.run("wobu -context -delete graftdot")
    says("no sandbox answers to the reserved name", "wobu -sandbox view default", "does not exist")
    says("and default is still the protected server context", "wobu -context -delete default", "cannot delete")

    step("while the graft that is shaped like a real row goes straight in")
    truthy("the sandbox keyed to its own owner is there",
           f"graftok@{GRAFT_OWNER}".lower() in listing, listing.strip()[:400])
    says("and it carries what the file gave it", f"wobu -sandbox view graftok@{GRAFT_OWNER}", "stepsize")
    rcon.run(f"wobu -sandbox delete graftok@{GRAFT_OWNER}")


def export_now():
    """Writes the module's cache out and answers with the file and the count it reported, or (None, 0)."""
    log = Log()
    rcon.run("wake database export obu")
    if not log.await_line("Database export completed for module obu", 30):
        return None, 0
    reported = re.search(r"export completed for module obu \((\d+) records\)", log.read())
    return EXPORT.read_text(encoding="utf-8", errors="replace"), int(reported.group(1)) if reported else -1


def section(text, name):
    """One top-level block of an export file, as the lines under it."""
    found = re.search(rf"(?m)^{re.escape(name)}:\n((?:[ \t]+.*\n?)*)", text)
    return found.group(1) if found else ""


def drill_rows_no_command_wrote():
    """The table is the other door into the store, and nothing on the way in validates what comes through
    it: an older build, a hand-run INSERT, a server sharing the database. Each such row has to cost that
    row alone, because the read that trips over one is the read that loads every context there is -- a
    loader that throws leaves the whole store unread, on this reload and on every one after it.

    A NULL type is the sharp one: `valueOf` on it is a NullPointerException, which is not the
    IllegalArgumentException a malformed row is caught by. A name carrying a `.` is the other kind: no
    command can name it, and an export would fold it into a nested section and lose it without a word.
    A name that is merely spelt in another case is the quiet one, and the reason the door judges the
    stored spelling rather than a folded copy of it: sqlite matches `WHERE name = ?` exactly, so a row
    taken into the cache under a name the delete can never match is a context that comes back from the
    dead on every reload. The purge sweep reads the same column and has to agree.

    The settings rows are the same question one level down -- one value the wire cannot carry must never
    cost a context the rest of what it holds, a setting filed under another spelling of the context must
    not attach to it, and one that acts once rather than describing a state is not something a context
    can hold at all, whatever put it there."""
    fresh = int(time.time() * 1000)
    stale = 0  # a sandbox stamped 0 is older than any keep window, so every sweep sees it
    # a day ahead, so the three-second window below can only ever be about the mis-cased row
    untouchable = fresh + 86_400_000
    write_rows_raw([
        f"REPLACE INTO wake_obu_contexts (name, type, owner_uuid, last_accessed_at) VALUES ('{RAW_NO_TYPE}', NULL, NULL, {fresh})",
        f"REPLACE INTO wake_obu_contexts (name, type, owner_uuid, last_accessed_at) VALUES ('{RAW_UNADDRESSABLE}', 'SERVER', NULL, {fresh})",
        f"REPLACE INTO wake_obu_contexts (name, type, owner_uuid, last_accessed_at) VALUES ('{RAW_MIXED_CASE}', 'SERVER', NULL, {fresh})",
        f"REPLACE INTO wake_obu_contexts (name, type, owner_uuid, last_accessed_at) VALUES ('{RAW_STALE_SANDBOX}', 'SANDBOX', NULL, {stale})",
        f"REPLACE INTO wake_obu_contexts (name, type, owner_uuid, last_accessed_at) VALUES ('{RAW_SETTINGS}', 'SANDBOX', NULL, {untouchable})",
        f"REPLACE INTO wake_obu_settings (context_name, unique_key, definition_name, args) VALUES ('{RAW_SETTINGS}', '1', 'stepsize', 'not json at all')",
        f"REPLACE INTO wake_obu_settings (context_name, unique_key, definition_name, args) VALUES ('{RAW_SETTINGS}', '2', 'defaultslipperiness', '[\"nonsense\"]')",
        f"REPLACE INTO wake_obu_settings (context_name, unique_key, definition_name, args) VALUES ('{RAW_SETTINGS}', '7', 'jumpforce', '[\"0.75\"]')",
        f"REPLACE INTO wake_obu_settings (context_name, unique_key, definition_name, args) VALUES ('{RAW_SETTINGS}', '43', 'applyimpulserelative', '[\"0.0\",\"5.0\",\"0.0\"]')",
        f"REPLACE INTO wake_obu_settings (context_name, unique_key, definition_name, args) VALUES ('{RAW_SETTINGS.title()}', '45', 'setmaxspeed', '[\"9.0\"]')",
    ])
    log = Log()
    reread_store()
    try:
        counts = run("wobu -settings query-context-count")
        truthy("the table was still read", "has not been read" not in counts, counts.strip()[:200])
        listing = run("wobu -context").lower()
        truthy("every context the store already had is still in it", KNOWN_CONTEXT in listing, listing.strip()[:300])
        truthy("the row with no type is not one of them", RAW_NO_TYPE not in listing, listing.strip()[:300])
        truthy("nor the one no command could ever name", RAW_UNADDRESSABLE not in listing, listing.strip()[:300])
        truthy("nor the one spelt in a case no write produces",
               RAW_MIXED_CASE.lower() not in listing, listing.strip()[:300])
        console = log.read()
        truthy("the console named the type it could not read",
               f"malformed OBU context row '{RAW_NO_TYPE}'" in console, console.strip()[-600:])
        truthy("and named the name nothing could reach",
               f"'{RAW_UNADDRESSABLE}': no command could reach" in console, console.strip()[-600:])
        truthy("and named the mis-cased one under the spelling the table actually holds",
               f"'{RAW_MIXED_CASE}': no command could reach" in console, console.strip()[-600:])

        step("and a context whose setting rows are broken keeps the ones that are not")
        view = run(f"wobu -sandbox view {RAW_SETTINGS}").lower()
        truthy("the context itself came through", RAW_SETTINGS in view, view.strip()[:300])
        truthy("carrying the setting that reads back", "jumpforce" in view, view.strip()[:300])
        truthy("with the row that is not json dropped", "stepsize" not in view, view.strip()[:300])
        truthy("and the value the wire cannot carry dropped beside it",
               "defaultslipperiness" not in view, view.strip()[:300])
        truthy("both of them named on the console",
               "'stepsize' of context" in console and "'defaultslipperiness' of context" in console,
               console.strip()[-600:])
        truthy("the impulse no context can hold never reached it either",
               "applyimpulserelative" not in view, view.strip()[:300])
        truthy("nor the setting filed under another spelling of its context",
               "setmaxspeed" not in view, view.strip()[:300])

        step("and the sweep leaves the row it could never delete rather than reporting it taken")
        log = Log()
        says("the keep window is cut to three seconds", "wobu -settings keep-unused-sandboxes 3s", "kept for")
        time.sleep(SETTLE * 8)
        truthy("no sweep claimed the sandbox stamped older than any window",
               "Purged" not in log.read(), log.read().strip()[-400:])
        truthy("and the row is still in the table, where it can be seen and fixed",
               RAW_STALE_SANDBOX in context_names_raw(), str(context_names_raw()[:8]))
        truthy("with nothing on the console", not traces(log), str(traces(log)[:2]))
    finally:
        rcon.run("wobu -settings keep-unused-sandboxes 30d")
        write_rows_raw([f"DELETE FROM wake_obu_settings WHERE context_name IN "
                        f"('{RAW_SETTINGS}', '{RAW_SETTINGS.title()}')",
                        f"DELETE FROM wake_obu_contexts WHERE name IN "
                        f"('{RAW_NO_TYPE}', '{RAW_UNADDRESSABLE}', '{RAW_MIXED_CASE}', "
                        f"'{RAW_STALE_SANDBOX}', '{RAW_SETTINGS}')"])
        reread_store()


def drill_import_settings_door():
    """A settings block in an export file is the one place a setting arrives without a command argument
    type behind it, so it is where the door has to stand.

    Five of them are not settings a context can hold: `-reset` clears a context, an impulse fires once at
    whoever is aimed at, the subtractive ones edit what a context already holds, and the interpolation
    flag is one the mod applies to itself -- no context holds any of them, so a file naming one has to be
    refused rather than written, or the table ends up holding a row the loader will never hand back and
    nothing can delete. The next shape is the same setting twice: the table keys settings by identity, so
    the file's last word is the one that survives, and the cache has to say the same thing the table
    does. The last is the same setting twice over one
    list, which is not a replacement but a fold -- both blocks are kept, in one entry, or the client is
    sent the same block twice and applies whichever arrived last."""
    console = graft_export({"sandbox": "  doorprobe:\n    settings:\n      '-reset': ''\n"
                                       "      applyimpulserelative: '0.0 5.0 0.0'\n"
                                       "      clearslipperiness: ''\n"
                                       "      removeblockslipperiness: 'ice'\n"
                                       "      setinterpolationten: 'true'\n"
                                       "      stepsize:\n        - '1.5'\n        - '2.5'\n"
                                       "      blockslipperiness:\n        - '0.9 ice'\n        - '0.9 packed_ice'\n"})
    if console is None:
        bad("the graft never imported, so there is nothing to judge")
        return
    try:
        truthy("the import named every setting no context can hold",
               console.count("no context holds that setting") == 5, console.strip()[-600:])
        view = run("wobu -sandbox view doorprobe").lower()
        truthy("none of them reached the context",
               "reset" not in view and "applyimpulse" not in view and "interpolation" not in view
               and "clearslipperiness" not in view and "removeblockslipperiness" not in view,
               view.strip()[:400])
        truthy("while the setting beside them did, as the file's last word",
               "stepsize" in view and "2.5" in view and "1.5" not in view, view.strip()[:400])

        step("and the export it comes back out of says the same")
        text, _ = export_now()
        if text is None:
            bad("the export never finished, so the row behind the view is unread")
            return
        block = re.search(r"(?ms)^  doorprobe:\n((?:[ \t]+.*\n?)*)", text)
        body = block.group(1) if block else ""
        truthy("one stepsize, not a list, and nothing no context can hold",
               body.count("stepsize") == 1 and "2.5" in body and "1.5" not in body
               and "reset" not in body and "impulse" not in body and "interpolation" not in body
               and "clearslipperiness" not in body and "removeblockslipperiness" not in body,
               body[:400] or text[:400])
        truthy("and one blockslipperiness carrying both blocks, not one entry per line",
               body.count("blockslipperiness") == 1
               and "ice,packed_ice" in body, body[:400] or text[:400])
    finally:
        rcon.run("wobu -sandbox delete doorprobe")
        time.sleep(SETTLE)


def drill_export_of_a_store_never_read():
    """An export writes the cache, so a cache that never read the table would write a file that looks like
    a full backup and is an empty one. That is the trap the export has to refuse instead of spring.

    It is reached by breaking the loader rather than the database: with a column renamed behind the
    server's back the first read of a fresh store throws, which is a failed read and not an empty table,
    so the store stays unread for as long as the module is up. Nothing else in the outage machinery is
    involved -- the write path is healthy, so `/wake database export` does not refuse it up front."""
    good = EXPORT.read_text(encoding="utf-8", errors="replace") if EXPORT.is_file() else None
    if good is None:
        bad("there is no export file to protect, so the drill would prove nothing")
        return
    set_module_enabled("obu", False)
    rcon.run("wake reload")
    time.sleep(SETTLE)
    write_rows_raw(["ALTER TABLE wake_obu_settings RENAME COLUMN args TO args_hidden"])
    log = Log()
    try:
        set_module_enabled("obu", True)
        rcon.run("wake reload")
        time.sleep(SETTLE * 2)
        counts = run("wobu -settings query-context-count")
        truthy("the store says it never read the table", "has not been read" in counts, counts.strip()[:200])
        truthy("and the console says so once, as a failed read",
               log.read().count("Failed to read wake_obu_contexts") == 1, log.read().strip()[-500:])

        step("so the export refuses rather than writing a file with nothing in it")
        log = Log()
        rcon.run("wake database export obu")
        truthy("the export reported failure", log.await_line("Database export failed for module obu", 30),
               log.read().strip()[-400:])
        truthy("naming what it could not read", "OBU contexts could not be read" in log.read(),
               log.read().strip()[-400:])
        truthy("and the file that was already there is untouched",
               EXPORT.read_text(encoding="utf-8", errors="replace") == good,
               EXPORT.read_text(encoding="utf-8", errors="replace")[:300])
        truthy("with no half-written file left beside it",
               not EXPORT.with_suffix(".yml.tmp").is_file(), str(EXPORT.parent))
    finally:
        set_module_enabled("obu", False)
        rcon.run("wake reload")
        time.sleep(SETTLE)
        # noinspection SqlResolve
        write_rows_raw(["ALTER TABLE wake_obu_settings RENAME COLUMN args_hidden TO args"])
        set_module_enabled("obu", True)
        rcon.run("wake reload")
        time.sleep(SETTLE * 2)
    truthy("and the store reads again once the column is back",
           "has not been read" not in run("wobu -settings query-context-count")
           and KNOWN_CONTEXT in run("wobu -context").lower(), run("wobu -context").strip()[:300])


def drill_export_round_trip():
    """An export looks like a backup, so everything an admin can reach has to come back out of it identical.

    Three shapes are the ones naive string handling loses. A sandbox key carries an `@uuid` and an owner
    beside it. A context with nothing in it is a section with no body, which is easy to write and easy to
    read as absent. And a setting's arguments are written joined by a space and read back by splitting on
    one, so a value holding a space would come back as two. The graft door is what puts a player-owned
    sandbox here without a second player."""
    # eight blocks whose keys together pass the width of the column that keys them, so the door cuts
    # them into buckets -- and the export has to write those in a shape that imports as the same ones
    spilling = ",".join(f"othermod:{'spill' * 6}{index}" for index in range(8))
    console = graft_export({
        "sandbox": f"  {ROUND_SANDBOX}:\n    owner_uuid: {GRAFT_OWNER}\n"
                   "    settings:\n      stepsize: '1.5'\n      setblocksetting:\n"
                   "        - 'JUMPS 2 stone'\n        - 'WALLTAP_MULTIPLIER 1 ice, othermod:blue_ice'\n"
                   f"      blockslipperiness: '0.9 {spilling}'\n",
        "server": f"  {ROUND_EMPTY}: {{}}\n"})
    if console is None:
        bad("the graft never imported, so there is nothing to round-trip")
        return
    try:
        before, counted = export_now()
        if before is None:
            bad("the export never finished, so there is nothing to judge")
            return
        sandboxes, servers, switches = section(before, "sandbox"), section(before, "server"), section(before, "obu")
        truthy("the sandbox is under its own section, keyed and owned",
               f"  {ROUND_SANDBOX}:" in sandboxes and f"owner_uuid: {GRAFT_OWNER}" in sandboxes,
               sandboxes.strip()[:400])
        truthy("the context holding nothing is still a section of its own",
               re.search(rf"(?m)^  {ROUND_EMPTY}: \{{}}$", servers) is not None, servers.strip()[:400])
        contexts = len(re.findall(r"(?m)^ {2}[\w@-]+:(?: \{\})?$", servers + sandboxes))
        truthy(f"and it is counted like any other ({contexts} contexts + {len(switches.splitlines())} switches)",
               counted == contexts + len(switches.splitlines()), f"reported {counted}")
        # and the stored spelling is bare in the default namespace and whole in every other, because
        # only the server a foreign key came from can resolve it back to a block
        truthy("a list argument came out as one word, the spaces inside it gone at the door",
               "JUMPS 2.0 stone" in sandboxes
               and "WALLTAP_MULTIPLIER 1.0 ice,othermod:blue_ice" in sandboxes,
               sandboxes.strip()[:400])
        truthy("and one too long for a single key came out as the buckets it was cut into",
               sandboxes.count("- 0.9 othermod:spill") == 2, sandboxes.strip()[:400])

        step("wipe the store and put the file back into it")
        log = Log()
        rcon.run("wake database drop obu confirm")
        if not log.await_line("Database drop completed for module obu", 30):
            bad("the drop never finished, so the import below would prove nothing")
            return
        truthy("the store is empty", KNOWN_CONTEXT not in run("wobu -context").lower(),
               run("wobu -context").strip()[:300])
        log = Log()
        rcon.run("wake database import obu confirm")
        if not log.await_line("Database import completed for module obu", 30):
            bad("the import never finished, so the store is not back")
            return
        after, restored = export_now()
        if after is None:
            bad("the second export never finished")
            return
        for label, was in (("sandbox", sandboxes), ("server", servers), ("obu", switches)):
            now = section(after, label)
            truthy(f"the {label} section came back line for line",
                   sorted(was.splitlines()) == sorted(now.splitlines()),
                   f"lost {[line for line in was.splitlines() if line not in now][:3]}")
        truthy("and the count with it", restored == counted, f"{counted} out, {restored} back")
        says("the sandbox reads back the repeatable it was given",
             f"wobu -sandbox view {ROUND_SANDBOX}", "walltap_multiplier")
        truthy("with nothing on the console", not traces(log), str(traces(log)[:2]))

        step("and a second import is a merge, not a mirror: it never deletes what the file does not name")
        says("a context the file has never heard of is created", "wobu -sandbox create roundlater", "created")
        rcon.run("wake database import obu confirm")
        time.sleep(SETTLE * 2)
        truthy("it is still there afterwards", "roundlater" in run("wobu -context").lower(),
               run("wobu -context").strip()[:300])
        rcon.run("wobu -sandbox delete roundlater")
    finally:
        rcon.run(f"wobu -sandbox delete {ROUND_SANDBOX}")
        rcon.run(f"wobu -context -delete {ROUND_EMPTY}")
        time.sleep(SETTLE)


def drill_import_without_contexts():
    """A file carrying the module's switches and no contexts at all is a real import, and the two halves of
    an import are counted separately -- so it has to land the switches and leave the store exactly as it
    found it, without walking every online player and every pinned boat for contexts nobody imported."""
    good, _ = export_now()
    if good is None:
        bad("the export never finished, so there is no file to trim")
        return
    listing = run("wobu -context")
    EXPORT.write_text('version: 1\nobu:\n  keep_unused_sandboxes: "31d"\n', encoding="utf-8")
    log = Log()
    try:
        rcon.run("wake database import obu confirm")
        truthy("the import finished", log.await_line("Database import completed for module obu", 30),
               log.read().strip()[-300:])
        truthy("counting the one switch it carried and no context",
               "for module obu (1 records)" in log.read(), log.read().strip()[-300:])
        truthy("the switch reached the database", state("obu.keep_unused_sandboxes", mariadb) == "31d",
               repr(state("obu.keep_unused_sandboxes", mariadb)))
        truthy("and the store is exactly what it was", run("wobu -context") == listing,
               run("wobu -context").strip()[:300])
        truthy("with nothing on the console", not traces(log), str(traces(log)[:2]))
    finally:
        EXPORT.write_text(good, encoding="utf-8")
        rcon.run("wake database import obu confirm")
        time.sleep(SETTLE)
        rcon.run("wobu -settings keep-unused-sandboxes 30d")


def drill_publish_collision():
    """Publishing renames a sandbox to the display half of its key, so it is the one operation that can
    walk a name into a namespace that already holds it. Only a key carrying an `@uuid` can collide --
    a console sandbox is already stored under its display name -- so the graft door is what puts one
    here without a second player. A publish that went through would fold two contexts into one row."""
    keyed = f"{KNOWN_CONTEXT}@{GRAFT_OWNER}"
    console = graft_export({"sandbox": f"  {keyed}:\n    owner_uuid: {GRAFT_OWNER}\n"
                                       "    settings:\n      stepsize: '9.0'\n"})
    if console is None:
        bad("the export/import round trip never finished, so there is nothing to publish")
        return
    try:
        listing = run("wobu -context")
        truthy("a sandbox arrives keyed under a name a server context already has",
               keyed.lower() in listing.lower(), listing.strip()[:400])
        says("publishing it is refused", f"wobu -sandbox publish {keyed}", "already exists")
        listing = run("wobu -context")
        truthy("it is still a sandbox", keyed.lower() in listing.lower(), listing.strip()[:400])
        says("carrying what it had", f"wobu -sandbox view {keyed}", "stepsize")
        truthy("and the server context of that name is untouched beside it",
               listing.lower().count(KNOWN_CONTEXT) == 2, listing.strip()[:400])
        says("which still forks as the server context, not the sandbox",
             f"wobu -sandbox fork {KNOWN_CONTEXT} deliverycollide", "forked")
        says("and what it copied is the server context's", "wobu -sandbox view deliverycollide", "defaultslipperiness")
    finally:
        rcon.run("wobu -sandbox delete deliverycollide")
        rcon.run(f"wobu -sandbox delete {keyed}")


def drill_context_listing():
    """The listing is what an admin reads the store off, so the two kinds are told apart and the internal one is in neither."""
    rcon.run("wobu -sandbox delete deliverylist")
    says("a console sandbox is created", "wobu -sandbox create deliverylist", "created")
    says("and refused the second time", "wobu -sandbox create deliverylist", "already exists")
    listing = run("wobu -context")
    truthy("the listing carries the server header", "server contexts" in listing.lower(), listing.strip()[:300])
    truthy("and the sandbox header beside it", "your sandboxes" in listing.lower(), listing.strip()[:300])
    truthy("with the sandbox under one of them", "deliverylist" in listing.lower(), listing.strip()[:300])
    truthy("and the internal context in neither", "wake:empty" not in listing.lower(), listing.strip()[:300])
    says("a fork copies a server context into a new sandbox",
         "wobu -sandbox fork harbour deliveryfork", "forked")
    says("and the fork carries what it copied", "wobu -sandbox view deliveryfork", "defaultslipperiness")
    rcon.run("wobu -sandbox delete deliveryfork")
    rcon.run("wobu -sandbox delete deliverylist")


def drill_context_delete():
    """The delete a command reports has to be the delete the store made."""
    rcon.run("wobu -sandbox delete deliverytest")
    rcon.run("wobu -context -delete deliverytest")
    says("a console sandbox is created", "wobu -sandbox create deliverytest", "created")
    says("and published as a server context", "wobu -sandbox publish deliverytest", "published")
    says("publishing takes it out of the sandboxes", "wobu -sandbox view deliverytest", "does not exist")
    truthy("and puts it in the context listing", "deliverytest" in run("wobu -context").lower())

    step("-delete is a literal, and the word without its dash is just a name")
    if boat():
        boat_says("'-context delete <name>' is read as a context called that",
                  "wobu -context delete deliverytest", "delete deliverytest")
        rcon.run(f"kill @e[tag={TAG}]")
    truthy("and the context it did not delete is still there", "deliverytest" in run("wobu -context").lower())

    step("deleting it names it once and takes it out of the listing")
    says("the delete is reported", "wobu -context -delete DELIVERYTEST", "deleted server context")
    truthy("and the context is gone", "deliverytest" not in run("wobu -context").lower(),
           run("wobu -context").strip()[:300])
    says("deleting it again finds nothing", "wobu -context -delete deliverytest", "does not exist")


def drill_pinned_to_a_deleted_context():
    """A boat pointing at a context that no longer exists must not take the server down with it."""
    rcon.run("wobu -sandbox delete deliverypin")
    rcon.run("wobu -context -delete deliverypin")
    run("wobu -sandbox create deliverypin")
    run("wobu -sandbox publish deliverypin")
    if not boat():
        bad("could not summon the boat this drill needs")
        return
    boat_says("the boat pins to it", "wobu -context deliverypin", "applied context")
    log = Log()
    says("the context is deleted under it", "wobu -context -delete deliverypin", "deleted server context")
    time.sleep(SETTLE)
    truthy("the sweep over the pinned boats reached the console as nothing at all", not traces(log), str(traces(log)[:2]))
    truthy("the boat keeps the pin it was given", pinned_on_boat() == "deliverypin", repr(pinned_on_boat()))
    boat_says("and still answers commands", "wobu stepsize 1.5", "set stepsize")
    boat_says("and can still be unpinned", "wobu -context default", "applied context")
    truthy("with the dangling pin dropped", pinned_on_boat() is None, repr(pinned_on_boat()))
    rcon.run(f"kill @e[tag={TAG}]")


def drill_publish_under_a_pinned_boat():
    """Publishing rewrites the context a boat is standing on, so the sweep behind it has to reach that boat."""
    rcon.run("wobu -sandbox delete deliverypub")
    rcon.run("wobu -context -delete deliverypub")
    says("a console sandbox is created", "wobu -sandbox create deliverypub", "created")
    if not boat():
        bad("could not summon the boat this drill needs")
        return
    boat_says("the boat pins to the sandbox", "wobu -context deliverypub", "applied context")
    truthy("and carries it", pinned_on_boat() == "deliverypub", repr(pinned_on_boat()))
    log = Log()
    says("it is published out from under the boat", "wobu -sandbox publish deliverypub", "published")
    time.sleep(SETTLE)
    truthy("the sweep reached the console as nothing at all", not traces(log), str(traces(log)[:2]))
    truthy("the pin still names what is now a server context", pinned_on_boat() == "deliverypub", repr(pinned_on_boat()))
    boat_says("and the boat still answers", "wobu stepsize 1.5", "set stepsize")
    rcon.run(f"kill @e[tag={TAG}]")
    rcon.run("wobu -context -delete deliverypub")


def drill_sweep_over_pinned_boats():
    """A reload and an import both re-read contexts under the boats standing on them, from a callback that has to land on the main thread."""
    if not boat():
        bad("could not summon the boat this drill needs")
        return
    boat_says("the boat is pinned", f"wobu -context {KNOWN_CONTEXT}", "applied context")

    step("a reload settles without disturbing it")
    log = Log()
    rcon.run("wake reload")
    time.sleep(SETTLE)
    truthy("the reload left nothing on the console", not traces(log), str(traces(log)[:2]))
    truthy("and the boat keeps its pin", pinned_on_boat() == KNOWN_CONTEXT, repr(pinned_on_boat()))

    step("an import re-reads every context, then sweeps every boat standing on one")
    log = Log()
    rcon.run("wake database export obu")
    if not log.await_line("Database export completed for module obu", 30):
        bad("the export never finished, so the import below would have nothing to read")
        return
    ok("the export finished")
    log = Log()
    rcon.run("wake database import obu confirm")
    truthy("the import finished", log.await_line("Database import completed for module obu", 30),
           log.read().strip()[-300:])
    truthy("with nothing on the console", not traces(log), str(traces(log)[:2]))
    truthy("the boat still carries its pin", pinned_on_boat() == KNOWN_CONTEXT, repr(pinned_on_boat()))
    boat_says("and still answers", "wobu stepsize 1.5", "set stepsize")
    rcon.run(f"kill @e[tag={TAG}]")


def drill_keep_window():
    """The keep window is the only number an admin types at the purger, and three answers have to stay
    apart: a duration it will sweep on, an off switch, and a refusal. A spelling that slid from one to
    another would either purge on a window nobody set or stop purging without saying so."""
    for spelling in ("90s", "45min", "10h", "30d", "2w", "6mo", "1y", "30D", '" 30d "'):
        says(f"{spelling} is a duration", f"wobu -settings keep-unused-sandboxes {spelling}", "kept for")
    step("and the spelling it echoes back is the one it stored")
    reply = run('wobu -settings keep-unused-sandboxes " 30D "')
    truthy("padding and case are gone from the echo", "30d" in reply and "30D" not in reply, reply.strip()[:200])

    truthy("and the overview shows the window it stored", settings_panel().get("unused sandboxes kept for") == "30d",
           str(settings_panel())[:300])

    step("every way of saying never is off, not a window of zero")
    for spelling in ("0", "0d", "off", "never", "disabled", "NEVER"):
        says(f"{spelling} disables the sweep", f"wobu -settings keep-unused-sandboxes {spelling}", "purging is now")
    truthy("and the overview says so rather than showing a window of zero",
           settings_panel().get("unused sandboxes kept for") == "never purged", str(settings_panel())[:300])

    step("and everything else is refused rather than read as one of the two")
    for spelling in ("30", "d", "abc", "-1d", "30m", '"30 d"', '""',
                     "9999999999y", "99999999999999999999d"):
        says(f"{spelling} is refused", f"wobu -settings keep-unused-sandboxes {spelling}", "invalid duration")
    rcon.run("wobu -settings keep-unused-sandboxes 30d")


def drill_purge():
    """The sweep is the only thing that deletes a context nobody named, so what it takes, what it
    spares and what it leaves behind are all read back off the server rather than trusted.

    The keep window is a state row every server sharing the database reads, so on mariadb either one
    can be the server that sweeps. The signal here is therefore the row leaving *this* server's
    cache, never a line on this server's console -- which is also the whole cross-server story: the
    loser of the race sees the purge as an ordinary invalidation."""
    rcon.run("wobu -sandbox delete purgestale")
    rcon.run("wobu -sandbox delete purgefresh")
    says("a sandbox to leave sitting is created", "wobu -sandbox create purgestale", "created")
    if not boat():
        bad("could not summon the boat this drill needs")
        return
    boat_says("and a boat is left standing on it", "wobu -context purgestale", "applied context")
    window = 3.0  # short enough to watch, and the sweep runs on the same interval
    log = Log()
    says("the keep window is cut to three seconds", f"wobu -settings keep-unused-sandboxes {window:.0f}s", "kept for")
    try:
        truthy("the sandbox nothing touched leaves the cache",
               wait_until(lambda: "purgestale" not in run("wobu -context").lower(), 30),
               run("wobu -context").strip()[:300])
        truthy("with nothing on the console", not traces(log), str(traces(log)[:2]))
        truthy("the boat keeps the pin it was given", pinned_on_boat() == "purgestale", repr(pinned_on_boat()))
        boat_says("and still answers", "wobu stepsize 1.5", "set stepsize")
        boat_says("and can still be unpinned", "wobu -context default", "applied context")

        step("a sandbox that keeps being used outlives the same window")
        says("a second sandbox is created", "wobu -sandbox create purgefresh", "created")
        deadline = time.monotonic() + window * 3
        while time.monotonic() < deadline:
            as_boat("wobu -context purgefresh")  # pinning is an access, so this one never ages out
            time.sleep(0.5)
        truthy("it survived several sweeps of a three-second window",
               "purgefresh" in run("wobu -context").lower(), run("wobu -context").strip()[:300])

        step("and with the sweep off it ages out without being taken")
        says("purging is disabled", "wobu -settings keep-unused-sandboxes never", "purging is now")
        time.sleep(window * 3)
        truthy("the sandbox is still there several windows later",
               "purgefresh" in run("wobu -context").lower(), run("wobu -context").strip()[:300])
    finally:
        rcon.run("wobu -settings keep-unused-sandboxes 30d")
        rcon.run("wobu -sandbox delete purgefresh")
        rcon.run(f"kill @e[tag={TAG}]")


def settings_panel():
    """The bare `-settings` overview, as {row name: value}. Read off the bullets rather than off the
    lines: whether a <br> reaches a console as a newline is the renderer's business, not this drill's."""
    return {name.strip().lower(): value.strip().lower()
            for name, value in PANEL_ROW.findall(run("wobu -settings"))}


def drill_settings_switches():
    """Every switch `-settings` carries, the answer it owes, and the two rows it has to land in.

    None of them shows in play from a console -- persistence only on a relog, the lag fix and the
    interpolation flag only on a client computing its own physics, the collapsed layer only inside
    `-status`, which needs a player -- so a key the command writes but nothing reads would still reply
    `enabled`. The bare command is the read-back: it renders the same list that declares the
    sub-commands, so a switch that is settable and not readable cannot exist without this drill seeing
    a row short. The export is the other binding: it sweeps the module's prefix, so the value set here
    has to come back out under the name its reader defaults."""
    switches = {"persistent-player-states": ("persistent player states", "persistent_player_states", "true"),
                "boat-lag-fix": ("boat lag fix", "boat_lag_fix", "true"),
                "collapse-default-context": ("collapsed default context", "collapse_default_context", "true"),
                "update-nag": ("update nag", "update_nag", "true"),
                "setinterpolationten": ("ten-step interpolation", "setinterpolationten", "false")}
    try:
        for literal, (feature, _, _) in switches.items():
            on = run(f"wobu -settings {literal} true")
            truthy(f"{literal} on names the feature", feature in on.lower() and "enabled" in on.lower(), on.strip()[:200])
            panel = settings_panel()
            truthy("and the overview reads it back on", panel.get(feature) == "on", str(panel)[:300])
            off = run(f"wobu -settings {literal} false")
            truthy(f"and {literal} off says disabled", "disabled" in off.lower(), off.strip()[:200])
            panel = settings_panel()
            truthy("and the overview reads it back off", panel.get(feature) == "off", str(panel)[:300])

        step("and the overview is exactly the switches, with the keep window beside them")
        expected = {feature for feature, _, _ in switches.values()} | {"unused sandboxes kept for"}
        panel = settings_panel()
        truthy(f"all {len(expected)} rows are there and no others", set(panel) == expected,
               f"missing {sorted(expected - set(panel))}, unexpected {sorted(set(panel) - expected)}")

        step("and each one landed under the key its reader defaults")
        log = Log()
        rcon.run("wake database export obu")
        if not log.await_line("Database export completed for module obu", 30):
            bad("the export never finished, so no row could be read back")
            return
        text = EXPORT.read_text(encoding="utf-8", errors="replace")
        for literal, (_, key, _) in switches.items():
            truthy(f"{literal} wrote {key}", f"{key}: false" in text, text[:400])
    finally:
        for literal, (_, _, default) in switches.items():
            rcon.run(f"wobu -settings {literal} {default}")


def main():
    global rcon, mariadb
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

    rcon.run("forceload add 0 0")
    try:
        for drill in [drill_seeds_an_empty_store,
                      drill_pin_storage, drill_boat_overrides, drill_context_replaces_overrides,
                      drill_chunk_unload_eviction, drill_override_eviction,
                      drill_module_cycle, drill_reserved_contexts, drill_imported_names,
                      drill_rows_no_command_wrote, drill_import_settings_door,
                      drill_export_round_trip, drill_import_without_contexts,
                      drill_export_of_a_store_never_read,
                      drill_publish_collision, drill_context_listing,
                      drill_context_delete, drill_pinned_to_a_deleted_context,
                      drill_publish_under_a_pinned_boat, drill_sweep_over_pinned_boats,
                      drill_keep_window, drill_purge, drill_settings_switches]:
            print(f"\n{drill.__name__.removeprefix('drill_').replace('_', ' ')}")
            drill()
    except RuntimeError as error:
        bad(str(error))
    finally:
        rcon.run(f"kill @e[tag={TAG}]")
        rcon.run("forceload remove 0 0")

    print()
    if failures:
        print(f"{len(failures)} drill step(s) failed")
        return 1
    print("all delivery drills passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
