# Golems Plus

Fabric 26.2 · Java 25 · Caszual Suite

Adds specialist golems with physical workplaces, tools, inventories, and meaningful world interactions.

## Current workers
- **Cooking Golem** — Ordering Kiosk takes customer orders for registered food recipes. Ingredients must exist; the worker travels to the appropriate workstation and processes the order. Support for different mod recipe types is staged rather than pretending all custom machines work automatically.
- **Beekeeper Golem** — Beekeeper Toolbox provides shears, bottles, and flowers. The worker seeks ready beehives/bee nests, harvests honey/honeycomb, retrieves produce, and breeds adult bees with flowers. Tool durability, full storage, and cooldowns matter.

## Planned worker: Builder Golem (later release)
- Import and read **Litematica `.litematic` schematics** (multi-region palettes and block-state data).
- Preview/validate area, orientation, and rotation before constructing; require player approval to build.
- Resolve a bill of materials, including modded blocks, from the schematic; indicate missing/unsupported blocks.
- Assign a **Caszual Additions warehouse** as the material source; request inventory through its existing order/transport interfaces rather than duplicating items.
- Physically navigate to placement positions; scaffold as needed; support pause/resume, chunk safety, protected-claim rules, missing-material waiting, and rollback-safe recovery.
- Handle block entities and inventory content conservatively to prevent unexpected placement or duplication.
- Separate milestone: schematic reader → materials planner → safe worker placement → warehouse link and resupply.

## Future specialists (later releases)
- **Builder Golem:** imports Litematica schematics and obtains construction materials from an assigned Caszual Additions warehouse ([roadmap #1](https://github.com/CaszGamerMD/golems_plus/issues/1)).
- **Fishing Golem:** fishes with supplied gear and deposits catches into storage ([roadmap #2](https://github.com/CaszGamerMD/golems_plus/issues/2)).
- **Trader Golem:** automated, inventory-backed trading; scope to be determined ([roadmap #3](https://github.com/CaszGamerMD/golems_plus/issues/3)).

## Development
Work directly on `main` unless a different branch is requested. Do not commit a release JAR until the build and tests succeed.
