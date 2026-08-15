#!/usr/bin/env python3
"""Geometry drills against the compiled classes -- no server, no world.

Covers what the four classes at the root of `core/` own: the swept segment-versus-box test, the block
range a sweep hands the caller, the legs a tick's path is cut into, and the clock that dates a point
inside a tick. All of it is arithmetic over doubles, so a running server would add nothing but noise
-- the drill compiles a probe into `dev.muggel.wake.core` (which is how it reaches `Legs.of`), runs it
against `build/classes/java/main`, and judges the numbers here. Nothing in the probe decides whether a
number is right.

    python testenv/drills_geometry.py       # needs ./gradlew compileJava first

The three scenarios at the end drive the geometry the way `BoostpadDetectorListener` drives it, with the
same surface band and hull, because a shape nobody asks for proves nothing. Two answer the claims
TESTPLAN.md used to leave to a human with a stopwatch: a strip of pads crossed in one leg loses none of
them, and a hard turn fires the pad on the corner rather than the pads under the chord. The third
answers one nobody could make with a stopwatch at all -- a tick must cross the same pads whether the
client sent it as one vehicle move or two, or the boost a player gets depends on how their packets fell.

The `scan_*` checks judge the loop the detector is about to run rather than any single number: whatever
the coordinates, a leg must never cost more than `CollisionGeometry`'s own cap, and it may only give up
on a path nothing could have driven. Their spans are multiplied in Python because three saturated ones
overflow a long and wrap back under the cap -- which is the failure they exist to catch.

What is left elsewhere: `claim()`/`release()` and the recording itself need a plugin manager, so the
listener's registration is exercised by `drills_module.py` cycling drydock off and on, not here. And
TESTPLAN.md keeps everything a client computes and everything drydock decides -- whether a boat is
grounded, which impulse axis a held jump cancels, what `setscale` does to the hull, and whether a boost
is felt at all. Also the world-mismatch branch of `VehiclePath.legs`, which needs two worlds and a move
event; the shape it produces is checked here as `legs_teleport`.

Exits non-zero if a check fails.
"""

import argparse
import os
import subprocess
import sys
import tempfile
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from drills import ROOT  # noqa: E402

CLASSES = ROOT / "build" / "classes" / "java" / "main"
MILLIS = 1_000_000
MAX_SWEPT_BLOCKS = 4096  # CollisionGeometry's own cap, restated here so the drill judges it from outside

