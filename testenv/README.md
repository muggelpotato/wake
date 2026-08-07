Fully automatic prod test env: when `run/plugins/wake/config.yml` has `database.type: mariadb`

| Service  | Address           | Purpose                                                                |
|----------|-------------------|------------------------------------------------------------------------|
| velocity | `localhost:25577` | **Join here.**                                                         |
| primary  | `localhost:25565` | `runServer` instance on the host (proxy-only while mariadb mode is on) |
| paper2   | `localhost:25566` | Dockerized clone backend, mirrors primary                              |
| mariadb  | `localhost:3306`  | Shared database (`wake` / root / password)                             |
| valkey   | `localhost:6379`  | Pub-sub bus for cross-server cache sync                                |

Switch backends in-game with `/server backend2` and `/server primary`

## Headless testing
- **`python testenv/drills_obu.py --encoding`** the bytes `features/obu/protocol` writes, judged against the ids, framing and vanilla defaults in `OBUSOURCE/OpenBoatUtils` — a probe compiled into the package prints each packet as hex (no server; needs `./gradlew compileJava` first). Without `--encoding` it also runs the command surface: every argument type parses, refuses, stores and displays, and a value the wire cannot carry never reaches the database
- **`python testenv/drills_delivery.py`** what a boat is actually running: the pin in its container and the spelling it lands in, one written behind Wake's back, temporary overrides added, cleared, reset and replaced by a context, an impulse that is delivered but never stored, eviction when the boat leaves and when its chunk unloads under it (both drilled by giving the replacement the same UUID), a module cycle, the sweep over pinned boats behind a context delete, a sandbox publish and the name collision it refuses, a reload and an import, the protected contexts and the listing they appear in, what an empty store seeds, the names the import door has to refuse, every spelling of the keep window and a real purge sweep (either backend)
- **`python testenv/drills_geometry.py`** the swept collision math at the root of `core/`: the segment-vs-box test, the block range it scans, the legs a tick's path is cut into, the tick clock (no server — compiles a probe against `build/classes`, so `./gradlew compileJava` first)
- **`python testenv/drills.py`** operational drills against primary. Add `--sync` for the cross-server drill (needs mariadb mode)
- **`python testenv/drills_database.py`** the `/wake database` admin surface and the outage journal's rough edges (either backend)
- **`python testenv/drills_core.py`** the core module's own commands: the switches and where they land, the boats `/wake killemptyboats` takes and the one it leaves alone (a mob aboard), the boat the auto-kill switch takes when a rider steps out and the one it waits on, the order a reload answers in (either backend)
- **`python testenv/drills_drydock.py`** the drydock commands: what `/dd boostpad add` stores and hands back, adding a block that already has a pad, the order the listing comes out in, a row stored under a key no command would have written, the early-out switches and where they land, and the pad flag on the export round trip (either backend)
- **`python testenv/drills_module.py`** the module lifecycle: config toggles, the module no toggle reaches, repeated off/on cycles, the hot listener a feature claims from the framework, an enable that throws, seeding (including the decision an outage postpones), the state prefix an export sweeps, the service seam (either backend)
- **`python testenv/drills_text.py`** the language file: every key the code asks for and every key the file carries, the palette and placeholders its header documents, and what a reload does to a file that is edited, broken or missing (either backend; the first half needs no server)
- **`python testenv/drills_changelog.py`** cross-server cache propagation over the sync bus: both directions, concurrent writes, bursts, a bus that is down at boot or killed mid-session, unusable sync settings (needs mariadb mode)
- Primary has RCON enabled (`localhost:25575`, password `wake-dev`) so console commands
  can be driven from scripts; 
- Paper2 answers to `docker exec wake-testenv-paper2-1 rcon-cli <cmd>`
- Outage drills: `docker stop wake-testenv-mariadb-1`, change configs (they journal to `run/plugins/wake/outage-journal.jsonl`), `docker start wake-testenv-mariadb-1` and watch the replay within ~5s.

## Notes
- Needs a populated run/ directory for server.properties etc. (run once with sqlite then with mariadb)
- MariaDB data and the paper2 world survive teardown. Removed plugins can linger in the paper2 volume; wipe everything with `docker compose -f testenv/docker-compose.yml down -v`
- If the gradle task is killed hard (Ctrl+C) the finalizer may not run and the stack stays up. The next `runServer` reuses it, or run `./gradlew testEnvDown` manually
- Port 3306 must be free