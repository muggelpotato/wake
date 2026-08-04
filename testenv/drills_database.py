#!/usr/bin/env python3
"""Drills for the /wake database admin surface and the outage journal's rough edges.

Walks export, import, setdefaults and drop against whichever backend the server is configured for,
and feeds the journal lines it cannot replay. What is checked is the store, not the file: an export
writes out what a module's cache holds, so a value that reached the database but not the cache
still fails here.

The last three go after the same failure from different sides: while writes are sitting in the
journal the table is behind the cache, so anything that reads it back -- a reload, a module
re-enable, a replay -- must not be allowed to answer with what the table still says.

    python testenv/drills_database.py       # needs a server up (./gradlew runServer)

Runs against sqlite and mariadb alike. Exits non-zero if a drill fails.
"""

import argparse
import os
import re
import sys
import time
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from drills import (JOURNAL, Log, Rcon, await_file, bad, detect_backend, failures, ok, outage,  # noqa: E402
                    set_module_enabled, state, state_keys, step, write_state_raw)

ROOT = Path(__file__).resolve().parents[1]
EXPORTS = ROOT / "run" / "plugins" / "wake" / "exports"
# the reply reaches the sender after the command returned, so the console line is what a script can read
COMPLETED = re.compile(r"Database (\w+) completed for module (\w+) \((\d+) records\)")
SETTLE = 1.5


def truthy(label, condition, detail=""):
    (ok if condition else bad)(label if condition else f"{label} -- {detail}")


def run(rcon: Rcon, log: Log, command, verb=None, module=None, timeout=20):
    """Runs a /wake database command and answers with the record count it reported, or None."""
    rcon.run(command)
    if verb is None:
        time.sleep(SETTLE)
        return None
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        for seen_verb, seen_module, count in COMPLETED.findall(log.read()):
            if seen_verb == verb and seen_module == module:
                return int(count)
        time.sleep(0.5)
    return None


def exported(module):
    path = EXPORTS / f"{module}_data.yml"
    return path.read_text(encoding="utf-8", errors="replace") if path.is_file() else ""


def section(text, name):
    """The block a top-level key owns in an exported file, up to the next line at its indent or shallower."""
    found = re.search(rf"(?ms)^{re.escape(name)}:.*?(?=^\S|\Z)", text)
    return found.group(0).rstrip() if found else ""


def switch(text):
    found = re.search(r"Status:\s*(\w+)", text)
    return found.group(1).lower() if found else None


def drill_export_counts(rcon: Rcon, log: Log, mariadb):
    """The reported count has to match the file, or the export silently double-counts what it swept."""
    step("an export reports exactly what it wrote")
    log.reset()
    count = run(rcon, log, "wake database export drydock", "export", "drydock")
    text = exported("drydock")
    pads = len(re.findall(r"(?m)^\s{4}enabled:", text))
    settings = len(re.findall(r"(?m)^\s{2}boostpads_", text))
    truthy("drydock exported its pads and its switches", pads and settings, f"{pads} pads, {settings} switches")
    truthy(f"the count is pads + switches, swept once ({pads} + {settings})", count == pads + settings,
           f"reported {count}")

    log.reset()
    count = run(rcon, log, "wake database export base", "export", "base")
    keys = len(re.findall(r"(?m)^\s{2}\w+:", exported("base")))
    truthy(f"base counts its state keys once ({keys})", count == keys, f"reported {count}")


def drill_confirm_gate(rcon: Rcon, log: Log, mariadb):
    """Without `confirm` the destructive branches must not touch the store."""
    step("import, drop and setdefaults do nothing without confirm")
    before = switch(rcon.run("dd boostpad list"))
    log.reset()
    for command in ["wake database drop drydock", "wake database setdefaults drydock", "wake database import drydock"]:
        rcon.run(command)
    time.sleep(SETTLE)
    truthy("no operation ran", not COMPLETED.search(log.read()), log.read()[-200:])
    truthy("the store is untouched", switch(rcon.run("dd boostpad list")) == before, f"was {before!r}")


