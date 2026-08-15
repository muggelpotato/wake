#!/usr/bin/env python3
"""Command-framework drills against a running Wake server.

Covers what `core/commands` owns wherever a console sender reaches it: the trees the modules declare
really register (roots, aliases, the namespaced label), each `executes...` flavour resolves the
subject it promises, a gate runs after the target and not before it, a disabled module's commands
disappear instead of half-answering, and the tree survives both `/wake reload` and the second
`COMMANDS` lifecycle event a datapack reload fires.

    python testenv/drills_commands.py

The permission ladder is not in here. The console holds every permission and nothing can take one
away from it, so bundles, denials and hidden literals stay in TESTPLAN section 5 with a second
player and LuckPerms.

A datapack reload rebuilds the whole command dispatcher, so Wake answering afterwards is the proof
that its `COMMANDS` handler ran a second time and re-registered cleanly.

Needs a server up with RCON (./gradlew runServer). Exits non-zero if a check fails.
"""

import argparse
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
# also installs the utf-8 stdout wrapper the section-sign colour codes need
from drills import CODES, WAKE, Log, Rcon  # noqa: E402

UNKNOWN = "Unknown or incomplete command"
CONFIG = WAKE / "config.yml"
# root -> (a sub-command a console can run under it, its aliases)
ROOTS = {
    "wake": ("help", ["wa"]),
    "wakeobu": ("-help", ["wobu", "wo"]),
    "drydock": ("boostpad list", ["dd"]),
}
ENTRY = re.compile(r"●\s+/(\S+)")
SCORE = re.compile(r"has (-?\d+) ")
# something to be that is neither a player nor the console, for /execute as
STAND = 'summon minecraft:armor_stand 0 100 0 {Tags:["wakedrill"],NoGravity:1b,Invulnerable:1b}'
AS_STAND = "execute as @e[tag=wakedrill,limit=1] run "

failures = []
rcon = None


def run(command):
    return CODES.sub("", rcon.run(command))


def ok(label):
    print(f"  ok    {label}")


def bad(label, detail):
    print(f"  FAIL  {label}: {detail.strip()[:200]}")
    failures.append(label)


def expect(label, command, needle):
    reply = run(command)
    if needle.lower() in reply.lower():
        ok(label)
    else:
        bad(label, f"{command!r} -> {reply}")
    return reply


def resolves(label, command):
    reply = run(command)
    if UNKNOWN in reply:
        bad(label, f"{command!r} -> {reply}")
    else:
        ok(label)


def gone(label, command):
    reply = run(command)
    if UNKNOWN in reply:
        ok(label)
    else:
        bad(label, f"{command!r} still answers -> {reply}")


def returns(label, command, expected):
    """The framework's own result contract: a refused command returns 0, one that ran returns 1."""
    run(f"execute store result score probe wakedrill run {command}")
    found = SCORE.search(run("scoreboard players get probe wakedrill"))
    seen = int(found.group(1)) if found else None
    if seen == expected:
        ok(label)
    else:
        bad(label, f"{command!r} returned {seen}, expected {expected}")


def quiet(label, log):
    noise = [line for line in log.read().splitlines()
             if "[wake]" in line and ("SEVERE" in line or "Exception" in line)]
    if noise:
        bad(label, noise[0])
    else:
        ok(label)


def labelled_probes():
    """Every label the tree should answer to, paired with a command under it."""
    for root, (probe, aliases) in ROOTS.items():
        for label in [root, *aliases]:
            yield label, f"{label} {probe}"


def roots_listed():
    return ENTRY.findall(run("wake help"))


def drill_registration():
    print("\nevery declared tree registers")
    for label, command in labelled_probes():
        resolves(f"/{label}", command)
    resolves("the namespaced label /wake:wake", "wake:wake help")
    resolves("and every other root has one too", "wake:wakeobu -help")

    listing = run("wake help")
    listed = ENTRY.findall(listing)
    if listed == sorted(ROOTS):
        ok(f"/wake help lists each root exactly once ({', '.join(listed)})")
    else:
        bad("root listing", f"{listed} != {sorted(ROOTS)}")
    for root, (_, aliases) in ROOTS.items():
        expect(f"/{root} is listed with its aliases", "wake help", f"(/{', /'.join(aliases)})")
    # the description is the only place a root's text comes from now, so a missing key would show here
    if "<commands.help.module" not in listing:
        ok("and every root's description is real text, not a raw message key")
    else:
        bad("root description", listing)

    print("\nand nothing else does")
    reply = run("wake nosuchsubcommand")
    if "<--[HERE]" in reply or UNKNOWN in reply:
        ok("an undeclared sub-command is refused")
    else:
        bad("undeclared sub-command", reply)
    gone("a root that was never declared", "wakenope help")
    gone("a literal whose argument is missing", "wake hints")


def drill_tree_readback():
    print("\na module reading its own declared tree back")
    reply = run("wobu -help")
    for branch in ["-status", "-defaults", "-context", "-sandbox", "-clear", "-settings", "-reset"]:
        if f"/wakeobu {branch}" in reply:
            ok(f"{branch} is described")
        else:
            bad(f"{branch} is described", reply)
    if reply.count("<setting> [args]") == 1:
        ok("every setting node collapses into the one settings line")
    else:
        bad("settings line", f"appears {reply.count('<setting> [args]')}x")
    if "/wakeobu -help" not in reply:
        ok("and -help does not describe itself")
    else:
        bad("-help self-listing", reply)
    if "/wakeobu <command>" not in reply:
        ok("no branch fell through to the generic line, so each one carries a help key")
    else:
        bad("help key", reply)


