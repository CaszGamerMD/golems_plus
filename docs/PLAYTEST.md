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

## Chef cooking + service and Fisher chair (0.3.24-dev)

1. Place a two-high Cooking Ordering Kiosk against stripped wood. Its wall
   screen must face the player and occupy only the rear half of each block.
   Click either top or bottom to open the same kiosk. Check all four facing
   directions; also test breaking either half and replacing it.
2. Place the Meal Pickup Counter alongside CFB kitchen counters. The surface
   should align in height, with no purple/black checkerboard missing textures.
   Place a meal and verify the rendered plate sits just above the worktop.
3. Link a connected CFB kitchen, fill its storage with Croptopia ingredients,
   and submit food containing milk, bottles and/or reusable cookware. Watch
   Chef Golems visit the kitchen crafting surface and real oven/furnace.
   Verify recipe remainders go to original storage slots or matching stacks,
   and otherwise empty kitchen slots. When all storage is full, ingredients
   must stay reserved instead of getting dropped or duplicated.
4. Confirm **exactly one** Chef physically collects the completed plate at
   the kitchen work area, walks to the serving counter and deposits the meal.
   The order-up bell should ring exactly once when the item actually arrives,
   never while a delivery is blocked. When paused, resume the same courier
   without conjuring a second meal.
5. Place multiple Fisher Golems by a marked lake. Observe full seated
   casting/reeling with a visible rod. Side chair braces should meet metal
   front/rear legs, and no front support may cut through the Golem's legs.
   Each new seating session should choose one of 12 fabric colors; color
   remains stable within that session, across players and save/reload.
6. Confirm all **12** Fisher chair atlas variants are packed in the JAR.
   `AssetIntegrityTest` checks them automatically on every CI build.

The Chef uses real connected kitchen inventories and actual furnaces/smokers;
crafting operations are processed by the recipe planner at the connected
kitchen work surface, rather than opening a vanilla crafting table GUI.

## Builder Job Planner and warehouse staging (0.3.25-dev)

### Job setup
- Open a Builder Planning Table: the Builder interface takes the whole viewport.
  The *left* inventory remains the block's real stored stacks and the player's
  inventory. Across the top, tabs show up to 12 saved placements and progress;
  +NEW JOB opens the currently enabled Litematica placement list.
- Choose a **loaded** Litematica placement. The client uploads its bounded
  snapshot to the server. Wait for the [Builder] import confirmation. The
  selected job, origin, rotation, required items and outstanding material
  manifest are saved in the Planning Table, including across close/reopen.
- Link a Rack Computer to the Caszual Additions Warehouse Scanner, then
  right-click the Builder Table with the scanner. The selected job gets this
  warehouse; a later scanner click relinks the selected job when no shipment
  is already reserved. The job panel displays its actual warehouse name.
- In the planner choose PICK STORAGE, close the GUI, and right-click a
  chest, barrel or shulker within 128 blocks of the schematic's origin.
  This is the physical construction-material staging location. If omitted,
  use the Planner itself. A second selection replaces the previous location.
- Item icons in the scrolling material grid show the remaining block count.
  Alphabetical or quantity descending sorts are available. Hover an item
  for quantity, full stacks plus remainder, staged count, and verified
  warehouse quantity. A white label means unchecked (no stock API or unloaded
  Rack Computer), green enough including staged stock, yellow partially
  available, and red zero verified warehouse stock.
- Set crew target to 1-4. Place crafted Builder Golems and confirm they
  register. Start requires an imported placement, at least one assigned
  physical Golem and a linked warehouse, **not full material coverage**.

### Actual construction
- Every 40 ticks the Planner rescans its real inventory, selected storage and
  on-duty Golem pouches. Do not duplicate queued stock; reloading must keep
  both shulker cargo and nine-slot Builder pouches intact.
- Build step order is low-to-high Y. Multiple workers can claim separate
  blocks within the same layer, never higher layers until the lower is done.
- Warehouse shulker requests look at the next **three vertical layers**
  and request complete stacks when future demand warrants them, or the exact
  partial stack when the entire job needs less than 64.
- One assigned worker claims the warehouse supply ticket and walks to the
  real output PTP. It carries the shulker to the selected staging container
  (or the Builder Table) and physically deposits it. Other Builders continue
  building with staged material. The solo worker handles both roles.
- Each builder walks to the actual material storage, transfers up to nine
  real stacks into its saved pouch, then walks to the build location. One
  actual item is consumed from the pouch per placed block. If placement is
  rejected, the material is refunded to the same pouch slot. Unused materials
  are brought back to storage after the job is complete or canceled.
- Scaffolding is requested and spent as physical minecraft:scaffolding from
  the Planner. It is only created beside the build for genuine height reach;
  once safely grounded, tracked scaffolds are instantly reclaimed to the job
  block, never other players' scaffolds.
