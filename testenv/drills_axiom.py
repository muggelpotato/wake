#!/usr/bin/env python3
"""Axiom display drills against a running Wake server.

Covers what `features/axiom` owns wherever a console reaches it: the bundled models an empty store
seeds, the order two exports of the same store have to agree on, the round trip through a drop, the
registrations a cycle must not double, and the keys the picker cannot be given.

    python testenv/drills_axiom.py

The picker itself is the one thing no console can see, so the registrations are watched through
Axiom instead: it refuses an id it already holds, so a pass that registered without unregistering
first, or one that handed it two rows naming one display, costs a SEVERE with an
`AxiomAlreadyRegisteredException` behind it. Silence across a cycle is the proof.

What is left to TESTPLAN.md: what the picker draws -- that the models are in it, in the order they
were registered, and that a drop empties it without a restart.

Needs a server up with RCON (./gradlew runServer) and AxiomPaper installed; skips itself when the
module reports the environment cannot support it. Exits non-zero if a drill fails.
"""

import argparse
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from drills import (ROOT, WAKE, Log, Rcon, bad, detect_backend, failures, ok,  # noqa: E402
                    reload_outcomes, set_module_enabled, step)

CONFIG = WAKE / "config.yml"
EXPORT = WAKE / "exports" / "axiom_data.yml"
BUNDLED = ROOT / "src" / "main" / "resources" / "defaults" / "axiom_default.yml"
COMPLETED = re.compile(r"Database (\w+) completed for module (\w+) \((\d+) records\)")
SKIPPED = re.compile(r"Skipping invalid Axiom model key: (.*)")
LISTED = re.compile(r"(?m)^displays:[ \t]*\n((?:[ \t]*-.*\n?)*)")
# an import file edited by hand: two pairs that each name one model, and three keys Paper will not read
UNUSABLE = ["muggel:banana", "MUGGEL:BANANA", "banana", "minecraft:banana",
            "''", "not a key", "a:b:c", "muggel:boats/oak_racer"]
UNREADABLE = 3
SETTLE = 1.5


def truthy(label, condition, detail=""):
    (ok if condition else bad)(label if condition else f"{label} -- {detail}")


def cycle(rcon: Rcon, enabled):
    """Flips axiom in config.yml and reloads, answering with what the reload said about it."""
    set_module_enabled("axiom", enabled)
    return reload_outcomes(rcon).get("axiom", [])


def database(rcon: Rcon, command, verb, timeout=20):
    """Runs a /wake database command and answers with the record count it reported, or None.

    It reads the log through a window of its own, so a drill watching for a line of its own keeps it.
    """
    window = Log()
    rcon.run(command)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        for seen_verb, seen_module, count in COMPLETED.findall(window.read()):
            if seen_verb == verb and seen_module == "axiom":
                return int(count)
        time.sleep(0.5)
    return None


def listed(text):
    """The model keys a yaml file carries, in the order it carries them."""
    block = LISTED.search(text)
    if not block:
        return []
    return [line.strip().lstrip("-").strip().strip("'\"")
            for line in block.group(1).splitlines() if line.strip()]


def errors(log: Log):
    """Paper prints a SEVERE record as ERROR, so the level and the trace are both worth watching for."""
    return [line for line in log.read().splitlines()
            if "[wake]" in line and ("ERROR" in line or "Exception" in line)]


def export(rcon: Rcon):
    """Exports the module and answers with the file it wrote and the count it reported."""
    count = database(rcon, "wake database export axiom", "export")
    return (EXPORT.read_text(encoding="utf-8") if EXPORT.is_file() else ""), count


