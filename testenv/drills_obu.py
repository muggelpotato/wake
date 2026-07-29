#!/usr/bin/env python3
"""OBU setting-protocol drills against a running Wake server.

Covers the surface that `SettingType` owns: every semantic argument type still parses, still refuses
what it should, still stores and displays, and still gates an unencodable setting out of the database.

    python testenv/drills_obu.py

Console has no entity, so a setting command that parses still reports an invalid target -- which is
the signal we want: a Brigadier parse error means the argument type is wrong, anything else means it
was accepted. Storage and display go through the sandbox commands, which run as the console sender.

Needs a server up with RCON (./gradlew runServer). Exits non-zero if a check fails.
"""

import argparse
import base64
import gzip
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
# also installs the utf-8 stdout wrapper the section-sign colour codes need
from drills import Rcon, CODES  # noqa: E402

# Brigadier's own rejections, including the range messages the byte type relies on
PARSE_ERROR = ("Incorrect argument", "Unknown or incomplete", "Expected", "must not be",
               "Invalid option", "Invalid block", "Invalid entity", "<--[HERE]")

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


def share(*entries):
    """A share code is gzipped, then url-safe base64 without padding. See SandboxCommandHelper"""
    payload = (";".join(entries) + ";").encode()
    return base64.urlsafe_b64encode(gzip.compress(payload)).decode().rstrip("=")


def drill_parsing():
    print("\nevery semantic type still parses")
    parses("float", "wo stepsize 0.6")
    parses("double", "wo boatgravity -0.04")
    parses("int", "wo coyotetime 3")
    parses("byte", "wo setcollisionresolution 200")
    parses("boolean", "wo falldamage true")
    parses("block_list", "wo removeblockslipperiness ice")
    parses("entity_list", "wo addcollisionfilter minecraft:pig")
    parses("collision_enum", "wo collisionmode VANILLA")
    parses("setting_enum + float + block_list", "wo setblocksetting JUMP_FORCE 1.0 ice")
    parses("float + block_list", "wo blockslipperiness 0.9 ice,packed_ice")
    parses("three doubles", "wo applyimpulse 0.1 0.2 0.3")
    parses("no arguments", "wo clearslipperiness")

    print("\nand still refuses what it should")
    rejects("byte above 255", "wo setcollisionresolution 300")
    rejects("byte below 0", "wo setcollisionresolution -1")
    rejects("unknown collision mode", "wo collisionmode NONSENSE")
    rejects("unknown per-block setting", "wo setblocksetting NONSENSE 1.0 ice")
    rejects("non-numeric float", "wo stepsize abc")
    rejects("unknown block", "wo removeblockslipperiness not_a_block")
    rejects("unknown entity", "wo addcollisionfilter not_an_entity")


def drill_storage():
    print("\nstorage, display and export")
    rcon.run("wo -sandbox delete codectest")
    # 1=stepsize(float) 27=collisionmode(collision_enum) 30=setcollisionresolution(byte)
    # 3=blockslipperiness(float, block_list), stored already namespaced, which is what display must strip
    code = share("1:0.6", "27:NO_ENTITIES", "30:3", "3:0.9 minecraft:ice")
    expect("a share code of every stored type imports", f'wo -sandbox import "{code}" codectest', "imported")
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


def drill_encodability_gate():
    print("\nthe encodability gate")
    rcon.run("wo -sandbox delete gatetest")
    # every second entry is one the wire cannot carry: an unknown enum, a float that is not a number,
    # and a byte out of range. Each must be dropped rather than stored, because a setting that throws
    # on encode would take down every later sync for whoever holds it
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

    drill_parsing()
    drill_storage()
    drill_encodability_gate()

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
