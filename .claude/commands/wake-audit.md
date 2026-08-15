---
description: Deep audit of a diff and everything it touches — shrink it, fix it, drill it live, then report whether it is commitable
argument-hint: "[package to audit instead, e.g. @src/main/java/dev/muggel/wake/core/text; defaults to the uncommitted diff]"
allowed-tools: Bash, Read, Edit, Write, Grep, Glob, ToolSearch, mcp__ide__getDiagnostics, mcp__idea__get_file_problems, mcp__idea__lint_files
---

Audit **and fix what you find**. This is not a read-only review — editing the code *and its call
sites* is the job. Run from the repository root. Work only from live command output; assume no prior
context and verify every claim a memory or comment makes against the current code.

Never commit, never `git add`, never push. The working tree is the deliverable — including work an
earlier audit or an unfinished session left in it. That is input, not noise: never revert, stash or
reset it, fix forward from it.

## Target — `$ARGUMENTS`

**Empty (the default): the uncommitted working tree against `HEAD`**, plus every call site that diff
affects.

**Otherwise it names a package to audit instead, and is read literally.** A path
(`@src/main/java/dev/muggel/wake/core/text`), with any qualifier honoured exactly as written
(`features/obu/commands without subpackages`). Read every file in it. Extend past it only where the
package forces you: the direct implementations when it is abstract core classes, and any call site
your fixes make wrong or misleading. A neighbouring package that merely looked interesting is a
follow-up, not scope. A commit range works here too.

Both modes have the same deliverable and the same bar. Package mode simply *creates* the diff the
default mode would audit — and that package is already-reviewed code, so a hunk not pointed at a
defect is a hunk that shouldn't exist.

## Already in context

Gathered before this prompt ran — don't re-run these for the default target:

- Working tree: !`git status --short`
- Size: !`git diff HEAD --shortstat`
- Untracked: !`git ls-files --others --exclude-standard`
- Structural rules over the changed files: !`FILES=$({ git diff --name-only --diff-filter=ACM HEAD -- 'src/main/java/**/*.java' 2>/dev/null; git ls-files --others --exclude-standard -- 'src/main/java/**/*.java'; } | sort -u); if [ -n "$FILES" ]; then ast-grep scan -c sgconfig.yml $FILES; echo "-- scanned $(echo "$FILES" | wc -l) files"; else echo "(no changed java files)"; fi`
- Compile: !`./gradlew compileJava -q 2>&1 | tail -20`

If `$ARGUMENTS` names a different target — a commit range, a package, a path — the block above
describes the working tree instead, so re-run the equivalents against the target you were given.

## The bar

`HEAD` is already reviewed line by line and is trusted. This audit must end with a diff that is
**smaller and simpler than the one it started with** — in package mode, the smallest diff that fixes
what you actually found. Every hunk is surface someone has to review again and a place an edge case
can hide, so the goal is fewer hunks, not better-worded ones.

- **Say what the diff buys**, in one line, before judging any of it: the capability or fix it adds
  that `HEAD` does not have — in package mode, what the package does wrong today that it won't
  after. Every later decision measures against that line. A hunk that doesn't serve it is a deletion;
  if no honest line can be written, the finding is that the change isn't worth its review cost, and
  that belongs in the verdict rather than in more edits.
- A hunk survives only if removing it breaks something. "More explicit", "while I'm here",
  "might as well" are deletions.
- Reviewed code is not touched without a defect to point at. Call sites are the exception: where
  the diff made a caller wrong, misleading, or redundant, fix the caller.
- Prefer the change that removes a concept. A new class, helper, key, flag, config option or file
  needs a reason no existing one already covers — otherwise fold it into what exists.
- `build.gradle.kts`, `testenv/` and the docs are audited at exactly this bar too. A change there
  costs the same maintenance as one in `src/`, and it is the one people forget to review.

## 1. Scope: the target and its blast radius

Record the `--shortstat` line above; the report compares against it (package mode starts from zero).
In the default mode, if nothing changed, say so and stop; otherwise read the whole diff
(`git diff HEAD`) before judging any part of it. In package mode, read every file in the package
first — all of it, before touching any of it — since a defect in one file is usually a decision made
in another.

### Running again in the same session

The first run in a session reads everything. A later one must not: re-reading what the previous run
already cleared is where an audit spends its budget without buying anything, and it crowds out the
part that is actually unreviewed. Your own earlier report in this conversation is the record of what
was audited and what it concluded — use it.