PROBE = r"""
package dev.muggel.wake.core;

import dev.muggel.wake.core.CollisionGeometry.BlockSweep;
import dev.muggel.wake.core.VehiclePath.Legs;
import com.destroystokyo.paper.event.server.ServerTickStartEvent;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class GeometryProbe {
    // the shapes BoostpadDetectorListener asks for: a band at the block's top face, and the hull it drives with
    private static final double BAND = 0.15;
    private static final double DROP = 0.5;
    private static final double HULL = 0.6875 - 0.01;

    public static void main(String[] args) {
        fractions();
        sweeps();
        legs();
        clock();
        strip();
        turn();
        split();
    }

    private static void fractions() {
        say("frac_mid", unit(v(-1, .5, .5), v(2, .5, .5)));
        say("frac_inside", unit(v(.5, .5, .5), v(2, .5, .5)));
        say("frac_before", unit(v(-3, .5, .5), v(-2, .5, .5)));
        say("frac_behind", unit(v(2, .5, .5), v(3, .5, .5)));
        say("frac_parallel_out", unit(v(-1, 5, .5), v(2, 5, .5)));
        say("frac_touch_face", unit(v(-1, .5, .5), v(0, .5, .5)));
        say("frac_point_in", unit(v(.5, .5, .5), v(.5, .5, .5)));
        say("frac_point_out", unit(v(5, 5, 5), v(5, 5, 5)));
        say("frac_diagonal", unit(v(-1, -1, .5), v(2, 2, .5)));
        say("frac_corner", unit(v(-1, -1, -1), v(2, 2, 2)));
        say("frac_nan", unit(v(Double.NaN, .5, .5), v(2, .5, .5)));
        // two centimetres of plane crossed at a thousand blocks a tick
        say("frac_thin_fast",
                CollisionGeometry.intersectionFraction(v(-500, .5, .5), v(500, .5, .5), 0, 0, 0, .02, 1, 1));
    }

    private static void sweeps() {
        BlockSweep flat = CollisionGeometry.sweep(v(10.2, 65.0, 20.7), v(14.9, 65.0, 20.7), HULL, -DROP);
        say("sweep_x", flat.minX() + " " + flat.maxX());
        say("sweep_y", flat.minY() + " " + flat.maxY());
        say("sweep_z", flat.minZ() + " " + flat.maxZ());
        BlockSweep jump = CollisionGeometry.sweep(v(0, 65, 0), v(500, 65, 500), HULL, -DROP);
        say("jump_from", jump.from().getX() + " " + jump.from().getZ());
        say("jump_span", (jump.maxX() - jump.minX()) + " " + (jump.maxZ() - jump.minZ()));
        say("sweep_covers", covers(v(10.2, 65.0, 20.7), v(14.9, 65.2, 27.3), 3.5));
        say("sweep_covers_slow", covers(v(10.2, 65.0, 20.7), v(10.25, 64.9, 20.72), 0.4));
        // forty blocks in one tick: faster than a boat drives, and still a path rather than a jump
        say("scan_fast", scanned(v(0, 65, 0), v(40, 65, 0)));
        // one corner of the world to the other, which nothing could have driven
        say("scan_border", scanned(v(-3e7, 65, -3e7), v(3e7, 65, 3e7)));
        // coordinates whose block bounds saturate the int range: one axis, then all three
        say("scan_saturated_x", scanned(v(-1e300, 65, 0), v(1e300, 65, 0)));
        say("scan_saturated_all", scanned(v(-1e300, -1e300, -1e300), v(1e300, 1e300, 1e300)));
        say("scan_infinite", scanned(v(0, 65, 0), v(Double.POSITIVE_INFINITY, 65, 0)));
        // not a number floors to zero rather than to a saturated bound, so this one collapses without giving up
        say("scan_nan", scanned(v(Double.NaN, 65, 0), v(5, 65, 0)));
    }

    /**
     * The three spans of the loop the caller is about to run, and whether the sweep kept the path it was handed.
     * Multiplied in Python, where the product of three saturated spans cannot quietly wrap the way a long would
     */
    private static String scanned(Vector from, Vector to) {
        BlockSweep swept = CollisionGeometry.sweep(from, to, HULL, -DROP);
        return ((long) swept.maxX() - swept.minX() + 1) + " " + ((long) swept.maxY() - swept.minY() + 1)
                + " " + ((long) swept.maxZ() - swept.minZ() + 1) + " " + (swept.from() == from);
    }

    /** How many blocks the segment really reaches into, and how many of those the sweep's range leaves out */
    private static String covers(Vector from, Vector to, double reach) {
        BlockSweep swept = CollisionGeometry.sweep(from, to, reach, -DROP);
        int touched = 0;
        int outside = 0;
        for (int x = swept.minX() - 3; x <= swept.maxX() + 3; x++) {
            for (int y = swept.minY() - 3; y <= swept.maxY() + 3; y++) {
                for (int z = swept.minZ() - 3; z <= swept.maxZ() + 3; z++) {
                    if (CollisionGeometry.intersectionFraction(from, to, x - reach, y + 1 - BAND, z - reach,
                            x + 1 + reach, y + 1 + BAND, z + 1 + reach) < 0) {
                        continue;
                    }
                    touched++;
                    if (x < swept.minX() || x > swept.maxX() || y < swept.minY() || y > swept.maxY()
                            || z < swept.minZ() || z > swept.maxZ()) {
                        outside++;
                    }
                }
            }
        }
        return touched + " " + outside;
    }

    private static void legs() {
        Vector start = v(0, 65, 0);
        Vector end = v(4, 65, 0);
        Vector first = v(1, 65, 0);
        Vector second = v(2, 65, 0);
        say("legs_plain", shape(Legs.of(List.of(), start, end)));
        say("legs_leading_dup", shape(Legs.of(List.of(start, first, second), start, end)));
        say("legs_trailing_dup", shape(Legs.of(List.of(first, second, end), start, end)));
        say("legs_both_dup", shape(Legs.of(List.of(start, first, end), start, end)));
        say("legs_teleport", shape(Legs.of(List.of(), end, end)));

        List<Vector> recorded = new ArrayList<>(List.of(first, second));
        Legs built = Legs.of(recorded, start, end);
        recorded.clear();
        say("legs_after_clear", shape(built));
        say("legs_modifiable", modifiable(built));
        say("legs_progress", built.progress(0, 0) + " " + built.progress(1, 0.5) + " " + built.progress(2, 1));
    }

    /** The leg count followed by every boundary's X, the only axis these cases move on */
    private static String shape(Legs legs) {
        StringBuilder text = new StringBuilder().append(legs.count());
        for (int boundary = 0; boundary <= legs.count(); boundary++) {
            text.append(' ').append(legs.at(boundary).getX());
        }
        return text.toString();
    }

    private static boolean modifiable(Legs legs) {
        try {
            legs.boundaries().add(v(0, 0, 0));
            return true;
        } catch (UnsupportedOperationException immutable) {
            return false;
        }
    }

    private static void clock() {
        say("clock_short", span(20));
        say("clock_long", span(80));
        say("clock_handover", handover());
    }

    /** What a tick of `millis` reads as, and how far the two halves of it fall apart */
    private static String span(long millis) {
        TickClock clock = new TickClock();
        clock.onTickStart(new ServerTickStartEvent(1));
        burn(millis);
        clock.onTickStart(new ServerTickStartEvent(2));
        long lead = clock.at(0.5) - clock.at(0.0);
        long trail = clock.at(1.0) - clock.at(0.5);
        return (clock.at(1.0) - clock.at(0.0)) + " " + (trail - lead);
    }

    /**
     * Whether one tick's end is the next tick's start, and whether a timestamp keeps climbing across the seam.
     * A cooldown subtracts two of these, so a tick that hands over badly would let a pad fire twice
     */
    private static String handover() {
        TickClock clock = new TickClock();
        clock.onTickStart(new ServerTickStartEvent(1));
        long previousEnd = clock.at(1.0);
        boolean seamless = true;
        boolean climbing = true;
        for (int tick = 2; tick <= 6; tick++) {
            burn(tick % 2 == 0 ? 1 : 12);
            clock.onTickStart(new ServerTickStartEvent(tick));
            seamless &= clock.at(0.0) == previousEnd;
            climbing &= clock.at(0.0) < clock.at(0.5) && clock.at(0.5) < clock.at(1.0);
            previousEnd = clock.at(1.0);
        }
        return seamless + " " + climbing;
    }

    private static void burn(long millis) {
        long until = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < until) {
            Thread.onSpinWait();
        }
    }

    /** Twenty pads in a row taken in one leg, at a speed no boat reaches */
    private static void strip() {
        List<int[]> pads = new ArrayList<>();
        for (int x = 0; x < 20; x++) {
            pads.add(new int[]{x, 64, 0});
        }
        say("strip", crossings(Legs.of(List.of(), v(-2, 65, 0.5), v(22, 65, 0.5)), pads, new HashSet<>()));
    }

    /**
     * One tick's movement handed over as one vehicle move and as two. The detector shares its seen-set across
     * the tick, so both must cross the same pads -- a set per move event counts the seam twice, and a client
     * whose packets bunched would be boosted harder than one whose packets did not
     */
    private static void split() {
        List<int[]> pads = new ArrayList<>();
        for (int x = 0; x < 20; x++) {
            pads.add(new int[]{x, 64, 0});
        }
        Legs first = Legs.of(List.of(), v(-2, 65, 0.5), v(10, 65, 0.5));
        Legs second = Legs.of(List.of(), v(10, 65, 0.5), v(22, 65, 0.5));
        Set<String> whole = new HashSet<>();
        crossings(Legs.of(List.of(), v(-2, 65, 0.5), v(22, 65, 0.5)), pads, whole);
        Set<String> tick = new HashSet<>();
        crossings(first, pads, tick);
        crossings(second, pads, tick);
        Set<String> perCallFirst = new HashSet<>();
        Set<String> perCallSecond = new HashSet<>();
        crossings(first, pads, perCallFirst);
        crossings(second, pads, perCallSecond);
        say("split_whole", whole.size());
        say("split_shared", tick.size());
        say("split_percall", perCallFirst.size() + perCallSecond.size());
    }

    /** A right-angle turn with a pad on the corner and a pad under the chord that closes it */
    private static void turn() {
        List<int[]> pads = List.of(new int[]{10, 64, 0}, new int[]{5, 64, 5});
        Vector start = v(0.5, 65, 0.5);
        Vector corner = v(10.5, 65, 0.5);
        Vector end = v(10.5, 65, 10.5);
        say("turn_legs", crossings(Legs.of(List.of(corner), start, end), pads, new HashSet<>()));
        say("turn_chord", crossings(Legs.of(List.of(), start, end), pads, new HashSet<>()));
    }

    /** The detector's scan reduced to its geometry: how many pads the legs crossed, in order, once each */
    private static String crossings(Legs legs, List<int[]> pads, Set<String> seen) {
        List<String> crossed = new ArrayList<>();
        double last = -1;
        boolean ordered = true;
        for (int leg = 0; leg < legs.count(); leg++) {
            Vector legEnd = legs.at(leg + 1);
            BlockSweep swept = CollisionGeometry.sweep(legs.at(leg), legEnd, HULL, -DROP);
            for (int[] pad : pads) {
                if (pad[0] < swept.minX() || pad[0] > swept.maxX() || pad[1] < swept.minY()
                        || pad[1] > swept.maxY() || pad[2] < swept.minZ() || pad[2] > swept.maxZ()) {
                    continue;
                }
                double fraction = CollisionGeometry.intersectionFraction(swept.from(), legEnd,
                        pad[0] - HULL, pad[1] + 1 - BAND, pad[2] - HULL,
                        pad[0] + 1 + HULL, pad[1] + 1 + BAND, pad[2] + 1 + HULL);
                String key = pad[0] + ":" + pad[1] + ":" + pad[2];
                if (fraction < 0 || !seen.add(key)) {
                    continue;
                }
                double progress = legs.progress(leg, fraction);
                ordered &= progress > last;
                last = progress;
                crossed.add(key);
            }
        }
        return crossed.size() + " " + ordered + " " + String.join(",", crossed);
    }

    private static double unit(Vector from, Vector to) {
        return CollisionGeometry.intersectionFraction(from, to, 0, 0, 0, 1, 1, 1);
    }

    private static Vector v(double x, double y, double z) {
        return new Vector(x, y, z);
    }

    private static void say(String name, Object value) {
        System.out.println(name + "\t" + value);
    }
}
"""

