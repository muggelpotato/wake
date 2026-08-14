#!/usr/bin/env python3
"""OBU command-surface drills: who owns an operation, and what a bad argument costs.

`features/obu/commands` is the module's whole command tree. Every branch of it forks on what the
subject is -- a player owns a sandbox under their own id, a console addresses one by the key the
table holds, and an entity is neither. A console can only ever stand on one side of that fork, so
this file drills the console side exhaustively and grafts the other one: a sandbox owned by a player
who has never been here is written straight into the table, and what the commands will and will not
resolve it under is read back off their replies.

The rest is the door every value that did not come from an argument type has to pass: a share code
that is not base64, one that is base64 but not gzip, a truncated one, a bomb, an empty one, and rows
naming a setting the wire cannot carry. Each has to cost that code or that row and nothing else --
above all, never a half-made sandbox, because the name is claimed before the settings are parsed and
a claim that outlives a refusal is a name its owner can no longer use.

`-defaults` is drilled against the definition table itself: the command answers "no default" for
exactly the settings that carry none, which is the same predicate its suggester filters on.

Two rules the tree turns on are pinned here because nothing else names them: a sandbox is the whole
truth while it is active, so a fork copies its source and never the `default` layer under it; and a
subject that owns no sandbox -- a console, a boat behind `/execute as` -- reaches one only by the key
the table holds, never by the display name a player addresses their own by. Beside them, every greedy
name argument is reachable with nothing typed after it, so every one of them is asked what it answers.

What needs a real player -- `-status`, a sandbox being *entered*, `-defaults` inside one, the
suggestions themselves -- is TESTPLAN section 4. A console is never a player and `/execute as` can
only offer an entity, so none of it is reachable from here.

    python testenv/drills_obu_commands.py    # needs a server up (./gradlew runServer)

Runs against sqlite and mariadb alike. Exits non-zero if a check fails.
"""

import argparse
import base64
import gzip
import os
import re
import sqlite3
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from drills import CODES, ROOT, Log, Rcon, WAKE, detect_backend, docker, set_module_enabled  # noqa: E402
from drills_obu import DEFINITIONS, settings_of  # noqa: E402

SETTLE = 1.0
STAND_TAG = "wakeobucmd"
STAND = f'summon minecraft:armor_stand 0 100 0 {{Tags:["{STAND_TAG}"],NoGravity:1b,Invulnerable:1b}}'
AS_STAND = f"execute as @e[tag={STAND_TAG},limit=1] run "
BOAT_TAG = "wakeobucmdboat"
AS_BOAT = f"execute as @e[tag={BOAT_TAG},limit=1] run "
# a player who has never joined: only their own id may address a sandbox keyed to them
GRAFT_OWNER = "3c4d5e6f-7081-4923-8a4b-5c6d7e8f9012"
# a context every install seeds from the jar
SEEDED_CONTEXT = "harbour"
# settings the seeded `default` context carries and SEEDED_CONTEXT does not, so only inheritance can supply them
DEFAULT_ONLY_SETTINGS = ["collisionmode", "falldamage"]
# the two the mod applies to itself rather than to the context carrying them: server state under
# `-settings`, so no context holds one and no node under `/wakeobu` or `-clear` takes one
CLIENT_WIDE = {"setinterpolationten", "setresetonworldload"}
# the internal names, which no sandbox may take and which the name pattern already turns back
INTERNAL = ["wake:empty", "wake:personal"]
BULB = "\U0001F4A1"
# the cap SandboxImportCommand stops adding at
MAX_IMPORT_SETTINGS = 256
# the width wake_obu_settings.unique_key is declared with, which SettingMerge bounds a union by
UNIQUE_KEY_WIDTH = 255
# a value each semantic type accepts, so every setting's own literal is reachable with nothing else wrong
SAMPLE = {"BOOLEAN": "true", "FLOAT": "1.0", "DOUBLE": "1.0", "INT": "1", "BYTE": "1",
          "BLOCK_LIST": "ice", "ENTITY_LIST": "minecraft:pig",
          "SETTING_ENUM": "JUMP_FORCE", "COLLISION_ENUM": "VANILLA"}

failures = []
rcon = None
mariadb = None


def run(command):
    return CODES.sub("", rcon.run(command))


def ok(label):
    print(f"  ok    {label}")


def bad(label, detail=""):
    print(f"  FAIL  {label}: {str(detail).strip()[:220]}")
    failures.append(label)


def traces(log):
    """Wake's own exceptions in whatever the console has printed since the mark."""
    return [line for line in log.read().splitlines() if "wake" in line.lower() and "xception" in line]


def truthy(label, condition, detail=""):
    ok(label) if condition else bad(label, detail)


def says(label, command, needle):
    reply = run(command)
    truthy(label, needle.lower() in reply.lower(), f"{command!r} -> {reply}")
    return reply


def setting_lines(sandbox):
    """What `-sandbox view` says a sandbox holds, sorted -- a read of the table brings no order with it."""
    return sorted(settings_of(run(f"wo -sandbox view {sandbox}")))


