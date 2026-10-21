# Golems Plus — current development playtest

The mod now has a physical Trader Golem in a temporary vanilla Iron Golem
model, a 36-slot Trader Counter, and a Builder schematic import tool. All
automation requires loaded areas; no force-loading or item generation.

## Trader Golem

Place and claim a Trader Counter with empty-hand right click. Right click
again for the inventory: first 27 slots hold payments, last 9 slots receive
purchased goods. A crouch + empty-hand click pauses the worker.

Stand near your owned counter to use:
- `/golems trader village 32` — selects the player's position as village
  center with a 32-block search radius.
- `/golems trader order minecraft:emerald 16` — requests 16 emeralds.
- `/golems trader budget minecraft:wheat 320` — authorizes spending up to
  320 wheat on this order; repeat for other payment item types.
- `/golems trader status` — shows progress, wait condition and output.
- `/golems trader cancel` — cancels order; current courier trip returns.

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
2. Claim a nearby Builder's Planning Table.
3. Run `/golems_use_placement` client-side to send the file and its exact
   origin, mirror and rotation; do not move the hologram after importing.
4. Run `/golembuilder preview` then put actual resources in the Table.
5. Run `/golembuilder start` to start the physical Builder worker.
6. Use `/golembuilder status`, `pause`, `resume`, `cancel`.

The Builder refuses occupied targets, unsupported or protected blocks,
unloaded chunks and missing materials; it never mines or creates free items.
Its current scope is safe, single-block placement, not automated warehouse
resupply or complex block-entity construction.

The previous `/golembuilder inspect <name>.litematic` still works as a
read-only materials analysis; a confirmed *placed* import is required for
the new construction mode.

## Playtest precautions

Use a backed-up world, especially around modded villagers and unloads.
An automated passing CI build verifies compilation and unit tests, not
end-to-end game behavior.
