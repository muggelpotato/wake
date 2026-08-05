#!/usr/bin/env python3
"""Argument-type drills against a running Wake server.

Covers what `core/commands/arguments` owns wherever a console sender reaches it: one key out of a
known set (a block, a boat type, a set a module owns), a name the type validates, and a module id,
whose set is whatever is running right now rather than a fixed list. The list types ride OBU setting
commands and are drilled in drills_obu.py.

    python testenv/drills_arguments.py

An executor that needs a player parses its arguments first, so "only players" is a pass -- a
Brigadier parse error is the one signal these checks look for.

Needs a server up with RCON (./gradlew runServer). Exits non-zero if a check fails.
"""

import argparse
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
# also installs the utf-8 stdout wrapper the section-sign colour codes need
from drills import Rcon, CODES, set_module_enabled  # noqa: E402

# Brigadier's own rejections, plus the localized ones the argument types raise
PARSE_ERROR = ("Incorrect argument", "Unknown or incomplete", "Expected", "Invalid block",
               "Invalid boat type", "Invalid option", "Invalid sandbox name", "Module not loaded",
               "not in the boostpad list", "<--[HERE]")
PAD = "sea_lantern"  # a block the shipped defaults leave without a pad
SANDBOX = "drillbox"

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
    return reply


def expect(label, command, needle):
    reply = run(command)
    if needle.lower() in reply.lower():
        ok(label)
    else:
        bad(label, f"{command!r} -> {reply}")
    return reply


def drill_key():
    print("\none key out of a known set")
    rcon.run(f"dd boostpad remove {PAD}")
    parses("a bare name", f"dd boostpad add {PAD} 0 0 .1 0")
    parses("a namespaced name", f"dd boostpad add minecraft:{PAD} 0 0 .1 0")
    parses("uppercase", f"dd boostpad add {PAD.upper()} 0 0 .1 0")
    parses("an empty namespace", f"dd boostpad add :{PAD} 0 0 .1 0")
    listed = run("dd boostpad list")
    if listed.count(PAD) == 1:
        ok("all four spellings stored the one canonical key")
    else:
        bad("canonical key", f"{PAD} appears {listed.count(PAD)}x: {listed}")

    print("\nand refuses everything else")
    rejects("an unknown block", "dd boostpad add not_a_block 0 0 .1 0")
    rejects("an item that is not a block", "dd boostpad add stick 0 0 .1 0")
    rejects("two colons", "dd boostpad add a:b:c 0 0 .1 0")
    rejects("a namespace with no key", "dd boostpad add minecraft: 0 0 .1 0")
    rejects("a non-ascii letter", "dd boostpad add İce 0 0 .1 0")
    rejects("nothing at all", "dd boostpad add")
    reply = rejects("a stray character", f"dd boostpad add {PAD}! 0 0 .1 0")
    if f"{PAD}!" in reply:
        ok("the refusal quotes the whole word, not the part a key could hold")
    else:
        bad("stray character", reply)


def drill_module_keys():
    print("\na key set a module owns")
    expect("a configured pad is addressable", f"dd boostpad toggle {PAD}", PAD)
    expect("and toggles back", f"dd boostpad toggle {PAD}", PAD)
    rejects("a block with no pad configured", "dd boostpad toggle stone")
    rejects("an unknown block", "dd boostpad remove not_a_block")
    expect("the pad is removed", f"dd boostpad remove {PAD}", "removed")
    rejects("and is not addressable any more", f"dd boostpad toggle {PAD}")


