#!/usr/bin/env python3
"""Module-system drills against a running Wake server.

Covers what the `core/module` framework package owns wherever a console reaches it: the config toggle
in every direction, the one module that toggle cannot reach, a module surviving repeated cycles
without duplicating or losing what it registered, the hot listener a feature claims from the
framework and has to give back, the store it is rebuilt from on the way back up, the bundled defaults
an empty store seeds, the seeding decision an outage postpones rather than settles, the format stamp
an export carries, the state prefix it sweeps, and the service seam between two modules.

    python testenv/drills_module.py

The service seam is watched from both ends. A published service is withdrawn by the disable that
ends it, so the next enable finds the slot free -- a leaked one makes `register` refuse, the enable
is reversed and the reload reports "Failed to sync" instead of "Enabled". The consumer side is
drydock, which has to keep working with the module it resolves switched off.

The `core` module is never cycled here because nothing can cycle it: it is not optional, so
`config.yml` has no say over it and only shutdown takes it down. `drill_core_is_not_toggleable`
holds that guarantee -- it is what keeps `/wake reload` from being able to remove itself.

What is left to TESTPLAN.md: anything a module registers that only a player can watch fire, the
incompatible branch on a host without AxiomPaper (skipped here when it is installed), a compatibility
check that throws rather than answering, an export whose save dies part-way rather than being refused
before it starts, and the boot-time refusals -- a duplicate module id is rejected before the server is
up, so a running one can never reach it.
Command visibility past "the tree comes and goes with it" belongs to drills_commands.py, and what a
seed, an import or a reset announces to the servers sharing the database to drills_changelog.py.

Needs a server up with RCON (./gradlew runServer). Exits non-zero if a drill fails.
"""

import argparse
import os
import re
import sqlite3
import sys
import time
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from drills import (Log, Rcon, bad, detect_backend, docker, failures, ok, outage,  # noqa: E402
                    set_module_enabled, state, state_keys, step, switch, write_state_raw)

WAKE = Path(__file__).resolve().parents[1] / "run" / "plugins" / "wake"
CONFIG = WAKE / "config.yml"
EXPORTS = WAKE / "exports"
UNKNOWN = "Unknown or incomplete command"
MOVE_EVENT = "org.bukkit.event.player.PlayerMoveEvent"
COMPLETED = re.compile(r"Database (\w+) completed for module (\w+) \((\d+) records\)")
OUTCOMES = [
    ("enabled", re.compile(r"(?<!Dis)Enabled module: (\w+)")),
    ("disabled", re.compile(r"Disabled module: (\w+)")),
    ("reloaded", re.compile(r"Reloaded module: (\w+)")),
    ("incompatible", re.compile(r"incompatible: (\w+)")),
    ("failed", re.compile(r"Failed to sync module: (\w+)")),
]
SETTLE = 1.5


def truthy(label, condition, detail=""):
    (ok if condition else bad)(label if condition else f"{label} -- {detail}")


def different(raw):
    """Another value of the same shape, so a reload takes it and the import has something to undo."""
    return {"true": "false", "false": "true"}.get(raw, '"zzdrill"')


def reload_outcomes(rcon: Rcon):
    """What /wake reload reported for each module, as a list per module so a doubled line shows."""
    reply = rcon.run("wake reload")
    seen = {}
    for name, pattern in OUTCOMES:
        for module in pattern.findall(reply):
            seen.setdefault(module, []).append(name)
    return seen


def cycle(rcon: Rcon, module, enabled):
    """Flips the module in config.yml and reloads, answering with what the reload said about it."""
    set_module_enabled(module, enabled)
    return reload_outcomes(rcon).get(module, [])


def write_schema_version(module, version, mariadb=None):
    """Stamps a schema version behind the server's back: a DAO that reads one it cannot support throws."""
    if mariadb:
        container, user, password, database = mariadb
        docker("exec", container, "mariadb", f"-u{user}", f"-p{password}", database, "-N", "-B",
               "-e", f"REPLACE INTO wake_schema_version (module, version) VALUES ('{module}', {version})")
        return
    connection = sqlite3.connect(str(WAKE / "wake.db"), timeout=10)
    try:
        connection.execute("REPLACE INTO wake_schema_version (module, version) VALUES (?, ?)", (module, version))
        connection.commit()
    finally:
        connection.close()