def drill_state_roundtrip(rcon: Rcon, log: Log, mariadb):
    """Every value an admin can set has to survive export -> change -> import."""
    step("a switch flipped after the export comes back on import")
    pads = switch(rcon.run("dd boostpad list"))
    rcon.run("wake hints true")
    rcon.run("dd boostpad add minecraft:blue_ice 0.5 0.0 0.25 400")
    time.sleep(SETTLE)

    log.reset()
    run(rcon, log, "wake database export base", "export", "base")
    run(rcon, log, "wake database export drydock", "export", "drydock")
    truthy("the export carries the switch", "show_hints: true" in exported("base"), exported("base"))
    truthy("and the new pad", "blue_ice" in exported("drydock"), exported("drydock")[:200])

    step("changing everything back, then importing")
    rcon.run("wake hints false")
    rcon.run("dd boostpad toggle")
    rcon.run("dd boostpad remove minecraft:blue_ice")
    time.sleep(SETTLE)
    log.reset()
    run(rcon, log, "wake database import base confirm", "import", "base")
    run(rcon, log, "wake database import drydock confirm", "import", "drydock")
    time.sleep(SETTLE)
    truthy("the state key is back", state("base.show_hints", mariadb) == "true",
           repr(state("base.show_hints", mariadb)))
    # the switch is read out of the state cache, so this is the cache half of the same round trip
    truthy("the module switch is back in the cache", switch(rcon.run("dd boostpad list")) == pads, f"want {pads!r}")
    truthy("the pad is back in the cache", "blue_ice" in rcon.run("dd boostpad list"), rcon.run("dd boostpad list"))

    rcon.run("dd boostpad remove minecraft:blue_ice")
    time.sleep(SETTLE)


def drill_drop_is_scoped(rcon: Rcon, log: Log, mariadb):
    """Drop deletes the module's state prefix and nothing beside it."""
    step("dropping one module leaves the others' state alone")
    rcon.run("wake hints true")
    rcon.run("wake killboatonexit true")
    time.sleep(SETTLE)
    base_before = state_keys("base.", mariadb)
    truthy("base has state to lose", len(base_before) >= 2, str(base_before))
    truthy("so does drydock", state_keys("drydock.", mariadb), "")

    log.reset()
    run(rcon, log, "wake database drop drydock confirm", "drop", "drydock")
    time.sleep(SETTLE)
    listing = rcon.run("dd boostpad list")
    truthy("drydock lost its pads", "coral" not in listing, listing[:200])
    truthy("and every drydock.* key", not state_keys("drydock.", mariadb), str(state_keys("drydock.", mariadb)))
    truthy("base kept all of its keys", state_keys("base.", mariadb) == base_before, str(state_keys("base.", mariadb)))
    truthy("obu kept its contexts", "default" in rcon.run("wobu -context"), rcon.run("wobu -context")[:200])

    step("setdefaults brings the bundled records back")
    log.reset()
    seeded = run(rcon, log, "wake database setdefaults drydock confirm", "setdefaults", "drydock")
    time.sleep(SETTLE)
    truthy("it seeded something", seeded and seeded > 0, f"reported {seeded}")
    truthy("the shipped pads are live again", "coral" in rcon.run("dd boostpad list"),
           rcon.run("dd boostpad list")[:200])


