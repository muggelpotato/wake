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

So is the store the whole surface stands on: what an empty one seeds, and the names the import door
has to refuse because no command would ever have written them.

What a client *does* with any of it is not here, because it needs a client. That half is TESTPLAN §2.

    python testenv/drills_delivery.py       # needs a server up (./gradlew runServer)

Runs against sqlite and mariadb alike. Exits non-zero if a drill fails.
"""

import argparse
import os
import re
import sys
import time
from collections.abc import Callable

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from drills import CODES, Log, ROOT, Rcon, bad, failures, ok, set_module_enabled, step  # noqa: E402

SETTLE = 1.0
TAG = "wakedelivery"
EXPORT = ROOT / "run" / "plugins" / "wake" / "exports" / "obu_data.yml"
# a boat summoned twice under this id is, to everything keyed by UUID, the same boat
FIXED_UUID = "[I;16,32,48,64]"
# where CraftBukkit keeps an entity's persistent data container inside its nbt
PDC = "BukkitValues"
PIN = re.compile(r'"?wake:obu_context"?\s*:\s*"([^"]*)"')
# a context every install has, seeded from the jar, and one nothing will ever seed
KNOWN_CONTEXT = "harbour"
UNKNOWN_CONTEXT = "nosuchcontextanywhere"
# two players who were never here: a sandbox key is only ever built from its owner's id
GRAFT_OWNER = "0f1e2d3c-4b5a-4968-8778-695a4b3c2d1e"
OTHER_OWNER = "1a2b3c4d-5e6f-4708-8192-a3b4c5d6e7f8"

rcon = None


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


def boat(uuid=None):
    """Puts one tagged boat in the world, optionally under a fixed id, and answers whether it is there."""
    rcon.run(f"kill @e[tag={TAG}]")
    nbt = f'{{Tags:["{TAG}"],NoGravity:1b' + (f",UUID:{uuid}}}" if uuid else "}")
    rcon.run(f"summon minecraft:oak_boat 0 100 0 {nbt}")
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

    step("a player-wide setting is refused on a boat rather than stored on it")
    boat_says("setinterpolationten names the reason", "wobu setinterpolationten true", "applies to players")
    boat_says("and left no override behind", "wobu -clear setinterpolationten", "is not active")
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
    """A chunk going out is a boat leaving the world: what Wake held for it goes, what the boat carries comes back."""
    if not boat(FIXED_UUID):
        bad("could not summon the boat this drill needs")
        return
    as_boat(f"wobu -context {KNOWN_CONTEXT}")
    as_boat("wobu stepsize 1.5")
    boat_says("the override is on the boat while its chunk is loaded", "wobu -clear stepsize", "cleared stepsize")
    as_boat("wobu stepsize 1.5")
    first = boat_uuid()

    step("let the chunk go")
    rcon.run("forceload remove 0 0")
    if not boat_unloaded():
        bad("the chunk never finished unloading, so nothing below would prove anything")
        rcon.run("forceload add 0 0")
        return
    ok("the boat left the world with its chunk")

    step("and bring it back")
    rcon.run("forceload add 0 0")
    if not wait_until(boat_present):
        bad("the boat did not come back with its chunk")
        return
    truthy("it is the same boat by id", boat_uuid() == first and first is not None,
           f"{first} vs {boat_uuid()}")
    truthy("carrying the pin it was given", pinned_on_boat() == KNOWN_CONTEXT, repr(pinned_on_boat()))
    boat_says("but not the override Wake was holding for it", "wobu -clear stepsize", "is not active")
    rcon.run(f"kill @e[tag={TAG}]")


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
    for section, body in sections.items():
        edited = (edited.replace(f"{section}:\n", f"{section}:\n{body}", 1) if f"{section}:\n" in edited
                  else edited + f"{section}:\n{body}")
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
    much later. The one well-formed graft in the same file is what says this is a door, not a wall."""
    console = graft_export({"sandbox":
                            "  default:\n    settings:\n      stepsize: '9.0'\n"
                            f"  default@{GRAFT_OWNER}:\n    owner_uuid: {GRAFT_OWNER}\n"
                            f"  graftstray@{GRAFT_OWNER}:\n    owner_uuid: {OTHER_OWNER}\n"
                            f"  graftok@{GRAFT_OWNER}:\n    owner_uuid: {GRAFT_OWNER}\n"
                            "    settings:\n      stepsize: '1.5'\n",
                            "server": "  graftat@server:\n    settings:\n      stepsize: '9.0'\n"})
    if console is None:
        bad("the export/import round trip never finished, so there is nothing to judge")
        return
    truthy("the import named both reserved names it refused, keyed and bare",
           console.count("that name is reserved") == 2, console.strip()[-600:])
    truthy("and named the two nothing could have addressed, for the other reason",
           console.count("no command could reach a context stored under that name") == 2, console.strip()[-600:])
    listing = run("wobu -context").lower()
    for refused in (f"default@{GRAFT_OWNER}".lower(), f"graftstray@{GRAFT_OWNER}".lower(), "graftat@server"):
        truthy(f"{refused} is not in the store", refused not in listing, listing.strip()[:400])
    says("no sandbox answers to the reserved name", "wobu -sandbox view default", "does not exist")
    says("and default is still the protected server context", "wobu -context -delete default", "cannot delete")

    step("while the graft that is shaped like a real row goes straight in")
    truthy("the sandbox keyed to its own owner is there",
           f"graftok@{GRAFT_OWNER}".lower() in listing, listing.strip()[:400])
    says("and it carries what the file gave it", f"wobu -sandbox view graftok@{GRAFT_OWNER}", "stepsize")
    rcon.run(f"wobu -sandbox delete graftok@{GRAFT_OWNER}")


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

    step("every way of saying never is off, not a window of zero")
    for spelling in ("0", "0d", "off", "never", "disabled", "NEVER"):
        says(f"{spelling} disables the sweep", f"wobu -settings keep-unused-sandboxes {spelling}", "purging is now")

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


