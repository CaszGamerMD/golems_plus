# Specialist golem foundations — v0.1.0-dev

The current code builds the shared mod environment and three **prototype workstations**.
It does NOT spawn Builder, Fishing or Trader mobs or perform autonomous actions yet.

## Builder / Litematica (#1)

`LitematicPaletteDecoder` extracts palette indices from dense packed region words
with bounds checks, including indices that cross a 64-bit boundary. The pure
`BuilderMaterialPlan` produces relative block positions, material counts and
unresolved/unsupported states. `BuildSite` transforms coordinates for a
player-selected anchor, rotation and mirror.

**Next:** implement a compressed NBT .litematic file importer on the appropriate
side, multi-region offsets, signed dimensions, metadata validation, and a robust
block-state-to-item mapping. Then implement in-world preview/confirmation and
chunk/claim-safe physical placement. Build plans are deliberately read-only today.

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

## Trader (#3)

`TradeOffer`, `TradeInventory` and `TradeEngine` simulate both sides of
each trade on copies, then commit only if all checks pass. Transaction IDs are
rejected on reuse within the running engine. Tests cover no partial transfer
on insufficient room, successful payment/goods swaps and duplicate requests.

**Next:** persistent transaction journal that survives server crashes,
atomic game inventory operations, item components/NBT matching, permissions,
GUI offer configuration, stock and payments storage, villager/player endpoints
and optional warehouse restocking. The sample in-memory ledger must **never**
be connected directly to real item transfers: it does not protect against a
crash between Minecraft inventory writes and durable journal writes.

## Shared workstation prototype

Three placeable Creative-tab blocks:
- Builder's Planning Table
- Fishing Tackle Station
- Trader's Counter

First empty-hand interaction claims ownership; subsequent interactions
display the station's saved state; crouch + empty hand toggles PAUSED / IDLE.
They persist owner, order identifier, phase, and optional warehouse anchor
using Minecraft's ValueInput/ValueOutput serialization.

Station appearances are placeholder vanilla textures. These are safe
worksite placeholders, **not working AI**, and are not final art.

## Build

Java 25, Fabric 26.2, Loom 1.18.2. Run `gradle clean test build` with a
compatible Gradle 9 installation. CI runs the same job and publishes a build
artifact only after it passes. No release JAR is committed to source control.
