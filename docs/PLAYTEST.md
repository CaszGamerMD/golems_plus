# Golems Plus — current development playtest

The mod now has a physical Trader Golem in a temporary vanilla Iron Golem
model, a 36-slot Trader Counter, and a Builder schematic import tool. All
automation requires loaded areas; no force-loading or item generation.

## Trader Golem

Place and claim a Trader Counter with empty-hand right click. Right click
again for the inventory: first 27 slots hold payments, last 9 slots receive
purchased goods. A crouch + empty-hand click pauses the worker.

Open the Counter's inventory. Use **ORDER** to set the requested item and
quantity, **BUDGET** to limit payments and **VILLAGE** to choose the village
search center and radius. Pause, resume, cancel and crew-size settings are
also buttons in the Counter. Server replies appear in chat; current status
appears at the bottom of the panel. Slash commands are optional fallbacks.

The visible worker navigates to a real nearby villager, withdraws actual
payment items from the counter, uses one live merchant offer, and returns
its output physically to the counter. Offer limits, effective prices and
multi-item costs are checked again at the villager. Refunds are returned
if the villager becomes unavailable. The worker holds real escrow items
persistently while moving and cannot create free loot.

The model and order interface remain preliminary. Dedicated multiplayer
transaction/restart recovery and protection-claim integration need testing.

## Builder Golem — building at a placed Litematica hologram

Use the full [placement-to-build walkthrough](LITEMATICA_PLACEMENTS.md).
With Litematica and Golems Plus installed on the client and Golems Plus
on the server:

1. Load a `.litematic`, position and select its placement in Litematica.
2. Claim a nearby Builder's Planning Table. Link a Caszual Additions
   Warehouse Scanner to a Rack Computer by sneak-right-clicking it, then
   **right-click the Builder Table with that linked scanner**. The Table
   should confirm the warehouse coordinates; no scanner settings are changed.
3. Open the Planner and click **Import Litematica placement** to send
   the file and its exact
   origin, mirror and rotation; do not move the hologram after importing.
4. Click **Preview** and put actual resources in the Table.
5. Optionally choose a crew of four with the Planner's **CREW +** button.
   Put scaffolding into the Table or stock it in the warehouse for taller
   structures. Click **Start build** to begin shared construction.
6. Use the Planner's **Pause**, **Resume** and **Cancel job** buttons.

The Builder refuses occupied targets, unsupported or protected blocks,
unloaded chunks and missing materials; it never mines or creates free items.
Its current scope is safe, single-block placement with experimental warehouse
resupply, not complex block-entity construction. A loaded warehouse requires
an empty OUTPUT PTP with pallet, a Shulker Station, empty packing boxes,
connected racks and working Storage Golems. The Builder collects shulkers
physically, saves them in its Planning Table and consumes their contents.
Missing items and empty shulkers trigger a status/chat notification.
Empty shulker shells remain at the Planning Table and move into its nine
output slots when empty; the player may collect or return them manually.
Builders reclaim tracked temporary scaffolding as soon as safe and reuse it.

The previous `/golembuilder inspect <name>.litematic` still works as a
read-only materials analysis; a confirmed *placed* import is required for
the new construction mode.

## Playtest precautions

Use a backed-up world, especially around modded villagers and unloads.
An automated passing CI build verifies compilation and unit tests, not
end-to-end game behavior.

## Multiple Trader Golems

Open a claimed Trader Counter and use its **CREW +** control to assign up to
four workers. Orders, stock, and spending budgets are shared. Each courier
carries its own genuine villager-trade payments; already-dispatched payments
and incoming results count against the collective order before any additional
trade is sent. `/golems trader status` displays the live crew size.

## Beekeeper Golem

Craft or use the creative tab to place a Beekeeper Job Block and a
Beekeeper Toolbox. Place a separate output chest or barrel nearby.
Claim/open the Job Block and press **Find TOOLBOX**, then **Find OUTPUT**.
Put real shears and empty glass bottles in the Toolbox, and choose
**Both**, **Comb**, or **Honey** collection mode. Set range and crew
(1–4) from the Job Block. With accessible honey-level-five hives, the
suited golems will travel to the toolbox, collect tools, harvest,
place their materials in the output container and return shears.