def share(*entries):
    """A share code is gzipped, then url-safe base64 without padding. See SandboxCommandHelper"""
    payload = ";".join(entries).encode()
    return base64.urlsafe_b64encode(gzip.compress(payload)).decode().rstrip("=")


def drop(*names):
    for name in names:
        rcon.run(f"wo -sandbox delete {name}")
        rcon.run(f"wo -context -delete {name}")
    for name in names:
        wait_row(name, absent=True)


def wait_row(name, kind=None, absent=False, timeout=20.0):
    """A write is answered before it lands, so a check that reads the table has to wait for it."""
    deadline = time.monotonic() + timeout
    row = context_row(name)
    while time.monotonic() < deadline:
        if (row is None) == absent and (kind is None or (row is not None and row[0] == kind)):
            return row
        time.sleep(0.25)
        row = context_row(name)
    return row


def write_rows_raw(statements):
    """Runs SQL straight at the obu tables, behind the server's back, on whichever backend it is on."""
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


def context_row(name):
    """(type, owner_uuid) for one context, read past the server's cache, or None."""
    if mariadb:
        container, user, password, database = mariadb
        out = docker("exec", container, "mariadb", f"-u{user}", f"-p{password}", database, "-N", "-B",
                     "-e", f"SELECT type, owner_uuid FROM wake_obu_contexts WHERE name = '{name}'").strip()
        return tuple(out.split("\t")) if out else None
    uri = "file:" + (WAKE / "wake.db").resolve().as_posix() + "?mode=ro"
    connection = sqlite3.connect(uri, uri=True, timeout=10)
    try:
        row = connection.execute("SELECT type, owner_uuid FROM wake_obu_contexts WHERE name = ?", (name,)).fetchone()
    finally:
        connection.close()
    return tuple(row) if row else None


def settings_count(name):
    """How many setting rows a context holds, read past the server's cache."""
    return len(setting_keys(name))


def setting_keys(name):
    """Every unique_key a context has stored, read past the server's cache."""
    if mariadb:
        container, user, password, database = mariadb
        out = docker("exec", container, "mariadb", f"-u{user}", f"-p{password}", database, "-N", "-B",
                     "-e", f"SELECT unique_key FROM wake_obu_settings WHERE context_name = '{name}'")
        return sorted(line.strip() for line in out.splitlines() if line.strip())
    uri = "file:" + (WAKE / "wake.db").resolve().as_posix() + "?mode=ro"
    connection = sqlite3.connect(uri, uri=True, timeout=10)
    try:
        return sorted(row[0] for row in connection.execute(
            "SELECT unique_key FROM wake_obu_settings WHERE context_name = ?", (name,)))
    finally:
        connection.close()


def await_count(name, expected, timeout=20.0):
    deadline = time.monotonic() + timeout
    seen = settings_count(name)
    while seen != expected and time.monotonic() < deadline:
        time.sleep(0.25)
        seen = settings_count(name)
    return seen


def reread_store():
    """Cycles the module, which is what makes the server read a row nothing announced to it."""
    set_module_enabled("obu", False)
    try:
        rcon.run("wake reload")
        time.sleep(SETTLE)
    finally:
        set_module_enabled("obu", True)
        rcon.run("wake reload")
        time.sleep(SETTLE * 2)


def drill_console_ownership():
    """A console owns nothing, so the key it claims is the bare name and never another player's."""
    print("\na console's sandbox is keyed by its name alone")
    drop("consolebox")
    says("a console can create one", "wo -sandbox create consolebox", "created")
    row = wait_row("consolebox", kind="SANDBOX")
    truthy("it is stored as an ownerless sandbox under the bare name",
           row is not None and row[0] == "SANDBOX" and row[1] in (None, "", "NULL"), repr(row))
    says("and the console can read it back", "wo -sandbox view consolebox", "consolebox")

    print("\nand a name is canonical before anything looks at it")
    drop("mixedcase")
    says("a mixed-case name is accepted", "wo -sandbox create MixedCase", "created")
    truthy("and stored lowercased", wait_row("mixedcase") is not None, repr(context_row("MixedCase")))
    says("so the same name in another case is the same sandbox", "wo -sandbox create mixedcase", "already exists")
    says("and it is addressable in either case", "wo -sandbox view MIXEDCASE", "mixedcase")
    drop("mixedcase")

    print("\nand a sandbox the console did not make is only addressable by its stored key")
    write_rows_raw([f"DELETE FROM wake_obu_contexts WHERE name = 'grafted@{GRAFT_OWNER}'",
                    f"INSERT INTO wake_obu_contexts (name, type, owner_uuid, last_accessed_at) "
                    f"VALUES ('grafted@{GRAFT_OWNER}', 'SANDBOX', '{GRAFT_OWNER}', 0)"])
    reread_store()
    says("the console reaches it by its full key", f"wo -sandbox view grafted@{GRAFT_OWNER}", "grafted")
    says("but the display name alone resolves nothing", "wo -sandbox view grafted", "does not exist")
    says("nor does export", "wo -sandbox export grafted", "does not exist")
    says("nor delete", "wo -sandbox delete grafted", "does not exist")
    truthy("so the grafted sandbox is still in the table", context_row(f"grafted@{GRAFT_OWNER}") is not None)

    print("\nand it enters none of them, so only the hint that is not about entering one reaches it")
    run("wake hints true")
    drop("consolehint")
    reply = says("creating one answers", "wo -sandbox create consolehint", "created")
    truthy("carrying no sandbox-active bulb", BULB not in reply, reply)
    reply = says("publishing it answers", "wo -sandbox publish consolehint", "published")
    truthy("and that one does carry its bulb", BULB in reply, reply)
    drop("consolehint")