failures = []


def ok(label):
    print(f"  ok    {label}")


def bad(label, detail):
    print(f"  FAIL  {label}: {detail}")
    failures.append(label)


def equals(facts, name, expected):
    seen = facts.get(name)
    if seen == expected:
        ok(f"{name} -> {seen}")
    else:
        bad(name, f"{seen!r}, expected {expected!r}")


def close(facts, name, expected, tolerance=1e-9):
    seen = facts.get(name)
    try:
        value = float(seen)
    except (TypeError, ValueError):
        bad(name, f"{seen!r} is not a number")
        return
    if abs(value - expected) <= tolerance:
        ok(f"{name} -> {value}")
    else:
        bad(name, f"{value}, expected {expected}")


def negative(facts, name):
    seen = facts.get(name)
    try:
        value = float(seen)
    except (TypeError, ValueError):
        bad(name, f"{seen!r} is not a number")
        return
    if value < 0:
        ok(f"{name} -> miss")
    else:
        bad(name, f"{value}, expected a miss")


def toolchain(name):
    home = os.environ.get("JAVA_HOME")
    if home and (Path(home) / "bin" / name).exists():
        return str(Path(home) / "bin" / name)
    if home and (Path(home) / "bin" / f"{name}.exe").exists():
        return str(Path(home) / "bin" / f"{name}.exe")
    return name


