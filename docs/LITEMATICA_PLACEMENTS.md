# Build from a placed Litematica schematic (experimental)

## First-time setup
Install Litematica and its matching MaLiLib on the **client**.
Install Golems Plus on both the **client and server** (Fabric 26.2).
The dedicated server does not need Litematica or MaLiLib.

1. Load a file-backed `.litematic` in Litematica and position its hologram
   exactly where you want the structure.
2. In **Schematic Placements**, select this placement. Keep it enabled and
   loaded; use all sub-regions at their default relative positions/rotations.
   The first bridge rejects modified/disabled sub-regions to avoid incorrect
   block positions.
3. Place and claim a **Builder's Planning Table** within eight blocks of you,
   and no farther than 256 horizontal blocks / 128 vertical blocks from
   the hologram's origin.
   **To link a warehouse:** sneak-right-click a Caszual Additions Rack
   Computer with your Warehouse Scanner to link it, then right-click
   the Builder's Planning Table with that same linked scanner. Any scanner
   mode works; it is not consumed or reconfigured. A valid scanner can
   link the Table even if the computer is in an unloaded chunk. Use
   `/golembuilder status` to confirm the saved warehouse.
4. Open your Builder Planning Table and click **Import Litematica
   placement** in the **BUILD** page (or use the legacy
   `/golems_use_placement` command). The selected file
   (up to 2 MiB compressed) and its **exact current origin, mirror and
   rotation** are transferred to the server in bounded chunks. This is an
   explicit upload initiated by the player, not an automatic file scan.
5. Click **Preview** in the Planner GUI to see the block count, unresolved states
   and a material estimate. You may stock the Table's first 27 slots manually;
   otherwise, the Builder can request materials from the warehouse linked with
   the scanner. The warehouse must have a connected empty OUTPUT PTP
   (with a pallet installed), Shulker Station and docked Storage Golems.
   Its Shulker Station needs empty shulker boxes for packing.
6. Click **Start build** to approve actual placement. A Builder
   Golem (temporary Iron Golem model) will move toward each planned block
   and place one at a time. The progress cursor is saved.
7. Use the GUI **Pause**, **Resume**, **Cancel job**, and **CREW −/+**
   controls. Set the crew size as high as four
   linked builders working separate blocks of the same schematic.

The previous server-side command `/golembuilder inspect house.litematic`
still works for planning, but a Builder can only **start** when a placed
Litematica snapshot has been imported and confirmed.

## Safety and limitations

- **No destructive editing.** Existing correct blocks are skipped; occupied
  mismatching blocks block the job. No automatic clearing.
- **No free resources.** Each newly placed block uses an actual item from the
  Builder Table before changing the world. A failed change refunds the item.
  High builds additionally require real scaffolding blocks, which are laid
  temporarily and reclaimed for reuse without mining drops. The Builder
  only removes scaffolding it placed and recorded itself.
- **No forced chunkloading or teleporting.** The Builder waits if targets,
  the worker, or table are unloaded. The owner must be online nearby in the
  dimension. Inaccessible locations block physical navigation.
- **Protected land.** Server interaction checks run as the owner, but claim
  plugin-specific hooks have not been audited; test on worlds you control.
- **No NBT inventory cloning.** Block entities, fluids, doors, crops, unsupported
  non-single-blocks, and waterlogged placements are intentionally rejected.
- **Rotation and mirror** apply to block coordinates and block-state
  orientation. Litematica grids/repeated placements, individual subregion
  transformations, and unsaved in-memory schematics are not supported yet.
- **Limits:** compressed file 2 MiB, 100,000 target blocks, 256-block radius
  from the Builder Table, world height checks. Use a backed-up test world.
- Repositioning the hologram **after import** does not change the server's
  snapshot: re-run `/golems_use_placement` before starting.
- A Caszual Additions Warehouse Scanner **right-clicking the Builder Table**
  copies its saved Rack Computer link. The Table's owner can repeat the
  action with a differently linked scanner to change warehouses. Unlinked,
  wrong-dimension or invalid loaded-computer targets are rejected without
  changing an existing link. A remote, unloaded computer is not force-loaded;
  it must be checked again when material integration is implemented.
- **Automatic warehouse fulfillment (experimental):** the Builder requests
  batches from a linked warehouse when local materials are missing. An empty
  OUTPUT PTP is exclusively assigned to the request. Warehouse Storage Golems
  fetch from racks and a connected Shulker Station packs real supplies into
  shulker boxes. The Builder physically retrieves those shulker boxes, stores
  them intact in the Planning Table, and builds using their real contents.
  PTP requests, Builder order metadata and carried boxes are persisted.
  When a supply shulker becomes empty, the intact empty container moves
  into the Planner's nine output slots (or stays in input if full).
  It remains at the Planning Table for the player to collect.
- **Missing-supply notices** appear in chat for the online owner and in the
  Planning Table's status, including rack shortages and missing empty shulkers.
  No material is synthesized to make an order succeed.
- **Operational constraints:** keep warehouse racks, golem shelves, Shulker
  Station and chosen PTP chunks loaded; ensure empty packing boxes and
  reachable paths between the Builder Table and warehouse. Warehouse supplies
  are shipped one packed shulker at a time. Empty boxes remain in the Builder
  Table for manual return to the warehouse at this stage. The Builder pauses
  if its input slots are full or no suitable PTP is available.
  A scanner link is not a general warehouse authorization grant; follow
  your server's build and ownership permissions.
- A passing build/test suite does not guarantee correct in-game Litematica
  API compatibility, multiplayer access-control behavior, rendering or
  AI pathing. These require in-world playtests.

## Work crews

Use the Planner's **CREW −/+** controls to assign 1–4 workers
without commands. Each linked Builder claims a different blueprint
block; the durable progress checkpoint advances only when preceding blocks
are complete. The same warehouse request uses one supply courier per trip,
so multiple Builders do not each collect the same shulker.

For Trader Counters, the **CREW −/+** controls set the count of
participating Trader Golems. Their order quantity and payment budgets account
for already dispatched transactions. Reducing crew size stops assigning
new tasks to the extra golems after their current jobs are done.

Temporary scaffolding for elevated blocks requires real
`minecraft:scaffolding` from the table or warehouse. Builders track every
temporary block they add, never remove player-placed scaffolding, and reclaim
scaffolds when they can safely descend and store the blocks.