def database(rcon: Rcon, log: Log, command, verb, module, timeout=20):
    """Runs a /wake database command and answers with the record count it reported, or None."""
    rcon.run(command)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        for seen_verb, seen_module, count in COMPLETED.findall(log.read()):
            if seen_verb == verb and seen_module == module:
                return int(count)
        time.sleep(0.5)
    return None


def drill_toggle_directions(rcon: Rcon, log: Log, mariadb):
    """Every direction the config toggle can be read in, including the two that change nothing."""
    step("a module that is already on reloads rather than enabling again")
    outcomes = reload_outcomes(rcon)
    truthy("drydock reports one reload and nothing else", outcomes.get("drydock") == ["reloaded"],
           str(outcomes.get("drydock")))

    step("switching it off in config.yml takes it down on the next reload")
    log.reset()
    truthy("it reports disabled once", cycle(rcon, "drydock", False) == ["disabled"])
    truthy("the console logged it", log.await_line("Module 'drydock' has been disabled", 10), log.read()[-200:])
    truthy("and its tree stopped answering", UNKNOWN in rcon.run("dd boostpad list"))

    step("and a reload while it is off says nothing about it at all")
    outcomes = reload_outcomes(rcon)
    truthy("no line names drydock", "drydock" not in outcomes, str(outcomes))
    truthy("its neighbours still reload", outcomes.get("core") == ["reloaded"], str(outcomes))

    step("switching it back on brings it up on the next reload")
    truthy("it reports enabled once", cycle(rcon, "drydock", True) == ["enabled"])
    listing = rcon.run("dd boostpad list")
    truthy("and its tree answers again", UNKNOWN not in listing, listing[:120])


def drill_core_is_not_toggleable(rcon: Rcon, log: Log, mariadb):
    """The one module config.yml cannot reach. Without this, a reload can take /wake reload with it."""
    step("the shipped config offers no switch for it")
    config = CONFIG.read_text(encoding="utf-8")
    truthy("there is no modules.core entry to flip", re.search(r"(?m)^  core:$", config) is None, config)

    step("a hand-written switch is ignored rather than obeyed")
    CONFIG.write_text(re.sub(r"(?m)^modules:.*\n", "modules:\n  core:\n    enabled: false\n", config, count=1),
                      encoding="utf-8")
    try:
        outcomes = reload_outcomes(rcon)
        truthy("core reloads anyway", outcomes.get("core") == ["reloaded"], str(outcomes))
        reply = rcon.run("wake help")
        truthy("and its tree still answers", UNKNOWN not in reply, reply[:120])
    finally:
        CONFIG.write_text(config, encoding="utf-8")
    truthy("it still reloads once the entry is gone", reload_outcomes(rcon).get("core") == ["reloaded"])


def drill_repeated_cycles(rcon: Rcon, log: Log, mariadb):
    """Three off/on cycles have to leave the module exactly as they found it.

    A service the disable did not withdraw is what the second enable trips over, so three clean enables in
    a row are the console-visible half of publish and withdraw. What drydock answers in between is the
    other half: a consumer resolves the service on every use, so it keeps working while it is gone.
    """
    before = rcon.run("wobu -context")
    log.reset()
    for round_number in range(1, 4):
        step(f"cycle {round_number}")
        truthy("obu goes down", cycle(rcon, "obu", False) == ["disabled"])
        listing = rcon.run("dd boostpad list")
        truthy("its consumer keeps working without it", "Status:" in listing, listing[:120])
        truthy("obu comes back up, so nothing it published outlived it", cycle(rcon, "obu", True) == ["enabled"])

    after = rcon.run("wobu -context")
    truthy("its contexts came back unchanged, none of them doubled", after == before, after[:200])
    # Paper prints a SEVERE record as ERROR, so the level and the trace are both worth watching for
    noise = [line for line in log.read().splitlines()
             if "[wake]" in line and ("ERROR" in line or "Exception" in line)]
    truthy("and no cycle logged an error", not noise, noise[0] if noise else "")

    step("every module is still named exactly once by a reload")
    outcomes = reload_outcomes(rcon)
    doubled = {module: names for module, names in outcomes.items() if len(names) != 1}
    truthy(f"one line each for {', '.join(sorted(outcomes))}", not doubled, str(doubled))


