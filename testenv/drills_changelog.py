#!/usr/bin/env python3
"""Cross-server drills for the mirrored-cache data layer, against the real testenv proxy network.

Every case is driven on one backend and observed on the other. What is observed is the other
server's CACHE, not the database: `wake database export` writes out what a module's store is
holding, so a change that reached the database but never reached the cache still fails here.

    python testenv/drills_changelog.py      # needs the mariadb stack up (./gradlew runServer)
"""

import argparse
import os
import re
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from drills import CODES, Rcon, bad, failures, ok, step  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
PRIMARY_EXPORTS = ROOT / "run" / "plugins" / "wake" / "exports"
DOCKER = os.environ.get("DOCKER", "docker")
PAPER2 = "wake-testenv-paper2-1"
MARIADB = "wake-testenv-mariadb-1"
SETTLE = 3.0


def docker(*args, timeout=120):
    result = subprocess.run([DOCKER, *args], capture_output=True, text=True,
                            encoding="utf-8", errors="replace", timeout=timeout)
    if result.returncode != 0:
        raise RuntimeError(f"docker {' '.join(args)}: {(result.stderr or result.stdout).strip()}")
    return result.stdout


def on_backend2(command):
    return CODES.sub("", docker("exec", PAPER2, "rcon-cli", command))


def sql(query):
    out = docker("exec", MARIADB, "mariadb", "-uroot", "-ppassword", "wake", "-N", "-B", "-e", query)
    return [line.split("\t") for line in out.strip().splitlines() if line]


def settle(rounds=1):
    time.sleep(SETTLE * rounds)


def check(label, got, want):
    (ok if got == want else bad)(label if got == want else f"{label} (got {got!r}, want {want!r})")


def truthy(label, condition, detail=""):
    (ok if condition else bad)(label if condition else f"{label} -- {detail}")


def primary_cache(primary: Rcon, module):
    """What the primary's store holds, via an export of the cache."""
    primary.run(f"wake database export {module}")
    time.sleep(1.0)
    path = PRIMARY_EXPORTS / f"{module}_data.yml"
    return path.read_text(encoding="utf-8", errors="replace") if path.is_file() else ""


def backend2_cache(module):
    on_backend2(f"wake database export {module}")
    time.sleep(1.0)
    try:
        return docker("exec", PAPER2, "cat", f"/data/plugins/wake/exports/{module}_data.yml")
    except RuntimeError:
        return ""


def both_caches(primary: Rcon, module):
    return primary_cache(primary, module), backend2_cache(module)


def pad_state(text):
    """The enabled/disabled switch out of a `dd boostpad list` reply."""
    found = re.search(r"Status:\s*(\w+)", CODES.sub("", text))
    return found.group(1).lower() if found else None


def drill_no_change_table():
    step("no change-log table exists any more: the keys ride on the sync bus")
    tables = {row[0] for row in sql("SHOW TABLES")}
    truthy("wake_changes is gone", "wake_changes" not in tables, str(sorted(tables)))
    truthy("the feature tables are untouched",
           {"wake_state", "wake_drydock_boostpads", "wake_obu_contexts"} <= tables, str(sorted(tables)))
    cols = {row[0] for row in sql("SHOW COLUMNS FROM wake_drydock_boostpads")}
    truthy("no bookkeeping column was added to a feature table", "updated_at" not in cols, str(cols))


def drill_boostpad_roundtrip(primary: Rcon):
    step("a boostpad written on the primary reaches the other backend's cache")
    primary.run("dd boostpad remove minecraft:blue_ice")
    settle()
    primary.run("dd boostpad add minecraft:blue_ice 0.5 0.0 0.0 500")
    settle()
    mine, theirs = both_caches(primary, "drydock")
    truthy("the primary cached it", "blue_ice" in mine, mine[:200])
    truthy("backend2 cached it too", "blue_ice" in theirs, theirs[:200])

    step("editing it again on the primary updates the other backend")
    primary.run("dd boostpad add minecraft:blue_ice 0.9 0.0 0.0 500")
    settle()
    theirs = backend2_cache("drydock")
    truthy("backend2 has the new force", "0.9" in theirs, theirs[:300])

    step("removing it on the primary removes it there as well")
    primary.run("dd boostpad remove minecraft:blue_ice")
    settle()
    mine, theirs = both_caches(primary, "drydock")
    truthy("the primary dropped it", "blue_ice" not in mine, mine[:200])
    truthy("backend2 dropped it -- a key with no row IS the deletion", "blue_ice" not in theirs, theirs[:200])