def drill_sandbox_is_not_a_context():
    """The sandbox commands never resolve a server context, and -context -delete never a sandbox."""
    print("\nthe two namespaces never answer for each other")
    for verb in ("view", "export", "delete", "publish"):
        says(f"-sandbox {verb} refuses a server context", f"wo -sandbox {verb} {SEEDED_CONTEXT}", "does not exist")
    truthy("and the server context survived every one of them",
           context_row(SEEDED_CONTEXT) is not None, "the seeded context was deleted by a sandbox command")

    drop("notacontext")
    run("wo -sandbox create notacontext")
    wait_row("notacontext", kind="SANDBOX")
    says("-context -delete refuses a sandbox", "wo -context -delete notacontext", "does not exist")
    truthy("and the sandbox survived it", context_row("notacontext") is not None)
    drop("notacontext")


def drill_reserved_and_collisions():
    print("\nreserved and taken names are refused, each with its own reason")
    says("create default", "wo -sandbox create default", "reserved name")
    says("fork onto default", f"wo -sandbox fork {SEEDED_CONTEXT} default", "reserved name")
    # an internal name carries a colon, so the argument type turns it back before the executor is reached
    for name in INTERNAL:
        says(f"create {name}", f"wo -sandbox create {name}", "invalid sandbox name")
        says(f"fork onto {name}", f"wo -sandbox fork {SEEDED_CONTEXT} {name}", "invalid sandbox name")
    truthy("and default is still a context afterwards", context_row("default") is not None)

    print("\nand a server context owns its name against a console sandbox")
    says("create a sandbox named after a seeded context", f"wo -sandbox create {SEEDED_CONTEXT}", "already exists")
    truthy("the context was not replaced by a sandbox",
           (context_row(SEEDED_CONTEXT) or ("", ""))[0] == "SERVER", repr(context_row(SEEDED_CONTEXT)))

    print("\nand a publish that would collide is refused rather than overwriting")
    write_rows_raw([f"DELETE FROM wake_obu_contexts WHERE name = '{SEEDED_CONTEXT}@{GRAFT_OWNER}'",
                    f"INSERT INTO wake_obu_contexts (name, type, owner_uuid, last_accessed_at) "
                    f"VALUES ('{SEEDED_CONTEXT}@{GRAFT_OWNER}', 'SANDBOX', '{GRAFT_OWNER}', 0)"])
    reread_store()
    says("publishing onto a taken name", f"wo -sandbox publish {SEEDED_CONTEXT}@{GRAFT_OWNER}", "already exists")
    truthy("the server context is untouched",
           (context_row(SEEDED_CONTEXT) or ("", ""))[0] == "SERVER", repr(context_row(SEEDED_CONTEXT)))
    truthy("and the sandbox is still a sandbox",
           (context_row(f"{SEEDED_CONTEXT}@{GRAFT_OWNER}") or ("", ""))[0] == "SANDBOX")
    write_rows_raw([f"DELETE FROM wake_obu_contexts WHERE name = '{SEEDED_CONTEXT}@{GRAFT_OWNER}'"])
    reread_store()


def drill_publish():
    print("\na published sandbox stops being one")
    drop("publishme")
    run("wo -sandbox create publishme")
    run(f'wo -sandbox import "{share("1:0.6")}" publishme2')
    says("publish answers", "wo -sandbox publish publishme", "published")
    truthy("and the row is a server context now",
           (wait_row("publishme", kind="SERVER") or ("", ""))[0] == "SERVER", repr(context_row("publishme")))
    says("it shows up in the context listing", "wo -context", "publishme")
    says("and publishing it again finds no sandbox", "wo -sandbox publish publishme", "does not exist")

    says("a sandbox's settings ride along", "wo -sandbox publish publishme2", "published")
    says("and are readable off the published context", "wo -context", "publishme2")
    drop("publishme", "publishme2")


