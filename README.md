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

**Builder Golem — planning progress:** A safe bounded `.litematic` file reader
supports gzip NBT, multi-region palettes, modded block-state properties and
negative region dimensions. A Builder Planning Table can remember a selected
schematic, estimated block count and unresolved states, rotation, and a
Caszual Additions rack-computer warehouse link. Construction, material
delivery and custom Builder mob AI are **not enabled yet**.

Builder commands: `/golembuilder inspect <file>.litematic`,
`/golembuilder rotate 90`, `/golembuilder warehouse`,
`/golembuilder status`.

**Fishing Golem:** event-driven state machine remains groundwork only.

Read [development playtest instructions](docs/PLAYTEST.md) and
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
