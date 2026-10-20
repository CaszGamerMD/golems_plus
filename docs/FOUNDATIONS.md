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

## Trader / villager assistant (#3)

**Confirmed design:** the Trader Golem goes to **villagers to trade for the
owner**. It is *not* a player-facing shop and does *not* manufacture emeralds.

Future gameplay loop:
1. Player supplies payment items and sets a wanted output and maximum spend
   at their Trader Counter. Both purchasing and selling through normal villager
   offers are supported (for example, sell wheat for emeralds; buy bread or
   enchanted books using emeralds and any second required ingredient).
2. Player selects an authorized village search area (dimension, center, radius);
   optional whitelist of individual villagers.
3. Golem reads nearby **loaded** villagers and their actual active offers,
   including adjusted current prices, per-offer availability and both inputs.
4. Golem physically walks to a villager and **revalidates** the real offer
   before handing over anything. Out-of-stock villagers wait for normal restock.
5. Only successfully completed merchant trades produce outputs; the golem
   physically returns the real items to the owner's Trader Counter.

**Implemented groundwork:**
- `VillageSearchArea` and station NBT owner-controlled village target storage
- `VillagerOfferSnapshot` with two inputs, output and remaining uses
- `VillagerTradeRequest` with owner, order ID, output goal, villager allowlist
  and **explicit maximum spend for every accepted payment item**
- `VillagerTradePlanner` producing read-only quotes, preventing cost
  overspending, rejecting missing supplies, full storage, mismatched items
  and exhausted villager offers
- `VillagerTradeTask` requiring actual trade confirmation before goods enter
  a return journey and actual deposit before order progress increases
- `VillagerTradingPort` interface defining the real game-side adapter boundary

The previous `TradeEngine` and `TradeOffer` classes remain as **legacy,
pure in-memory simulations** for tests; they do not implement villager trading,
should not be plugged into Minecraft inventories, and are not a shop feature.
Quotes are not reservations and are not actual payment or delivery.

**Next:** station configuration UI, real golem mob/model/animation, player
stock and output slots, live offer adapter based on Minecraft MerchantOffer,
pathfinding, serializable orders, unloaded-village pause, safe resumption,
dimension checks, accurate stack components/enchants, owner access policies,
actual merchant side effects and durable transaction recovery. Never set
villager offers, uses or experience through simulated calls: use the game's
supported server-side merchant mechanics and respect trade locks, profession,
pricing, restocking, reputation, discounts and claims. The in-memory planner
must not be used to credit items. Duplicate transaction IDs require a durable
journal before real-world trade operations can be enabled.

## Shared workstation prototype

Three placeable Creative-tab blocks:
- Builder's Planning Table
- Fishing Tackle Station
- Trader's Counter

First empty-hand interaction claims ownership; subsequent interactions
display the station's saved state; crouch + empty hand toggles PAUSED / IDLE.
They persist owner, order identifier, phase, optional warehouse anchor,
and, for Trader Counters, an owner-selected village target using
Minecraft's ValueInput/ValueOutput serialization.

Station appearances are placeholder vanilla textures. These are safe
worksite placeholders, **not working AI**, and are not final art.

## Build

Java 25, Fabric 26.2, Loom 1.18.2. Run `gradle clean test build` with
Gradle 9.7+. CI runs the same job and publishes a build
artifact only after it passes. No release JAR is committed to source control.