def drill_claimed_listener(rcon: Rcon, log: Log, mariadb):
    """A hot listener a module claims from core has to come and go with it, exactly once.

    `VehiclePath` registers on PlayerMoveEvent only while a feature holds a claim on it, which drydock's
    boostpad detector takes and gives back. Paper is asked rather than Wake: a claim that is never given
    back leaves the listener on the busiest event in the game, and one given back twice loses the
    recording for whoever still wanted it. Both read straight off `/paper dumplisteners`.
    """
    def registrations():
        return rcon.run(f"paper dumplisteners {MOVE_EVENT}").count("VehiclePath")

    def listing():
        return rcon.run("dd boostpad list")

    def set_boostpads(enabled):
        if (switch(listing()) == "enabled") != enabled:
            rcon.run("dd boostpad toggle")

    if "→" not in listing():
        raise RuntimeError("no boostpad is configured, so nothing would claim the recording")
    was_on = switch(listing()) == "enabled"
    try:
        set_boostpads(False)
        truthy("nothing is on PlayerMoveEvent while the feature is off", registrations() == 0)
        set_boostpads(True)
        truthy("a configured pad and the switch on registers it once", registrations() == 1)

        step("ten off/on cycles of the feature switch")
        for round_number in range(10):
            set_boostpads(False)
            if registrations() != 0:
                truthy(f"cycle {round_number} released it", False, "still registered with the feature off")
                break
            set_boostpads(True)
            if registrations() != 1:
                truthy(f"cycle {round_number} reclaimed it once", False, f"{registrations()} registration(s)")
                break
        else:
            truthy("ten cycles leave exactly one registration", registrations() == 1)

        step("and the module going down releases it too")
        cycle(rcon, "drydock", False)
        truthy("nothing is left on PlayerMoveEvent", registrations() == 0)
        cycle(rcon, "drydock", True)
        truthy("the enable claims it again, once", registrations() == 1)
    finally:
        set_boostpads(was_on)


def drill_state_survives_cycle(rcon: Rcon, log: Log, mariadb):
    """A module rebuilds its caches from the database, so what was set before a cycle is there after it."""
    pad = "minecraft:blue_ice"
    rcon.run("dd boostpad toggle")
    rcon.run(f"dd boostpad add {pad} 0.5 0.0 0.25 400")
    time.sleep(SETTLE)
    before = rcon.run("dd boostpad list")
    truthy("the drill's pad is in the listing to begin with", "blue_ice" in before, before[:200])

    step("cycling the module off and on")
    cycle(rcon, "drydock", False)
    cycle(rcon, "drydock", True)
    time.sleep(SETTLE)
    after = rcon.run("dd boostpad list")
    truthy("the listing came back identical, neither reset to defaults nor doubled", after == before, after[:200])

    rcon.run(f"dd boostpad remove {pad}")
    rcon.run("dd boostpad toggle")
    time.sleep(SETTLE)


def drill_reseeds_empty_store(rcon: Rcon, log: Log, mariadb):
    """A module that comes up on an empty store seeds its bundled defaults, without an admin asking."""
    step("emptying the store")
    log.reset()
    dropped = database(rcon, log, "wake database drop drydock confirm", "drop", "drydock")
    time.sleep(SETTLE)
    truthy("the drop ran", dropped is not None, "no completion line")
    listing = rcon.run("dd boostpad list")
    truthy("and left no pads behind", "none configured" in listing, listing[:200])

    step("bringing the module back up on it")
    cycle(rcon, "drydock", False)
    log.reset()
    truthy("it enables again", cycle(rcon, "drydock", True) == ["enabled"])
    time.sleep(SETTLE)
    truthy("the console says it seeded the jar's records", log.await_line("Auto-seeded", 10), log.read()[-300:])
    listing = rcon.run("dd boostpad list")
    truthy("and the bundled pads are live without a setdefaults", "coral" in listing, listing[:200])


