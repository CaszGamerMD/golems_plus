# Configure Golems Plus from job blocks

Settings are now in the **workstation inventory** instead of requiring
slash commands. Install the same Golems Plus build on client and server.
Existing commands remain optional for compatibility and debugging.

## Interaction troubleshooting

A normal right-click (main hand, without sneaking) on any Golems Plus
workstation must reach its ownership/inventory handler, even if the player
holds an item. The Builder's Planning Table still reserves the linked
Warehouse Scanner interaction for warehouse assignment. An empty-hand
sneak-right-click toggles the workstation pause state.

If a job block appears inert, confirm the running client and server both
use a build containing the workstation right-click fix, restart Minecraft
and the server after replacing the JAR, then test with an empty hand on a
newly placed Chef's Kitchen Station. Opening the menu (and spawning a
Chef once claimed) verifies the workstation code is running. A visible
block alone does not prove both sides are using the same JAR.

## Crafting and placing Golems

Craft a Worker Golem with `3 iron ingots + 1 carved pumpkin + 1 profession item`
(Crafting Table for Builder, Emerald for Trader, Honeycomb for Beekeeper,
Smoker for Chef). Right-click a floor or other block to place its physical
worker in an empty two-block-high space within 10 blocks of a matching job block.
The nearest eligible workstation is claimed/assigned to you if unowned.
Workers idle-patrol within 10 blocks until given work. They require a real
crafted item and never appear free when a job is created. Increase crew capacity
using the station's CREW controls, then physically deploy workers (up to four,
or one Chef). The Fishing job block still has no physical worker.

## Builder's Planning Table

1. Empty-hand right-click the placed Table. The first click claims an
   unowned Table; the next click opens its inventory and control panel.
2. In **BUILD**, click **Choose Litematica placement**, then select the
   named placement from the paginated list of loaded client placements. The upload always targets
   **this opened Table** instead of a nearest-block search. Keep the
   Litematica schematic enabled, unmodified and file-backed.
3. Click **Preview** to print the material requirements and unsupported
   states to your chat. Make sure your blueprint and location are correct.
4. Click **Start build**. Use **Pause**, **Resume**, and **Cancel job**
   without leaving the interface.
5. Use the **− / +** controls near **CREW** to request 1–4 workers. The
   current linked/target count, job progress and problem messages appear
   below the buttons.
6. Under **SETUP**, rotate an *unplaced* plan by 90° increments. Once a
   Litematica placement has been imported, its exact orientation is
   preserved; reposition the hologram and re-import instead of rotating it.

### Warehouse linking

Link a Caszual Additions Warehouse Scanner to a Rack Computer, then
right-click the Builder Planning Table **with that scanner**. The SETUP
page shows its saved coordinates; the scanner itself is never consumed or
modified. Material requests still use real warehouse golems, shulker
stations and output PTPs.

## Trader Counter

Open your owned Trader Counter with an empty-hand right-click. The right
settings panel has three pages:

- **ORDER:** enter the full requested item ID such as
  `minecraft:emerald` and the total item quantity. Click **Set order**.
  You can cancel a previous order from this page.
- **BUDGET:** enter a payment item ID such as `minecraft:wheat` and
  the maximum total amount to spend on the order. Click **Set payment
  limit**. Repeat for another payment type when an offer requires two.
  Spending is bounded across all assigned Trader Golems.
- **VILLAGE:** enter the search area's world **X Y Z** and a radius
  (4–128 blocks); click **Save village**. **Use my position** fills the
  coordinates of your player. The center must be in the same dimension
  and within 512 blocks of the Counter.

Each page includes **Pause**, **Resume**, and **− / + CREW** controls
for 1–4 assigned Traders. The Counter displays order progress, status,
and current errors. The first 27 inventory slots hold payment supplies;
the last nine receive purchased goods.

## Beekeeper Job Block

Place and claim the **Beekeeper Job Block**, then place a **Beekeeper
Toolbox** and a separate chest or barrel (or another inventory container)
within eight blocks of it. In the job block's GUI:

- **Find TOOLBOX** links the nearest dedicated Beekeeper Toolbox.
- **Find OUTPUT** links the nearest compatible output inventory; it will
  not select the toolbox or another Golem workstation.
- **Mode** cycles between **Both**, **Comb**, and **Honey**. In Both mode,
  available shears/honeycomb are preferred before glass bottles.
- **Range −/+** selects a 4–24 block horizontal search radius.
- **Pause / Resume** and **CREW −/+** control 1–4 Beekeeper Golems.

Place real shears and/or glass bottles in the toolbox. Workers walk to
borrow tools, visit full hives, deliver honeycomb or honey bottles to
the assigned output, and return used shears to the toolbox. No tools or
harvests are conjured, and saved cargo stays on the entity. For safety
the owner must be online in the same dimension while a worker harvests.

See [Beekeeper guide](BEEKEEPER.md).

## Chef's Kitchen Station

Place/claim a **Chef's Kitchen Station** and deploy a crafted Chef Golem nearby in
the new white uniform with a 3D chef's hat and apron. The chef remains near
the job block as a preview of the future cooking worker. **It does not
take ingredients, cook recipes or create food yet.** A saved worker identity
prevents duplicate chefs across normal chunk unloads. Configuration will
become available with the Cooking Golem's actual work queue.

## Other job blocks

Fishing, Cooking and Beekeeper workstations continue to use their
existing prototype inventories. Their job-specific settings will appear
here as those golem implementations become functional. Builder controls
are **not** shown for other workstation specialties.

## Multiplayer safety and limitations

- All settings are checked on the server. The player must own the exact
  opened job block and be close enough for the inventory to remain valid.
- The UI does not edit local block entity data, bypass material costs, or
  execute arbitrary player commands.
- The interface displays status from the actual block and confirms
  individual actions in the chat. Incorrect IDs, blocked orders or
  unauthorized settings are rejected without modifying other blocks.
- Settings and crew targets persist with the workstation. The client GUI
  is a control surface, not a separate saved configuration.
- Vanilla Minecraft/Fabric GUI scaling may affect smaller displays. This
  remains a development build requiring in-game playtesting.

See [Litematica placement instructions](LITEMATICA_PLACEMENTS.md) and
[playtest guide](PLAYTEST.md) for further behavior and limitations.

## Fisherman's bait and chest targeting (0.3.17-dev)

The Fisherman's Coolers now accept fishing rods and `golems_plus:fishing_bait`
in the first 27 slots, including shift-click transfers from player inventory.
Bait uses an eight-frame wriggling worm-ball texture.

To link an output chest on either the Fisherman's Coolers or the Beekeeper Tool
Bag & Smoker, open its job-block menu, press **CHOOSE OUTPUT CHEST**, then
right-click the desired chest, barrel, shulker box or other storage inventory.
The click marks the container *instead of opening it*; the nearest container is
no longer selected automatically. The Fisher range is 16 blocks, the Beekeeper
range is 8. Invalid clicks keep the picker armed for 60 seconds. A confirmed
link persists across world reloads.

Playtest: place bait and a rod in the Coolers, mark a fishing waypoint and link
a chest other than the nearest one. Confirm the fisherman takes stored bait and
delivers catches to that exact chest. Confirm out-of-range/non-chest clicks
cannot change the link. Repeat selection for the Beekeeper station.