- **The work since the last run is the target.** Hunks the previous run read and passed, that nothing
  has touched since, get a skim to confirm they are unchanged, not a fresh judgment pass. Say in the
  verdict which parts you carried forward on the earlier run's authority rather than re-derived.
- **What changed is read cold**, at full depth — including your own fixes from the last run, which
  §5 already treats as unreviewed code.
- **A conclusion is only as good as what it rested on.** Before carrying one forward, ask what would
  have to change to invalidate it: a new caller, a moved semantic, a constant the new work reads. If
  the new work touches that, the old conclusion is void and gets re-derived, however clean it looked.
- **A claim the last run made is not evidence.** "Verified" in an earlier report means a check ran
  then, not that it holds now — re-run the scan, the compile and the drills every time, because they
  are cheap and they are the only thing that carries forward for free.

Never let this turn into a smaller bar. It narrows *where* you look, never *how hard*.

Then build the **affected surface** — the half a file-by-file review misses. In package mode this is
what the package publishes and who consumes it; in the default mode, what the diff moved:

- Signatures added, removed or changed → grep every caller.
- **Semantics changed with the signature intact** — a return that can now be null, a method that
  now assumes the main thread, a key whose spelling moved, an ordering guarantee dropped. These
  have no compile error to find them; find them by hand.
- Language-file keys, state-prefix keys, config keys, protocol ids the target touches.
- Anything now *obsolete*: the old path still called, the branch nothing reaches, the helper with
  one caller left, the lang key nothing asks for.
- Everything outside `src/` in reach — gradle tasks, drills, README rows, ast-grep rules.

## 2. Deterministic layer

The scan and the compile are above; extend the scan to the **call-site files** step 1 turned up, and
in package mode to the package itself:

```
ast-grep scan -c sgconfig.yml <files>
```

`error` findings are blocking, `warning`s are should-fix. Then pull IntelliJ's own analysis for each
touched file (`mcp__ide__getDiagnostics`, or `mcp__idea__get_file_problems` /
`mcp__idea__lint_files`) and clear what it reports — unused symbols, redundant casts, nullability
contradictions, unreachable branches. A warning you decide to keep needs a reason in the report, not
silence. If the diff touches an API newer than the floor, `./gradlew checkVersions --continue`.

### Growing the deterministic layer

When a defect you fixed is **mechanical and syntax-matchable** and reflects a standing CLAUDE.md
principle rather than a one-off, write the rule — don't propose it. A rule is cheap to delete and
expensive to never have written: it is the only thing that stops the same defect returning in a
diff nobody audits.

Read two or three existing rules in `.ast-grep/rules/` first and match their shape exactly — `id`
(matching the filename, `no-<thing>` or `<condition>-<thing>`), `language: Java`, `severity`
(`error` for an architecture or safety violation, `warning` for form), a one-line `message` naming
the fix, and a `note` citing the CLAUDE.md section and *why*. Then prove it both ways:

```
ast-grep scan -c sgconfig.yml --filter '^<id>$'
```

- It must fire on the broken code — check out the pre-fix version of the hunk, or paste it into a
  scratch file outside the repo, and see the rule hit.
- It must be **silent across the whole tree** as it now stands. A rule with pre-existing hits is
  either wrong or a much larger cleanup than this audit; delete it and make it a follow-up instead.

A rule that can't be made precise is a rule that doesn't get written — a false positive costs
every future diff. Report each rule added in one line so it's easy to delete.

## 3. Judgment pass

Read CLAUDE.md now — it is the source of truth and it evolves; don't recite it from memory.

**Judge the approach, not only its execution.** That the code compiles, passes and reads well is
evidence it was written carefully, never evidence it should exist in that shape. Take nothing as
settled because it is already written: for every concept the target introduces — a class, a helper, a
key, a cache, a parameter, a seam — the question is not "is this done correctly" but "should this be
here at all, and is this where it belongs". A change can be flawless and still be the wrong change.

Prior art is searched for, never recalled. Before accepting any new helper, parser, cache, formatter,
message key or registration path, go and look: grep the framework, `CommandHelper` and the module's
own helpers, the DAOs, the existing keys, the argument types. Name the thing it should have reused, or
state that you looked and there is nothing. "I don't remember one" is not an answer, and neither is a
mechanism that already exists two packages away under a different name. A custom implementation of
something the project already owns is a finding, not a style note — it is maintenance nobody asked for.