def drill_obu_export_shape(rcon: Rcon, log: Log, mariadb):
    """The obu export has to carry the module's state prefix beside its contexts, swept rather than named.

    A file holding only the records a module knows it owns still looks like a backup, and quietly loses every
    setting an admin can change with a command -- the drift starts the moment a setting is added, because
    nothing names it. The reported count is the other half of the same check: contexts plus state entries, so
    a sweep that missed one shows up as a number here instead of as a restore that comes back wrong.
    """
    step("an unknown module is refused before anything runs")
    log.reset()
    reply = rcon.run("wake database export doesnotexist")
    time.sleep(SETTLE)
    truthy("the module is named back and no operation ran",
           "doesnotexist" in reply and not COMPLETED.search(log.read()), reply.strip())

    step("setdefaults brings the bundled contexts and the obu switches back together")
    log.reset()
    run(rcon, log, "wake database setdefaults obu confirm", "setdefaults", "obu")
    time.sleep(SETTLE)
    listing = rcon.run("wobu -context")
    missing = [name for name in ["default", "harbour", "parkour1", "city", "mazewave", "example"]
               if name not in listing]
    truthy("every bundled context is live", not missing, f"missing {missing}")

    step("and an export sweeps them out beside every obu.* key")
    log.reset()
    count = run(rcon, log, "wake database export obu", "export", "obu")
    text = exported("obu")
    switches = section(text, "obu")
    contexts = len(re.findall(r"(?m)^ {2}[\w@-]+:(?: \{\})?$", text))
    truthy("the contexts are under server:, none of them carrying a type: key",
           "server:" in text and "type:" not in text, text[:200])
    truthy("the state prefix came along, swept rather than named",
           "persistent_player_states" in switches and "keep_unused_sandboxes" in switches, repr(switches))
    entries = len(re.findall(r"(?m)^ {2}\w+:", switches))
    truthy(f"the count is contexts + switches, swept once ({contexts} + {entries})", count == contexts + entries,
           f"reported {count}")

    step("importing that file back keeps a repeatable setting's every entry")
    before = section(text, "server")
    log.reset()
    run(rcon, log, "wake database import obu confirm", "import", "obu")
    time.sleep(SETTLE)
    run(rcon, log, "wake database export obu", "export", "obu")
    after = section(exported("obu"), "server")
    lost = [line for line in before.splitlines() if line.strip() and line not in after]
    truthy("the round trip changed nothing under server:", not lost, f"lost {lost[:3]}")
    truthy("including both entries of the repeatable one",
           after.count("WALLTAP_MULTIPLIER") == 1 and after.count("JUMPS 2 stone") == 1, after[-200:])


def drill_refused_while_degraded(rcon: Rcon, log: Log, mariadb):
    """An export taken during an outage would write out a cache nobody can vouch for."""
    step("the admin surface refuses to run while the database is unreachable")
    pads = rcon.run("dd boostpad list")
    contexts = rcon.run("wobu -context")
    with outage(mariadb):
        # a write has to fail before the layer knows it is degraded: the outage is discovered, not announced
        rcon.run("wake hints false")
        if not await_file(JOURNAL, True, 45):
            bad("no outage journal appeared, so the server never noticed the outage")
            return
        stamp = (EXPORTS / "base_data.yml").stat().st_mtime if (EXPORTS / "base_data.yml").is_file() else 0
        log.reset()
        reply = rcon.run("wake database export base")
        time.sleep(SETTLE)
        truthy("export is refused", "unreachable" in reply.lower(), reply.strip())
        truthy("and wrote no file over the last good one",
               stamp == ((EXPORTS / "base_data.yml").stat().st_mtime if (EXPORTS / "base_data.yml").is_file() else 0))
        truthy("nothing ran behind the refusal", not COMPLETED.search(log.read()), log.read()[-200:])

        # a reload is the one thing that can ask a dead database for a whole table: an empty answer must
        # never reach the cache, because an empty table and an unreachable one are not the same reply
        step("and a reload during the outage empties nothing")
        rcon.run("wake reload")
        time.sleep(3)
        truthy("the pads survived the reload", rcon.run("dd boostpad list") == pads, rcon.run("dd boostpad list")[:200])
        truthy("so did the contexts", rcon.run("wobu -context") == contexts, rcon.run("wobu -context")[:200])

    if log.await_line("Database recovered", 90):
        ok("and it comes back on its own")
    else:
        bad("no recovery within 90s")


def drill_journal_bad_line(rcon: Rcon, log: Log, mariadb):
    """A line the replay cannot parse must cost that line, not the ones around it."""
    step("a corrupt journal line is dropped, the rest still replays")
    log.reset()
    with outage(mariadb):
        rcon.run("wake hints true")
        if not await_file(JOURNAL, True, 45):
            bad("no outage journal appeared")
            return
        time.sleep(2)
        good = len([ln for ln in JOURNAL.read_text(encoding="utf-8").splitlines() if ln.strip()])
        # a hard kill mid-append leaves exactly this: a line that is not a whole JSON object
        with JOURNAL.open("a", encoding="utf-8") as handle:
            handle.write('{"q": "REPLACE INTO wake_state (state_key, sta\n')
            handle.write('{"q": "UPDATE wake_no_such_table SET x = ?", "p": [{"t": "i", "v": 1}]}\n')
        step(f"appended a truncated line and one naming a table that does not exist to {good} good one(s)")

    if not log.await_line("Database recovered", 90):
        bad("no recovery within 90s of the database returning")
        return
    text = log.read()
    dropped = text.count("Dropped unreplayable journal entry")
    truthy("both unreplayable lines were dropped, named in the log", dropped == 2, f"saw {dropped}")
    truthy(f"the {good} good write(s) still replayed", f"replayed {good} journaled writes" in text,
           [ln for ln in text.splitlines() if "Database recovered" in ln][-1:])
    truthy("and the journal was cleared", await_file(JOURNAL, False, 30))
    truthy("the value written during the outage reached the database",
           state("base.show_hints", mariadb) == "true", repr(state("base.show_hints", mariadb)))