def drill_reverse_direction(primary: Rcon):
    step("the same in the other direction, so neither server is special")
    on_backend2("dd boostpad add minecraft:packed_ice 0.3 0.0 0.0 500")
    settle()
    mine = primary_cache(primary, "drydock")
    truthy("the primary sees a pad written by backend2", "packed_ice" in mine, mine[:200])
    on_backend2("dd boostpad remove minecraft:packed_ice")
    settle()
    mine = primary_cache(primary, "drydock")
    truthy("and sees it removed", "packed_ice" not in mine, mine[:200])


def drill_delete_then_recreate(primary: Rcon):
    step("delete and recreate the same key before the other backend ever looks")
    primary.run("dd boostpad add minecraft:magma_block 0.2 0.0 0.0 500")
    settle()
    truthy("backend2 has the first one", "magma_block" in backend2_cache("drydock"), "")
    # both edits inside one catch-up window: backend2 must land on the live row, not the deleted one
    primary.run("dd boostpad remove minecraft:magma_block")
    primary.run("dd boostpad add minecraft:magma_block 0.7 0.0 0.0 500")
    settle()
    theirs = backend2_cache("drydock")
    truthy("backend2 lands on the live version, not the deletion", "magma_block" in theirs, theirs[:300])
    truthy("with the recreated value", "0.7" in theirs, theirs[:300])

    step("and the reverse order inside one window: recreate then delete")
    primary.run("dd boostpad add minecraft:soul_sand 0.2 0.0 0.0 500")
    primary.run("dd boostpad remove minecraft:soul_sand")
    settle()
    theirs = backend2_cache("drydock")
    truthy("backend2 correctly holds nothing", "soul_sand" not in theirs, theirs[:300])


def drill_state_scope(primary: Rcon):
    step("state (a different scope, different store) propagates too")
    before = pad_state(on_backend2("dd boostpad list"))
    primary.run("dd boostpad toggle")
    settle()
    after = pad_state(on_backend2("dd boostpad list"))
    truthy("backend2 observed the state flip", after is not None and after != before,
           f"{before!r} -> {after!r}")
    primary.run("dd boostpad toggle")
    settle()
    restored = pad_state(on_backend2("dd boostpad list"))
    truthy("and the flip back", restored == before, f"{restored!r} vs {before!r}")


def backend2_verbose(on):
    """Turns backend2's per-module sync logging on or off, so a reload can be observed at all."""
    want, other = ("true", "false") if on else ("false", "true")
    docker("exec", PAPER2, "sh", "-c",
           f"sed -i 's/verbose_logging: {other}/verbose_logging: {want}/' /data/plugins/wake/config.yml")
    on_backend2("wake reload")
    time.sleep(1.5)


def synced_modules(since):
    lines = docker("logs", PAPER2, timeout=180).splitlines()[since:]
    return {name for line in lines for name in re.findall(r"Synced module '(\w+)'", line)}


