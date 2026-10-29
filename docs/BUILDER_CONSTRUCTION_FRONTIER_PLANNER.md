# Builder Construction Frontier Planner — Fabric 26.2

The trained `WorkerNavigation` movement rules, real Builder Golem entities, reusable Grapple Hook, normal 1-block jump/drop, door traversal, 0.8-block X/Z clearance, physical item consumption, and **shared all-workers-complete-Y-before-next-Y** construction rule remain unchanged.

## Problem solved by this first planner iteration

Previously `claimBuilderZoneStep` picked the first not-yet-complete indexed cell in a ten/32-cell work order. Only *after* that claim did `BuilderBuildService` attempt to find a place from which the Builder could place it. A narrow ledge, blocked target, missing block support or lack of exit could therefore pin the active claim forever, even though other same-Y blocks in the Builder's X/Z zone were physically reachable. Repeated near-tile retests gave the appearance of failing navigation AI.

## Actual implementation

- `BuilderConstructionPlanner.choose` runs server-side *before* persistent claim. It sees only incomplete, non-deferred cells at `table.builderActiveLayer`, in the worker's own original X/Z zone.
- For each candidate it reads the real block state (matching blocks and ignored plants are instant no-ops), resolves the actual schematic block, and checks `state.canSurvive` in the current world.
- `BuilderPlacementSpace.approachWithRules` checks actual grounded Y or Y+1 feet poses, 0.8m center-to-edge spacing, complete mob collision, the existing 14-squared work reach and trained `WorkerNavigation.canReachExact` A*. The planner's predicate also requires a direct *collision ray* to the target, and at least one collision-swept departure that would remain clear of the **future** placed block, using real cardinal/diagonal movement, a normal one-block jump, or a supported one-block descent.
- If no walkable work pose exists at a higher Y, the planner may accept a **previewed physically valid** `BuilderGrappleAccess.canPlanAscent` only when the worker actually carries its hook, the anchor/side/rope column pass real checks, and A* can reach the rope entry. This does not throw or teleport anything during preview.
- Reachable work poses with direct skylight have a **score advantage**; darkness/cover is not a prohibition. Ordinary walking is favored over grappling.
- The search evaluates a bounded 32 same-Y cells per pass. If none is feasible, the per-worker search window rotates so the next pass can examine the *rest* of the current Y rather than retrying the same 32 unreachable cells.
- `WorkerStationBlockEntity.claimPreferredBuilderZoneStep` persists the validated selected index. The stage is reordered without removing completed tasks, moving inventory remotely, duplicating items, or crossing zone/Y boundaries. `BuilderBuildService` revalidates the physical stance/exit right before consuming the item and placing the block.
- If a worker has no safe same-Y candidate it returns OnSite while preserving outstanding work, rather than claiming an imaginary route, stepping into a future solid block, or starting the next Y.

## Physical test evidence