def bukkit_jar():
    """Whatever already holds org.bukkit.util.Vector: the compile dependency, or the jar runServer fetched."""
    gradle = Path(os.environ.get("GRADLE_USER_HOME") or (Path.home() / ".gradle"))
    cached = [jar for jar in gradle.glob("caches/modules-2/files-2.1/io.papermc.paper/paper-api/*/*/paper-api-*.jar")
              if "sources" not in jar.name]
    return next(iter(cached + sorted((ROOT / "run" / "versions").glob("*/paper-*.jar"))), None)


def probe():
    """Compiles the probe against the built classes and returns everything it printed, as name -> value."""
    if not CLASSES.is_dir():
        raise SystemExit(f"{CLASSES} is missing -- run ./gradlew compileJava first.")
    jar = bukkit_jar()
    if jar is None:
        raise SystemExit("no paper-api jar found -- run ./gradlew compileJava or ./gradlew runServer once.")
    with tempfile.TemporaryDirectory() as work:
        source = Path(work) / "GeometryProbe.java"
        source.write_text(PROBE, encoding="utf-8")
        classpath = os.pathsep.join([str(CLASSES), str(jar)])
        built = subprocess.run([toolchain("javac"), "--release", "21", "-cp", classpath, "-d", work, str(source)],
                               capture_output=True, text=True, encoding="utf-8", errors="replace")
        if built.returncode != 0:
            raise SystemExit(f"the probe did not compile against {CLASSES}:\n{built.stderr}")
        ran = subprocess.run([toolchain("java"), "-cp", os.pathsep.join([work, classpath]),
                              "dev.muggel.wake.core.GeometryProbe"],
                             capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=120)
        if ran.returncode != 0:
            raise SystemExit(f"the probe failed to run:\n{ran.stdout}\n{ran.stderr}")
    return dict(line.split("\t", 1) for line in ran.stdout.splitlines() if "\t" in line)


