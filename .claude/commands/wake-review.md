---
description: Review uncommitted working-tree changes against Wake's standards (CLAUDE.md) before committing
allowed-tools: Bash, Read, Grep, Glob
---

Review the current **uncommitted** changes against the project's standards and summarize their
state, so the user can fix issues or commit. Run from the repository root (all commands are
relative; if they fail, `cd` into the Wake repo first). Work only from live command output — don't
assume prior context.

## 1. Gather the working-tree changes

```
git status --short
git diff --stat HEAD
```

The changed/added Java files (tracked + untracked, excluding deletions):

```
git diff --name-only --diff-filter=ACM HEAD -- 'src/main/java/**/*.java'
git ls-files --others --exclude-standard -- 'src/main/java/**/*.java'
```

If there are no changed source files, say so and stop.

## 2. Deterministic layer — ast-grep

Run the project's structural rules against **exactly the changed files** (no whole-tree scan):

```
FILES=$({ git diff --name-only --diff-filter=ACM HEAD -- 'src/main/java/**/*.java'; git ls-files --others --exclude-standard -- 'src/main/java/**/*.java'; } | sort -u) && npx --yes --package @ast-grep/cli ast-grep scan -c sgconfig.yml $FILES
```

Read the human-readable output directly — it gives rule-id, severity, and `file:line`.
`error` findings are blocking; `warning`s are should-fix. These are violations, not judgment calls.
(If `npx` is offline, say the deterministic layer was skipped and continue.)

## 3. Compile check

```
./gradlew compileJava -q 2>&1 | tail -20
```

A compile failure outranks every stylistic note.

## 4. Judgment layer — read the diff against CLAUDE.md

`Read` CLAUDE.md — it is the source of truth. Then read each changed file and check it against the
principles there, focusing on what ast-grep can't see: the ones requiring understanding, not
pattern-matching. In particular:

- **Modular boundaries** — cross-module reach into internals; a service cached instead of resolved
  defensively; coupling outside an integration seam.
- **Clean lifecycle** — is everything registered on enable reversed on disable? Could the module be
  toggled off and on without leaks or duplicates?
- **Threading & memory** — Bukkit calls off the main thread; non-concurrent state on packet-thread
  paths; a cache keyed by Player/Entity, or missing its eviction.
- **Localization** — new player-facing text going through the message system with a language-file
  key; untrusted input inserted safely.
- **OBU** — physics/vector math added to that module; a shared context mutated for one player.
- **Zero bloat** — dead code, speculative fallbacks, a class doing two jobs, duplication.

Don't restate CLAUDE.md's rules from memory — read the current file, since it evolves.

## 5. New-rule suggestion

If you find a **mechanical, syntax-matchable** violation that no current ast-grep rule catches and
that reflects a standing CLAUDE.md principle (not a one-off), propose a rule for `.ast-grep/rules/`
in one line (id + the pattern it would match). Don't create it — just surface it for approval. This
is how the deterministic layer grows.

## 6. Report

Lead with a one-line verdict: **ready to commit** or **N issues**. Then:

- **Blocking** — compile failures, ast-grep `error`s, boundary/lifecycle/threading violations.
- **Should fix** — ast-grep `warning`s, localization gaps, bloat.
- **Suggested rule** — if any, from step 5.

Be concrete: `file:line`, what's wrong, the fix. Don't restate what's already correct. If nothing
is wrong, say the change conforms and is ready to commit, in one line.