def drill_scoped_reload(primary: Rcon):
    step("a change reloads only the modules it can reach, never every module")
    backend2_verbose(True)
    try:
        mark = backend2_log_lines()
        primary.run("dd boostpad toggle")
        settle()
        seen = synced_modules(mark)
        truthy("a drydock state key reloads drydock", "drydock" in seen, str(sorted(seen)))
        truthy("and leaves obu alone -- resyncing its players would be the whole cost",
               "obu" not in seen, str(sorted(seen)))
        primary.run("dd boostpad toggle")
        settle()

        mark = backend2_log_lines()
        primary.run("dd boostpad add minecraft:diorite 0.3 0.0 0.0 300")
        settle()
        seen = synced_modules(mark)
        truthy("a boostpad row reloads drydock", "drydock" in seen, str(sorted(seen)))
        truthy("and still not obu", "obu" not in seen, str(sorted(seen)))
        primary.run("dd boostpad remove minecraft:diorite")
        settle()

        mark = backend2_log_lines()
        obu_import(primary, {"scopedctx": {"setscale": "1.5"}})
        settle(2)
        seen = synced_modules(mark)
        truthy("an obu context does reload obu", "obu" in seen, str(sorted(seen)))
        primary.run("wake database drop obu confirm")
        settle(2)
    finally:
        backend2_verbose(False)


def drill_own_writes(primary: Rcon):
    step("a server holds what it wrote without reading anything back")
    primary.run("dd boostpad add minecraft:ice 0.1 0.0 0.0 500")
    settle()
    mine = primary_cache(primary, "drydock")
    truthy("the primary holds its own write", "minecraft:ice" in mine, mine[:200])
    truthy("and so does backend2", "minecraft:ice" in backend2_cache("drydock"), "")
    primary.run("dd boostpad remove minecraft:ice")
    settle()


def drill_reset(primary: Rcon):
    step("a module reset empties the other backend's cache as well")
    primary.run("dd boostpad add minecraft:packed_ice 0.4 0.0 0.0 500")
    settle()
    truthy("backend2 has a pad to lose", "packed_ice" in backend2_cache("drydock"), "")
    primary.run("wake database drop drydock confirm")
    settle(2)
    mine, theirs = both_caches(primary, "drydock")
    truthy("the primary's cache is empty", "packed_ice" not in mine, mine[:200])
    truthy("backend2's cache is empty too", "packed_ice" not in theirs, theirs[:200])


def drill_seed(primary: Rcon):
    step("re-seeding defaults converges both backends")
    primary.run("wake database setdefaults drydock confirm")
    settle(2)
    mine, theirs = both_caches(primary, "drydock")
    seeded = "boostpads" in mine and "boostpads" in theirs
    truthy("both backends hold the seeded pads", seeded, f"primary={mine[:120]} backend2={theirs[:120]}")


def drill_obu_import(primary: Rcon):
    step("the OBU aggregate: an import on the primary reaches backend2 whole")
    primary.run("wake database export obu")
    time.sleep(1.0)
    path = PRIMARY_EXPORTS / "obu_data.yml"
    if not path.is_file():
        bad("no obu export to work from")
        return
    text = path.read_text(encoding="utf-8", errors="replace")
    if "server:" not in text:
        text += "\nserver:\n  drilltrack:\n    settings:\n      setscale: '2.5'\n      falldamage: 'false'\n"
    else:
        text = text.replace("server:\n", "server:\n  drilltrack:\n    settings:\n      setscale: '2.5'\n      falldamage: 'false'\n", 1)
    # import reads back out of the exports directory
    path.write_text(text, encoding="utf-8")
    primary.run("wake database import obu confirm")
    settle(2)
    mine, theirs = both_caches(primary, "obu")
    truthy("the primary imported the context", "drilltrack" in mine, mine[:300])
    truthy("backend2 received it with its settings", "drilltrack" in theirs and "setscale" in theirs, theirs[:400])

    step("a context deleted on backend2 disappears on the primary")
    # drop the whole module on backend2 so the removal is driven from the other side
    on_backend2("wake database drop obu confirm")
    settle(2)
    mine = primary_cache(primary, "obu")
    truthy("the primary's obu cache lost it too", "drilltrack" not in mine, mine[:300])


def drill_bus_down(primary: Rcon):
    step("with the sync bus down, writes still land and both recover on reconnect")
    docker("stop", "wake-testenv-valkey-1")
    time.sleep(2)
    primary.run("dd boostpad add minecraft:obsidian 0.6 0.0 0.0 500")
    settle()
    truthy("backend2 has not heard yet (the bus is down)",
           "obsidian" not in backend2_cache("drydock"), "")
    docker("start", "wake-testenv-valkey-1")
    time.sleep(15)
    theirs = backend2_cache("drydock")
    truthy("backend2 catches up once the bus returns", "obsidian" in theirs, theirs[:300])
    primary.run("dd boostpad remove minecraft:obsidian")
    settle()