def drill_malformed_state_row(rcon: Rcon, log: Log, mariadb):
    """One state value nobody can parse costs that key, never the whole table.

    The loader answers with one map for every row it read, so a value that is not JSON used to throw out of it
    -- which the store reads as a failed read for the entire table. A state mirror stuck behind a failing read
    stops merging: every setting quietly serves its default and no later change is ever taken back off the
    table, on this server or from another one.
    """
    step("a corrupt state value is skipped and the rest of the table still loads")
    before = switch(rcon.run("dd boostpad list"))
    if before is None:
        bad("could not read the boostpad switch to start from")
        return
    key = "base.zz_drill_corrupt"
    write_state_raw(key, '{"broken":', mariadb)
    try:
        log.reset()
        rcon.run("wake reload")
        time.sleep(3)
        text = log.read()
        truthy("the bad row is named on the console", "Skipping malformed state row" in text, text[-300:])
        truthy("and the table was not written off as unreadable", "Failed to read wake_state" not in text,
               text[-300:])
        truthy("the switches beside it survived the reload", switch(rcon.run("dd boostpad list")) == before,
               f"want {before!r}")

        # a mirror frozen behind a failing read never merges again: what that costs is every change another
        # server makes, so the check is a row that moved without this server writing it
        step("and the store still reads back a row that moved under it")
        write_state_raw("drydock.boostpads_enabled", "false" if before == "enabled" else "true", mariadb)
        rcon.run("wake reload")
        time.sleep(3)
        want = "disabled" if before == "enabled" else "enabled"
        truthy("the moved row reached the cache", switch(rcon.run("dd boostpad list")) == want,
               f"want {want!r}, saw {switch(rcon.run('dd boostpad list'))!r}")
    finally:
        write_state_raw(key, None, mariadb)
        if switch(rcon.run("dd boostpad list")) != before:
            rcon.run("dd boostpad toggle")
        time.sleep(SETTLE)


def drill_reload_during_outage(rcon: Rcon, log: Log, mariadb):
    """A reload while the database is behind the journal must not read the cache back to before the outage.

    The dangerous backend is sqlite, where holding the write lock stops writes but leaves reads working: the
    table still answers, and what it answers is every value as it stood before the outage began. A reload that
    trusts that answer silently reverts what the player just did, in game, while telling them it applied.
    """
    step("a change made during an outage survives a reload taken during the same outage")
    pads_before = switch(rcon.run("dd boostpad list"))
    if pads_before is None:
        bad("could not read the boostpad switch to start from")
        return
    pad = "minecraft:packed_ice"
    listed = "packed_ice"  # the listing prints the short name, the command takes the namespaced one
    rcon.run(f"dd boostpad remove {pad}")
    time.sleep(SETTLE)
    truthy("the pad this drill adds is not there to begin with", listed not in rcon.run("dd boostpad list"))

    log.reset()
    with outage(mariadb):
        # both halves of the layer: a state key (wake_state) and a row in a module's own mirrored table
        rcon.run("dd boostpad toggle")
        rcon.run(f"dd boostpad add {pad} 0.4 0.0 0.1 300")
        if not await_file(JOURNAL, True, 45):
            bad("no outage journal appeared, so the server never noticed the outage")
            return
        time.sleep(SETTLE)
        toggled = switch(rcon.run("dd boostpad list"))
        truthy("the switch flipped in the cache", toggled and toggled != pads_before,
               f"{pads_before!r} -> {toggled!r}")
        truthy("and the new pad is in the cache", listed in rcon.run("dd boostpad list"))

        step("reloading with the database still unreachable")
        rcon.run("wake reload")
        time.sleep(3)
        truthy("the switch kept the value the outage journaled",
               switch(rcon.run("dd boostpad list")) == toggled, f"want {toggled!r}")
        truthy("and the pad is still there", listed in rcon.run("dd boostpad list"),
               rcon.run("dd boostpad list")[:200])

    if not log.await_line("Database recovered", 90):
        bad("no recovery within 90s of the database returning")
        return
    time.sleep(SETTLE)
    truthy("after replay the database holds the switch too",
           state("drydock.boostpads_enabled", mariadb) == ("true" if toggled == "enabled" else "false"),
           repr(state("drydock.boostpads_enabled", mariadb)))
    truthy("and the cache still agrees with it", switch(rcon.run("dd boostpad list")) == toggled,
           f"want {toggled!r}")
    truthy("the pad replayed as well", listed in rcon.run("dd boostpad list"), rcon.run("dd boostpad list")[:200])

    rcon.run(f"dd boostpad remove {pad}")
    if switch(rcon.run("dd boostpad list")) != pads_before:
        rcon.run("dd boostpad toggle")
    time.sleep(SETTLE)


