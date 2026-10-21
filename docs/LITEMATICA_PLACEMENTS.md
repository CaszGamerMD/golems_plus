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
4. Type **`/golems_use_placement`** (client-side command). The selected file
   (up to 2 MiB compressed) and its **exact current origin, mirror and
   rotation** are transferred to the server in bounded chunks. This is an
   explicit upload initiated by the player, not an automatic file scan.
5. Run **`/golembuilder preview`** to see the block count, unresolved states
   and a material estimate. Put building items in the Table's first 27 slots.
6. Run **`/golembuilder start`** to approve actual placement. A Builder
   Golem (temporary Iron Golem model) will move toward each planned block
   and place one at a time. The progress cursor is saved.
7. Use **`/golembuilder status`**, **`pause`**, **`resume`**, and **`cancel`**
   to control the job.

The previous server-side command `/golembuilder inspect house.litematic`
still works for planning, but a Builder can only **start** when a placed
Litematica snapshot has been imported and confirmed.

## Safety and limitations

- **No destructive editing.** Existing correct blocks are skipped; occupied
  mismatching blocks block the job. No automatic clearing.
- **No free resources.** Each newly placed block uses an actual item from the
  Builder Table before changing the world. A failed change refunds the item.
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
- The warehouse-computer link remains metadata-only; the Builder currently
  uses the **Builder Table's actual input slots**, not automated warehouse
  reservations/delivery.
- A passing build/test suite does not guarantee correct in-game Litematica
  API compatibility, multiplayer access-control behavior, rendering or
  AI pathing. These require in-world playtests.
