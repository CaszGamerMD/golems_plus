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
- **Trader (villager trading):** targets **villagers**, not player storefronts.
  A golem will take the player's supplied goods from its Trader Counter, visit
  an assigned village and perform available **vanilla villager offers** on the
  player's behalf, then bring the traded items home. Trades may be
  crops-for-emeralds or emeralds-for-items, including two-input offers.
  Groundwork includes effective-price offer snapshots, an authorized village
  search area, required goods, spending limits, stock/space checks,
  and a navigation/trade/delivery task state machine. It cannot interact with
  real villagers yet; that needs a safe Minecraft merchant adapter.

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
- **Trader Golem** — Player-supplied inventory traded **to villagers**:
  [issue #3](https://github.com/CaszGamerMD/golems_plus/issues/3).

## Development

Run `gradle clean test build` with Java 25 and Gradle 9.7 or later.
The GitHub Actions workflow checks `main`. No release JAR should be
published until the build and tests succeed.