def drill_outage(primary: Rcon):
    step("a database outage: writes journal, replay, and reach the other backend")
    docker("stop", MARIADB)
    time.sleep(3)
    primary.run("dd boostpad add minecraft:sand 0.8 0.0 0.0 500")
    time.sleep(3)
    docker("start", MARIADB)
    time.sleep(25)
    mine, theirs = both_caches(primary, "drydock")
    truthy("the primary kept the write across the outage", "minecraft:sand" in mine, mine[:300])
    truthy("backend2 has it after the replay", "minecraft:sand" in theirs, theirs[:300])
    primary.run("dd boostpad remove minecraft:sand")
    settle()


def obu_import(primary: Rcon, contexts):
    """Rewrites the primary's obu export with `contexts` ({name: {setting: value}}) and imports it."""
    primary.run("wake database export obu")
    time.sleep(1.0)
    path = PRIMARY_EXPORTS / "obu_data.yml"
    body = ["version: 1", "server:"]
    for name, settings in contexts.items():
        body.append(f"  {name!r}:")
        body.append("    settings:")
        for key, value in settings.items():
            body.append(f"      {key}: '{value}'")
    path.write_text("\n".join(body) + "\n", encoding="utf-8")
    primary.run("wake database import obu confirm")


def drill_separator_keys(primary: Rcon):
    step("a key holding the message's field separator still reaches the other backend")
    # a name reaches a row through an imported file as well as through a command, so it is not held to what the
    # command parser accepts: the key list is the last field of the message and keeps every pipe it was handed
    obu_import(primary, {"pipe|ctx": {"setscale": "1.5"}, "plainctx": {"setscale": "2.5"}})
    settle(3)
    mine, theirs = both_caches(primary, "obu")
    truthy("the primary imported both names", "pipe|ctx" in mine and "plainctx" in mine, mine[:400])
    truthy("backend2 received the pipe key", "pipe|ctx" in theirs, theirs[:400])
    truthy("backend2 received the plain one beside it", "plainctx" in theirs, theirs[:400])
    on_backend2("wake database drop obu confirm")
    settle(2)
    truthy("and the primary loses both when backend2 drops the module",
           "pipe|ctx" not in primary_cache(primary, "obu"), "")


def drill_bulk_import(primary: Rcon, count=600):
    step(f"a {count}-context import: past what one message can name, both caches still agree")
    obu_import(primary, {f"bulk{i}": {"setscale": "1.0"} for i in range(count)})
    settle(4)
    rows = {row[0] for row in sql("SELECT name FROM wake_obu_contexts WHERE name LIKE 'bulk%'")}
    truthy(f"the database holds all {count}", len(rows) == count, f"{len(rows)} rows")
    mine, theirs = both_caches(primary, "obu")
    missing_mine = [n for n in rows if n not in mine]
    missing_theirs = [n for n in rows if n not in theirs]
    truthy("the primary cached every one", not missing_mine, f"{len(missing_mine)} missing")
    truthy("backend2 cached every one", not missing_theirs, f"{len(missing_theirs)} missing")

    step("and a drop of that many clears both")
    primary.run("wake database drop obu confirm")
    settle(3)
    mine, theirs = both_caches(primary, "obu")
    truthy("the primary is clear", "bulk1" not in mine, mine[:200])
    truthy("backend2 is clear", "bulk1" not in theirs, theirs[:200])


def pad_fields(text, block_key):
    """force_x and delay_ms of one pad out of an export, or None when the pad is absent."""
    block = re.search(r"(?m)^  " + re.escape(block_key) + r":\n((?:    .*\n)+)", text)
    if not block:
        return None
    fields = dict(re.findall(r"(?m)^    (\w+): (\S+)$", block.group(1)))
    if "force_x" not in fields or "delay_ms" not in fields:
        return None
    return float(fields["force_x"]), int(fields["delay_ms"])


