# Navigation-to-construction transfer (NNGolems only)

## Problem and goal
The dome waypoint course measures whether the trained navigator can reach
specific physically supported positions. It does **not** measure whether
builders can reach an executable pose, place the next material-paid block,
preserve the exact schematic block state, or finish their current Y phase.

Construction acceptance requires a fresh, non-prefilled, unmodified
3,493-required-block Dome with eight real workers. The main branch is not
modified. No route, flight, stock, collision, support or post-placement-exit
check is relaxed.

## Opt-in construction throughput
Set `GOLEMS_NN_PRODUCTIVE_BUILD=true` on the NNGolems branch:

- **Placement first:** after each actual verified placement, inspect other
  available, stocked current-phase tasks from the worker's *actual current*
  supported/hover work pose. Previously the immediate-followup scan excluded
  blocks more than three X/Z cells from the last placement even when they were
  physically within reach from the same safe stance. The policy now ranks
  already-safe candidates by actual hand distance, with a slight strip
  locality bias. The existing resolver, block survival, inventory, body
  collision, work reach, exit, claim and Y gating remain authoritative.
- **Exact-route rejection:** if a previously proposed exact stance cannot be
  executed, invalidate that stance and try another. At most four rejected
  exact work routes, spread across at least 10 ticks, are attempted before
  deferring the still-unfinished block and publishing a teammate handoff.
  A physically executing route or paid scaffold resets that window.
- **No-work-pose expiry:** if the worker remains unable to find a safe,
  executable stance or physical support alternative for 100 game ticks,
  release the unfinished block instead of walking forever toward an
  approximate target marker. Active grapple/scaffold/flight movement is
  exempt. The ordinary route-progress watchdog remains in force.
- **Learning target:** the NN frontier policy receives real completed world
  placements as positive examples and explicitly handed-off unfinished
  steps as negative examples. Movement alone, a route's completion, a worker
  reaching OnSite and rejected hypothetical paths are never labeled successful
  construction. The original schematic indices and item counts remain intact.

All behavior is OFF by default; `main` remains conventional.

## Real physical A/B
GitHub Actions workflow:
`.github/workflows/nngolems-productive-building.yml`

Uses the existing `BuilderNNDomeStrategyGameTest` against the **original**
Dome with 0 prefabricated blocks and two matched seeds in both groups.
Both groups use the same NNGolems navigation/frontier/detour configuration;
only `GOLEMS_NN_PRODUCTIVE_BUILD` differs. Full server logs and per-run
JSON artifacts are retained to investigate every stall.

Track:
- `outcome` (COMPLETE / NO_WORK_PROGRESS / limit)
- exact correct world blocks / 3,493 (not logical placements)
- full-build rate across independent trials
- largest number of game ticks without a newly correct block
- builder, propeller and new early-handoff counts
- positive neural frontier construction feedback

Workflow-success means **the physical tests executed and produced valid
source-world observations**. It does not imply any Dome finished.
Construction acceptance is a stricter reported gate: **2/2 productive
trials finish the entire Dome, and neither goes 2,400 ticks without a new
correct block**. Until measurements demonstrate this, the result is
`NOT YET`. Treat the <5 failed navigation waypoints milestone as a
separate navigation-only result, never as a construction success count.

## Next diagnosis from data
If the productive group remains stalled, inspect its last `[NN DOME PENDING]`
cells and `[NN DOME MATERIAL]` stock, world-state verification, recent
`[NN PRODUCTIVE NO POSE]` /`[NN PRODUCTIVE HANDOFF]`, flight/route
trace and crew handoff board together. Classify every event as
NO_SAFE_WORK_POSE, ROUTE_REJECTED, POST_PLACEMENT_EXIT, CREW_COLLISION,
MATERIAL, SUPPORT/DEPENDENCY or WORLD_STATE_REJECTED.
Fix the largest **repeated** blocker, not the specific coordinate that
happened to fail in one trial.
