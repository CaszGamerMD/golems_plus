# Beekeeper Golem: toolbox, output and bee suit

This is the first in-game Beekeeper for **Minecraft Fabric 26.2**.

## Workstation and containers

Place a **Beekeeper Job Block**. Empty-hand right click to claim/open
the settings. Place a **Beekeeper Toolbox** and separate output container
(chest, barrel or another compatible inventory block) within 8 blocks
of that job block.

Use **Find TOOLBOX** to link the nearest purpose-made 27-slot toolbox.
Use **Find OUTPUT** to link the nearest other container. Their locations
are persisted separately with the job block. The two roles never mix:
spent shears go back in the toolbox and harvest products go only into
the output container. The toolbox accepts shears and empty glass bottles;
it is not used for storing finished honey products.

## Worker behavior

- A compact physical golem with a protective hood, mesh face veil, ivory
  coveralls, reinforced gloves and boots matches the approximate height
  and silhouette of Caszual Additions' small Storage Golem.
- Each worker borrows **one actual** shears or bottle from the toolbox,
  carries it physically to a honey-level-five hive/nest, and only
  harvests when the linked output can fit the result.
- Shears collect **3 honeycomb** per harvest, lose one durability, and
  return to the toolbox if not broken.
- Glass bottles collect **1 honey bottle** and are consumed by that
  operation, like vanilla.
- Honey goes to the assigned output, not the toolbox. If the output
  becomes full while traveling, the worker retains its collected cargo
  and waits rather than losing or dropping it.
- Hives retain their resident bees and are reset to honey level zero.
  The first implementation is modeled after bee-safe dispenser-style
  harvests, rather than aggressively releasing bees.
- Workers are peaceful and never attack bees. To avoid unauthorized
  changes, the owning player must be online in the same dimension.
- Tools and harvests are saved on individual entities across normal world
  reloads. Worker membership, linked containers, radius/mode and
  crew target are saved at the job block.

### Job block controls

**Mode** cycles *Both → Comb → Honey*. *Both* prefers shears when
stocked; bottles are fallback. **Range −/+** selects 4–24 blocks
horizontally (hives are checked up to 6 blocks vertically).
**CREW −/+** assigns 1–4 workers to share full hives without
selecting the same target at the same time. **Pause / Resume** stop
new work and movement.

## Limitations

All target chunks, the toolbox and output must be loaded. Workers
physically walk paths and can get blocked by unusual hive layouts.
The GUI currently links the nearest eligible placed container, so
with several boxes together the linked coordinates should be verified.
Honey bottling and comb harvest are implemented; bee breeding,
new equipment crafting, dispenser integration, and special modded hives
are future features. No automated world or multiplayer playtest has yet
been completed.

Build/test passing only verifies Java compilation and the automated
test suite, not all in-game model/routing behavior.
