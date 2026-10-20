# Golems Plus

Minecraft **26.2 Fabric**, Java **25** — Caszual Suite

Specialist golems with real workstations and finite inventories. Development
is directly on `main`.

## Current implementation (v0.1.0-dev groundwork)

This is a **source-code foundation, not a gameplay release**. The three
new Creative-tab prototype blocks are the Builder's Planning Table,
Fishing Tackle Station and Trader's Counter. Empty-hand click shows status,
and crouch + empty-hand click pauses/resumes. Ownership and selected warehouse
metadata can be persisted, but no worker entities are currently spawned.

- **Builder:** validated packed Litematica palette decoder, relative block
  planner, material manifest, unresolved-state report, anchor/rotation/mirror,
  plus a safe, interface-only Caszual warehouse supply boundary. Actual
  `.litematic` file import, preview and construction are upcoming.
- **Fishing:** event-driven cast/bite/reel/deposit task state machine. No
  simulated free fish, no fishing-rod use or pathfinding yet.
- **Trader:** in-memory atomic two-inventory trade model, validation, duplicate
  transaction rejection and tests. Not connected to real Minecraft inventory
  or multiplayer endpoints until persistent journaling is implemented.

Implementation details, safety constraints and remaining work are in
[docs/FOUNDATIONS.md](docs/FOUNDATIONS.md).

## Planned golems

- **Cooking Golem** — Ordering kiosk and registered recipe preparation;
  progress tracked as future implementation.
- **Beekeeper Golem** — Beekeeper toolbox, shears/bottles harvesting and
  bee breeding; progress tracked as future implementation.
- **Builder Golem** — Litematica plans and linked warehouse materials:
  [issue #1](https://github.com/CaszGamerMD/golems_plus/issues/1).
- **Fishing Golem** — Supply a rod, fish and deposit catches:
  [issue #2](https://github.com/CaszGamerMD/golems_plus/issues/2).
- **Trader Golem** — Inventory-backed, permissioned trading:
  [issue #3](https://github.com/CaszGamerMD/golems_plus/issues/3).

## Development

Run `gradle clean test build` with Java 25 and a compatible Gradle 9
installation. The GitHub Actions workflow checks `main`. No release JAR
should be published until the build and tests succeed.
