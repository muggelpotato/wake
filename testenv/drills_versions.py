#!/usr/bin/env python3
"""Is what Wake claims about Paper versions still true?

One jar covers every version in the `claimed` map in build.gradle.kts, and two gradle tasks hold that up.
`./gradlew checkVersions` compiles the source against each version's own paper-api, which is exhaustive over
every call Wake makes. `./gradlew run<version>` boots a real server of one on the JVM it needs with this
build's jar, and every drill in this directory can be pointed at it with `WAKE_RUN`.

What neither can see is the map itself. It is hand-written, because Gradle needs it at configuration time and
reaching the network there is worse than an edit per release, so this asks Paper which versions exist and
fails if the map has fallen behind -- or if a row is pinned to a paper-api that is not its own, which compiles
green while checking a version twice and one never. It also reads the drills, because a path spelled out from
the repository root ignores `WAKE_RUN` and quietly drives whatever is in `run/`.

    python testenv/drills_versions.py

Needs the network, no server and no build. Exits non-zero if anything failed.
"""

import json
import os
import re
import sys
import urllib.error
import urllib.request

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from drills import ROOT, bad, failures, ok, step  # noqa: E402

BUILD = ROOT / "build.gradle.kts"
FILL = "https://fill.papermc.io/v3/projects/paper/versions"
# what Wake claims: 1.21 and its point releases, and every 26.x as it lands. Widening the claim is this line
# and the map in build.gradle.kts, and the claim drill is what keeps the two agreeing
CLAIMED = re.compile(r"^1\.21(\.\d+)?$|^26\.\d+(\.\d+)?$")
# the rows of the `claimed` map: "1.21.4" to "1.21.4-R0.1-SNAPSHOT"
ROW = re.compile(r'"([\d.]+)"\s+to\s+"([^"]+)"')
# a path component spelled out instead of taken from drills.RUN: `ROOT / "run" / "plugins"`
RUN_LITERAL = re.compile(r"""/\s*["']run["']""")


def order(version):
    return tuple(int(part) for part in version.split("."))


def pinned(coordinate):
    """The version a paper-api coordinate is for -- 1.21.4-R0.1-SNAPSHOT and 26.2.build.111-stable each open
    with theirs -- or None when it does not open with one."""
    found = re.match(r"[\d.]+", coordinate)
    return found.group().rstrip(".") if found else None


def catalogue():
    """Every claimed version Paper publishes, in order."""
    # Paper's host refuses the default urllib agent: it asks callers to name themselves
    request = urllib.request.Request(FILL, headers={"User-Agent": "wake-testenv/1 (+https://muggel.dev)"})
    try:
        with urllib.request.urlopen(request, timeout=60) as answer:
            payload = json.load(answer)
    except (urllib.error.URLError, OSError, ValueError) as error:
        raise SystemExit(f"cannot reach Paper's version list ({error})")
    found = [entry["version"]["id"] for entry in payload["versions"]
             if CLAIMED.fullmatch(entry["version"]["id"]) and entry["builds"]]
    if not found:
        raise SystemExit("Paper's API listed no version Wake claims -- check CLAIMED")
    return sorted(found, key=order)


def drill_claim(catalogued):
    """build.gradle.kts is hand-written; Paper's list is not. They have to still agree.

    A version that ships and never reaches the map is the failure that matters: `checkVersions` would go on
    passing, and the first anyone would hear of it is a server nobody compiled against.
    """
    print("\nthe claim")
    written = dict(ROW.findall(BUILD.read_text(encoding="utf-8")))
    behind = [version for version in catalogued if version not in written]
    if behind:
        bad(f"Paper publishes {len(behind)} claimed version(s) build.gradle.kts does not compile against: "
            f"{', '.join(behind)}")
        for version in behind:
            print(f'          "{version}" to "<paper-api coordinate>",')
    else:
        ok(f"build.gradle.kts covers every claimed version Paper publishes ({len(written)})")
    # a coordinate copied off the row above compiles one version against its neighbour's api and still passes,
    # so the row would report a version nothing ever checked
    mispinned = [f"{version} -> {api}" for version, api in written.items() if pinned(api) != version]
    if mispinned:
        bad(f"build.gradle.kts pins a paper-api belonging to another version: {', '.join(mispinned)}")
    else:
        ok("every row is pinned to its own version's paper-api")
    gone = [version for version in written if version not in catalogued]
    if gone:
        step(f"build.gradle.kts names {', '.join(gone)}, which Paper no longer lists")


def drill_run_directory():
    """A drill has to read the server it was pointed at, not the one runServer owns.

    `WAKE_RUN` only reaches a path built from `drills.RUN`. One spelled out from the repository root goes on
    following `run/` while the drill drives a version's server, and drills_commands.py writes the config file
    it finds there -- so a stray literal does not answer wrongly, it edits another server. A drill that
    declares a `--port` is one that drives a server, which is the whole list without one being written down.
    """
    print("\nthe run directory")
    driven = {path.name: path.read_text(encoding="utf-8") for path in (ROOT / "testenv").glob("drills*.py")}
    driven = {name: text for name, text in driven.items() if 'add_argument("--port"' in text}
    stray = [f"{name}:{number}" for name, text in sorted(driven.items())
             for number, line in enumerate(text.splitlines(), 1)
             if RUN_LITERAL.search(line) and "WAKE_RUN" not in line and not line.lstrip().startswith("#")]
    if stray:
        bad(f"{len(stray)} path(s) are pinned to run/ rather than following it: {', '.join(stray)}")
    else:
        ok(f"every path in the {len(driven)} drills that drive a server follows the directory it is given")


def main():
    catalogued = catalogue()
    print(f"claimed: {', '.join(catalogued)}")
    drill_claim(catalogued)
    drill_run_directory()

    print()
    if failures:
        print(f"{len(failures)} drill step(s) failed")
        return 1
    print("all drills passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
