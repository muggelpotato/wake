#!/usr/bin/env python3
"""Drills for the base module's own commands.

Covers what a console can judge about the flat `/wake` commands: the switches an admin flips and
where they land, which boats `/wake killemptyboats` takes and which it has to leave alone, and the
order a reload answers in.

The boats are the half that used to need a human. Only a passenger keeps a boat: a chest boat is a
boat, and cargo does not make it somebody's. So each case gets its own boat and its own tag, and
the drill sweeps the world first so the reported count can be checked exactly rather than
"at least".

    python testenv/drills_base.py       # needs a server up (./gradlew runServer)

Runs against sqlite and mariadb alike. Exits non-zero if a drill fails.
"""

import argparse
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from drills import Rcon, bad, detect_backend, failures, ok, state, step  # noqa: E402

SETTLE = 1.5
REMOVED = re.compile(r"Removed (\d+) empty boats")
BOAT = "minecraft:oak_boat"
CHEST_BOAT = "minecraft:oak_chest_boat"
# one tag per case, so what survived is read off the world rather than off a count
CASES = ["wakedrill_empty", "wakedrill_ridden", "wakedrill_chest", "wakedrill_cargo"]


def truthy(label, condition, detail=""):
    (ok if condition else bad)(label if condition else f"{label} -- {detail}")


def probe(rcon: Rcon, entity, tag, at="0 100 0"):
    """Summons one tagged entity that stays where it is put."""
    rcon.run(f'summon {entity} {at} {{Tags:["wakedrill","{tag}"],NoGravity:1b}}')


def alive(rcon: Rcon, tag):
    return "passed" in rcon.run(f"execute if entity @e[tag={tag},limit=1]").lower()


def holds(rcon: Rcon, tag, path):
    """Whether the tagged entity carries something at an NBT path (a passenger, an item)."""
    return "passed" in rcon.run(f"execute if data entity @e[tag={tag},limit=1] {path}").lower()


def drill_kill_empty_boats(rcon: Rcon, mariadb):
    """A boat nobody is sitting in is an empty boat, whatever it is carrying."""
    step("every boat without a passenger is removed")
    rcon.run("forceload add 0 0")
    rcon.run("kill @e[tag=wakedrill]")
    # start from a world with no abandoned boat in it, so the count below is exactly this drill's
    rcon.run("wake killemptyboats")
    time.sleep(SETTLE)
    try:
        probe(rcon, BOAT, "wakedrill_empty")
        probe(rcon, BOAT, "wakedrill_ridden")
        probe(rcon, CHEST_BOAT, "wakedrill_chest")
        probe(rcon, CHEST_BOAT, "wakedrill_cargo")
        rcon.run('summon minecraft:pig 0 101 0 {Tags:["wakedrill","wakedrill_pig"],NoAI:1b,NoGravity:1b}')
        rcon.run("ride @e[tag=wakedrill_pig,limit=1] mount @e[tag=wakedrill_ridden,limit=1]")
        rcon.run("item replace entity @e[tag=wakedrill_cargo,limit=1] container.0 with minecraft:stone")

        absent = [tag for tag in CASES if not alive(rcon, tag)]
        if absent:
            bad(f"could not put the boats this drill needs in the world ({', '.join(absent)})")
            return
        if not holds(rcon, "wakedrill_ridden", "Passengers[0]"):
            bad("the mob never boarded its boat, so nothing below would prove anything")
            return
        if not holds(rcon, "wakedrill_cargo", "Items[0]"):
            bad("the chest boat never took its cargo, so nothing below would prove anything")
            return

        reply = rcon.run("wake killemptyboats")
        count = REMOVED.search(reply)
        truthy("it reports what it removed", count, reply.strip())
        truthy("the abandoned boat is gone", not alive(rcon, "wakedrill_empty"))
        truthy("and so is the empty chest boat", not alive(rcon, "wakedrill_chest"))
        truthy("cargo does not save a chest boat either", not alive(rcon, "wakedrill_cargo"))
        truthy("the boat a mob is sitting in stayed", alive(rcon, "wakedrill_ridden"))
        truthy("the count is exactly the three it took", count and count.group(1) == "3",
               f"reported {count.group(1) if count else None}")
    finally:
        rcon.run("kill @e[tag=wakedrill]")
        rcon.run("forceload remove 0 0")


def drill_toggles(rcon: Rcon, mariadb):
    """A switch has to name what it changed and reach the database, not only the cache."""
    step("a switch confirms what it set and lands in the database")
    for command, key, feature in [("wake hints", "base.show_hints", "Hints"),
                                  ("wake killboatonexit", "base.killboatonexit", "Auto-kill boat")]:
        before = state(key, mariadb)
        reply = rcon.run(f"{command} true")
        time.sleep(SETTLE)
        truthy(f"/{command} true names the feature and its new value",
               feature in reply and "enabled" in reply, reply.strip())
        truthy("and the database holds it", state(key, mariadb) == "true", repr(state(key, mariadb)))

        reply = rcon.run(f"{command} false")
        time.sleep(SETTLE)
        truthy(f"/{command} false says disabled", "disabled" in reply, reply.strip())
        truthy("and the database holds that too", state(key, mariadb) == "false", repr(state(key, mariadb)))
        if before in ("true", "false"):
            rcon.run(f"{command} {before}")
            time.sleep(SETTLE)


def drill_reload_order(rcon: Rcon, mariadb):
    """The success line is the header the per-module lines hang under, so it has to come first."""
    step("a reload answers with its header first and every module under it")
    lines = [line for line in rcon.run("wake reload").splitlines() if line.strip()]
    truthy("the header is the first line", lines and "Reloaded configuration" in lines[0], str(lines[:1]))
    tail = lines[1:]
    unheard = [module for module in ["base", "obu", "drydock"]
               if sum(module in line for line in tail) != 1]
    truthy(f"each module reports exactly once below it ({len(tail)} lines)", not unheard,
           f"{unheard} missing or doubled in {tail}")


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
        for drill in [drill_toggles, drill_kill_empty_boats, drill_reload_order]:
            print(f"\n{drill.__name__.removeprefix('drill_').replace('_', ' ')}")
            drill(rcon, mariadb)
    except RuntimeError as error:
        bad(str(error))

    print()
    if failures:
        print(f"{len(failures)} drill step(s) failed")
        return 1
    print("all base drills passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