def drill_concurrent_same_key(primary: Rcon):
    step("both backends write the same row at once: they converge on the row that was stored")
    on_backend2("dd boostpad add minecraft:blue_ice 0.1 0.0 0.0 100")
    primary.run("dd boostpad add minecraft:blue_ice 0.9 0.0 0.0 900")
    settle(2)
    mine, theirs = both_caches(primary, "drydock")
    row = sql("SELECT force_x, delay_ms FROM wake_drydock_boostpads WHERE block_key = 'minecraft:blue_ice'")
    stored = (float(row[0][0]), int(row[0][1])) if row else None
    truthy("the database kept exactly one of the two writes",
           stored in {(0.1, 100), (0.9, 900)}, str(stored))
    truthy("the primary's cache matches the stored row",
           pad_fields(mine, "minecraft:blue_ice") == stored,
           f"{pad_fields(mine, 'minecraft:blue_ice')} vs {stored}")
    truthy("backend2's cache matches it too",
           pad_fields(theirs, "minecraft:blue_ice") == stored,
           f"{pad_fields(theirs, 'minecraft:blue_ice')} vs {stored}")
    primary.run("dd boostpad remove minecraft:blue_ice")
    settle()


def drill_concurrent_cross_writes(primary: Rcon):
    step("each backend writes a different row at once: both end up holding both")
    on_backend2("dd boostpad add minecraft:packed_ice 0.3 0.0 0.0 300")
    primary.run("dd boostpad add minecraft:magma_block 0.4 0.0 0.0 400")
    settle(2)
    mine, theirs = both_caches(primary, "drydock")
    truthy("the primary holds both", "packed_ice" in mine and "magma_block" in mine, mine[:300])
    truthy("backend2 holds both", "packed_ice" in theirs and "magma_block" in theirs, theirs[:300])
    primary.run("dd boostpad remove minecraft:packed_ice")
    primary.run("dd boostpad remove minecraft:magma_block")
    settle()


def drill_write_storm(primary: Rcon, rounds=15):
    step(f"{rounds} rounds of add/remove on one row inside one catch-up window")
    for i in range(rounds):
        primary.run(f"dd boostpad add minecraft:ice 0.{i % 9 + 1} 0.0 0.0 500")
        primary.run("dd boostpad remove minecraft:ice")
    primary.run("dd boostpad add minecraft:ice 0.5 0.0 0.0 777")
    settle(3)
    mine, theirs = both_caches(primary, "drydock")
    truthy("the primary lands on the last write", "777" in mine, mine[:300])
    truthy("backend2 lands on the last write too", "minecraft:ice" in theirs and "777" in theirs, theirs[:300])
    primary.run("dd boostpad remove minecraft:ice")
    settle(2)
    truthy("and on the final removal", "minecraft:ice" not in backend2_cache("drydock"), "")


GAPS = [0.0, 0.02, 0.04, 0.06, 0.08, 0.10, 0.12, 0.14, 0.16, 0.20, 0.25, 0.30]