def drill_seeds_the_bundled_models(rcon: Rcon, log: Log):
    """A module that comes up on an empty store offers the jar's models, without an admin asking."""
    step("emptying the store and bringing the module back up on it")
    database(rcon, "wake database drop axiom confirm", "drop")
    time.sleep(SETTLE)
    cycle(rcon, False)
    log.reset()
    truthy("it enables again", cycle(rcon, True) == ["enabled"])
    truthy("the console says it seeded the jar's records", log.await_line("Auto-seeded", 10), log.read()[-300:])

    bundled = listed(BUNDLED.read_text(encoding="utf-8"))
    truthy(f"the jar carries {len(bundled)} models to seed", bundled, "the defaults resource lists none")
    text, count = export(rcon)
    truthy(f"the store holds exactly those {len(bundled)}", listed(text) == sorted(bundled), str(listed(text)))
    truthy(f"and the export reported all of them ({count})", count == len(bundled), str(count))
    truthy("with nothing logged against a registration", not errors(log), errors(log)[:1])


def drill_export_is_ordered(rcon: Rcon, log: Log):
    """Two exports of one store have to be the same file.

    The keys come out of a cache that is a hash map, so the order they arrive in is the order that map
    happens to hold them in that run -- an export writing them straight out looks like a backup and
    diffs against its own predecessor. The sort is also what makes the picker's order the module's
    decision rather than the map's.
    """
    first, _ = export(rcon)
    second, _ = export(rcon)
    truthy("the second export is the same file, byte for byte", first == second)
    models = listed(first)
    truthy("and the models in it are sorted", models == sorted(models), str(models))


def drill_round_trip(rcon: Rcon, log: Log):
    """Everything the store holds reaches the file, and everything the file carries reaches the store back."""
    log.reset()
    before, exported = export(rcon)
    truthy("the export wrote a file with something in it", exported, str(exported))

    step("dropping the store and importing the file over it")
    dropped = database(rcon, "wake database drop axiom confirm", "drop")
    time.sleep(SETTLE)
    truthy("the drop ran", dropped is not None, "no completion line")
    emptied, _ = export(rcon)
    truthy("and left the store empty", listed(emptied) == [], str(listed(emptied)))

    # the export above wrote the empty store over the file, which is what an admin restores from
    EXPORT.write_text(before, encoding="utf-8")
    imported = database(rcon, "wake database import axiom confirm", "import")
    time.sleep(SETTLE)
    truthy(f"the import read the same count back in ({exported})", imported == exported, str(imported))
    after, _ = export(rcon)
    truthy("and the store exports identically to before the drop", after == before, str(listed(after)))
    truthy("with nothing logged against a registration", not errors(log), errors(log)[:1])


def drill_cycles_register_once(rcon: Rcon, log: Log):
    """Three off/on cycles and three reloads have to leave Axiom holding one registration per model.

    What this one is about is the disable and the reload that changes nothing: a module coming back up
    registers against an Axiom its own disable emptied, and a reload with the store as it was registers
    nothing at all. Either getting it wrong costs an `AxiomAlreadyRegisteredException` per model rather
    than a silent second entry in the picker. A set that changes under a module that stays up is
    drill_round_trip and drill_unusable_keys_are_skipped -- removing the unregister from the apply
    leaves this drill passing and those two failing.
    """
    log.reset()
    for round_number in range(1, 4):
        step(f"cycle {round_number}")
        truthy("axiom goes down", cycle(rcon, False) == ["disabled"])
        truthy("and comes back up", cycle(rcon, True) == ["enabled"])

    step("three reloads with nothing changed")
    for _ in range(3):
        reload_outcomes(rcon)
    time.sleep(SETTLE)
    truthy("no registration was refused as one Axiom already held", not errors(log), errors(log)[:1])
    models = listed(export(rcon)[0])
    truthy("and the models came back unchanged, none of them doubled",
           models and models == sorted(set(models)), str(models))