def drill_fork():
    print("\nfork resolves its source before it claims a name")
    drop("forked", "forkedtwice")
    says("forking a context that does not exist", "wo -sandbox fork nosuchsource forked", "does not exist")
    truthy("leaves no half-made sandbox behind", wait_row("forked", absent=True) is None, repr(context_row("forked")))

    says("forking a real context", f"wo -sandbox fork {SEEDED_CONTEXT} forked", "forked")
    wait_row("forked", kind="SANDBOX")
    settings = run("wo -sandbox view forked")
    truthy("and the copy carries the source's settings", "→" in settings or "●" in settings, settings)
    says("forking onto a name already taken", f"wo -sandbox fork {SEEDED_CONTEXT} forked", "already exists")
    truthy("and the source context is untouched",
           (context_row(SEEDED_CONTEXT) or ("", ""))[0] == "SERVER", repr(context_row(SEEDED_CONTEXT)))

    says("a sandbox can be forked too", "wo -sandbox fork forked forkedtwice", "forked")
    truthy("and lands as a sandbox of its own",
           (wait_row("forkedtwice", kind="SANDBOX") or ("", ""))[0] == "SANDBOX", repr(context_row("forkedtwice")))
    drop("forked", "forkedtwice")

    # a sandbox is the whole truth while it is active, so a fork of a context has to be the context alone:
    # copying what `default` lends the source would hand the sandbox settings its owner never asked for
    print("\nand what a fork copies is the source itself, never the default layer under it")
    drop("slatefork")
    says("forking a context that inherits the default", f"wo -sandbox fork {SEEDED_CONTEXT} slatefork", "forked")
    wait_row("slatefork", kind="SANDBOX")
    view = run("wo -sandbox view slatefork").lower()
    truthy("the source's own settings came across", "defaultslipperiness" in view, view)
    for lent in DEFAULT_ONLY_SETTINGS:
        truthy(f"and {lent}, which only the default context sets, did not", lent not in view, view)
    source_rows = settings_count(SEEDED_CONTEXT)
    held = await_count("slatefork", source_rows)
    truthy(f"so the fork holds exactly what the source holds ({source_rows})", held == source_rows, f"holds {held}")
    drop("slatefork")


def drill_name_argument():
    print("\na name that could never key a context is refused at the argument, not in the executor")
    for label, name in (("a dot", "has.dot"), ("a space", '"has space"'), ("an at sign", "has@sign"),
                        ("a colon", "has:colon"), ("33 characters", "x" * 33)):
        reply = run(f"wo -sandbox create {name}")
        truthy(f"create refuses {label}", "invalid sandbox name" in reply.lower() or "<--[HERE]" in reply, reply)
    says("but 32 characters is still fine", f"wo -sandbox create {'y' * 32}", "created")
    drop("y" * 32)


def drill_share_code_door():
    """Everything the decode door has to refuse, and what a refusal is allowed to cost."""
    print("\nthe share-code door refuses what it cannot read, and claims nothing when it does")
    good = share("1:0.6", "19:3", "27:VANILLA")
    # the last base64 character of an unpadded code carries bits the decoder throws away, so a flip
    # that has to be felt goes into the deflate stream itself
    middle = len(good) // 2
    flipped = good[:middle] + ("A" if good[middle] != "A" else "B") + good[middle + 1:]
    cases = [
        ("not base64 at all", "!!!not base64!!!"),
        ("base64 that is not gzip", base64.urlsafe_b64encode(b"plain text").decode().rstrip("=")),
        ("a truncated code", good[:-4]),
        ("a code with a flipped character", flipped),
        ("an empty string", ""),
    ]
    for label, code in cases:
        name = "doortest"
        drop(name)
        log = Log()
        reply = run(f'wo -sandbox import "{code}" {name}')
        truthy(f"{label} is refused", "failed to import" in reply.lower(), reply)
        truthy(f"and {label} claimed no name", wait_row(name, absent=True) is None, repr(context_row(name)))
        truthy(f"and the console was told about {label}",
               log.await_line("Failed to decode share code", 10), log.read()[-200:])

    print("\nand a gzip bomb never finishes decompressing into memory")
    # 120KB of payload behind a ~290-character code: past the 64KB cap, and small enough for one rcon packet
    bomb = base64.urlsafe_b64encode(gzip.compress(b"1:0.6;" * 20_000)).decode().rstrip("=")
    drop("bombtest")
    reply = run(f'wo -sandbox import "{bomb}" bombtest')
    truthy("a payload past the 64KB cap is refused", "failed to import" in reply.lower(), reply)
    truthy("and claimed no name", wait_row("bombtest", absent=True) is None, repr(context_row("bombtest")))

    print("\nand an empty payload is a legal share code, not a failure")
    drop("emptytest")
    reply = says("an empty payload imports", f'wo -sandbox import "{share()}" emptytest', "imported")
    truthy("with nothing reported skipped", "skip" not in reply.lower(), reply)
    says("and the sandbox is really empty", "wo -sandbox view emptytest", "no settings configured")
    drop("emptytest")