def drill_fractions(facts):
    """The slab test: where it enters, what counts as a miss, and that nothing thin slips through it."""
    print("\nintersection fraction")
    close(facts, "frac_mid", 1 / 3)
    close(facts, "frac_diagonal", 1 / 3)
    close(facts, "frac_corner", 1 / 3)
    close(facts, "frac_touch_face", 1.0)
    close(facts, "frac_inside", 0.0)
    close(facts, "frac_point_in", 0.0)
    close(facts, "frac_thin_fast", 0.5, tolerance=1e-4)
    negative(facts, "frac_before")
    negative(facts, "frac_behind")
    negative(facts, "frac_parallel_out")
    negative(facts, "frac_point_out")
    negative(facts, "frac_nan")


def drill_sweep(facts):
    """The block range: the surface band it drops onto, the padding it widens by, and the jump it gives up on."""
    print("\nblock sweep")
    # x 10.2..14.9 widened by the 0.6775 hull spans 9.52..15.58, and y 65.0 dropped half a block sits on 64
    equals(facts, "sweep_x", "9 15")
    equals(facts, "sweep_y", "64 64")
    equals(facts, "sweep_z", "20 21")
    # 500 blocks in one leg is a teleport: the range collapses onto the destination, one block wide on both axes
    equals(facts, "jump_from", "500.0 500.0")
    equals(facts, "jump_span", "1 1")
    for name in ("sweep_covers", "sweep_covers_slow"):
        touched, outside = facts.get(name, "0 0").split()
        if int(touched) > 0 and int(outside) == 0:
            ok(f"{name} -> every one of the {touched} blocks the segment reaches is in the range")
        else:
            bad(name, f"{outside} of {touched} touched blocks fall outside the range")
    scan(facts, "scan_fast", kept=True)
    # a coordinate that is not a number floors to zero, so there is no oversized path to give up on --
    # the half that matters for it is frac_nan, where every block it does land on misses
    scan(facts, "scan_nan", kept=True)
    for name in ("scan_border", "scan_saturated_x", "scan_saturated_all", "scan_infinite"):
        scan(facts, name, kept=False)


def scan(facts, name, kept):
    """The loop the detector is about to run: never past the cap, and it only gives up on a path it cannot drive.

    Multiplied here rather than in Java on purpose -- three saturated spans overflow a long and wrap back under
    the cap, which is exactly the failure this is watching for.
    """
    parts = (facts.get(name) or "").split()
    if len(parts) != 4:
        bad(name, f"{facts.get(name)!r} is not three spans and a verdict")
        return
    blocks = int(parts[0]) * int(parts[1]) * int(parts[2])
    kept_path = parts[3] == "true"
    if blocks > MAX_SWEPT_BLOCKS:
        bad(name, f"{blocks} blocks to scan, past the {MAX_SWEPT_BLOCKS} cap")
    elif kept_path != kept:
        bad(name, f"{blocks} blocks, but it {'kept' if kept_path else 'dropped'} the path and should not have")
    elif kept and blocks < 2:
        bad(name, f"kept the path but collapsed it to {blocks} block(s)")
    else:
        ok(f"{name} -> {blocks} block(s), path {'swept' if kept_path else 'given up on'}")