Where the design is wrong but the code works, that is still a finding — it goes under *Concepts* or
*Follow-ups* with what it should have been. Reporting it costs nothing and is not the same as
rewriting reviewed code on a hunch; the bar on *editing* is unchanged.

Then read each file in scope whole (not just the hunks) and judge it on:

- **Necessity.** Per hunk: does this need to exist? Can two hunks become one? Can it live inside a
  file the diff already touches instead of a new one? The cheapest fix for a finding is deletion.
- **Edge cases.** Enumerate every state each changed entry point can be entered in — empty, absent,
  concurrent, disabled, mid-reload, second server, database down, module gone, client gone, chunk
  unloaded, sender is console, value came from an import instead of an argument type. Mark each
  *impossible by construction* / *handled* / *broken*. Hunt the ambiguous ones: two spellings of one
  key, a default that means both "unset" and "zero", an ordering only one caller happens to respect.
  Prefer removing an edge case (make the state unrepresentable) over handling it.
- **Robustness & reliability.** What happens when the thing this code depends on isn't there? A
  failed write is journaled, not dropped; a failed read leaves the cache alone; a bad row costs that
  row and nothing else. No exception reaches a player.
- **Performance & scalability.** Hot paths (packet, tick, movement) early-out cheaply and allocate
  nothing. Cost must not grow with players, boats, contexts or rows where it doesn't have to. A
  listener that only matters when a feature is in use registers only then. No polling.
- **Form factor.** Compact and readable. A class doing two jobs splits.
- **Bloat.** Dead code, speculative fallbacks, migration shims, a guard for a case that can't
  happen, a comment restating the line below it.
- **Architecture.** The CLAUDE.md rules, and beyond them: is the seam in the right place at all?
  Does the module boundary this change implies still make sense, or is it coupling wearing an
  `integration/` label?
- **Annotations.** Nullness correctness first, convention second — read the neighbouring files for
  the current jspecify style rather than assuming. An annotation that is wrong is worse than absent.
- **Best practice.** Modern Java 21 idiom where it shortens the code, and the house idiom over any
  idiom that doesn't.

## 4. Fix

Edit in place, minimal and targeted. Never reformat or rewrite surrounding code, never delete an
existing rationale comment. New comments are one-liners, only where the code can't show the
constraint — no prose blocks, no restating what the signature says. Match the terse style of the
file you're in; code that reads as generated gets rejected on sight.

## 5. Converge — audit your own fixes

The edits you just made are unreviewed code, written by the only person who read the diff, after
they had read it. That is precisely the state `HEAD` is *not* in — and it is the whole reason a
second audit run finds new things. Not carelessness: a pass structurally cannot see its own output.
So run the second pass here, now, instead of leaving it for the user.

Go back to §1–§3 and read the **current** diff whole, as if someone else had written it and you had
no memory of why. Re-derive the blast radius (your fixes may have moved it), re-enumerate the edge
cases at each changed entry point, re-run the scan and the compile. Repeat until a sweep produces no
edit.

What counts as a sweep finding something is **a defect** — a state that behaves wrong, a caller left
inconsistent, a hunk that turns out to buy nothing. Rewording, resequencing, a name you now prefer,
a comment you'd phrase differently: those are a sweep finding *nothing*. Revert them and stop. A loop
that terminates on taste never terminates, and every no-defect edit is fresh risk bought with no
return — which is the exact failure this step exists to prevent.

Two sweeps is normal. If a third still turns up real defects, do it. If a fourth does, stop editing
and say so in the verdict: something about this change resists being made correct — a seam in the
wrong place, a state that shouldn't be representable — and naming that is worth more to the user
than another round of patches.

Record per sweep: how many real defects it found, and the `--shortstat` after it. Those numbers are
the convergence evidence the verdict rests on; without them "no edge cases left" is just a claim.

## 6. Drills — against a live server

Read `testenv/README.md` for the current roster. Pick the drills whose surface the target touches,
**add cases for the logic it introduced** — in package mode, for the package's behaviour, not merely
for the lines you edited — and run them against a running server
(`./gradlew runServer`; RCON on `localhost:25575`, password `wake-dev`). Drive the interesting
states, not the happy path — the edge cases you marked *handled* in §3 are exactly the list. A drill
that fails sends you back to §5: fix, then sweep again.

The drill code is held to the bar above:

- New cases go into the existing `drills_*.py` whose surface they belong to, reusing that file's
  helpers and idiom. A new drill file needs a surface no existing file covers.
- Never add a Gradle task, and never add a check to `build.gradle.kts` — a check is an ast-grep
  rule or a drill. Touch `build.gradle.kts` only when the diff itself changed the build.
- Judge a case by the state it lands in (row, packet bytes, log line), never by the reply text.
- A README row changes only if a drill's surface actually changed.

A passing drill is one line in the report; a **failing** one pastes its verbatim output, because a
failure claimed without evidence is not a result. A drill you skipped is reported as skipped, with
the reason.

## 7. TESTPLAN

Every path this change introduces must end up covered by a drill or by a TESTPLAN step — not
neither, and not both. `TESTPLAN.md` holds only what a human in the world can check and a script
cannot: a client computing physics, a component clicked, a second player, a boat moving. Anything
you just proved headlessly comes **out** of TESTPLAN, so the user never re-runs a test you already
ran. Anything the diff added that a drill can't reach goes **in**, written against behaviour rather
than wording.

## 8. CLAUDE.md

If the change establishes, changes or violates a *standing* principle, update CLAUDE.md. It is
durable principles and their reasons — never an API reference, never a changelog. Most audits touch
nothing here; say so in one line rather than inventing an edit.

## 9. Report

Write for someone who did not watch this run and wants, in thirty seconds, to know what moved and
whether they need to look again. No preamble, no restating the task, no section that exists only to
be complete: **a section with nothing in it is dropped, not filled**. The audit's own working notes
— the edge-case enumeration, the states that were fine, the files that were clean — stay out.

Lead with the verdict on its own line, bolded: `--shortstat` before → after, and one of

- **ready to commit** — nothing left to decide,
- **re-read `<files>`** — the audit rewrote enough that the diff deserves fresh eyes first,
- **blocked: `<reason>`** — something broken the audit could not fix alone.

A state you could not settle is never silent: it either makes the verdict **blocked** or gets a line
under *Know this*. "No edge cases left" has to be something the report actually claims.

Then **one line of convergence evidence**, because it is the question the user would otherwise have
to ask: how many sweeps ran, what each found, and — plainly — whether another audit would find
anything. `sweeps: 3 defects → 1 → 0; another pass would microoptimise` is the shape. Where it
wouldn't be microoptimisation, say what a further pass should go after and why this one couldn't.
Never report a convergence you didn't reach; an honest "still finding things at sweep 4" is worth
far more than a clean number. On a repeat run in the same session, name in the same line what you
read cold and what you carried forward from the earlier run untouched — a reader has to be able to
see which half of the diff this run actually judged.

Follow that with **one sentence** saying what the change does as it now stands — the line from *the
bar*, orientation before findings, so the rest reads against something. Say plainly if the diff was
already sound.

**One claim per line, in every section below.** Where an entry would carry several — three things
aligned, four states checked, two reasons it was left — break them into a **second level of bullets**
under it rather than chaining them into one sentence with commas and semicolons. The parent line names
what they have in common and nothing else; each child is one claim, scannable on its own:

```
- **Fixed**
  - `file:line` — what was broken → what it does now
  - `file:line` — what was broken → what it does now
```

A reader looking for the one item that concerns them must never have to read a sentence to find it.
Where an entry genuinely is one claim, it stays one line — nesting a single child is noise.

Then, only where there is something to say:

- **Fixed** — one line per real defect: `file:line`, what was broken, what the state it produced
  was, what it does now. Ordered by how much it matters, never by file. A change that only reads
  better does not get a line.
- **Know this** — what changes how the rest of the tree should be read: a semantic that moved
  without its signature, a caller that now behaves differently, a key that was renamed, a warning
  kept on purpose. Never something the diff already shows on its face.
- **Concepts** — only when a seam, module boundary, published surface or standing principle
  actually moved: was → is, one line each, plus the CLAUDE.md edit if there was one. One line
  saying nothing moved is the normal answer.
- **Verified** — one line per drill (name, cases, pass/fail); verbatim output only for failures.
  Then TESTPLAN steps added or removed, one line each, and any drill skipped with its reason. Each
  ast-grep rule written gets one line too: id, what it catches, and that the tree is clean under it.
- **Left alone** — findings deliberately not acted on, one line each with the reason.
- **Follow-ups** — numbered, out of scope, enough `file:line` to act on later.

Close with one line of tree state: nothing committed, plus anything left running.