[GameTest CI #40765618586](https://github.com/CaszGamerMD/golems_plus/actions/runs/40765618586): **20/20 required tests passed**, including the existing 16 movement/clearance tests and four new construction planner tests:

1. An unsupported lower-Y ladder is skipped, a reachable same-Y stone is claimed and built, Y+1 stays locked, and the previously blocked ladder is retried after real support appears.
2. A fully enclosed target is rejected rather than reaching through a two-block-thick wall; another same-Y accessible block can proceed.
3. A proposed block must not seal the Builder's only safe side-exit. Opening an actual alternate tile makes the pose usable.
4. 35 individually unsupported nearby cells do not starve the one farther reachable same-Y task; rotating two consecutive bounded search windows finds it.

## Limits and next work

This is the **first construction-aware planning milestone**, not a claim that the 3,537-block dome finishes. The post-placement exit check guarantees an immediate physically supported exit move (including an ordinary jump or drop); it does **not** prove a full post-build A* route to OnSite through the future structure. It does not yet reserve the chosen work stance/corridor from other Builders in advance, nor construct cranes or temporary platforms. These are explicit future enhancements once we have physical dome evidence.

Full 3,537-block original dome with four real Golems is run separately on `test/dome-frontier-planner-262` so the known-passing focused suite is preserved.

## Full-dome integration measurement (first iteration)

The first 3,537-block physical dome pilot regressed to **63 blocks**, because changing staged frontier targets caused repeated OnSite supply return attempts. Fixed the inventory handoff to let a Builder use materials **actually carried in its persisted pouch** before considering restaging; the second pilot placed **404 blocks**. Neither was a complete dome, and both exposed an overly restrictive cardinal/same-height exit rule. The planner now also evaluates 8-direction walking, one-block jumping and one-block supported descent against the future block and the current world's swept collision shapes. [All 20 focused tests still pass](https://github.com/CaszGamerMD/golems_plus/actions/runs/40766893650). The revised 3,537-block pilot runs on `test/dome-frontier-planner-262`.


## October 28 real-dome findings and retained checkpoint

- Ignored azalea, short grass and other configured ignored vegetation
  are completed as zero-placement no-ops before support checks. The
  separate test verifies that 35 inaccessible tasks do not starve ignored
  vegetation. No temporary plant supports are invented.
- Escape proof uses real swept body collisions, normal jumps, diagonal
  movement and safe one-block descents, not the 0.8 m *placement-arm*
  clearance applied to a subsequent walk.
- Frontiers rotate through their bounded same-Y scan windows even after
  a task was found. That stopped the same five nearest deferred cells from
  monopolizing each worker, with a 286/3537 real dome high point on CI
  run 40792952211.
- The live trained route controller still sometimes rejects a pose that
  its original *native-path* preflight labels exact-reachable. Those are
  not proven physically executable by the trained route policy. The
  latest logged worker deferrals identify **trained routes rejected work
  pose** as the primary active failure.
- Holding claims through every trained retry for 120-160 ticks was tested
  separately and regressed to 262 and 238 real blocks with extensive
  low-progress loops, so the held-claim experiment was rolled back.
- Focused Fabric GameTests pass; they do **not** demonstrate that the
  complete 3,537-block dome is built. The full-dome branch still blocks
  at Y=0. Its game-test fixture and detailed per-worker failure logs
  remain separate from main.

The next production-quality step is to make `WorkerNavigation.canReachExact`
preview the SAME weighted collision-validated route that `routeToExact`
can actually execute, including its native short-leg physical handoff.
Then build a multi-option access planner for cells where the trained
route has no real safe first leg, rather than claiming an unreachable
position or allowing any legacy fallback/creative flight.


## Follow-up: trained-route parity and exact physical dome trials

**Verified targeted CI:** run 40795734956 passed 25/25 physical GameTests.
The Builder work-position selector now uses one bounded multi-goal trained
A* and checks that a real native short leg can execute before claiming
the stance. It no longer mistakes a native-only exact route for a
feasible trained work stance. The 3,537-block, four-Builder Dome test
advanced to **340 real blocks** (run 40795799999).

The physical descent planner was then made to select trained-executable
one-block drops and grapple ledges rather than native-only attachment
poses. Focused tests remained 25/25 green (run 40796150132); the Dome
reached the new **436/3,537** high point (run 40796206822) with
global Y still locked to 0 and the exact original file.

Further controlled trials:
- A single-attempt hook prefetch yielded just one equipped Builder
  due to temporary shared chest stock and **397** real blocks
  (run 40796697940).
- A bounded 80-tick startup equipment window equipped **all four**
  Builders with real OnSite Grapple Hooks, but produced **313** blocks
  (run 40796999311).
- Preserving an intermediate trained descent route from being
  immediately replaced by the OnSite route yielded **307** blocks
  (run 40797530506).
- No actual grapple traversal was logged in either all-hooks trial.
  The blocked current-layer task candidates still often lacked a
  physically executable path from the builders' elevated Y+1 rim.
  Additional hooks are therefore NOT a substitute for access geometry.

The startup hook and modified parking experiments were **reverted**;
their attempts remain in Git history for reproducible comparison.
The production feature branch and independent dome branch are restored
to the highest observed **436-block** movement configuration.
The test still has not completed the first schematic layer.

**Next engineering requirement:** plan physical access before sealing
narrow Y=0 wall/rim corridors. A reachability-preserving construction
order or actual material-backed, supported temporary scaffolding/bridge
must keep at least one verifiable route between each Builder's current
Y+1 rim and remaining Y=0 work stances, including safe return to OnSite.
It must honor the existing same-Y construction gate and never cheat
with creative flight, instant blocks, teleports or pathfinding fallbacks.
