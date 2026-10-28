# Builder Golem Navigation — Plan B: Grapple Hook (Fabric 26.2)

## Current state

Plan B replaces **new Builder scaffolding access routes** with a reusable physical item, `golems_plus:grapple_hook`. The legacy scaffold classes remain available for old saves/recovery but **the Builder's active work ascent/descent uses `BuilderGrappleAccess`**.

Each Builder takes one Grapple Hook from its physical OnSite Storage chest (including shulker contents) into the nine-slot persisted materials pouch. It stays there during building and can be used repeatedly. No grapple blocks, scaffolds or support towers are placed; nothing is consumed on an ordinary ascent or rappel. Recipe: string and iron ingots in a hook shape; item is in Golems Plus creative tab.

## Navigation rules (physical server behavior)

**Up (maximum 20 vertical blocks from feet to landing):**
1. Look for an actual support block one below the work-height level, within four horizontal blocks of the work position.
2. Verify a sturdy upper surface, two air/headroom blocks above it, and an open cardinal X+, X-, Z+, or Z- face.
3. The adjacent vertical air column must be collision-free and fluid-free along its entire length, and its bottom entry must be reachable from the golem's current position using trained `WorkerNavigation`.
4. Walk to the entry; attach the reusable hook to the valid block. The rope suspends the golem under Minecraft's normal collision handling, and the golem climbs by tickwise physical delta movement.
5. At the ledge, climb slightly above its surface and shift onto the top of that real block. Lower the rope's hold and retract once physically on the ledge. Resume ordinary construction A*.

**Down (maximum 20 vertical blocks):**
1. Start grounded on a real solid ledge and attach to the block beneath the golem.
2. Require open air along a cardinal side. Scan downward for the highest valid landing, with a solid supporting floor, two-block standing headroom, and a clear, fluid-free rope column.
3. While attached, move out over the edge, then lower along the line, collision-checked per tick.
4. Release just above a verified landing (no fall/teleport), retract the hook and resume the existing route.

**Failure:** Absent hook -> require actual OnSite supply; obstructed face/headroom, fluid, no physical entry, missing sturdy anchor, unsafe exit, or distance beyond 20 -> do not attempt traversal; report the blocked construction job instead of inventing a flight/scaffold fallback. If a player removes the anchor while suspended, freeze that rope session rather than letting the golem free-fall.

**Visuals:** The registered item currently uses the vanilla tripwire hook sprite as placeholder artwork. Particle markers show the physical attached line; a dedicated taut rope and hook renderer is a later art task. This is NOT an animation claiming an invisible scaffold.

## Verifiable tests

- [Initial real Builder hook traversal, up & down, Fabric 26.2 CI](https://github.com/CaszGamerMD/golems_plus/actions/runs/40677778900): all three required tests passed; real climb tick 45, return tick 91, hook retained, no scaffolds.
- [Exact 20-block ascent/descent regression](https://github.com/CaszGamerMD/golems_plus/actions/runs/40678582855): test added; consult run conclusion before claiming it passes.
- [Original full 3,537-block dome with four real Grapple Builders](https://github.com/CaszGamerMD/golems_plus/actions/runs/40678080541): failed after **723/3,537** verified physical blocks at tick **6,440**. Real grapple ascent and rappel each occurred in logs. No scaffold towers. Blocking reason: neighboring Builders cannot vacate positions needed for new solid schematic cells.
- [One-block sidestep experiment](https://github.com/CaszGamerMD/golems_plus/actions/runs/40678345607): **693/3,537** at tick **6,740**; did **not** help. Kept in separate dome test branch, not adopted in the Grapple feature.

## Remaining dome blockers

1. **Placement space conflicts:** assigning four Builders X/Z zones does not guarantee their own/teammates' full bounding boxes are clear of the next solid schematic block. The current `BuilderPlacementSpace.ready` / `yieldTick` still creates repeated hold / sidestep loops.
2. **Lower-layer work stranded beneath upper work:** tasks at Y=-55 can remain unfinished while other zone workers climb to Y=-53. A lower-layer Builder may stand on the layer above its own target and be unable to approach at the required placement Y.
3. **Crew fairness:** when a Builder has no pending step but stands in another worker's next placement volume, it must physically vacate or avoid parking there.
4. **Future game tests:** create a narrow shared-ledge four-Builder occupancy deadlock fixture based on these logs; verify it first, then repeat the same full dome.

Do not modify the trained A* with an unverified fallback, teleport, noclip, unsafe gravity drop, or fake construction inventory.