def drill_import_rows():
    print("\nand one row the wire cannot carry costs that row and no other")
    drop("rowtest")
    # 42 is applyimpulse: an action the client fires once, so it must never be stored
    code = share("1:0.6", "999:1.0", "notanumber:1.0", "42:1 2 3", "19:3")
    reply = says("a code of good and bad rows imports", f'wo -sandbox import "{code}" rowtest', "imported")
    truthy("and says how many rows it stepped over", "skipped 3" in reply.lower(), reply)
    view = run("wo -sandbox view rowtest")
    truthy("the two sound rows landed", "stepsize" in view.lower() and "coyotetime" in view.lower(), view)
    truthy("and the action setting was never stored", "applyimpulse" not in view.lower(), view)
    drop("rowtest")

    print("\nand the cap is reported, never silently applied")
    drop("captest")
    # a value and a block of its own per entry, so no two of them fold together and the cap is a count
    # the table can be asked for; one value across them all would land as a single folded entry. The
    # names are as short as they are because RCON caps the packet the code rides in at ~1.4kB
    code = share(*[f"3:{index:03d} x:{index:03d}" for index in range(MAX_IMPORT_SETTINGS + 20)])
    reply = says("a code past the cap imports", f'wo -sandbox import "{code}" captest', "imported")
    truthy("and reports the excess as skipped", "skipped 20" in reply.lower(), reply)
    held = await_count("captest", MAX_IMPORT_SETTINGS)
    truthy(f"and the sandbox holds exactly the cap ({MAX_IMPORT_SETTINGS})", held == MAX_IMPORT_SETTINGS, f"holds {held}")
    drop("captest")

    print("\nand a bad code is answered before a name that is already taken")
    drop("ordertest")
    run("wo -sandbox create ordertest")
    run(f'wo -sandbox import "{share("1:0.6")}" ordertest')  # warm it so a wrong order would say "exists"
    reply = run('wo -sandbox import "not-a-code" ordertest')
    truthy("the code is reported, not the collision", "failed to import" in reply.lower(), reply)
    says("and the sandbox that was already there is untouched", "wo -sandbox view ordertest", "no settings configured")
    drop("ordertest")


def drill_folded_rows():
    """Folding decides rows, not only list entries, so the table has to end up saying what the cache does.

    A fold that reached only the cache reads right until something reads the table again, and then the
    invocations are back as rows -- one block under two values, which is the state folding exists to
    prevent. Only a store that has re-read can tell the two apart, so the listing is taken twice with a
    module cycle between them. A sandbox is claimed empty, so the row a fold *renames* needs a player
    standing in one: that half is TESTPLAN section 4."""
    print("\nwhat folding leaves in the table is what the cache says it holds")
    drop("foldrows")
    # 0.9 keeps packed_ice, 0.4 takes ice off it, the two filters join, and stepsize folds with nothing
    code = share("3:0.9 ice", "3:0.9 packed_ice", "3:0.4 ice", "31:pig", "31:cow", "1:0.6")
    says("a code whose settings fold imports", f'wo -sandbox import "{code}" foldrows', "imported")
    held = await_count("foldrows", 4)
    truthy("the table holds a row per folded setting, not one per invocation", held == 4, f"holds {held}")
    cached = setting_lines("foldrows")
    truthy("and the cache lists the same four", len(cached) == 4, repr(cached))
    reread_store()
    truthy("a store that has read the table back lists exactly what the cache did",
           setting_lines("foldrows") == cached, f"{setting_lines('foldrows')} vs {cached}")
    drop("foldrows")

    print("\nand a union stops at the width the key column is declared with")
    drop("foldwide")
    # nine blocks at one value: their sorted keys joined pass the column well before the ninth
    code = share(*[f"3:0.9 othermod:{'a' * 30}{index}" for index in range(9)])
    says("a code whose blocks would union past the column imports",
         f'wo -sandbox import "{code}" foldwide', "imported")
    rows = await_count("foldwide", 2)
    truthy(f"the union started a second row rather than overrunning ({rows} rows)", rows > 1, f"holds {rows}")
    widest = max((len(key) for key in setting_keys("foldwide")), default=0)
    truthy(f"and no key it wrote is wider than the column ({widest} of {UNIQUE_KEY_WIDTH})",
           0 < widest <= UNIQUE_KEY_WIDTH, f"the widest key is {widest}")
    drop("foldwide")

    print("\nand a list too long for one key arrives as buckets, never as a row the column refuses")
    drop("foldspill")
    blocks = ",".join(f"othermod:block{index:02d}" for index in range(25))
    says("a twenty-five block list imports in one setting",
         f'wo -sandbox import "{share(f"3:0.9 {blocks}")}" foldspill', "imported")
    await_count("foldspill", 2)
    keys = setting_keys("foldspill")
    truthy(f"it landed as more than one setting ({len(keys)} buckets)", len(keys) > 1, repr(keys))
    truthy("with every key inside the column",
           all(len(key) <= UNIQUE_KEY_WIDTH for key in keys), [len(key) for key in keys])
    carried = sum(key.count(",") + 1 for key in keys)
    truthy(f"and no block lost on the way into them ({carried} of 25)", carried == 25, repr(keys))
    drop("foldspill")

    print("\nand a block one bucket already holds is never added to a second")
    spilled = [f"othermod:block{index:02d}" for index in range(25)]
    twice = (f"3:0.9 {','.join(spilled)}", f"3:0.9 {spilled[0]}")
    # then every *other* block to a second value: two buckets that both kept the first one are left
    # spelling one unique_key, and one key is one row -- the cache would claim a setting the table lost
    for name, code, rows in (("folddup", twice, 2),
                             ("foldcollide", twice + (f"3:0.4 {','.join(spilled[1:])}",), 3)):
        drop(name)
        says(f"a code that re-enters its own buckets imports ({name})",
             f'wo -sandbox import "{share(*code)}" {name}', "imported")
        await_count(name, rows)
        keys = setting_keys(name)
        carried = sorted(entry for key in keys for entry in key.split(":", 1)[1].split(","))
        truthy(f"every block sits in exactly one setting ({len(carried)} of 25)", carried == spilled, repr(keys))
        truthy("and no two of them spell one row",
               len(keys) == len(setting_lines(name)), f"{len(keys)} rows vs {setting_lines(name)}")
        drop(name)

    print("\nand an entry no key could hold even alone is dropped rather than written")
    drop("foldhuge")
    # one statement the database turns back fails the transaction its whole write shares
    code = share("3:0.9 othermod:" + "a" * 260, "1:0.6")
    says("a code carrying one imports", f'wo -sandbox import "{code}" foldhuge', "imported")
    await_count("foldhuge", 1)
    keys = setting_keys("foldhuge")
    truthy("the entry no key holds never reached the table", keys == ["1"], repr(keys))
    drop("foldhuge")

    # what the key is spelled with is what the width is spent on, and a foreign key is the one thing
    # that cannot be shortened: only the server it came from can resolve it back to a block
    print("\nand the key spells the default namespace bare and every other one whole")
    drop("foldnames")
    code = share("3:0.9 minecraft:ice, othermod:turbo_ice, packed_ice")
    says("a mixed-namespace list imports", f'wo -sandbox import "{code}" foldnames', "imported")
    await_count("foldnames", 1)
    keys = setting_keys("foldnames")
    truthy("one key, the vanilla blocks bare in it and the foreign one untouched",
           keys == ["3:ice,othermod:turbo_ice,packed_ice"], repr(keys))
    drop("foldnames")


