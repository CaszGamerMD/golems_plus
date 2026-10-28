# Dome scaffold access — experimental policy (Fabric 26.2)

This is an **experimental test branch**; do not copy it to production until physical GameTests pass.

## Verified work-position changes
- The shared navigation AI must not interpret matching `mob.blockPosition()` as precise arrival. Work positions need actual, collision-checked in-cell centering; validated with a real Builder in 5 ticks.
- Candidate work standing cells consider X/Z 3-block rim and feet Y offsets 0, ±1, ±2, require a real floor and collision-free body, and remain strictly within 14-squared placement reach. Rejected poses are remembered for 80 ticks so repeated reroutes do not retry the same blocked stance.
- Real upper-rim landing verified in 9 ticks, no teleport or scaffold jumping; physical isolated suite [40646206494](https://github.com/CaszGamerMD/golems_plus/actions/runs/40646206494) passed all 7 GameTests plus unit tests.
- The verified shared-work-position subset is tracked by PR [#103](https://github.com/CaszGamerMD/golems_plus/pull/103).

## Current experimental scaffold-site decision
1. **Safety filter first**: contiguous support and loaded chunks, real air through each intended column segment, no overlapping present/future schematic cells, real path to the base, no tower outside physical placing reach. Never plan through solid walls or place an imaginary temporary block.
2. **Reuse**: prefer an existing scaffold column tracked to the current Builder, before paying new materials.
3. **Score safe candidates**: walk distance from worker, horizontal squared work range, actual vertical climb, scaffold item cost, future two-layer work coverage, and conflicts with construction cells. No memorized world route.
4. **Access**: check the actual feet Y for possible existing rim platforms (not their solid support blocks). If no upper platform exit exists, complete the scaffold DESCENT and dismount before starting another A* route.
5. **Retention**: next iteration should cache the chosen scaffold plan across several job ticks and mark physically stalled approaches temporarily unusable. Persist only reusable navigation preferences—not paths tied to one Dome.

## Next real-physics scaffold planning requirements
- If ALL safe direct columns are blocked, consider a **separately verified scaffold bridge or cantilever platform** from a physically reachable outer tower toward the work face. New temporary cells must have real support/collision, cost actual scaffold items, preserve every future Dome target, and be safely reclaimed.
- Score travel to the tower **and from its top to the selected work stance**; simply being within Euclidean 14-squared range from the tower is insufficient.
- Permit multiple Builders to reuse a shared tower only with exclusive climbing reservation and per-Builder inventory ownership; never force one worker through another.
- Optimize weights using actual measured completion/movement outcomes across repeated random-world tests and retain known-good rules before mutations. Reject variants that decrease safe full-team completion, not just fastest single-mob time.

## Current full-dome evidence
- Source: exact original 3,537-block user Dome; 4 real Builder entities, 41,106 clearance cells.
- Before this investigation, best physically matched count was 828 (unfinished).
- [Run 40646432520](https://github.com/CaszGamerMD/golems_plus/actions/runs/40646432520): **879/3,537** actual matched cells, 41,106/41,106 site-clear done, failed on inability to find physically safe scaffold column for upper rim. Counts are not comparable on identical spawn/seed and not proof of stable performance uplift.
- Still an **unsolved full-dome test**, so experimental scoring, new-site decisions, and incomplete site support have NOT been merged into main.
