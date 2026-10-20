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

## Builder Golem — planning phase

Copy a `.litematic` file to the SERVER:
`config/golems_plus/schematics/<name>.litematic`.
Place and claim the Builder's Planning Table, and run:
- `/golembuilder inspect <name>.litematic` — imports and saves selection,
  reports counts, estimated materials and unsupported states.
- `/golembuilder rotate 90` — sets build orientation (0/90/180/270).
- `/golembuilder warehouse` — associates a nearby loaded Caszual Additions
  rack computer as the source warehouse (selection only; not stock permission).
- `/golembuilder status` — inspect saved schematic name, counts, rotation
  and warehouse link.

The importer is bounded, read-only, parses gzip NBT multi-regions, palette
indices and signed dimensions. It never places blocks, copies block-entity
contents, requests stock or creates any items. Safe physical construction and
warehouse delivery are subsequent milestones.

## Playtest precautions

Use a backed-up world, especially around modded villagers and unloads.
An automated passing CI build verifies compilation and unit tests, not
end-to-end game behavior.
