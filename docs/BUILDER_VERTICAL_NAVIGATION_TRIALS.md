# Builder vertical navigation and temporary bridge trials (Fabric 26.2)

Status: **experimental specification and tests, NOT a claim of implemented bridge-building**.
Branch: `experiment/vanilla-worker-pathfinding-trials`, draft PR #80.
Do not merge to `main` until Minecraft server GameTests and real construction testing confirm behavior.

## Navigation requirements

1. Reaching Y+ is a **multi-leg route problem**, not a request to walk at a high coordinate.
   The route may contain walking, steps/stairs, owned-scaffold ascent, upper-floor walking,
   temporary horizontal scaffold bridges, return-to-scaffold, descent, and safe dismount.
   A destination is *reached* only when actual feet and reachable work position agree.
2. Prefer an existing safe, navigable floor / stair / existing owned scaffold before
   consuming any blocks. If that fails, seek a safe foundation and build an owned tower.
3. If an overhang, platform, wall, or roof blocks the first vertical column, compare
   alternate foundations *and* accessible top landing positions. Do not keep rebuilding
   the same blocked tower or repeatedly walk into the same wall. Persist a brief
   avoidance reason (overhead obstruction, unwalkable approach, unavailable top exit).
4. Reuse completed elevated walkways. After stepping off a scaffold, run real A* on that
   level to find a detour around obstacles, rather than abandoning the elevation.
5. Cross a gap only on supported real blocks with physical collision. Never jump across
   an unsupported fall, phase through blocks, teleport, or create a floating walkway.
   A **temporary bridge** will be placed from a reachable, supporting anchor toward a
   verified destination/landing. Minecraft scaffold support-distance rules apply.
   Stop safely and request more materials / another route when the gap exceeds the
   current scaffold's support range.
6. All temporary scaffolding/bridge positions are tracked per owner and per planner.
   Withdraw **real** items from builder inventory or OnSite supply with exact receipts.
   Block placement requires permission and an empty space; if a placement or tracking
   step fails, refund that exact receipt. On cleanup, remove only owned surviving blocks,
   after the worker is safely elsewhere, and refund only the actually recovered blocks.
7. A builder must be able to descend from upper work to real ground to resupply. Never
   cancel downward motion over open air. Avoid taking ownership away from a live climb.
8. Coordinate shared narrow bridges and scaffold landings among builders (reservations or
   yielding) to prevent head-on deadlocks without blocking unrelated construction sites.
9. Search and repair paths in loaded chunks with bounded work per tick; no aggressive
   asynchronous world access or forced chunk loads just to evaluate every candidate.
10. On schematic completion, never remove scaffold blocks still needed by another
    builder or supporting a route that has not yet finished. Do not overwrite schematic
    blocks with temporary supports.

## Suggested route decision order

- Evaluate a validated existing ground/stair/platform route to a legal work stance.
- Evaluate reachable owned towers, ranking by walking distance + vertical climb +
  ability to step off onto a genuine supported landing at the required height.
- Evaluate placement of a new, collision-clear tower. Reject blocked shafts;
  search around obstacles rather than repeatedly testing only the closest X/Z.
- Where a safe upper landing lies across a gap, evaluate a bounded temporary scaffold
  bridge from a real support. Reserve and consume supplies one placement at a time.
- If no safe bridge or landing exists, choose another tower/approach or return for
  materials; report a precise reason instead of issuing a doomed walking path.

## Test inventory

| Trial | Test | What it proves |
|---|---|---|
| Flat obstacle detour | BuilderNavigationTrialGameTest.builderTakesRealDetourAroundSolidWall | Ground collision and native A* route execution |
| Midroute obstacle | BuilderNavigationTrialGameTest.builderReplansAfterNewBlockCutsItsRoute | Stop/re-plan instead of walking through freshly placed blocks |
| Shared doorway | BuilderNavigationTrialGameTest.twoBuildersShareOneDoorwayWithoutDeadlocking | Two workers physically cross one narrow opening |
| Y+ physical ascent and descent | BuilderScaffoldRingGameTest.climbWalkRingReturnAndDescend | Existing regression: climb, perimeter, descent, ground |
| Y+ obstacle detour | BuilderVerticalNavigationGameTest.climbsDetoursAtYPlusFiveReturnsAndDescends | Elevated wall bypass and handoffs between scaffold and walking |
| Temporary bridge feasibility | BuilderVerticalNavigationGameTest.traversesPrebuiltTemporaryScaffoldBridge | Prebuilt span crossable under actual Minecraft physics |
| Rising route planning | WorkerRoutePlannerTest.findsAlternativeRisingRouteWhenDirectYPlusApproachIsBlocked | A* uses a valid stepped detour rather than a blocked direct climb |
| Vertical-controller boundary | WorkerRoutePlannerTest.highTargetOutsideGroundWindowNeedsVerticalMovementController | Ground-only A* does not falsely complete tall routes |

## What remains before production

- Builder-built scaffolding **bridges** are not yet implemented; current bridge
  fixture places the spans in test setup solely to determine whether the vanilla
  ground navigator can physically traverse them.
- Add separate end-to-end tests for placing each bridge segment from real inventory,
  walking across, reconstructing route after obstruction, returning to ground, and
  retrieving only owned materials with no drops/duplication.
- Add direct collision tests for a **blocked vertical shaft** and selecting an
  alternative safe tower near a higher destination.
- Add builder-on-bridge versus builder-on-bridge traffic with safe turnouts.
- Confirm all Minecraft-specific methods compile and dedicated-server trials succeed.
  An isolated planner/JUnit test does not prove actual game physics.
- Trial CI has been failing before jobs execute on multiple unrelated PRs as of
  2026-10-25, so test outcomes remain **unverified** until the runner executes.

## Useful in-game diagnostics

- `/golembuilder navdebug` prints nearby Builder movement status even without an
  assigned job. Records coordinates, velocity, native next node, weighted-route
  cursor, temporary avoidance, doors and scaffold movement state.
- Shift-right-click a Builder with an active assignment for job-level diagnostics.

Do not make a feature branch experiment seem successful solely because the
GameTest source exists or a static code smoke test passed.