def drill_export():
    print("\nexport answers for every shape a sandbox can be in")
    drop("exportempty", "exportfull")
    run("wo -sandbox create exportempty")
    reply = says("an empty sandbox exports", "wo -sandbox export exportempty", "exported sandbox")
    truthy("and still offers the copy button", "share code" in reply.lower(), reply)

    # a list argument, an enum and a scalar: every branch displayValue has to walk on the way out
    run(f'wo -sandbox import "{share("1:0.6", "27:NO_ENTITIES", "3:0.9 minecraft:ice")}" exportfull')
    reply = says("a sandbox holding every shape exports", "wo -sandbox export exportfull", "exported sandbox")
    truthy("with no error line beside it", "failed" not in reply.lower(), reply)
    says("and exporting a sandbox that is not there is a plain refusal",
         "wo -sandbox export nosuchsandbox", "does not exist")
    drop("exportempty", "exportfull")


def drill_clear_targets():
    """-clear resolves the aimed boat, so its answers are about the boat and never about the sender."""
    print("\n-clear answers about the target it resolved")
    run("forceload add 0 0")
    run(f"kill @e[tag={BOAT_TAG}]")
    run(f"kill @e[tag={STAND_TAG}]")
    run(f'summon minecraft:oak_boat 0 100 0 {{Tags:["{BOAT_TAG}"],NoGravity:1b}}')
    run(STAND)
    try:
        says("a setting aimed at a boat lands on it", AS_BOAT + "wo stepsize 1.5", "set stepsize")
        says("and -clear takes it back off", AS_BOAT + "wo -clear stepsize", "cleared stepsize")
        says("a second -clear reports nothing left", AS_BOAT + "wo -clear stepsize", "is not active")
        says("a name no setting carries is answered as unknown", AS_BOAT + "wo -clear nonsense", "unknown obu setting")
        says("an entity that is neither carries nothing to clear", AS_STAND + "wo -clear stepsize", "is not active")
        says("and the console is not an entity at all", "wo -clear stepsize", "must be executed by an entity")

        # a setting with a list argument holds several rows under one name, so the stored key is the only way
        # to name one of them; a name the definition table does not know falls through to that spelling
        print("\nand a stored key names one row where a setting name names them all")
        says("the setting is set again", AS_BOAT + "wo stepsize 1.5", "set stepsize")
        says("its stored key clears it", AS_BOAT + "wo -clear 1", "cleared stepsize")
        says("and the same key over nothing is unknown, not an error", AS_BOAT + "wo -clear 1", "unknown obu setting")
    finally:
        run(f"kill @e[tag={BOAT_TAG}]")
        run(f"kill @e[tag={STAND_TAG}]")
        run("forceload remove 0 0")