def drill_legs(facts):
    """The boundaries a tick's path is cut at, whichever end of the packet the recorded points came from."""
    print("\nlegs")
    equals(facts, "legs_plain", "1 0.0 4.0")
    # a build that records before the packet moves the vehicle repeats the start, one that records after repeats the end
    equals(facts, "legs_leading_dup", "3 0.0 1.0 2.0 4.0")
    equals(facts, "legs_trailing_dup", "3 0.0 1.0 2.0 4.0")
    equals(facts, "legs_both_dup", "2 0.0 1.0 4.0")
    equals(facts, "legs_teleport", "1 4.0 4.0")
    equals(facts, "legs_after_clear", "3 0.0 1.0 2.0 4.0")
    equals(facts, "legs_modifiable", "false")
    equals(facts, "legs_progress", "0.0 0.5 1.0")


def drill_clock(facts):
    """The tick clock stretches with the tick, which is the whole reason a millisecond cooldown survives lag."""
    print("\ntick clock")
    spans = {}
    for name, target in (("clock_short", 20), ("clock_long", 80)):
        span, drift = (int(part) for part in facts.get(name, "0 0").split())
        spans[name] = span
        if target * MILLIS <= span <= (target + 25) * MILLIS and abs(drift) <= 1:
            ok(f"{name} -> {span / MILLIS:.1f}ms, halves within {drift}ns")
        else:
            bad(name, f"{span / MILLIS:.1f}ms for a {target}ms tick, halves {drift}ns apart")
    if spans.get("clock_long", 0) > spans.get("clock_short", 1) * 2:
        ok("a longer tick reads as a longer span, so the clock is not counting ticks")
    else:
        bad("clock_scales", f"{spans.get('clock_long')} is not clear of {spans.get('clock_short')}")
    # ticks of 1ms and 12ms in turn: the seam has to hold whether the tick got faster or slower
    equals(facts, "clock_handover", "true true")


def drill_path(facts):
    """The two claims a human used to have to make with a boat: no pad is skipped, and the chord is not the path."""
    print("\npads along a path")
    count, ordered, blocks = facts.get("strip", "0 false ").split(" ", 2)
    crossed = blocks.split(",") if blocks else []
    if int(count) == 20 and ordered == "true" and crossed == [f"{x}:64:0" for x in range(20)]:
        ok("all 20 pads of a strip crossed in one leg, once each, in the order they were driven over")
    else:
        bad("strip", f"{count} pads, ordered={ordered}: {blocks}")
    equals(facts, "turn_legs", "1 true 10:64:0")
    # the same two pads under the straight line from the first boundary to the last: the corner is not on it
    equals(facts, "turn_chord", "1 true 5:64:5")
    whole, shared, percall = facts.get("split_whole"), facts.get("split_shared"), facts.get("split_percall")
    if whole is not None and shared == whole:
        ok(f"a tick split into two move events crosses the same {whole} pads as one")
    else:
        bad("split_shared", f"{shared} pads split across two moves, {whole} in one")
    # the seam blocks sit inside both padded sweeps, so a set per move event pays for them twice
    if percall is not None and whole is not None and int(percall) > int(whole):
        ok(f"a set per move event would have counted {percall}, so the shared one is load-bearing")
    else:
        bad("split_percall", f"{percall} is not above {whole}, so the drill proves nothing")


def main():
    argparse.ArgumentParser(description=__doc__.split("\n")[0]).parse_args()
    facts = probe()
    drill_fractions(facts)
    drill_sweep(facts)
    drill_legs(facts)
    drill_clock(facts)
    drill_path(facts)

    print()
    if failures:
        print(f"{len(failures)} check(s) failed: {', '.join(failures)}")
        return 1
    print("all geometry drills passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