def drill_settings_switches():
    """Every switch `-settings` carries, the answer it owes, and the row it has to land in.

    Neither one shows from a console -- persistence only on a relog, the lag fix only on a client
    computing its own physics -- so a key the command writes but nothing reads would still reply
    `enabled`. The export is what binds the two: it sweeps the module's prefix, so the value set here
    has to come back out under the name the reader asks for."""
    switches = {"persistence": ("persistent player states", "persistent_player_states"),
                "boat-lag-fix": ("boat lag fix", "boat_lag_fix")}
    try:
        for literal, (feature, _) in switches.items():
            on = run(f"wobu -settings {literal} true")
            truthy(f"{literal} on names the feature", feature in on.lower() and "enabled" in on.lower(), on.strip()[:200])
            off = run(f"wobu -settings {literal} false")
            truthy(f"and {literal} off says disabled", "disabled" in off.lower(), off.strip()[:200])

        step("and each one landed under the key its reader defaults")
        log = Log()
        rcon.run("wake database export obu")
        if not log.await_line("Database export completed for module obu", 30):
            bad("the export never finished, so no row could be read back")
            return
        text = EXPORT.read_text(encoding="utf-8", errors="replace")
        for literal, (_, key) in switches.items():
            truthy(f"{literal} wrote {key}", f"{key}: false" in text, text[:400])
    finally:
        for literal in switches:
            rcon.run(f"wobu -settings {literal} true")


def main():
    global rcon
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25575)
    parser.add_argument("--password", default="wake-dev")
    args = parser.parse_args()

    try:
        rcon = Rcon(args.host, args.port, args.password)
    except OSError as error:
        raise SystemExit(f"cannot reach RCON at {args.host}:{args.port} ({error}). "
                         f"Start ./gradlew runServer first.")

    rcon.run("forceload add 0 0")
    try:
        for drill in [drill_seeds_an_empty_store,
                      drill_pin_storage, drill_boat_overrides, drill_context_replaces_overrides,
                      drill_chunk_unload_eviction, drill_override_eviction,
                      drill_module_cycle, drill_reserved_contexts, drill_imported_names,
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
