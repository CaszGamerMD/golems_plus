# Builder navigation / vanilla pathfinding trials (Fabric 26.2)

This is an isolated experiment; it does **not** change production movement
or automatically merge into `main`.

## Why

A planned A* route, a native Minecraft `Path`, and a moving Builder are three
different things. A route is only successful when the entity **physically**
reaches the intended location, including after obstacles appear or another
worker takes the same narrow passage.

## Validation matrix

| Test | Mechanism | Pass condition |
| --- | --- | --- |
| Blocked route | `WorkerRoutePlannerTest` | Replans around a newly unavailable cell and never traverses it |
| Temporarily blacklisted waypoint | `WorkerRoutePlannerTest` | Reroutes without revisiting the problematic cell |
| Multiple reachable goals | `WorkerRoutePlannerTest` | Chooses the least expensive *actual* route |
| Disconnected near destination | `WorkerRoutePlannerTest` | Returns no false completion |
| Wall detour vs native A* | `BuilderNavigationTrialGameTest` | Vanilla sees a route, Builder physically walks around the wall to the exact target |
| Block placed mid-route | `BuilderNavigationTrialGameTest` | Builder leaves its original line, avoids the new collision, and physically arrives |
| Two-worker shared doorway | `BuilderNavigationTrialGameTest` | Both workers enter the door and emerge on the far side without permanent queue deadlock |
| Existing scaffold ring & doors | Existing server GameTests | No regression in climbing, ring walking, descent, oak door, or fence-gate passage |

## Commands

From the repository root:

```sh
gradle --no-daemon test
gradle --no-daemon runGameTest
```

The CI workflow already runs `runGameTest` and `clean test build` for PRs.

When testing in-game, walk within 16 blocks of a Builder and run:

```
/golembuilder navdebug
```

This is a **read-only snapshot** of the nearest loaded Builder. It includes
world position, grounded state, velocity, next vanilla path node,
native path index/length, current weighted-route index/length, retry ticks,
stalled/unsafe counts, avoided nodes, doorway queue state and scaffold state.
It can be called even without a live construction assignment. Alternatively,
shift-right-click an assigned Builder.

If a Builder stops moving, capture the command output **before** resetting or
replacing it. Check for:

- `native=none`: vanilla has not accepted a path (or it was stopped).
- `native=N/M next=x,y,z`: the mob has an active native route; compare its
  next waypoint with the actual body collision and the last movement.
- `weighted=N/M`: custom planner progress, which is independent from the
  native path index.
- `avoid=` and `retry=`: path rejection and backoff, not necessarily a
  genuinely unreachable job.
- `door= ... WAIT/EXIT/CROSS`: a door queue or explicit physical crossing.
- `scaffold=`: manual vertical movement may have taken control from native A*.

## Actual execution evidence (October 25, 2026)

- Current `main` baseline GameTest/Gradle workflow: **passed**, run
  [40184739410](https://github.com/CaszGamerMD/golems_plus/actions/runs/40184739410).
- Independent Java harness compiling the route planner with minimal
  `BlockPos` and `Direction` stand-ins: **5/5 algorithm scenarios passed**
  (straight, new obstruction, blacklist, lower-cost destination, disconnected).
  This is **not** a Minecraft physics test or a full Gradle build.
- The pull request's GitHub Actions jobs: **failed before recording any job steps (cause not yet identified)**;
  the first PR workflow ended in seconds with no recorded job steps. The same
  immediate behavior appears on unrelated PR branches. The three new in-game
  GameTests are **written but not yet executed**. Do not claim a physical pass
  until the dedicated server run succeeds and the GameTest reports are checked.

## Evaluation

The planner's local search uses four cardinal directions and is limited by
`RADIUS=40`, `NODE_BUDGET=2800`, and eight blocks of vertical variation.
These are deliberate route search constraints, not evidence of a physics bug.
Extended scaffold ascent/descent is performed by `BuilderScaffolding` under
separate movement ownership. Failures at the route handoff (native A* to
scaffolding, tower to platform, queued door to door exit) need Minecraft
physics tests, not only unit tests.

Do not relax collision safety or teleport Builders to make trials pass.
Keep this experiment as a draft until both CI and in-game movement checks pass.