def drill_context_targets():
    """-context resolves an entity, and a subject that owns no sandbox reaches one only by its stored key."""
    print("\n-context applies to the entity behind it and to nothing else")
    run("forceload add 0 0")
    run(f"kill @e[tag={BOAT_TAG}]")
    run(f"kill @e[tag={STAND_TAG}]")
    run(f'summon minecraft:oak_boat 0 100 0 {{Tags:["{BOAT_TAG}"],NoGravity:1b}}')
    run(STAND)
    try:
        says("the console is not an entity", f"wo -context {SEEDED_CONTEXT}", "must be executed by an entity")
        says("an armour stand is one, but not one a context fits",
             AS_STAND + f"wo -context {SEEDED_CONTEXT}", "can only be applied to players or boats")
        says("a boat takes the pin", AS_BOAT + f"wo -context {SEEDED_CONTEXT}", "applied context")

        # only a player owns a sandbox, so only a player may name one by its display name; everyone else
        # addresses the row as the table holds it, which is the same rule -sandbox view answers by
        print("\nand the key a sandbox is stored under is the only name a boat can reach it by")
        says("the full key resolves", AS_BOAT + f"wo -context grafted@{GRAFT_OWNER}", "applied context")
        says("the display name alone resolves nothing", AS_BOAT + "wo -context grafted", "does not exist")
        says("and the boat can be unpinned again", AS_BOAT + "wo -context default", "applied context")
    finally:
        run(f"kill @e[tag={BOAT_TAG}]")
        run(f"kill @e[tag={STAND_TAG}]")
        run("forceload remove 0 0")


def drill_empty_names():
    """Every greedy name argument is reachable with nothing typed after it, so every one of them has an answer."""
    print("\na name argument with nothing behind it is answered, never thrown")
    commands = ["wo -sandbox view", "wo -sandbox export", "wo -sandbox delete", "wo -sandbox publish",
                "wo -sandbox create", "wo -sandbox switch", "wo -sandbox fork", "wo -sandbox import",
                "wo -context", "wo -context -delete", "wo -clear", "wo -defaults"]
    log = Log()
    for command in commands:
        reply = run(command)
        truthy(f"{command!r} answers", "internal error" not in reply.lower() and reply.strip() != "", repr(reply))
    truthy("and none of them reached the console", not traces(log), str(traces(log)[:2]))


def drill_defaults_table():
    """The command answers 'no default' for exactly the settings that carry one a context could be
    holding instead -- the predicate its suggester filters on, so a name it offers can never answer with
    a refusal. The two the mod applies to itself carry a default the table records and no context can
    hold, so this surface has nothing to compare it against and refuses them with the rest."""
    print("\n-defaults answers for exactly the settings that have a vanilla default")
    missing, offered = [], []
    for _, name, _, default in DEFINITIONS:
        command_name = "-reset" if name == "reset" else name
        reply = run(f"wo -defaults {command_name}").lower()
        refused = "no default exists" in reply
        answerable = default is not None and name not in CLIENT_WIDE
        if refused == answerable:
            (offered if answerable else missing).append(name)
    truthy(f"all {len(DEFINITIONS)} settings agree with the definition table",
           not missing and not offered,
           f"answered a value for {missing}, refused {offered}")
    says("and a name no setting carries is refused too", "wo -defaults nonsense", "no default exists")

    # the [Clear current] button carries a command as text in the language file, so nothing links it to the
    # tree it addresses -- a renamed root, literal or argument shape breaks it and only a click would show
    print("\nand the button that line offers still addresses a command that exists")
    lang = (ROOT / "src" / "main" / "resources" / "lang" / "en_us.yml").read_text(encoding="utf-8")
    # the command runs to the tag that follows it, not to the first '>': its own placeholder carries one
    button = re.search(r"clear_btn:.*?<click:run_command:(.+?)><", lang)
    if button is None:
        bad("clear button", "no run_command found under commands.obu.defaults.clear_btn")
        return
    # every setting -defaults offers a button for carries a vanilla default, and none of those is a one-shot
    typed = button.group(1).replace("<setting>", "stepsize").strip()
    if "<" in typed:
        bad("clear button", f"the drill does not fill every placeholder in {typed!r}")
        return
    reply = run(typed).lower()
    truthy(f"{typed!r} resolves", "unknown or incomplete" not in reply and "incorrect argument" not in reply, reply)


