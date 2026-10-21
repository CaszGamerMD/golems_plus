# Configure Golems Plus from job blocks

Settings are now in the **workstation inventory** instead of requiring
slash commands. Install the same Golems Plus build on client and server.
Existing commands remain optional for compatibility and debugging.

## Builder's Planning Table

1. Empty-hand right-click the placed Table. The first click claims an
   unowned Table; the next click opens its inventory and control panel.
2. In **BUILD**, click **Import Litematica placement** to use the placement
   selected in the client's Litematica interface. The upload always targets
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
