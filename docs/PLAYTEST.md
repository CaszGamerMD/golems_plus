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
