<div align="center">
<img src="https://raw.githubusercontent.com/muggelpotato/drydock-modpack/main/pack/icon.png" alt="Wake logo" width="10%" height="10%">

# Wake
</div>

> [!NOTE]
> See [AI Usage](#ai-usage)
## Wake
A lightweight and highly configurable framework for Minecraft boatracing
- Supports: **Paper `1.21.x`, `26.x`**

<p>
  <a href="https://github.com/muggelpotato/wake/releases"><img src="https://img.shields.io/badge/Github-Download-181717?logo=github&logoColor=white" width="155" alt="GitHub Download" /></a>
  <a href="https://modrinth.com/project/wake"><img src="https://img.shields.io/badge/Modrinth-Download-00AF5C?logo=modrinth&logoColor=00AF5C" width="169" alt="Modrinth Download" /></a>
</p>

## Feature Modules
Wake is a framework with isolated modules that can be toggled off

### Core Module
- Adds administration and utility commands
- The only module that cannot be toggled off
- see the [docs](#documentation)

### OBU Module
- Adds commands to configure boat physics via an [OpenBoatUtils](https://github.com/OpenBoatUtils/OpenBoatUtils) client
- Adds a custom command interface and features that OBU doesn't provide natively
  - Saving/loading preconfigured boat modes (contexts)
  - Immutable contexts you can layer and add temporary overrides to
  - Entity contexts (per boat settings) are easier to use via commands and sync to other players
  - Sandboxes that provide isolated environments to build contexts instead of using loose command block chains
  - A dashboard showing applied settings/contexts/overrides of boats and players
  - Listing default values for OBU settings
  - Clearing specific settings in your context rather than resetting or writing the default value
  - Keeping contexts across relogs and fixing boat lag on collisions (configurable)
  - etc.
- Supports OBU `0.5.0+`
- see the [docs](#documentation)

### Drydock Module
- Adds content for the Drydock boatracing server
- see the [docs](#documentation)

### Axiom Module
- Lets admins register namespaced item models in the create item display menu of Axiom
- see the [docs](#documentation)

## Showcase
<div align="center">
  <img src="assets/wakeshowcase/cover.png" alt="Wake" width="100%">
</div>

<details>
<summary><b>OBU context layering</b></summary>

**The status dashboard for OBU settings and contexts**
<p align="center">
  <img src="assets/wakeshowcase/1/obustatus.png" alt="/wakeobu -status" width="49%">
  <img src="assets/wakeshowcase/1/obustatushover.png" alt="A collapsed context layer on hover" width="49%">
</p>

**Shared server contexts and your own sandboxes**
<p align="center">
  <img src="assets/wakeshowcase/2/obucontexts.png" alt="/wakeobu -context" width="49%">
  <img src="assets/wakeshowcase/2/obucontextshover.png" alt="A context preview on hover" width="49%">
</p>

</details>

<details>
<summary><b>Sandboxes</b></summary>

**Two sample workflows:** Create, fork, switch and publish a sandbox. Add, narrow or remove single settings instead of resetting everything.
<p align="center">
  <img src="assets/wakeshowcase/3/obusandboxworkflow.png" alt="Creating/forking/switching/publishing a sandbox" width="49%">
  <img src="assets/wakeshowcase/3/obuworkflow.png" alt="Setting/narrowing/clearing OBU settings" width="49%">
</p>

**Look up and share sandboxes**
<p align="center">
  <img src="assets/wakeshowcase/4/obusandboxview.png" alt="/wakeobu -sandbox view" width="49%">
  <img src="assets/wakeshowcase/4/obusandboxexport.png" alt="/wakeobu -sandbox export" width="49%">
</p>

</details>

<details>
<summary><b>More custom OBU features</b></summary>

**Server-side settings for the OBU module**
<div align="center">
  <img src="assets/wakeshowcase/5/obusettings.png" alt="/wakeobu -settings" width="100%">
</div>

**Autocompletion and a command to look up OBU defaults**
<p align="center">
  <img src="assets/wakeshowcase/6/obuclear.png" alt="/wakeobu -clear with narrowed suggestions" width="49%">
  <img src="assets/wakeshowcase/6/obudefaults.png" alt="/wakeobu -defaults" width="49%">
</p>

**All commands are listed and explained in custom help menus**
<p align="center">
  <img src="assets/wakeshowcase/7/obuhelp.png" alt="/wakeobu -help" width="49%">
  <img src="assets/wakeshowcase/7/wakehelp.png" alt="/wake help" width="49%">
</p>

</details>

<details>
<summary><b>More</b></summary>

**OBU Client handling:** Outdated clients are notified and see badges on unavailable settings. Unsupported ones are refused.
<p align="center">
  <img src="assets/wakeshowcase/8/obuclientoutdated.png" alt="An outdated OpenBoatUtils client" width="49%">
  <img src="assets/wakeshowcase/8/obuclientrejected.png" alt="An unsupported OpenBoatUtils client" width="49%">
</p>

**Boostpads and durability:** Wake queues every change in case of database outages and replays them on recovery.
<p align="center">
  <img src="assets/wakeshowcase/9/drydockboostpads.png" alt="/drydock boostpad list" width="49%">
  <img src="assets/wakeshowcase/9/wakedatabaseoutage.png" alt="A database outage, journaled and replayed" width="49%">
</p>

**Current modules in the reload dashboard**
<div align="center">
  <img src="assets/wakeshowcase/10/wakemodules.png" alt="/wake reload" width="100%">
</div>

</details>

## Documentation
<p>
  <a href="soon"><img src="https://img.shields.io/badge/Wake-Docs-5C66FF?logo=read-the-docs&logoColor=33B5FF" width="114" alt="Wakes documentation" /></a>
</p>

## AI Usage
Large parts of the codebase are written by or with the help of LLMs. They were used for code generation, reviews, drills and unit tests. <br>
While I use them heavily to generate boilerplate, the architectural/implementation decisions, ideas and code reviews are entirely my responsibility. For releases, I dedicate days to manual testing following iterative reviews and automated tests. See [PR #10](/../../pull/10): I spent 12 full days to review code, fix findings and run ~350 manual testcases on top of the automated ones for the 2.0.0 release.
I'm aware that using LLMs is a dealbreaker for some. But I can ensure you that Wake is properly tested and that I'm aware of my responsibilities using LLMs. I'm developing and sharing Wake as a passion project that smaller private servers like mine might benefit from as well. **Not critical infrastructure**.

## Developer & Admins
Wake is primarily developed and tested on Paper 1.21.11.<br>
It is compiled on all versions Wake claims to support. It is also sporadically tested on the lowest and highest supported versions but the majority of automated and manual testing will happen on the latest Minecraft version OBU supports.

### Database
By default Wake uses a **SQLite** database. <br>
You can set up a **MariaDB** database and point Wake to it via the [config](src/main/resources/config.yml). Wake automatically recovers after database outages and handles ingame notifications and cache reloading for you.

Wake caches gameplay related data in memory for instant access. Therefore the caches of individual servers can drift from the shared MariaDB database in a multi-server setup. Set up a **Valkey** instance and point Wake to it via the [config](src/main/resources/config.yml). Backends announce changes to their cache via **pub/sub** and Wake handles the invalidation/reloads so your backends stay synced if you choose to use that feature.

### Things you should know
Make sure to increase `moved-wrongly-threshold` and `moved-too-quickly-multiplier` in your `spigot.yml`

> [!Warning]
> Wake's OBU module cancels vehicle correction packets (VEHICLE_MOVE) for OBU clients in boats to avoid rubber banding when airstepping or at speed. Or anything that trips vanilla movement checks that isn't handled by the configs above.
> Make sure you account for that with a dedicated anti-cheat plugin or toggle it off via `/wakeobu -settings boat-lag-fix false`.<br>
> Private servers can ignore this warning.

1.21-1.21.3 will log a warning about EntityRemoveEvent which you can ignore (or suppress via `deprecated-verbose: false` in `bukkit.yml`)

## Credits
- [PaperMC](https://papermc.io/) provides the Paper API
- [PacketEvents](https://www.packetevents.com/) saves me from NMS packet manipulation
- [OpenBoatUtils](https://github.com/OpenBoatUtils/OpenBoatUtils) introduced me to a new genre of boatracing and inspired me to start working on Wake
- [@o7Moon](https://github.com/o7Moon) and [@microwavedram](https://github.com/microwavedram) for maintaining OBU
- [HikariCP](https://github.com/brettwooldridge/hikaricp) - [SQLite JDBC](https://github.com/xerial/sqlite-jdbc) - [MariaDB JDBC](https://github.com/mariadb-corporation/mariadb-connector-j) - [Lettuce](https://github.com/redis/lettuce) - [Aikar IDB](https://github.com/aikar/db)