def drill_second_edit_during_read(primary: Rcon):
    """Two edits to one key from the other side, swept across the width of the primary's read.

    The first edit sends the primary off to read that key back. The second announces the key again
    while that read is still in flight, and the read may have run before the second edit committed,
    so it cannot count as covering it. Retiring the key's dirty mark on the strength of that read
    leaves the primary holding edit one for good: nothing names the key again, so nothing re-reads it.
    The sweep is there because the window is one main-thread hop wide -- the gaps around 0.1s are the
    ones that land inside it. A burst of edits hides this rather than showing it, because a later
    announcement re-marks the key and covers up the swallowed one."""
    step(f"a second edit to the same row {GAPS[0]}s-{GAPS[-1]}s behind the first, {len(GAPS)} gaps")
    stale = []
    for index, gap in enumerate(GAPS):
        first, second = 700 + index * 2, 701 + index * 2
        docker("exec", PAPER2, "sh", "-c",
               f'rcon-cli "dd boostpad add minecraft:ice 0.5 0.0 0.0 {first}"; sleep {gap}; '
               f'rcon-cli "dd boostpad add minecraft:ice 0.5 0.0 0.0 {second}"')
        settle(1.3)
        held = pad_fields(primary_cache(primary, "drydock"), "minecraft:ice")
        if held != (0.5, second):
            stale.append(f"{gap}s -> {held}, wanted delay_ms {second}")
    truthy("the primary read back the second edit at every gap", not stale, " | ".join(stale))

    step("and the same for a removal that follows an edit")
    docker("exec", PAPER2, "sh", "-c",
           'rcon-cli "dd boostpad add minecraft:ice 0.5 0.0 0.0 999"; sleep 0.1; '
           'rcon-cli "dd boostpad remove minecraft:ice"')
    settle(2)
    truthy("the primary holds nothing, not the edit before it",
           pad_fields(primary_cache(primary, "drydock"), "minecraft:ice") is None,
           str(pad_fields(primary_cache(primary, "drydock"), "minecraft:ice")))


def drill_state_prefix_clear(primary: Rcon):
    step("clearing a state prefix (a change no row names) reaches the other backend")
    before = pad_state(on_backend2("dd boostpad list"))
    primary.run("dd boostpad toggle")
    settle()
    truthy("backend2 saw the flip first", pad_state(on_backend2("dd boostpad list")) != before, "")
    primary.run("wake database drop drydock confirm")
    settle(2)
    rows = sql("SELECT state_key FROM wake_state WHERE state_key LIKE 'drydock.%'")
    truthy("the drydock state rows are gone from the database", not rows, str(rows))
    truthy("backend2 no longer reports the toggled value",
           pad_state(on_backend2("dd boostpad list")) == pad_state(primary.run("dd boostpad list")), "")
    primary.run("wake database setdefaults drydock confirm")
    settle(2)


def drill_reload_command(primary: Rcon):
    step("/wake reload on both backends loses nothing")
    primary.run("dd boostpad add minecraft:obsidian 0.6 0.0 0.0 600")
    settle()
    primary.run("wake reload")
    on_backend2("wake reload")
    settle(2)
    mine, theirs = both_caches(primary, "drydock")
    truthy("the primary still holds it", "obsidian" in mine, mine[:300])
    truthy("backend2 still holds it", "obsidian" in theirs, theirs[:300])


def drill_cold_start(primary: Rcon):
    step("a backend restarted cold rebuilds its cache from the database alone")
    primary.run("dd boostpad add minecraft:sandstone 0.25 0.0 0.0 250")
    settle()
    docker("restart", PAPER2, timeout=300)
    deadline = time.monotonic() + 180
    while time.monotonic() < deadline:
        try:
            if "Boostpads" in on_backend2("dd boostpad list"):
                break
        except RuntimeError:
            pass
        time.sleep(3)
    else:
        bad("backend2 did not come back within 180s")
        return
    settle()
    theirs = backend2_cache("drydock")
    truthy("the cold cache has what was written before the restart", "sandstone" in theirs, theirs[:300])
    truthy("and still nothing that was deleted", "obsidian" in theirs, theirs[:300])
    primary.run("dd boostpad remove minecraft:sandstone")
    primary.run("dd boostpad remove minecraft:obsidian")
    settle()
    truthy("and it still receives invalidations after the restart",
           "sandstone" not in backend2_cache("drydock"), "")


def drill_bus_down_both_sides(primary: Rcon):
    step("with the bus down each backend writes its own row: the reconnect resync must find both")
    docker("stop", "wake-testenv-valkey-1")
    time.sleep(2)
    primary.run("dd boostpad add minecraft:basalt 0.11 0.0 0.0 111")
    on_backend2("dd boostpad add minecraft:calcite 0.22 0.0 0.0 222")
    settle()
    truthy("neither has heard the other yet", "calcite" not in primary_cache(primary, "drydock")
           and "basalt" not in backend2_cache("drydock"), "")
    docker("start", "wake-testenv-valkey-1")
    time.sleep(20)
    mine, theirs = both_caches(primary, "drydock")
    truthy("the primary picked up backend2's row", "calcite" in mine, mine[:400])
    truthy("backend2 picked up the primary's row", "basalt" in theirs, theirs[:400])
    primary.run("dd boostpad remove minecraft:basalt")
    primary.run("dd boostpad remove minecraft:calcite")
    settle()


