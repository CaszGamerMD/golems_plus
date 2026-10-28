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

## Revised placement, OnSite return, and layer scheduling rules (Oct 28)

- **Schematic-local coordinate frame** for all future dome GameTest reports: the Litematica placement origin is reported as `(0,0,0)`, construction starts at local Y=0 and support terrain is Y=-1. Fabric GameTest still generates at an arbitrary actual world coordinate, but progress, targets, feet, and yield escapes are reported as stable schematic-relative XYZ. [Latest normalized dome test](https://github.com/CaszGamerMD/golems_plus/actions/runs/40682000149).
- **0.8-block clearance:** every new solid schematic block requires the center of every nearby Builder to be at least 0.8m outside its nearest X/Z face and the actual 0.65-wide entity bounding box not to intersect the future block. Stand selection considers this margin; a precision sidestep uses collision-checked horizontal Minecraft entity movement each tick, including alternative cardinal choices and partial-block/slab footing.
- **Shared Y-layer barrier reinstated:** ALL Builders must complete every schematic step on the lowest unfinished Y before ANY Builder can receive a placement assignment on a higher Y. The Planner's first unfinished step (`builderCursor`) determines the active layer, including low-Y steps that have been deferred for support, collision, materials, or access. X/Z ownership, independent movement, grapple climbing/rappelling, and door pathfinding remain unchanged. Workers with no runnable task in the current Y return to OnSite until their layer becomes available. This gates **build assignments**, not physical traversal to safe work positions.
- **Return to OnSite:** an assigned Builder with no currently runnable tasks (whether fully finished or temporarily deferred) returns via physical Grapple Hook descent and trained A* to its designated OnSite Storage area. It remains available for retries, not abandoned in the structure.
- **Blocked grapple recovery:** a repeatedly blocked top landing initiates controlled rope retreat back to grounded entry, and temporarily blacklists that actual anchor before choosing a new one. Never drop the worker or teleport.
- **Physical clearance proof:** [dome CI #40682000149](https://github.com/CaszGamerMD/golems_plus/actions/runs/40682000149) logged a focused live Builder self-clearance test at ~0.94m, no teleportation/collision, passed. Dome E2E still FAILED at 821/3537; narrow construction work stances remain a separate problem and must not be represented as solved.

The user-facing requirement is that a Golem should occupy build-site space only while it has a runnable physical construction assignment; otherwise it should return OnSite. The actual Minecraft GameTest world coordinate cannot be forced to absolute world `(0,0,0)` without replacing the isolated GameTest runner; logs make the Litematica-local `(0,0,0)` coordinate system authoritative.

## Strict shared-layer regression

The two-Builder Fabric GameTest `BuilderLayerGateGameTest` verifies independent X/Z zone ownership, a delayed lower-Y step, and that neither Builder can claim higher-Y work while ANY lower-Y block is incomplete. After the lower step is physically reported complete, both Builders may claim the next layer. The full 3,537-step dome test stays separate from these focused navigation regressions. Test results should be checked before claiming an end-to-end dome completion.

## One-block-below placement stance (Oct 28)

Builders may place an assigned schematic block at **feetY-1** as well as at their own feetY. This is **not** permission to start constructing the next Y layer: global `builderActiveLayer` gating continues to restrict *assigned target blocks* to the lowest unfinished Y across the crew.

A downward placement is legal only when the Builder is **physically grounded** one block above the assigned block, within the existing work reach, with **at least 0.8 blocks of horizontal X/Z clearance from the nearest edge of the future block**. The same margin applies to same-Y placements. All Builders whose bodies intersect the future cube, or whose centers violate the X/Z margin even from a higher ledge, must step aside before payment or `setBlock`. The upper Builder is not instructed to rappel down unnecessarily; stand selection searches Y and Y+1 for actual collision-safe, reachable footing. No flight, teleportation or scaffolding fallback.

Physical test cases in `BuilderPlaceOneBelowGameTest`: grounded upper ledge at 0.85 margin (accept); 0.60 margin (reject, physically sidestep and then place); feet at Y+2 (reject). Dome GameTest shares these rules with the focused physical tests.

## One-block jump precedence and grapple disengage (Oct 28)

**Normal movement first:** Before starting a grapple, Builders test the existing trained, physically walkable route, including real one-block vanilla jumps/steps and single-block drops. A valid ground route wins, even when the Builder carries a hook; unreachable jumps do not become free flight. Grapples are reserved for vertical changes without such a safe path.

**Actual-motion timeout:** A hooked Builder is judged by change in its real server position every tick, not by successful navigation or velocity commands. A hooked climb/rappel without meaningful motion for 45 ticks enters a physical recovery phase.

**Climb fails:** Retract onto the same open cardinal rope column, descend to the original real grounded foot position, and only then release the rope. Reject this specific anchor AND face for 300 ticks before planning the next hook.

**Rappel fails:** Follow the same rope **back up**, slightly above the original anchor, physically step onto the supported upper ledge and only then release. Reject the failed anchor/face for 300 ticks, leaving alternate anchors/faces available. Never teleport, disable collision, turn on creative flight, free-fall, or drop rope support while suspended. If the route itself becomes obstructed during recovery, keep the rope attached and emit an explicit safety diagnostic instead of silently abandoning the golem midair.

**Regression coverage:** `BuilderGrappleRecoveryGameTest` tests real native one-block jump, normal one-block drop, obstructed ascent returning to original ground, and obstructed rappel climbing back up and selecting a different face. Existing 20-block range, equipment ownership, rooftop return, shared Y-layer construction and 0.8 X/Z placement tests remain in the same Fabric GameTest suite.