def drill_setting_nodes():
    """The tree is swept out of the definition enum, so what needs pinning is that the sweep reaches
    every row: a setting the table knows and the tree does not is a command nobody can type."""
    print("\nand every setting in the definition table is a command of its own")
    missing, offered = [], []
    for _, name, types, _ in DEFINITIONS:
        args = " ".join(SAMPLE[semantic] for semantic in types.split(",") if semantic)
        command = f"wo {'-reset' if name == 'reset' else name} {args}".strip()
        # a console is never an entity, so the target refusal is as far as a node that exists can get
        answered = "must be executed by an entity" in run(command).lower()
        if name in CLIENT_WIDE:
            if answered:
                offered.append(name)
        elif not answered:
            missing.append(name)
    truthy(f"all {len(DEFINITIONS) - len(CLIENT_WIDE)} of them resolve under their own literal", not missing,
           f"no node answered for {missing}")
    truthy(f"and neither of the {len(CLIENT_WIDE)} the mod applies to itself has one", not offered,
           f"a node answered for {offered}")

    # -clear sweeps the same enum, minus the rows no context can hold: a node for one of those would be a
    # command that can only ever answer "nothing to clear", and a permission an admin could grant for it
    print("\nand -clear mirrors that sweep for exactly the settings a context can hold")
    CONTEXTLESS = {"reset": "-reset", "applyimpulse": "applyimpulse", "applyimpulserelative": "applyimpulserelative",
                   "removeblockslipperiness": "removeblockslipperiness", "clearslipperiness": "clearslipperiness",
                   "clearcollisionfilter": "clearcollisionfilter",
                   "setinterpolationten": "setinterpolationten", "setresetonworldload": "setresetonworldload"}
    unmirrored, mirrored, unnamed = [], [], []
    run("forceload add 0 0")
    run(f"kill @e[tag={STAND_TAG}]")
    run(STAND)
    time.sleep(SETTLE)  # the first row of the table is a one-shot, so the stand is selected immediately
    try:
        for _, name, types, _ in DEFINITIONS:
            if name in CONTEXTLESS:
                # no literal of its own, so the greedy key argument swallows the whole tail and resolves
                # nothing. Driven from the stand: a console fails the entity target before any of this
                if "unknown obu setting" not in run(f"{AS_STAND}wo -clear {CONTEXTLESS[name]} 1.0").lower():
                    mirrored.append(name)
                # the bare name is still a setting though, so it reads as absent rather than as unknown
                if "is not active" not in run(f"{AS_STAND}wo -clear {CONTEXTLESS[name]}").lower():
                    unnamed.append(name)
                continue
            identity = [semantic for semantic in types.split(",")
                        if semantic in ("SETTING_ENUM", "BLOCK_LIST", "ENTITY_LIST")]
            args = " ".join(SAMPLE[semantic] for semantic in identity)
            # from the stand, so a literal that is missing reads apart from one that is there: the greedy
            # key argument would swallow the same words and answer "unknown", never "nothing to clear"
            if "is not active" not in run(f"{AS_STAND}wo -clear {name} {args}".strip()).lower():
                unmirrored.append(name)
    finally:
        run(f"kill @e[tag={STAND_TAG}]")
        run("forceload remove 0 0")
    truthy(f"every setting a context holds has a -clear node taking its identity arguments ({len(DEFINITIONS) - len(CONTEXTLESS)})",
           not unmirrored, f"no -clear node answered for {unmirrored}")
    truthy(f"and none of the {len(CONTEXTLESS)} a context never holds has a node of its own",
           not mirrored, f"a node answered for {mirrored}")
    truthy("while each of them named alone still reads as absent rather than as unknown",
           not unnamed, f"answered as unknown for {unnamed}")


def drill_player_only():
    print("\nthe branches that need a player refuse a console and an entity alike")
    run("forceload add 0 0")
    run(f"kill @e[tag={STAND_TAG}]")
    run(STAND)
    try:
        for label, command in (("-status", "wo -status"),
                               ("-sandbox switch", "wo -sandbox switch consolebox"),
                               ("-sandbox exit", "wo -sandbox exit")):
            says(f"{label} refuses the console", command, "only be executed by players")
            says(f"{label} refuses an armour stand too", AS_STAND + command, "only be executed by players")
        # a removal verb takes the targets a set does, so one that owns no layer is turned back rather
        # than answered as empty -- the opposite of `-clear`, which reads such a target as holding nothing
        says("a removal verb refuses an entity that owns no layer",
             AS_STAND + "wo removeblockslipperiness ice", "applied to players or boats")
    finally:
        run(f"kill @e[tag={STAND_TAG}]")
        run("forceload remove 0 0")
    drop("consolebox")


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
    print(f"backend: {'mariadb' if mariadb else 'sqlite'}  ({ROOT.name})")

    try:
        drill_console_ownership()
        drill_sandbox_is_not_a_context()
        drill_reserved_and_collisions()
        drill_publish()
        drill_fork()
        drill_name_argument()
        drill_share_code_door()
        drill_import_rows()
        drill_folded_rows()
        drill_export()
        drill_clear_targets()
        drill_context_targets()
        drill_empty_names()
        drill_defaults_table()
        drill_setting_nodes()
        drill_player_only()
    except RuntimeError as error:
        bad("drill run", str(error))
    finally:
        write_rows_raw([f"DELETE FROM wake_obu_contexts WHERE name = 'grafted@{GRAFT_OWNER}'"])
        reread_store()

    print()
    if failures:
        print(f"{len(failures)} check(s) failed: {', '.join(failures)}")
        return 1
    print("all OBU command drills passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