def drill_outage_both_sides(primary: Rcon):
    step("a database outage with a write journaled on each backend: both replays land, both caches agree")
    docker("stop", MARIADB)
    time.sleep(4)
    primary.run("dd boostpad add minecraft:tuff 0.33 0.0 0.0 333")
    on_backend2("dd boostpad add minecraft:deepslate 0.44 0.0 0.0 444")
    time.sleep(4)
    docker("start", MARIADB)
    time.sleep(35)
    rows = {row[0] for row in sql("SELECT block_key FROM wake_drydock_boostpads")}
    truthy("both journaled writes reached the database",
           {"minecraft:tuff", "minecraft:deepslate"} <= rows, str(sorted(rows)))
    mine, theirs = both_caches(primary, "drydock")
    truthy("the primary holds both after recovery", "tuff" in mine and "deepslate" in mine, mine[:400])
    truthy("backend2 holds both after recovery", "tuff" in theirs and "deepslate" in theirs, theirs[:400])
    primary.run("dd boostpad remove minecraft:tuff")
    primary.run("dd boostpad remove minecraft:deepslate")
    settle()


def backend2_log_lines():
    return len(docker("logs", PAPER2, timeout=180).splitlines())


def drill_no_errors(since, backend_since):
    step("no errors in either server's log since these drills began")
    log = ROOT / "run" / "logs" / "latest.log"
    with log.open(encoding="utf-8", errors="replace") as handle:
        handle.seek(min(since, log.stat().st_size))
        primary_log = handle.read()
    noise = [line for line in primary_log.splitlines()
             if ("wake" in line.lower() and ("SEVERE" in line or "Exception" in line))]
    truthy("primary log is clean of wake errors", not noise, " | ".join(noise[-3:]))
    # from where this run started, not the tail: a container keeps its log across restarts, so an
    # unanchored check reports whatever an earlier run left behind
    backend_log = docker("logs", PAPER2, timeout=180).splitlines()[backend_since:]
    noise2 = [line for line in backend_log
              if ("wake" in line.lower() and ("SEVERE" in line or "Exception" in line))]
    truthy("backend2 log is clean of wake errors", not noise2, " | ".join(noise2[-3:]))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=25575)
    parser.add_argument("--password", default="wake-dev")
    args = parser.parse_args()

    primary = Rcon(args.host, args.port, args.password)
    log = ROOT / "run" / "logs" / "latest.log"
    since = log.stat().st_size if log.is_file() else 0
    backend_since = backend2_log_lines()
    print("change-log drills against the mariadb proxy network\n")
    drill_no_change_table()
    drill_boostpad_roundtrip(primary)
    drill_reverse_direction(primary)
    drill_delete_then_recreate(primary)
    drill_state_scope(primary)
    drill_scoped_reload(primary)
    drill_own_writes(primary)
    drill_concurrent_same_key(primary)
    drill_concurrent_cross_writes(primary)
    drill_write_storm(primary)
    drill_second_edit_during_read(primary)
    drill_reset(primary)
    drill_seed(primary)
    drill_state_prefix_clear(primary)
    drill_obu_import(primary)
    drill_separator_keys(primary)
    drill_bulk_import(primary)
    drill_reload_command(primary)
    # before the drills that break things on purpose, so the log check means something
    drill_no_errors(since, backend_since)
    drill_cold_start(primary)
    drill_bus_down(primary)
    drill_bus_down_both_sides(primary)
    drill_outage(primary)
    drill_outage_both_sides(primary)

    print()
    if failures:
        print(f"{len(failures)} check(s) failed")
        for failure in failures:
            print(f"  - {failure}")
        sys.exit(1)
    print("all change-log drills passed")


if __name__ == "__main__":
    main()
