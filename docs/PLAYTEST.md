# Golems Plus — development playtest (Minecraft 26.2 / Fabric)

## Trader Golem

1. Place **Trader Counter** from the Golems Plus creative tab and right-click
   empty-handed once to claim it, then again to open its 36-slot depot.
2. Put actual payment items in its top 27 input slots; bottom 9 slots are
   output only. No payment items are created automatically.
3. Stand at your village and run `/golems trader village 32` while your
   owned Trader Counter is within 8 blocks. This selects the center/radius.
4. Run `/golems trader order minecraft:emerald 16` to set an output goal.
5. Run `/golems trader budget minecraft:wheat 320` to authorize spending.
   Repeat for other payment types if a trade uses two inputs.
6. Check `/golems trader status`. The Iron-Golem-shaped Trader Golem spawns
   at the counter, uses its navigation to reach loaded villagers and attempts
   one actual trade per trip before returning with results.
7. Watch actual villager offer availability, pathing, spending and output
   inventory. A full output stops delivery; clear slots to allow return.
   Breaking a counter drops its inventory. Canceling an order makes a
   traveling worker return unused payment items.

**Current limitations:** Temporary iron-golem appearance, no dedicated
3D model, and command configuration rather than in-GUI order settings.
Order settings require a claimed Trader Counter within command reach; the
village center may also be set at the counter itself. A villager needs to
remain loaded and reachable; there is no forced chunkloading or teleport.
The worker refuses offers when payment costs change after departure.
Keep the depot loaded while testing. Server restarts/unloaded entities
may pause worker dispatch until the entity is loaded again rather than
spawning a duplicate. Transaction recovery and rollback with concurrent
players requires extended multiplayer testing.

## Builder import (read-only)

1. Copy a `.litematic` file to the SERVER config folder:
   `config/golems_plus/schematics/<name>.litematic`.
2. Place and claim a Builder's Planning Table within 8 blocks.
3. Run `/golembuilder inspect <name>.litematic`.
4. Inspect block count, item estimates and unresolved block states in chat.

The importer reads compressed NBT and supports multiple regions, palettes
and signed region extents. It does not place blocks, import block entities,
read schematics from other directories, or move supplies yet. Unsupported
states are intentionally flagged instead of guessed, and no materials are
ever created from schematic input. Use it to verify a build before future
placement/warehouse integration.

## Safety

Do not test initially with irreplaceable valuables. Test on a backed-up
world while villagers, items and paths are actively changing.
