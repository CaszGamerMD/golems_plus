# Golems Plus

Minecraft **26.2 Fabric**, Java **25** — Caszual Suite

Specialist golems with real workstations and finite inventories. Development
is directly on `main`.

## Current development status

**Trader Golem — in-game implementation (experimental):** A persistent,
pathfinding Trader Golem uses actual vanilla villager offers and actual
payment items withdrawn from the owned Trader Counter's 27 stock slots. It
returns purchased items to nine output slots, refunds unspent payments,
checks per-order spending limits, and waits when villagers are unavailable,
offers change, or storage fills. The worker currently uses an Iron Golem
model; the inventory and commands are usable but need live multiplayer
testing and a dedicated model.

Configure while near a claimed Trader Counter:
`/golems trader village 32`, `/golems trader order minecraft:emerald 16`,
`/golems trader budget minecraft:wheat 320`,
`/golems trader status`, `/golems trader cancel`.

**Builder Golem — placed Litematica construction (experimental):**
Select a placed, enabled, file-backed schematic in the Litematica client
and use `/golems_use_placement`. Golems Plus sends a bounded snapshot
with the placement's origin, rotation and mirror to the server and saves it
at your claimed Builder's Planning Table. Run `/golembuilder preview` and
`/golembuilder start` to explicitly authorize the Builder to place blocks.
A real Builder Golem moves to target positions, uses materials from the
table's 27 input slots, saves a work cursor across reloads, skips existing
matching blocks, and refuses to destroy occupied locations. Controls:
`/golembuilder status`, `pause`, `resume`, `cancel`.

Litematica is an **optional client-only integration**; it is not needed
on dedicated servers. The bridge currently requires all subregions enabled
and unmodified, files ≤2 MiB compressed and builds ≤100,000 blocks.
Selected position/rotation are captured only when importing; moving the
hologram later requires reimport. This is a development implementation and
has not been playtested end-to-end in Minecraft yet.

To link a warehouse, **right-click the Builder's Planning Table with a
Caszual Additions Warehouse Scanner already linked to a Rack Computer**.
The Table copies and saves that warehouse assignment without changing the
scanner. Right-click again with a differently linked scanner to change it.
The older `/golembuilder warehouse` selection command remains available,
but scanner interaction is now the preferred workflow. Automatic
warehouse withdrawals and deliveries are still not implemented. Some blocks (block
entities, fluids, doors, etc.) are intentionally refused until safe
placement rules are added. The previous server-side `inspect` command
remains available for read-only planning.

**Fishing Golem:** event-driven state machine remains groundwork only.

Read [placed Litematica build instructions](docs/LITEMATICA_PLACEMENTS.md),
[development playtest instructions](docs/PLAYTEST.md) and
[implementation foundations](docs/FOUNDATIONS.md) for details and limits.

## Planned golems

- **Cooking Golem** — Ordering kiosk and registered recipe preparation;
  progress tracked as future implementation.
- **Beekeeper Golem** — Beekeeper toolbox, shears/bottles harvesting and
  bee breeding; progress tracked as future implementation.
- **Builder Golem** — Litematica plans and linked warehouse materials:
  [issue #1](https://github.com/CaszGamerMD/golems_plus/issues/1).
- **Fishing Golem** — Supply a rod, fish and deposit catches:
  [issue #2](https://github.com/CaszGamerMD/golems_plus/issues/2).
- **Trader Golem** — Experimental autonomous villager courier, needs live QA:
  [issue #3](https://github.com/CaszGamerMD/golems_plus/issues/3).

## Development

Run `gradle clean test build` with Java 25 and Gradle 9.7 or later.
The GitHub Actions workflow checks `main`. No release JAR should be
published until the build and tests succeed.