- When a warehouse has no more items, existing on-site stock and pouch items
  can continue being built. The station gives an actionable missing-stock
  message rather than creating materials, destroying obstructing blocks, or
  skipping unsupported schematic states.

### Regression checklist
- Choose and upload multiple placements; ensure each job keeps its own
  material manifest, warehouse, storage and progress after reloading a save.
  Selecting a different job while one is running must be rejected, not alter
  the running cursor or inventory.
- Put a half-full shulker of stone inside a storage chest by the site; start
  solo with a warehouse missing some items. Observe the Golem actually walk
  to the chest, pick physical items, travel and place the lower layer first.
- Place four Builders at one table and confirm one warehouse courier and up
  to three simultaneous on-site builders, with no duplicate item consumption
  or two workers claiming the same block.
- Block a scaffold column or replace a planned block with a player's block:
  no item is spent or modified in the wrong location.
- Full staging chest, unloaded PTP, unloaded Rack Computer and disconnected
  warehouse should never delete held shulkers. Inspect the persistent error.
- Watch the palette status after opening/removing shulker contents, and compare
  counted warehouse quantities with actual pallets in Caszual Additions.
  Verify white unchecked when the updated Additions bridge is absent.

## Builder debris & tools / Chef repair (0.3.26-dev)

- Chef oven-accessory slots are counted as real utensil supply, never as oven fuel or cooking output. Prepare Croptopia recipes using a pot or pan stored with the CFB oven; check that the real utensil returns to its original oven tool slot if permitted, otherwise to matching/empty kitchen storage.
- Once food is genuinely in the Chef's saved reservation, a courier walks the finished plate to a reachable pickup counter and rings once on deposit. Full ingredient storage may delay cookware return but must NOT delay the order-up bell or produce a duplicate meal.
- Check the Meal Pickup Counter on all faces and in inventory for quartz texture resolution: no pink/black missing model faces.
- JEI's ingredient grid must stay outside the full-screen Builder planner; default other JEI screens and recipe browsing must remain unchanged.
- DELETE JOB on a selected inactive job requires a second click to confirm. It removes only that job's saved metadata, not physical blocks, stored materials or uploaded schematic files. It must be rejected while a job runs or shulker reservation is in flight.
- Start a build with an empty site chest and populated linked warehouse. One Builder must request real partial material stock, retrieve shulkers and put them into the selected site chest. Building then proceeds; empty/partially stocked warehouses must not require the user to prefill the site chest manually.
- Put the appropriate pickaxe/axe/shovel/hoe and scaffolding in the 27 Planner input slots. At a planned occupied block, verify a Builder physically collects a matching tool, walks to the site, checks permissions and debris capacity, breaks only that specific mismatched block, and deposits its actual loot in the on-site container. It should damage the real tool, and return it to the Planner when idle.
- Never remove a tile entity/inventory, a liquid, a world-protected block, a worker's temporary scaffold or a non-schematic position. If debris storage is full or the correct tool is missing, the obstructed block must remain unchanged with an actionable station warning.

## 0.3.28-dev — Builder worksite clearing, GUI and courier dispatch regression

**Planner inventory:** Open a Builder Table with a pickaxe, axe, shovel,
scaffolding and building blocks in your player inventory. Regular click,
right-click and shift-click each stack into the real 27 input slots;
retrieve them. Specifically test the bottom hotbar row with a carried stack:
a click must never throw it onto the ground. Repeat at 2×, 3× and 4× GUI
scales. Background slot rectangles must align with the real menu slots.

**Builder clearing:** Place stone/dirt in the exact location occupied by
a schematic step and a matching tool in the Builder Planner. The Builder
returns to the planner, picks up the real tool and comes back to clear the
obstruction, deposits its drops into the selected site chest, damages the
tool, then places the planned block. If the nine-slot pouch is full, it must
unload real inventory to the site chest before borrowing the pickaxe; if the
chest is full, it must stop rather than discard anything.

**Warehouse dispatch:** Start a build with an EMPTY on-site storage chest and
required stone in Caszual Additions warehouse racks. Observe that a real
output PTP reservation is accepted before a supply ticket persists, and the
closest available Builder starts walking toward the PTP even while the shulker
is still being packed. Once full, the golem carries the physical packed box
back to the selected site chest, then builders use its contents. For a solo
Builder, the same Golem must perform both jobs. Force an unavailable output
PTP, then clear it: the Planner must show a retryable warning and recover
automatically (no permanent ticket stuck at 0,0,0). Load a saved game with
an orphaned pre-0.3.28 ticket and confirm it retries safely.

**Stock shortfalls:** When the warehouse has only part of a requested batch,
request the actual amount; never forge the missing materials or lose a
shulker if its delivery chest fills up. Verify that missing stock and PTP
problems remain visible, rather than being masked by a generic 'site storage
empty' warning.