def drill_boat_type():
    print("\na boat type")
    parses("a boat", "dd getboat oak_boat parkour oars")
    parses("a raft", "dd getboat bamboo_raft parkour nooars")
    parses("namespaced, and an enum in any case", "dd getboat minecraft:oak_boat PARKOUR")
    rejects("a block that is not a boat", "dd getboat stone parkour oars")
    rejects("a boat that does not exist", "dd getboat oak_bot parkour oars")
    rejects("an unknown variant", "dd getboat oak_boat nonsense oars")
    rejects("an unknown oars value", "dd getboat oak_boat parkour maybe")
    reply = rejects("a stray character in a choice", "dd getboat oak_boat park!our oars")
    if "park!our" in reply:
        ok("the refusal quotes the whole word, not the part a choice could hold")
    else:
        bad("stray character", reply)
    reply = rejects("a quoted choice", 'dd getboat oak_boat "parkour" oars')
    if '"parkour"' in reply:
        ok("and names a quoted one rather than refusing nothing at all")
    else:
        bad("quoted choice", reply)


def drill_name():
    print("\na name the type validates")
    rcon.run(f"wo -sandbox delete {SANDBOX}")
    expect("a name is stored lowercased", f"wo -sandbox create {SANDBOX.capitalize()}", SANDBOX)
    expect("and resolves in any case", f"wo -sandbox view {SANDBOX.upper()}", SANDBOX)
    rejects("an @", "wo -sandbox create with@at")
    rejects("a dot", "wo -sandbox create a.b")
    rejects("a space", "wo -sandbox create a b")
    rejects("quotes", 'wo -sandbox create "quoted"')
    rejects("33 characters", "wo -sandbox create " + "a" * 33)
    expect("the refusal quotes the name typed", "wo -sandbox create with@at", "with@at")
    rejects("a fork destination", "wo -sandbox fork default with@at")
    rejects("an import destination", "wo -sandbox import somecode with@at")
    rejects("a word name stops where the client stops", "wo -sandbox fork my:ctx copy")
    expect("a greedy name swallows the whole tail", "wo -context -delete delete nothere", "delete nothere")
    rcon.run(f"wo -sandbox delete {SANDBOX}")


def drill_module():
    """A module id is refused where it is typed, so no destructive branch is ever entered with a bad one.

    The set is the modules that are running, not the ones this build ships: one turned off in
    config.yml has no cache to export and no tables to drop, so it must stop being addressable the
    moment it goes down and start again when it comes back.

    Driven from the warning half of `drop`, which answers on the spot and drops nothing: the confirm
    branch under it is the one that acts.
    """
    print("\na module id")
    parses("a running module", "wake database drop drydock")
    reply = expect("in any case", "wake database drop DRYDOCK", "drydock")
    if "DRYDOCK" in reply:
        bad("canonical id", f"the executor was handed what was typed: {reply}")
    else:
        ok("and what comes back out is the id Wake knows, not the spelling that was typed")
    rejects("a module this build does not have", "wake database drop doesnotexist")
    reply = rejects("even in front of confirm", "wake database drop doesnotexist confirm")
    if "doesnotexist" in reply:
        ok("the refusal quotes what was typed")
    else:
        bad("refusal text", reply)

    print("\nand reads the whole word before it judges it")
    for label, typed in [("a stray character at the end", "drydock!"),
                         ("one in the middle", "dry!dock"),
                         ("a quoted id", '"drydock"')]:
        reply = rejects(label, f"wake database drop {typed}")
        if typed in reply:
            ok(f"the refusal quotes {typed}, not the part an id could hold")
        else:
            bad(label, reply)
    rejects("nothing at all", "wake database drop")
    rejects("a trailing space and nothing after it", "wake database drop ")

    print("\nand only while the module is running")
    # an export replies after the command returned, so a clean parse is all a console can read here
    parses("an acting branch takes one that is up", "wake database export drydock")
    time.sleep(2)  # let that export finish: a module is only operated on one command at a time
    set_module_enabled("drydock", False)
    try:
        run("wake reload")
        rejects("a module turned off is not addressable", "wake database export drydock")
    finally:
        set_module_enabled("drydock", True)
        run("wake reload")
    parses("and is addressable again once it is back", "wake database export drydock")


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

    drill_key()
    drill_module_keys()
    drill_boat_type()
    drill_name()
    drill_module()

    print()
    if failures:
        print(f"{len(failures)} check(s) failed: {', '.join(failures)}")
        return 1
    print("all argument drills passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
