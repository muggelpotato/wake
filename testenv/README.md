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
- Primary has RCON enabled (`localhost:25575`, password `wake-dev`) so console commands
  can be driven from scripts; 
- Paper2 answers to `docker exec wake-testenv-paper2-1 rcon-cli <cmd>`
- Outage drills: `docker stop wake-testenv-mariadb-1`, change configs (they journal to `run/plugins/wake/outage-journal.jsonl`), `docker start wake-testenv-mariadb-1` and watch the replay within ~5s.

## Notes
- Needs a populated run/ directory for server.properties etc. (run once with sqlite then with mariadb)
- MariaDB data and the paper2 world survive teardown. Removed plugins can linger in the paper2 volume; wipe everything with `docker compose -f testenv/docker-compose.yml down -v`
- If the gradle task is killed hard (Ctrl+C) the finalizer may not run and the stack stays up. The next `runServer` reuses it, or run `./gradlew testEnvDown` manually
- Port 3306 must be free