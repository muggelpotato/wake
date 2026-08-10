---
description: Deep audit of a diff and everything it touches — shrink it, fix it, drill it live, report
argument-hint: "[commit range or path; defaults to uncommitted vs HEAD]"
allowed-tools: Bash, Read, Edit, Write, Grep, Glob, ToolSearch, mcp__ide__getDiagnostics
---

Audit the change described by `$ARGUMENTS` (default: the uncommitted working tree against `HEAD`)
and **fix what you find**. This is not a read-only review — editing the diff *and its call sites*
is the job. Run from the repository root. Work only from live command output; assume no prior
context and verify every claim a memory or comment makes against the current code.

Never commit, never `git add`, never push. The working tree is the deliverable.

## The bar

`HEAD` is already reviewed line by line and is trusted. This audit must end with a diff that is
**smaller and simpler than the one it started with**. Every hunk is surface someone has to review
again and a place an edge case can hide, so the goal is fewer hunks, not better-worded ones.

- A hunk survives only if removing it breaks something. "More explicit", "while I'm here",
  "might as well" are deletions.
- Reviewed code is not touched without a defect to point at. Call sites are the exception: where
  the diff made a caller wrong, misleading, or redundant, fix the caller.
- Prefer the change that removes a concept. A new class, helper, key, flag, config option or file
  needs a reason no existing one already covers — otherwise fold it into what exists.
- `build.gradle.kts`, `testenv/` and the docs are audited at exactly this bar too. A change there
  costs the same maintenance as one in `src/`, and it is the one people forget to review.

## 1. Scope: the diff and its blast radius

```
git status --short
git diff HEAD --shortstat
git diff HEAD
git ls-files --others --exclude-standard
```

Record the `--shortstat` line; the report compares against it. Read the whole diff before judging
any part of it. Then build the **affected surface** — the half a diff-only review misses:

- Signatures added, removed or changed → grep every caller.
- **Semantics changed with the signature intact** — a return that can now be null, a method that
  now assumes the main thread, a key whose spelling moved, an ordering guarantee dropped. These
  have no compile error to find them; find them by hand.
- Language-file keys, state-prefix keys, config keys, protocol ids touched by the diff.
- Anything the diff made *obsolete*: the old path still called, the branch nothing reaches now,
  the helper with one caller left, the lang key nothing asks for.
- Everything outside `src/` the diff carries — gradle tasks, drills, README rows, ast-grep rules.

## 2. Deterministic layer

Changed files **plus the call-site files** from step 1:

```
FILES=$({ git diff --name-only --diff-filter=ACM HEAD -- 'src/main/java/**/*.java'; git ls-files --others --exclude-standard -- 'src/main/java/**/*.java'; } | sort -u) && npx --yes --package @ast-grep/cli ast-grep scan -c sgconfig.yml $FILES
./gradlew compileJava -q 2>&1 | tail -20
```

`error` findings are blocking, `warning`s are should-fix. Then pull IntelliJ's own analysis for
each touched file (`mcp__ide__getDiagnostics`, or `mcp__idea__get_file_problems` /
`mcp__idea__lint_files`) and clear what it reports — unused symbols, redundant casts, nullability
contradictions, unreachable branches. A warning you decide to keep needs a reason in the report,
not silence. If the diff touches an API newer than the floor, `./gradlew checkVersions --continue`.

## 3. Judgment pass

Read CLAUDE.md now — it is the source of truth and it evolves; don't recite it from memory. Then
read each changed file whole (not just the hunks) and judge it on:

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
- **Reuse over reimplementation.** Before any new helper, parser, cache, formatter or registration
  path: does the framework, an existing `CommandHelper`, a DAO or an existing message key already do
  it? A custom implementation of something the project already owns is a finding, not a style note —
  it is maintenance the project didn't ask for.
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

Re-run step 2 after fixing, and `git diff HEAD --shortstat` again.

## 5. Drills — against a live server

Read `testenv/README.md` for the current roster. Pick the drills whose surface this diff touches,
**add cases for the logic the diff introduced**, and run them against a running server
(`./gradlew runServer`; RCON on `localhost:25575`, password `wake-dev`). Drive the interesting
states, not the happy path — the edge cases you marked *handled* in step 3 are exactly the list.

The drill code is held to the bar above:

- New cases go into the existing `drills_*.py` whose surface they belong to, reusing that file's
  helpers and idiom. A new drill file needs a surface no existing file covers.
- Never add a Gradle task, and never add a check to `build.gradle.kts` — a check is an ast-grep
  rule or a drill. Touch `build.gradle.kts` only when the diff itself changed the build.
- Judge a case by the state it lands in (row, packet bytes, log line), never by the reply text.
- A README row changes only if a drill's surface actually changed.

Paste the **verbatim** pass/fail output. "Everything passed" without output is not a result. A
drill you skipped is reported as skipped, with the reason.

## 6. TESTPLAN

Every path this change introduces must end up covered by a drill or by a TESTPLAN step — not
neither, and not both. `TESTPLAN.md` holds only what a human in the world can check and a script
cannot: a client computing physics, a component clicked, a second player, a boat moving. Anything
you just proved headlessly comes **out** of TESTPLAN, so the user never re-runs a test you already
ran. Anything the diff added that a drill can't reach goes **in**, written against behaviour rather
than wording.

## 7. CLAUDE.md

If the change establishes, changes or violates a *standing* principle, update CLAUDE.md. It is
durable principles and their reasons — never an API reference, never a changelog. Most audits touch
nothing here; say so in one line rather than inventing an edit.

## 8. Report

Terse and compact. No preamble, no restating the task. An empty section is one line, not a
paragraph.

1. **One-line verdict**, bolded: what was audited, the count and kind of real defects, and the
   `--shortstat` before → after. Say plainly if it was already sound.
2. **Changed** — grouped by concern (Necessity / Robustness / Edge case / Scalability / Reuse /
   Bloat / Text), each entry anchored to `file:line`, one line: what was wrong and why this fix.
   Name the drill or path that proves the broken state was reachable.
3. **Edge cases** — two-column table, one row per state, marked *impossible by construction* /
   *handled* / *was wrong, now fixed*. Keep the impossible-by-construction rows.
4. **Drills** — what was added and run, then the verbatim output. Then the TESTPLAN steps removed
   and added, one line each.
5. **Left alone** — findings deliberately not acted on, one line each with the reason (cost, blast
   radius, matches house idiom, would grow the diff).
6. **Follow-ups** — numbered, out-of-scope, with enough `file:line` to act on later.
7. Tree state: nothing committed, plus any background process left running.