def drill_seeds_after_recovery(rcon: Rcon, log: Log, mariadb):
    """A store that could not be read when its module came up is not an empty store, and not a decided one either.

    Coming up during an outage is the one case where "seed the defaults" cannot be answered: a mirror
    refuses to read while the database is degraded rather than rewind the cache to nothing. Skipping the
    seed there is right; leaving it skipped for good is not, because the module then runs on no defaults
    at all until somebody restarts it. So the question has to come back once the database does.
    """
    step("emptying the store while the database still answers")
    log.reset()
    truthy("the drop ran", database(rcon, log, "wake database drop drydock confirm", "drop", "drydock") is not None,
           "no completion line")
    time.sleep(SETTLE)
    cycle(rcon, "drydock", False)

    with outage(mariadb):
        step("making the database unreachable, then bringing the module up on it")
        rcon.run("wake hints true")  # a write that cannot land is what puts the database into degraded mode
        time.sleep(SETTLE)
        log.reset()
        truthy("it still enables", cycle(rcon, "drydock", True) == ["enabled"])
        time.sleep(SETTLE)
        truthy("but seeds nothing, because an unread store is not an empty one",
               "Auto-seeded" not in log.read(), log.read()[-300:])
        listing = rcon.run("dd boostpad list")
        truthy("and it is running on no pads at all", "none configured" in listing, listing[:200])

    step("and the database comes back")
    truthy("recovery is reported", log.await_line("Database recovered", 90), log.read()[-300:])
    truthy("the seeding decision it could not take is taken now", log.await_line("Auto-seeded", 30), log.read()[-300:])
    listing = rcon.run("dd boostpad list")
    truthy("the bundled pads are live without a restart or a setdefaults", "coral" in listing, listing[:200])


def drill_export_every_module(rcon: Rcon, log: Log, mariadb):
    """Every module that is running exports and takes its own file back; one that is off is refused."""
    running = [module for module, outcome in reload_outcomes(rcon).items() if outcome == ["reloaded"]]
    truthy(f"the reload names the running modules ({', '.join(running)})", running, "none reported")
    for module in running:
        log.reset()
        exported = database(rcon, log, f"wake database export {module}", "export", module)
        path = EXPORTS / f"{module}_data.yml"
        truthy(f"{module} exported a file", exported is not None and path.is_file(), f"reported {exported}")
        # the file is staged beside itself and moved over the last one, so a save that dies half-way cannot truncate a good backup
        truthy("under its own name, with nothing staged left beside it", not (EXPORTS / f"{module}_data.yml.tmp").exists())
        log.reset()
        imported = database(rcon, log, f"wake database import {module} confirm", "import", module)
        truthy(f"and read the same count back in ({exported})", imported == exported, f"reported {imported}")
        time.sleep(SETTLE)

    step("a module that is switched off cannot be operated on")
    cycle(rcon, "drydock", False)
    log.reset()
    reply = rcon.run("wake database export drydock")
    time.sleep(SETTLE)
    truthy("the export is refused and nothing ran",
           "not loaded" in reply and not COMPLETED.search(log.read()), reply.strip())
    cycle(rcon, "drydock", True)


