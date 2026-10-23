# Compact professional golem models

All three new golems use the Caszual small-worker proportions (roughly the
Beekeeper / Storage Golem silhouette). They no longer render using Minecraft's
full-size Iron Golem model. Shared skeleton pieces are independently
textured with **256×128** Minecraft-style palette atlases; hats, safety
stripes, aprons and cases are actual raised cuboids rather than flat decals.

## Builder

- Bright yellow hard hat: raised crown, protective projecting brim and
  central rib, attached to the animated head pivot.
- Orange/yellow hi-vis coat and 3D raised white/silver reflective strips.
- Utility belt and equipment pouch, reinforced gloves and work boots.
- When carrying a real shulker box, both forearms enter a supporting pose.
- Uses existing blueprint construction, scaffold, and warehouse delivery
  logic; entity is now small rather than 2.7 blocks tall.

## Trader

- Tailored navy/dark charcoal jacket, modelled lapels, crisp white shirt,
  red tie, waistcoat, neat shoes and styled hair.
- Brown 3D briefcase with handle and brass latch; parented to the right
  forearm, so the case moves with walking rather than floating in the body.
- Arm swing is restrained, and the left arm can gesture when on an order.
- Existing villager-trading state, payment escrow and deliveries are kept.

## Chef

- Tall white chef's toque with a crown, cuff/band and folded lip.
- White chef coat, modeled apron and straps, cuffs, dark practical trousers
  and non-slip shoes.
- A new craftable **Chef's Kitchen Station** summons one persistent Chef
  once it has been claimed. Chef is a passive presence at the station.
- **Cooking automation is not implemented.** No ingredients, recipes,
  cooking AI, new loot or magical item creation have been enabled by
  this visual feature.

## Assets and compatibility

- Shared model/renderer code lives under
  `src/client/java/com/caszgamermd/golemsplus/render/`.
- Per-profession textures:
  `assets/golems_plus/textures/entity/{builder,trader,chef}_golem.png`.
- Server-side Builder and Trader logic is unchanged except inheriting
  `PathfinderMob` instead of a vanilla `IronGolem` to avoid giant
  collision dimensions, iron-golem effects and unintended iron drops.
- Renderer model layers are registered client-side only.
- All existing Builder and Trader job block ownership and work orders
  remain in their existing block entities.
- Smoke validation checks that assets are valid 256×128 images; CI
  compiles and runs JUnit tests. The in-game appearance, animation,
  collision and transitions still require manual Minecraft playtesting.

To see all three in-world: run existing Builder and Trader jobs to spawn
their workers; place and claim the Chef's Kitchen Station for the idle
chef. The Chef also has entity ID `golems_plus:chef_golem`.

## Full asset polish — 0.3.20-dev

- The Beekeeper's entity atlas has been rebuilt at 256×128, and each suit
  material has dedicated UV space instead of overflowing the 64×64 sheet.
  The protective face mesh, hood, gloves, sleeves and boots have distinctive
  low-resolution materials; the existing worker rig/animation is preserved.
- The Beekeeper workstation no longer references the nonexistent vanilla
  `minecraft:block/leather` texture. Its front pocket seams are spaced
  away from neighboring faces to reduce depth flicker.
- Fishing Coolers (including the blinking variant) have slightly raised
  aluminum drink-can rims, with interior fin faces hidden to prevent z-clipping.
- The Trader Counter's nested cabinet faces and lamp shade are tidied so
  co-planar surfaces cannot fight over the same pixels.
- The Builder's Planning Table now has a detailed drafting station model:
  four legs, a framed wooden top, a parchment blueprint, measuring rail,
  metal clips, pen and brass accents. All are vanilla-like cuboids and the
  original registered block/item IDs and behavior are unchanged.
- Asset checks ensure declared Golems Plus model references resolve and
  entity PNG dimensions match the UV atlases.

Review worker outfits at walk, idle, carry and work animations. Check the
new Planning Table from all four sides, Trader lamps at night, Fisher cooler
fish face and cans, and Beekeeper veils at close and distant camera ranges.