def drill_reenable_during_outage(rcon: Rcon, log: Log, mariadb):
    """Re-enabling a module during an outage rebuilds its cache from scratch, and the table is the wrong source.

    A fresh store loads by reading the table, and during an outage the table is behind the journal. It must
    come up empty and unloaded rather than populated with pre-outage rows -- an unloaded store is also what
    stops the module from seeding its bundled defaults over a table it could not read.
    """
    step("a module re-enabled mid-outage does not come back holding pre-outage rows")
    pad, listed = "minecraft:packed_ice", "packed_ice"
    rcon.run(f"dd boostpad remove {pad}")
    time.sleep(SETTLE)

    log.reset()
    try:
        with outage(mariadb):
            rcon.run(f"dd boostpad add {pad} 0.4 0.0 0.1 300")
            if not await_file(JOURNAL, True, 45):
                bad("no outage journal appeared, so the server never noticed the outage")
                return
            set_module_enabled("drydock", False)
            rcon.run("wake reload")
            time.sleep(2)
            truthy("drydock went down", "has been disabled" in log.read(), log.read()[-200:])

            set_module_enabled("drydock", True)
            log.reset()
            rcon.run("wake reload")
            time.sleep(3)
            listing = rcon.run("dd boostpad list")
            # sqlite still answers reads, so the module enables with an unloaded store; mariadb is gone outright,
            # so the schema check fails and the module refuses to enable. Neither may show pre-outage rows
            came_up = "Boostpads" in listing
            step("it enabled with an unloaded store" if came_up
                 else "it refused to enable at all, with the reason on the console")
            truthy("no pre-outage row reached the cache", listed not in listing and "coral" not in listing,
                   listing[:200])
            truthy("and nothing seeded bundled defaults over the table it could not read",
                   "Auto-seeded" not in log.read(), log.read()[-200:])
    finally:
        set_module_enabled("drydock", True)

    if not log.await_line("Database recovered", 90):
        bad("no recovery within 90s of the database returning")
        return
    # a module that refused to enable is only retried by a reload, so this is the same reload an admin would run
    rcon.run("wake reload")
    time.sleep(3)
    listing = rcon.run("dd boostpad list")
    truthy("recovery replayed the journal and refilled the cache", listed in listing, listing[:200])
    truthy("including the pads that were there before the outage", "coral" in listing, listing[:200])

    rcon.run(f"dd boostpad remove {pad}")
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
    log = Log()

    try:
        for drill in [drill_export_counts, drill_confirm_gate, drill_state_roundtrip, drill_drop_is_scoped,
                      drill_obu_export_shape, drill_malformed_state_row, drill_refused_while_degraded,
                      drill_reload_during_outage, drill_reenable_during_outage, drill_journal_bad_line]:
            print(f"\n{drill.__name__.removeprefix('drill_').replace('_', ' ')}")
            drill(rcon, log, mariadb)
    except RuntimeError as error:
        bad(str(error))

    print()
    if failures:
        print(f"{len(failures)} drill step(s) failed")
        return 1
    print("all database drills passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