def drill_export_format_gate(rcon: Rcon, log: Log, mariadb):
    """An export file stamped past what this build reads is refused whole rather than read as defaults.

    The stamp is the only thing that can tell a file's layout from the one this jar expects, and a backup
    outlives the build that wrote it -- so an import that reads a newer file key by key silently writes
    every key it does not recognise as a default, over the records it was meant to restore.

    A file with no stamp is the other end of the same question. It predates the stamp, so it is the v1
    layout and must still import; reading it as whatever this build writes would make every pre-stamp
    backup claim the newest layout the moment the number is bumped for the first time.
    """
    step("exporting a good file")
    log.reset()
    exported = database(rcon, log, "wake database export drydock", "export", "drydock")
    path = EXPORTS / "drydock_data.yml"
    good = path.read_text(encoding="utf-8")
    truthy("it carries the format stamp", "version: 1" in good, good[:120])

    step("stamping it past this build and importing it back")
    path.write_text(good.replace("version: 1", "version: 99"), encoding="utf-8")
    before = rcon.run("dd boostpad list")
    try:
        log.reset()
        rcon.run("wake database import drydock confirm")
        time.sleep(SETTLE)
        truthy("the import is refused", "export format v99" in log.read(), log.read()[-300:])
        truthy("and nothing ran behind the refusal", not COMPLETED.search(log.read()), log.read()[-200:])
        truthy("the store is untouched", rcon.run("dd boostpad list") == before, before[:200])
    finally:
        path.write_text(good, encoding="utf-8")

    step("and the same file reads back in once the stamp is one it knows")
    log.reset()
    truthy(f"it imports the {exported} records it wrote",
           database(rcon, log, "wake database import drydock confirm", "import", "drydock") == exported)

    step("a file carrying no stamp at all is the layout that predates the stamp, not the one this build writes")
    path.write_text("\n".join(line for line in good.splitlines() if not line.startswith("version:")), encoding="utf-8")
    try:
        log.reset()
        truthy(f"it imports the same {exported} records rather than being refused",
               database(rcon, log, "wake database import drydock confirm", "import", "drydock") == exported)
    finally:
        path.write_text(good, encoding="utf-8")


def drill_enable_failure(rcon: Rcon, log: Log, mariadb):
    """A module that throws on the way up has to leave the server as if it had never started.

    The database is made to refuse it: a schema stamped past what this build supports is exactly the failure
    an admin meets after a downgrade, and it throws from `initTables()`, halfway through the enable. What
    must not survive it is anything the module registered before that line -- and the reload after the cause
    is fixed has to bring it up, or a transient failure would cost a restart.
    """
    step("stamping a schema version this build cannot support")
    cycle(rcon, "drydock", False)
    write_schema_version("drydock", 99, mariadb)
    try:
        log.reset()
        outcome = cycle(rcon, "drydock", True)
        truthy("the reload reports it failed rather than enabled", outcome == ["failed"], str(outcome))
        truthy("the console names the reason", log.await_line("update the Wake jar", 10), log.read()[-300:])
        truthy("its tree never appeared", UNKNOWN in rcon.run("dd boostpad list"))
        truthy("and nothing it half-registered is operable",
               "not loaded" in rcon.run("wake database export drydock"))
    finally:
        write_schema_version("drydock", 1, mariadb)

    step("and the next reload brings it up on its own")
    truthy("it enables", cycle(rcon, "drydock", True) == ["enabled"])
    listing = rcon.run("dd boostpad list")
    truthy("with its pads, none of them doubled by the failed attempt", "coral" in listing, listing[:200])


def drill_failure_reaches_no_further(rcon: Rcon, log: Log, mariadb):
    """One module that cannot come up is the only one that fails; the sync still reaches the ones behind it.

    Modules are enabled in declaration order, so anything thrown past `syncModules` would take every module
    after the failing one with it -- silently, and leaving the failed one in the active set with its tree
    still showing. obu is second of four, so drydock behind it is the probe. The same reload has to name
    both, one failed and one reloaded.
    """
    step("failing the module declared before drydock")
    cycle(rcon, "obu", False)
    write_schema_version("obu", 99, mariadb)
    try:
        log.reset()
        set_module_enabled("obu", True)
        outcomes = reload_outcomes(rcon)
        truthy("obu reports failed", outcomes.get("obu") == ["failed"], str(outcomes))
        truthy("drydock, declared after it, still reloaded", outcomes.get("drydock") == ["reloaded"], str(outcomes))
        truthy("and core, declared before it, reloaded too", outcomes.get("core") == ["reloaded"], str(outcomes))
        truthy("obu never entered the active set", "not loaded" in rcon.run("wake database export obu"))
        truthy("and its tree stayed hidden", UNKNOWN in rcon.run("wobu -context"))
    finally:
        write_schema_version("obu", 1, mariadb)

    step("and the next reload brings it up beside the rest")
    outcomes = reload_outcomes(rcon)
    truthy("obu enables", outcomes.get("obu") == ["enabled"], str(outcomes))
    truthy("its tree answers again", UNKNOWN not in rcon.run("wobu -context"))


