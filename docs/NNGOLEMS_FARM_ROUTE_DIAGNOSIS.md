# NNGolems Farm Demo route/recovery diagnosis (2026-11-16)

Baseline on the previous NNGolems branch: physical Farm Demo with 8 Builders, no handoff, 522/13,143 strictly correct cells and 531/11,523 reported ordinary task completions. 25 crew retreats. With reachable teammate handoff, 307 correct. Structure switch from project 3 to project 4 succeeded. Final project 4 stalls on Y=-41 schematic scaffolding: some placement cells cannot survive without neighboring support; other cells have supported stand poses but all trained A* routes fail within the search budget. A distant pathfinding failure is not proof that the work pose itself is impossible.

New opt-in `GOLEMS_NN_PARTIAL_YIELD=true`:
* After two failed crew recovery attempts and an additional full reduced-crew stall, attempt a **fresh physical return** with no active claims. Prior unloaded status is cleared because Builders may have picked up stock since the last return.
* At least one Builder must have physically arrived and unloaded, and the remaining workers get at least 600 game ticks to return before partial project handoff.
* Keep those remaining workers in independent recovery with their actual inventory and movement; only the newly free crew is released to the next unfinished project. If all are unloaded, switch immediately.
* No pending cell is marked finished, no item is duplicated, no claim or progress is fabricated. The old handoff behavior stays available with the new flag off.
* On each crew retreat, emit `[NN FRONTIER SNAPSHOT]` with real support and nearby block types (including scaffold N/E/S/W and below). Distinguishes support chain failure from a route-search failure.
* Physical Farm Demo A/B runs with and without this flag, with reachable-teammate handoff held OFF. Report correct blocks, ordinary placements, retreat count, projects visited, project yields, partial returns, and exact frontier diagnostics. A successful GitHub job indicates testing executed, **not** that the 99% build target was met.

Known remaining issues: physically reaching exposed stand tiles on large scaffolding platforms, route-budget exhaustion for distant targets, and 9 cells of mismatch between reported ordinary task completion and exact world-state match. Those need independent interventions if the controlled A/B is unsuccessful.