def drill_subjects():
    print("\nthe subject each executes... flavour promises")
    run("forceload add 0 0")
    run("kill @e[tag=wakedrill]")
    run("scoreboard objectives add wakedrill dummy")
    run(STAND)
    try:
        expect("a console runs an executesSender command", "wake help", "Wake Commands")
        expect("but not an executesPlayer one", "wobu -status", "only be executed by players")
        expect("nor an executesEntity one", "wobu -context default", "must be executed by an entity")
        expect("nor an executesEntityOrAimedBoat one", "wobu stepsize 1.5", "must be executed by an entity")

        print("\nand /execute as hands the executor the subject, not the sender")
        expect("any entity satisfies executesSender", AS_STAND + "wake help", "Wake Commands")
        expect("an armour stand is still not a player", AS_STAND + "wobu -status", "only be executed by players")
        expect("it is an entity, so the executor is reached and judges it for itself",
               AS_STAND + "wobu -reset", "players or boats")
        expect("and the reply goes to whoever typed it, not to the subject",
               AS_STAND + "wobu -context default", "players or boats")

        print("\nand the result the framework returns says which of the two happened")
        returns("a command that ran returns 1", "wake help", 1)
        returns("a command refused on its target returns 0", "wobu -status", 0)
        returns("a command refused by its executor returns 0", AS_STAND + "wobu -reset", 0)
    finally:
        run("kill @e[tag=wakedrill]")
        run("scoreboard objectives remove wakedrill")
        run("forceload remove 0 0")


def drill_gate():
    print("\na gate is checked after the target, and Gate.OPEN lifts it for a branch")
    # /wakeobu gates its whole tree on the OBU client; these three branches declare Gate.OPEN
    expect("an ungated branch runs from a console that could never satisfy the gate",
           "wobu -help", "OpenBoatUtils Help")
    expect("so does the second one", "wobu -context", "Contexts")
    expect("and the third", "wobu -settings query-context-count", "Total")
    # the gated branch never reaches its gate, because the target is resolved first and fails
    expect("a gated branch answers about its target, not about the gate",
           "wobu -context default", "must be executed by an entity")


def drill_module_gate():
    print("\na disabled module's commands are gone, not refused")
    original = CONFIG.read_text(encoding="utf-8")
    disabled, swaps = re.subn(r"(?m)^(  obu:\n    enabled: )true", r"\1false", original)
    if swaps != 1:
        bad("module toggle", f"could not find modules.obu.enabled in {CONFIG}")
        return
    try:
        CONFIG.write_text(disabled, encoding="utf-8")
        expect("the module goes down on /wake reload", "wake reload", "Disabled module: obu")
        gone("its root stops parsing", "wakeobu -help")
        gone("and so does the first alias", "wobu -help")
        gone("and the second", "wo -help")
        gone("and the namespaced label, which no alias could shadow", "wake:wakeobu -help")
        listed = roots_listed()
        if listed == sorted(set(ROOTS) - {"wakeobu"}):
            ok(f"/wake help drops it and keeps the rest ({', '.join(listed)})")
        else:
            bad("help listing", f"{listed} != {sorted(set(ROOTS) - {'wakeobu'})}")
        for root, (probe, _) in ROOTS.items():
            if root != "wakeobu":
                resolves(f"/{root} is untouched by its neighbour going down", f"{root} {probe}")
    finally:
        CONFIG.write_text(original, encoding="utf-8")
        expect("it comes back on the next reload", "wake reload", "Enabled module: obu")
    resolves("with its whole tree", "wobu -help")
    resolves("and its aliases", "wo -context")
    listed = roots_listed()
    if listed == sorted(ROOTS):
        ok("and nothing was lost or doubled by the round trip")
    else:
        bad("round trip", f"{listed} != {sorted(ROOTS)}")


def drill_reload():
    print("\n/wake reload leaves the tree alone")
    log = Log()
    run("wake reload")
    run("wake reload")
    resolves("the tree still answers after two reloads", "wobu -help")
    listed = roots_listed()
    if listed == sorted(ROOTS):
        ok("still one entry per root")
    else:
        bad("reload idempotence", f"{listed} != {sorted(ROOTS)}")
    quiet("no wake errors across either reload", log)


def drill_command_lifecycle():
    print("\nthe COMMANDS lifecycle event survives being fired a second time")
    log = Log()
    run("minecraft:reload")
    time.sleep(3)
    for label, command in labelled_probes():
        resolves(f"/{label} re-registered", command)
    resolves("and the namespaced label with it", "wake:wake help")
    listed = roots_listed()
    if listed == sorted(ROOTS):
        ok("with no root registered twice")
    else:
        bad("re-registration", f"{listed} != {sorted(ROOTS)}")
    quiet("and no permission or registration error", log)


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

    drill_registration()
    drill_tree_readback()
    drill_subjects()
    drill_gate()
    drill_module_gate()
    drill_reload()
    drill_command_lifecycle()

    print()
    if failures:
        print(f"{len(failures)} check(s) failed: {', '.join(failures)}")
        return 1
    print("all command drills passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
