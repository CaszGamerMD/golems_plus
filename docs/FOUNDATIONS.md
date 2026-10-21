# Specialist golem foundations — v0.1.0-dev

The current code builds the shared mod environment and prototype workstations.
The Trader Golem now has an experimental physical entity and villager-trading
runtime. The Builder has an experimental client-placed Litematica bridge and\nitem-backed construction worker; Fishing remains groundwork.

## Builder / Litematica (#1)

`LitematicPaletteDecoder` extracts palette indices from dense packed region words
with bounds checks, including indices that cross a 64-bit boundary. The pure
`BuilderMaterialPlan` produces relative block positions, material counts and
unresolved/unsupported states. `BuildSite` transforms coordinates for a
player-selected anchor, rotation and mirror.

**Now implemented:** a defensive gzip NBT importer for multiple regions,
signed extents and block-state properties; an optional client command that
reflects the SELECTED Litematica placement (without server dependency) and
uploads its file, origin, rotation and mirror in bounded packets. The server
validates table ownership and distance, saves a snapshot, runs material and
unsupported-state checks, and requires `/golembuilder start` before any world
change. The Builder worker follows each planned coordinate, skips matching
blocks and only fills air with actual items removed from the Builder Table.
Each completed step advances a saved cursor. It refuses unsupported states,
out-of-range targets, failed paths, unavailable players, unloaded chunks,
missing support and occupied targets.

**Not yet complete:** interactive in-world hologram preview beyond the
Litematica overlay, Litematica grids/repeated placements, individually
modified subregions, block-entity/fluid/multiblock placement, worker
scaffolding, automatic warehouse item delivery and multiplayer claim-system
testing. The current code is experimental; successful CI is not an
end-to-end gameplay test. See `docs/LITEMATICA_PLACEMENTS.md`.

**Scanner-based warehouse selection:** Right-clicking the Builder's Planning
Table with a linked Caszual Additions Warehouse Scanner reads the existing
`minecraft:custom_data` fields `warehouse_dimension`, `warehouse_x`,
`warehouse_y` and `warehouse_z`. The same-dimension link is copied into
the owner's persistent Builder Table settings, replacing any prior warehouse.
If its Rack Computer is currently loaded, the actual block is verified.
Unloaded computers are not force-loaded, and unlinked/wrong-dimension/
invalid loaded links are rejected without clearing a previous assignment.
The scanner item is never modified. `/golembuilder status` shows the link.

Warehouse integration is an interface in `warehouse/WarehouseSupplyPort`.
A future Caszual Additions adapter MUST authorize owner and warehouse link,
reserve existing material once per order, physically transfer supplies, and
record delivery acknowledgements. A station can store warehouse coordinates,
but coordinates are not authorization and do not mean materials are available.

## Fishing (#2)

`FishingTask` models need-rod, need-water, cast, waiting for bite, reel,
deposit, full-storage and pause states. Only an actual vanilla/mod fishing
hook event should advance the cast; the core code **does not generate loot**.

**Next:** player-supplied rod and storage inventories, verified water waypoint,
server-side hook entity/catch integration, durability and enchants,
loot collection, navigation, rod retrieval, and unload/reload recovery.

## Trader / villager assistant (#3)

The owned Trader Counter now has a persistent 36-slot inventory: 27 input
and 9 output, using a server-backed menu. The experimental Trader Golem is
an actual Iron-Golem-based entity with physical pathfinding and saved
payment/result escrow. It is invulnerable while under development to avoid
accidental cargo loss and farmable free iron drops; it does not attack mobs
or randomly wander.

The owner selects a loaded village area with `/golems trader village <radius>`,
an output goal with `/golems trader order <item> <count>`, and per-item spend
caps with `/golems trader budget <item> <max>`. Status and cancel commands
are supported. The golem searches loaded villagers for current merchant
offers, validates stock, current costs and output space, withdraws real
payment stacks and walks to the villager. On arrival it checks the live
offer again, performs the actual `MerchantOffer.take()` and
`Villager.notifyTrade()`, then returns with the acquired ItemStack.
It returns unused escrow when the offer becomes unavailable; no synthetic
trade output is generated.

The earlier pure `VillagerTradePlanner` / task classes remain tested
and useful for later quote UI, but the current in-game worker executes
via `TraderWorkService`. It only processes one trade per round trip.
Per-order budgets are persisted at the station; cargo remains on the
golem. The worker pauses rather than forcing unloaded chunks.

**Still needed:** end-to-end multiplayer playtesting, durable transaction
journal/restart recovery across separate chunk saves, dedicated entity
appearance/animations and proper visual configuration GUI. Long-distance
pathing/worker-loss recovery and protected claims also need work.

## Shared workstation prototype

Three placeable Creative-tab blocks:
- Builder's Planning Table
- Fishing Tackle Station
- Trader's Counter

First empty-hand interaction claims ownership; subsequent interactions
open the persistent workstation inventory. Crouch + empty hand toggles PAUSED.
Stations persist ownership and task state with Minecraft's ValueInput/ValueOutput
serialization. Builder plans and Trader Counter inventories survive saves.

Station appearance remains placeholder vanilla textures. Trader AI is now
executable, but its model is temporary and requires playtest validation.

## Build

Java 25, Fabric 26.2, Loom 1.18.2. Run `gradle clean test build` with
Gradle 9.7+. CI runs the same job and publishes a build
artifact only after it passes. No release JAR is committed to source control.
