# Golems Plus — Minecraft Builder Golem time-trial replays

These files are published **directly in GitHub**, not as a ChatGPT sandbox
attachment or GitHub Actions artifact ZIP.

## Open or save

1. **[Animated, offline HTML route viewer](route-viewer.html)** — use GitHub's
   **Raw / Download raw file** control, save as `route-viewer.html`, and open
   it in Edge or Chrome. No internet is needed after saving. You can pick
   Test 1, Test 2, Test 3, and a golem; then press Play.
2. **[Plain-text routes and actual completion times](three-tests-readable-routes.txt)** —
   view directly on GitHub, or use **Raw** to copy or save the data.
3. **[Machine-readable JSON](2026-10-26-three-real-entity-runs.json)** —
   every logged winner's planned compass route, expanded tile waypoints,
   learned genome, and actual arrival tick, plus all three trials' recorded
   generation fitness statistics.

**Important:** The historical server logs did *not* record frame-by-frame
in-game entity coordinates or Minecraft video. The HTML viewer therefore
animates the **planned routes**, approximately spaced across actual finish
times. It is NOT footage, and it does not claim to reproduce actual
collisions or pauses. The recorded finish times and generational outcomes
**were measured on real `BuilderGolem` entities**.

## New: All-generation ACTUAL movement replay

The training workflow now executes **three consecutive, independent real-entity
Minecraft GameTests**, carrying forward learned genes between attempts. The
same [offline HTML route viewer](route-viewer.html) exposes a **Generation**
selector and replays all 10 actual physical entity positions per tick, even for
generations where zero bots finish. Select a golem to inspect its real measured
speed, stationary time, recovery/backoff, door alignment, and collisions.

The viewer is regenerated directly in GitHub after each three-trial workflow.
During a running workflow or until the first new replay is published, it
continues to show the original archived dataset (which never contained real
per-tick movement). Fresh captures are also retained as individual
[physical-generation JSON files](live/), independently of the HTML viewer.

## Three historical physical Minecraft trial outcomes

| Trial | Best real generation | Strictly finished within 360 ticks | Outcome |
|---|---:|---:|---|
| [Test 1](https://github.com/CaszGamerMD/golems_plus/actions/runs/40321155300/job/121568761499) | 0 | 10/10 | PASS, slowest 338 ticks (16.9 s) |
| [Test 2](https://github.com/CaszGamerMD/golems_plus/actions/runs/40321155300/job/121569521421) | 3 | 9/10 strictly; the 10th at tick 361 | FAIL by 1 tick, despite the GameTest reporting green |
| [Test 3](https://github.com/CaszGamerMD/golems_plus/actions/runs/40321155300/job/121570342298) | 4 | 8/10 best | FAIL after 24 generations |

The 18-second test rule is 360 Minecraft ticks for all ten golems in
**one generation**. The physical experiment used commit
`a3f13dad72d10bda47443b2db72173486b54ed59` from the independent
training PR #97. The replay files are data-only additions on the same
training branch.

### Why there is no movement-route playback for Test 3

Only trials completing the entire population caused the Minecraft
GameTest to print full champion planned routes. Test 3 did not finish,
so its logs contain generation summaries and final stalled physical
positions, but not enough information to reconstruct trajectories for
each bot. The HTML shows its generation progress instead.

### Link fallback

If the GitHub source view shows code instead of an animation,
choose **Download raw file** and open the downloaded `.html` locally.
The HTML contains its full data and works offline.

**Path in repository:** `training/replays/`