def drill_unusable_keys_are_skipped(rcon: Rcon, log: Log):
    """A model key the picker cannot be given costs that model, and nothing else.

    An import file is hand-edited, so it is the one place a key arrives that Paper will not read: a
    space, two colons, or nothing at all. Each has to be named and stepped over. The rows themselves
    still belong in the store: an import that quietly drops what it could not use is a backup that
    loses an admin's typo without telling anyone.

    Two spellings the file can carry are not two models, and the two are settled in different places.
    Case is settled in the store, because MariaDB compares the key column case-insensitively where
    sqlite does not -- the key folds on the way in, so both backends hold one row instead of one
    backend holding two and disagreeing with its own cache. A missing namespace is settled at the
    picker: `banana` and `minecraft:banana` are two honest rows naming one display, and handing Axiom
    both is a refusal this would rather catch here.
    """
    step("importing a file carrying two pairs that each name one model, and three keys Paper cannot read")
    EXPORT.write_text("version: 1\ndisplays:\n" + "".join(f"- {key}\n" for key in UNUSABLE), encoding="utf-8")
    log.reset()
    imported = database(rcon, "wake database import axiom confirm", "import")
    log.await_line("Skipping invalid Axiom model key", 10)
    time.sleep(SETTLE)
    truthy(f"the import took every line the file carried ({len(UNUSABLE)})", imported == len(UNUSABLE), str(imported))

    skipped = SKIPPED.findall(log.read())
    truthy(f"the {UNREADABLE} keys Paper cannot read are named, one line each",
           len(skipped) == UNREADABLE, str(skipped))
    truthy("nothing was refused as a registration Axiom already held", not errors(log), errors(log)[:1])

    models = set(listed(export(rcon)[0]))
    truthy("and the export carries the rows back, the unusable ones included",
           {"not a key", "a:b:c", "muggel:boats/oak_racer"} <= models, str(sorted(models)))
    truthy("with the upper-case spelling folded onto the row already there rather than beside it",
           "MUGGEL:BANANA" not in models and "muggel:banana" in models, str(sorted(models)))


def drill_a_refused_row_fails_the_import(rcon: Rcon, log: Log):
    """A row the database refuses has to fail the import, not be logged behind a line saying it worked.

    `model_key` is `VARCHAR(255)`: sqlite stores an oversized key, MariaDB refuses it, so this is the
    shape where one export imports on one backend and silently loses a row on the other. The import
    writes each row itself rather than queueing it, so the refusal is what the admin is answered with.
    What landed ahead of it stays -- every row announced itself as it went, and the file re-imports.
    """
    if not detect_backend(None):
        step("skipped: sqlite stores an oversized key rather than refusing it")
        return
    step("importing a file whose second row is longer than the column takes")
    kept = "muggel:refused_probe"
    EXPORT.write_text(f"version: 1\ndisplays:\n- {kept}\n- muggel:{'x' * 300}\n", encoding="utf-8")
    log.reset()
    rcon.run("wake database import axiom confirm")
    truthy("the console names the import as failed", log.await_line("Database import failed for module axiom", 20),
           log.read()[-300:])
    truthy("and never reported it completed", "import completed for module axiom" not in log.read(),
           log.read()[-300:])
    truthy("while the row ahead of the refused one is in the store", kept in set(listed(export(rcon)[0])))


def main():
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

    log = Log()
    original = CONFIG.read_text(encoding="utf-8")
    outcome = cycle(rcon, True)
    if outcome not in (["enabled"], ["reloaded"]):
        CONFIG.write_text(original, encoding="utf-8")
        rcon.run("wake reload")
        if outcome == ["incompatible"]:
            print("skipped: AxiomPaper is not installed here, so the module cannot come up")
            return 0
        raise SystemExit(f"axiom would not come up ({outcome or 'no line named it'})")
    try:
        for drill in [drill_seeds_the_bundled_models, drill_export_is_ordered, drill_round_trip,
                      drill_cycles_register_once, drill_unusable_keys_are_skipped,
                      drill_a_refused_row_fails_the_import]:
            print(f"\n{drill.__name__.removeprefix('drill_').replace('_', ' ')}")
            drill(rcon, log)
    except RuntimeError as error:
        bad(str(error))
    finally:
        database(rcon, "wake database drop axiom confirm", "drop")
        database(rcon, "wake database setdefaults axiom confirm", "setdefaults")
        export(rcon)
        CONFIG.write_text(original, encoding="utf-8")
        rcon.run("wake reload")

    print()
    if failures:
        print(f"{len(failures)} drill step(s) failed")
        return 1
    print("all axiom drills passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