def drill_state_sweep(rcon: Rcon, log: Log, mariadb):
    """Every state key a module holds reaches the file, and every one the file carries reaches the store back.

    `exportState` sweeps the module's prefix rather than naming keys, so this walks whatever the store actually
    holds instead of a list here that would drift the moment a setting is added. What it catches is the half a
    single-key check cannot: a sweep that reaches all but one key, or an import that writes all but one back,
    still looks like a working backup right up to the restore that quietly loses a setting.
    """
    for module in [name for name, outcome in reload_outcomes(rcon).items() if outcome == ["reloaded"]]:
        keys = state_keys(f"{module}.", mariadb)
        if not keys:
            step(f"{module} holds no state of its own")
            continue

        step(f"{module} exports all {len(keys)} of the keys the store holds for it")
        log.reset()
        database(rcon, log, f"wake database export {module}", "export", module)
        text = (EXPORTS / f"{module}_data.yml").read_text(encoding="utf-8")
        missing = [key for key in keys if f"{key.split('.', 1)[1]}:" not in text]
        truthy("none of them was left out of the file", not missing, f"missing {missing}")
        before = {key: state(key, mariadb) for key in keys}

        step("changing every one of them behind the server's back")
        for key, value in before.items():
            write_state_raw(key, different(value), mariadb)
        rcon.run("wake reload")
        time.sleep(SETTLE)
        unchanged = [key for key in keys if state(key, mariadb) == before[key]]
        truthy("the store answers with a different value for each", not unchanged, f"still original {unchanged}")

        step("and the import puts every one back")
        log.reset()
        database(rcon, log, f"wake database import {module} confirm", "import", module)
        time.sleep(SETTLE)
        lost = {key: state(key, mariadb) for key in keys if state(key, mariadb) != before[key]}
        truthy(f"all {len(keys)} came back to what the file carried", not lost, f"still wrong {lost}")


def drill_incompatible(rcon: Rcon, log: Log, mariadb):
    """A module the environment cannot support stays off and says so, however config.yml reads."""
    log.reset()
    outcome = cycle(rcon, "axiom", True)
    if outcome in (["enabled"], ["reloaded"]):
        step("skipped: AxiomPaper is installed here, so nothing this build ships is incompatible")
        return
    truthy("axiom reports itself incompatible rather than failing", outcome == ["incompatible"], str(outcome))
    truthy("the console says why", "incompatible with this environment" in log.read(), log.read()[-200:])
    truthy("and it is not operable, because it never came up",
           "not loaded" in rcon.run("wake database export axiom"))


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
    log = Log()
    original = CONFIG.read_text(encoding="utf-8")
    try:
        for drill in [drill_toggle_directions, drill_core_is_not_toggleable,
                      drill_repeated_cycles, drill_claimed_listener,
                      drill_state_survives_cycle,
                      drill_reseeds_empty_store, drill_seeds_after_recovery,
                      drill_export_format_gate, drill_enable_failure,
                      drill_failure_reaches_no_further, drill_export_every_module, drill_state_sweep,
                      drill_incompatible]:
            print(f"\n{drill.__name__.removeprefix('drill_').replace('_', ' ')}")
            drill(rcon, log, mariadb)
    except RuntimeError as error:
        bad(str(error))
    finally:
        CONFIG.write_text(original, encoding="utf-8")
        rcon.run("wake reload")

    print()
    if failures:
        print(f"{len(failures)} drill step(s) failed")
        return 1
    print("all module drills passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
