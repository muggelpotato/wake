# Wake — Boatracing Framework (Paper)

An all-in-one boat-racing engine: ships as one jar, but every feature is an isolated module an
admin can toggle on or off. Java 21 · Gradle KTS · Paper · PacketEvents · Aikar IDB (SQLite/MariaDB).

Build: `./gradlew compileJava` (run often) · `build` · `runServer`

## How to work here

This document is a set of **durable principles**, not an API reference. It states *what* to uphold
and *why*; the code is the source of truth for *how* the current APIs look — read the relevant
module before writing, and match its established idiom. Where existing code contradicts a principle
below, the principle wins: fix it if you're already in that file, never propagate the pattern.

Good code here is compact, reactive, and deletable. Every feature is a module an admin can switch
off and a developer can remove without unpicking the rest. Prefer the change that removes a concept
over the one that adds one. When a rule and a shortcut conflict, take the rule — this codebase is
optimised for the next person reading it, not the current diff.

Modules today: `base` (plugin chrome + shared commands), `obu` (OpenBoatUtils integration),
`drydock` (track utilities), `axiom` (optional AxiomPaper integration). Future: races, gui.

## Architecture — non-negotiable

1. **Modular boundaries.** A module never reaches into another module's internals. Each module
   publishes a small surface (its `api/` package) and keeps everything else private. Modules
   communicate two ways only: **look up another module's published service and tolerate its
   absence** (any module may be disabled at runtime, so resolve defensively and no-op when it's
   gone — never cache the reference), and **fire/observe events** for actions. Any unavoidable
   cross-module coupling is quarantined to a single integration seam and stays one-directional:
   the provider never learns the consumer exists.
2. **Clean lifecycle.** A module must fully reverse itself: anything registered on enable is undone
   on disable, so it can be toggled off and back on without leaks or duplicates. Use the framework's
   registration helpers so teardown is automatic rather than hand-rolled.
3. **Optional integrations.** Every third-party plugin or mod is optional. Gate on a runtime
   capability check and reach it by reflection — never a hard import or hard dependency. Wake must
   start and run correctly with none of them present.
4. **No NMS.** Paper API only; manipulate packets exclusively through PacketEvents. Prefer robust,
   version-portable approaches over clever ones — cross-version support (1.21→1.26+) outranks
   elegance.
5. **Event-driven, never poll.** Compute state reactively when it changes, not on a repeating
   timer. Hot events must early-out cheaply; a listener that only matters when its feature is in
   use should register only then. Low-frequency maintenance tasks are fine.
6. **Zero bloat.** No migration shims, compatibility layers, speculative fallbacks, or god classes.
   Reuse before adding; delete rather than deprecate. Add defensive handling only where it buys real
   stability. If a class does two jobs, split it; if a guard covers a case that can't happen, remove
   it.
7. **Package layout.** Three fixed words mean the same thing everywhere and nothing else does:
   `api/` (what other modules may reference — nothing outside it is cross-module public), `commands/`
   (the command framework's contract), `integration/` (a quarantined seam to something that may not
   be there, another module or a third-party plugin). Every other package is named for what it is
   *about*, never for what kind of thing it holds — `util`, `model`, `service`, `listeners`, `impl`
   and `helpers` are not package names. A subject package needs two or more files; one file is never
   a package, it sits at the module root. The three fixed words are exempt — a boundary is worth
   signposting even at one file. A module class is lifecycle wiring only: if it holds logic, the
   logic moves out.

## Commands

Register commands through the project's command framework, never Bukkit `CommandExecutor`.
Permissions are derived from command structure automatically — don't declare them in `plugin.yml`
or hand-write permission strings. Command handlers report failure to the user as a localized
message, never an exception.

File layout and helper conventions live in `core/commands/package-info.java` and are non-negotiable:
one class per command node (`getNode` builds the tree, private methods hold the logic); sub-commands
that would form a god-class grouped in their own package; shared helpers factored into `CommandHelper`
(core), `<Module>CommandHelper` (module-wide), and `<Group>CommandHelper` (group-only).

## Player-facing text — always localized

All text shown to a player goes through the message system with a key in the language file — never
hardcode strings or build chat components ad hoc. Use the project's semantic colour tags, never raw
hex, so the palette stays one edit. Treat any player-supplied value as untrusted: insert it in a way
that can't inject markup. Speak database terms to admins ("saved to database", not "config.yml").

## Data & persistence

- **`config.yml` is boot/admin-only** (which modules are on, DB connection). Never store
  runtime-mutable state there; keep it flat enough for admins to hand-edit.
- **Runtime state and module data go through the persistence layer**, not files. Writes happen
  asynchronously with the in-memory cache updated immediately, so gameplay never waits on I/O; reads
  are served from cache. Never issue raw database calls from feature/module code — that belongs in a
  data-access object.
- **SQL must be parameterized and portable** across the supported databases (SQLite and MariaDB) —
  never concatenate values into a query, never rely on one dialect's types or functions.
- Ship factory defaults as bundled resources, applied only when the store is empty.

## Threading & memory — hard rules

- **Never touch the Bukkit API from an async task.** Do off-thread work asynchronously, then hop
  back to the main thread to apply anything that touches the server.
- **Never cache `Player` or `Entity`** — key by `UUID` and evict on the lifecycle events that end
  their relevance (quit, vehicle/entity removal). Every insertion needs a matching removal.
- Any state reachable from network (PacketEvents) threads must be concurrency-safe.

## OBU (OpenBoatUtils integration)

OpenBoatUtils is a client-side boat-physics mod; Wake is its server half. **The client computes
physics — the server only resolves configuration and ships it over plugin-message channels. Never
write physics or vector math in this module.** (Server-side math is fine elsewhere — a future races
module will need it.)

- Settings live in named, persisted **contexts**; a **sandbox** is a personal, ownership-scoped,
  disposable context. Enforce ownership on every sandbox operation.
- **Never mutate a shared context to represent one player.** Compose a per-player view and deliver
  it through the reserved per-player channel, leaving stored contexts untouched.
- Adding a setting should be a minimal, localized change to the protocol definition — if it forces
  edits across many files, the abstraction is leaking.

## Style

- **Never inline fully-qualified class names** — always a top-level import, referred to by simple
  name. Fix opportunistically in files you already edit; no unrequested sweeps.
- Use the project's nullness annotations. Prefer records with defensive copies for immutable data.
- Comment only where the code can't show the constraint; never delete existing rationale comments
  during a refactor.
- Keep edits minimal and targeted — never reformat or rewrite surrounding code.