The golem has a small custom Storage-Golem-style body and protective
beekeeper outfit. Harvested products persist in its inventory while
moving. The current test build intentionally does not breed bees,
craft more tools, force-load distant hives, or farm without a player
online in the dimension. Use the job block status if tools or output
are missing; further end-to-end game testing is needed.

## Fisher Golems — rod casting and visible catches (0.3.18-dev)

1. Place a Fisherman's Coolers job block and insert real fishing rods plus
   `golems_plus:fishing_bait` in the supply slots.
2. Link a specific catch chest using **CHOOSE OUTPUT CHEST**, then right-click
   the chest. Mark an open-water surface with the Fishing Waypoint Flag.
3. Place up to four Fisher Golems and allow them to reach the shoreline.
   The chair appears when seated; their extended 3D rods point into water.
4. Each cast produces a thin particle fishing line with a distinct red/white
   bobber at real open source water. Fish ripples approach a bite, then a
   splash and retrieval sound play. A fish hangs from the raised rod briefly.
5. The actual catch goes into the Golem's nine saved pocket slots; stored
   bait and rod durability are consumed only on a successful catch. The
   Fisher visits the linked chest to deposit fish when it needs to resupply
   or its pockets are full.

Check that no bobber is shown without a real rod/bait, none is cast into
solid blocks, and the line disappears if the Fisher is paused, travels,
runs out of supplies, or water is removed. Existing in-progress fishermen
should resume their saved task after reloading a world.


## Shared obstacle-aware worker navigation (0.3.19-dev)

Builder, Trader, Chef, Beekeeper and Fisher Golems now use one ground route
controller. Every five server ticks it validates the upcoming four path nodes
and swept body width (including corners and low ceilings). Routes are not
replanned repeatedly when still clear. When a new wall, rack, table, block,
water or hazard cuts across the route, the worker stops and its next work
step requests a fresh route. Unreachable jobs choose a different nearby
standable approach, not a direct push against the obstructing block. Closed
wooden doors may be opened and are closed after the golem clears them.

Playtest in a **backed-up** world:

- Place a two- or three-wide corridor with a wall across the direct line
  from workstation to destination, but an alternate route around it.
  Confirm all five professions follow the detour without pushing at walls.
- While walking, place a full block or a fence across the chosen path.
  Check the worker stops and reroutes. Then clear the obstruction and confirm
  it proceeds without having to respawn it.
- Check carpets, half-slabs, wooden doors, tight corners, two-block headroom,
  golems meeting head-on, and multiple golems using one workspace.
- Confirm full-height hazards, water and unsupported ledges are avoided;
  no waypoint or scan may force-load a chunk.
- Confirm Fisher casting still works once seated and Chef workers do not
  walk on top of counters or ovens.

The five-tick check is a lightweight route inspection, **not** a full A*
search every five ticks. Full navigation remains server-controlled.

## Chef idle ingredient return — 0.3.22-dev

Chefs must clear their displayed hand contents whenever there is no active
order, and also when an order is paused. The physical ingredient reserve
belongs to the Kitchen Ordering Kiosk, **not** the individual Golem; do not
create synthetic item stacks to mirror the animated hands.

- Reserve items from actual connected CFB fridges, cabinets and counters.
  Original source block positions and slots are saved in the job block.
- After serving a meal, return surplus to the original slot when compatible;
  otherwise a same-item stack, then a free slot anywhere in the *linked*
  kitchen. Retained surplus never overflows or drops onto the floor.
- Idle orphaned ingredient reserves are returned by the same rules; when
  storage is full/disconnected, keep the reserve and show a kitchen warning.
- A PAUSED chef job retains its reserved material for resumption; it only
  clears the rendered hand item. No duplicate refunds on tick/reload.

Regression: use a stocked fridge with two matching ingredient stacks and
additional free space in connected drawers. Finish a recipe with surplus,
fill the original slot while the chef works, then confirm the spare stack
moves to another matching slot. Fill every slot and check the chef waits
without voiding stock or issuing free food. Save/reload an active order and
confirm the original slot provenance is retained.
