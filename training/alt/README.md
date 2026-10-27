## Replay viewer publication paused (October 27, 2026)

At the user's request, the **desktop HTML viewer, mobile HTML viewer, and live
mobile JSON feed have been cleared** and are not automatically refreshed.
The files contain small paused/empty placeholders only. Earlier instructions
about downloading and auto-refreshing viewer data below are historical.

**Preserved for AI improvement and chat reports:** real physical per-generation
JSON traces in `replays/live/`, checkpoint and elite gene pool in `state/`,
strategy/rule memory, `history.jsonl`, historical champions, and CI test logs.
No movement or learning logic was changed by the viewer cleanup. The workflow
no longer rebuilds or commits HTML/mobile feeds. When requested, summarize
actual test scores, slab entry diagnostics, door usage, and saved learning in
chat from checkpoint/log evidence. Do not imply periodic chat updates occur
automatically.

---

## Native simulation qualification

The full course receives temporary simulation tickets before spawning racers.
Actual entity tick counters must cover each scored trip. Incomplete simulations
fail before training or saving. Existing genes are retained as seeds and earlier
fitness evidence is archived; newly verified full-team winners use
`state/native-champion.json`. Tick coverage diagnostics are saved in checkpoints.
This is a test-integrity change; performance improvement requires physical results.

## Current experiment: consistency and obstacle-level rules

See [controlled comparisons and remembered local rules](CONSISTENCY_AND_RULES.md).
Default tests continue genetic and contextual strategy evolution. Explicit
`GOLEMS_EVOLUTION_MODE=consistency` freezes the cohort, route policy and static
memory for three matched baseline/challenger pairs per block. Obstacle-level
condition/action bindings require repeated local improvement without crew
regression. Genetic breeding is paused only in that comparison mode. Earlier sections below
describe prior revisions and must not be read as current test results.

## Contextual strategy experience — previous experiment

See [how strategy learning is saved and used](STRATEGY_LEARNING.md).
The controller now supplements motor genes with persistent, coordinate-free
experience for choosing ordinary A*, fewer-turn routing, or a legal door
alternative. Repeated outcomes determine preferences; explicit exploration
uses one decision in five. This replaces the old gene-threshold door probe
described in earlier sections below. This is a new policy revision, not a
claim that physical performance or generalization has improved.

## Replay publication recovery — October 27

Run `40493141358` completed its three physical sessions, but did not meet
the 224-tick qualification. Best current-session 260-tick counts were
**8/10, 2/10, 7/10**; final generations were **7/10, 0/10, 5/10**.
The historical `bestFinishedEver=10` is inherited and is not a new success.

The regenerated historical HTML grew to **109,110,415 bytes**, beyond
GitHub's 100 MiB single-file limit, blocking publication of the entire
checkpoint/replay commit. New physical evidence was recovered from the
preserved run artifact. The inline desktop HTML now includes the newest
three run bundles, matching the mobile viewer; every raw generation JSON
remains archived. Recovered history is also retained in
`state/history-recovered-40493141358.jsonl` and merged into ordinary history
by the next checkpoint merge. The old fastest 236-tick champion is retained.

## Individual knowledge pool and operable doors (October 27)

The physical alt-course trainer now saves **per-golem improvements**, without
waiting for 10/10. Each golem keeps its own personal-best 12-gene chromosome
and score. A shared, persistent `knowledgePool` contains the **best three
different golems** by measured fitness: reaching red wool sooner outranks a
nonfinisher, while unfinished golems compete on physical course progress.
The next generation preserves those three exact parents and breeds seven
mutated descendants **only from these three**. No route coordinates or
teammate thoughts are passed between simultaneous runners.

The previous `personalBests` and `personalScores` are not thrown away:
on first restore the new pool is seeded from the strongest stored personal
results. Future validation runs restore pooled `nextPopulation` by default
rather than silently replacing it with the latest successful ten-member team.
The latest full-team population and fastest old champion stay separately
preserved for reference. Use `GOLEMS_EVOLUTION_MODE=validate` only when
explicitly requesting the historical full-team qualification.

Each 260-tick real-entity generation durably saves the pool, individual
scores, offspring, live movement replays and historic winning cohort.
The CI merger unions competing checkpoint pools **by golem slot and
physical fitness**, retaining the global best three across GitHub runs.
Failed 10/10 attempts must not erase independent improvements.

Wooden doors are now **actionable route nodes even when closed**. Workers
have to approach within 1.7 blocks, open both vanilla door halves, and
then physically pass through collision geometry. Iron doors and missing
doors remain impassable. Two rotating independent workers per generation
test the discoverable door alternative; the other eight use the regular
geometry-based route. The course's original door state and blocks are
unchanged outside of actual golem opening actions and isolated GameTests.

**Verification:** these are code changes, not a claim of a successful
11.2-second 10/10 run. The target remains 224 ticks for all ten real
Minecraft golems; 260 ticks remains the historical 13-second reference.

---

## Phone replay viewer — latest tests and automatic future updates

- **[Download mobile replay viewer](https://github.com/CaszGamerMD/golems_plus/blob/feature/builder-evolutionary-alt-course-262/training/alt/replays/mobile-viewer.html)** — lightweight self-contained HTML; tap **Download raw file** on GitHub and open it with Android Chrome. Includes the newest **three GitHub Actions test bundles**, with all their actual 10-entity generations, even when physical qualification fails.
- **[Live update feed](https://raw.githubusercontent.com/CaszGamerMD/golems_plus/feature/builder-evolutionary-alt-course-262/training/alt/replays/mobile-data.json)** — when connected, tap **Check for new tests** inside an already-downloaded viewer. It fetches updated physical movement traces without reinstalling the HTML. Offline playback uses the original embedded snapshots.
- **[Complete historical viewer](https://github.com/CaszGamerMD/golems_plus/blob/feature/builder-evolutionary-alt-course-262/training/alt/replays/route-viewer.html)** — all preserved generations, much larger than the mobile file.
- **[Automatic test workflow](https://github.com/CaszGamerMD/golems_plus/actions/workflows/builder-alt-physical-timetrial.yml)** — after EVERY three-test bundle, successful or failed, the workflow merges current learning/replays and regenerates both the lightweight mobile HTML and its JSON feed from the latest three run IDs. The same links will always point to the latest published versions.

No coordinate is simulated: the viewer uses positions captured from real BuilderGolem entities under native Minecraft collisions. The mobile app stores the last 3 bundles offline; historic batches remain in the full viewer and the per-generation JSON files. Publishing updates requires the test workflow to finish uploading its GitHub changes.

---

## Bottom-slab shortcut investigation — current status (1,305 physical generations)

The user identified two separate problems: the wide ground-level half-slab shortcut (x6–8, z2–7) was never selected, and golems gathered at a nearby corner even when the next tile looked open.

**Confirmed root cause:** old physically collected `blockedEdges` memory records 81–100 failures on the x9→8 bottom-slab entrances and 100 on several x16→15 steps. The A* planner added up to +10 cost per such edge, effectively excluding them long after physical grounded movement was fixed. Previously it ALSO added `slabAvoid` on every bottom slab and disallowed all diagonal movements on bottom slabs. Commits `303642b` and `0ae3c98` fix the geometric cost and remove obsolete blockage penalties *only* for live-geometry-verified bottom-slab edges. Historical records are retained, not deleted.

**Verified Minecraft regressions** (same original schematic): `realBuilderPrefersWideGroundSlabShortcut` selects four westward steps crossing 3 bottom slab cells even when supplied an old 100-failure entry edge; `realBuilderWalksAcrossWideBottomSlabField` physically crosses the 4-block field in 22–25 ticks via native entity movement and .6 block step-up; `realBuilderFindsGlobalBottomSlabShortcutWithoutDoor` discovers a 21-step path across 6 slab cells without the slower open birch door; `diagonalBodyClear` allows diagonals on a level, supported slab plateau but rejects an unsafe diagonal across the raised edge.

**Still failing at full crew level:** full three-session run [40465513613](https://github.com/CaszGamerMD/golems_plus/actions/runs/40465513613) and regression-enhanced [40465597880](https://github.com/CaszGamerMD/golems_plus/actions/runs/40465597880) both failed the 224-tick 10/10 qualification. Latest 40465597880 generation-0 physical 13s completions: 5/10, 5/10, 5/10; 224-tick completions: 3/10, 2/10, 3/10. The replay shows multiple runners meeting and remaining near x16,z2, with no static-block collision counted. The planner now *does* choose slab routes, but live teammates crowd a turning lane upstream. The existing courtesy budget of four lifetime ticks and `teammateWithin` stuck reset prevents reverse dance yet can also leave teams stationary. **Next work:** cooperative corridor occupancy and temporary congestion-aware replanning/arrival ordering, WITHOUT reinstating old half-slab penalties or treating teammates as permanent walls. The protected physically verified 236-tick historical champion is unchanged, and both mutant and successful-crew chromosomes remain separate. Never claim 224-tick qualification succeeded.

---
## Physical confirmation of complete-crew retention

[GitHub Actions run 40419002888](https://github.com/CaszGamerMD/golems_plus/actions/runs/40419002888) tested the restored, verified 10/10 cohort on the unchanged vanilla 13-second course:

| Test | Generation 0 | First complete generation | Slowest arrival |
|---|---:|---:|---:|
| 1 | 10/10 | 0 | 244 ticks |
| 2 | 10/10 | 0 | 244 ticks |
| 3 | 7/10 | 1 | 253 ticks |

All three **physical** Minecraft sessions passed; 2/3 passed generation zero. Crucial distinction: the **same ten gene arrays and same ten planned routes** appeared in generation 0 of all three trials; test 3 still had three late golems. That demonstrates *physical movement variability* independently of the now-fixed checkpoint retention problem. The recorded failures show golems #1 and #3 oscillating/backing off against one another at X≈9–10, Z≈1.5–2.5; golem #6 subsequently arrived too late. Static-collision counters remained zero for those stalls, so the existing *static-only* reroute trigger never activated. Further improvements must handle *other moving entities as obstruction* separately, and avoid falsely poisoning static blocked-edge memory.

State updated after run: **365 generations**, latest physically verified cohort at generation **365** (slowest **253 ticks**), its full ten genes saved with separate mutated offspring; protected best-speed champion still **236 ticks**. The HTML offline viewer includes all 365 complete real-entity generations. A repeated 10/10 by generation zero is **not proven** yet across all three sessions, despite all three sessions ultimately succeeding.

## Why a 10/10 team was lost between tests — retained-cohort fix

Previous test sessions resumed `checkpoint.nextPopulation` (mutated offspring), not the last physically winning ten-genome cohort. A slower 10/10 also did not replace `champion.json` (correct for speed ranking), but the complete slower winning team wasn't retained independently. Thus test 2 could achieve 10/10 and test 3 would begin with a different, untested team.

**New checkpoint fields:** `lastSuccessfulPopulation` (the exact ten 12-gene chromosomes, not waypoint routes), `lastSuccessfulArrivalTicks`, `lastSuccessfulGeneration`, and `lastSuccessfulSlowestTicks`. Real successes refresh this entire crew even if their times are slower than the protected fastest champion. A failure never erases the crew; CI merges select the latest complete result. The migrated source was **actual Minecraft physical replay generation 336**, with 10/10 golems finishing by 260 ticks, not headless simulation.

By default generation 0 **revalidates the last winning crew physically**, while `nextPopulation` continues preserving mutated offspring for exploration. Set `GOLEMS_EVOLUTION_MODE=explore` for a separate training run that starts generation 0 from those mutants, then use the usual three-trial validation mode to assess reliability. After failed validation generation 0, the saved exploratory offspring are used for generation 1.

A preserved winning genome combination does not ensure identical outcomes if golem interactions, physics, collision-memory weights or the movement logic differ; a repeat pass must still be actually scored. Saved gene logic is never a memorized route.

---
## October 26: More accurate half-slab and top-slab navigation

The course is unchanged. We continue to use exactly **10 real Builder Golems**, **260 ticks / 13 seconds**, native 0.6 step-up, and 4m/s max.

### New motion and pathing checks
- Full 0.65×1.6 body sweeps along prospective route edges, rather than relying on a clear tile center.
- Slab-edge/overhead-clearance precision control; no early corner anticipation across risky edges.
- Evidence-based, bounded (max 2) A* replans after real repeated static obstruction. Chromosomes, not old routes, are retained.
- On open cardinal straightaways only, verified safe early waypoint handoff prevents needless sub-4m/s slowdowns.
- Three overhead upper-slab tiles **x=12,z=5..7** fail real 1.6-block vertical clearance; the x=12,z=9 corridor remains passable. Never assume this visually open hole is navigable.
- Additional physical GameTest validates headroom rejection plus legal 0.5 slab access. All existing dedicated slab-step and birch-door regressions continue to pass.

### Actual new 3-test batches

| GitHub Actions run | Test 1 | Test 2 | Test 3 |
|---|---|---|---|
| [40401960397](https://github.com/CaszGamerMD/golems_plus/actions/runs/40401960397) initial swept route/precision | fail (best 7/10) | 10/10 at generation 16 | 10/10 at generation 1 |
| [40402904138](https://github.com/CaszGamerMD/golems_plus/actions/runs/40402904138) bounded real replan | fail (best 6/10) | fail (best 5/10) | **10/10 at generation 0, slowest 240 ticks** |
| [40403749983](https://github.com/CaszGamerMD/golems_plus/actions/runs/40403749983) straight handoff | 10/10 at generation 4, slowest 257 ticks | **10/10 at generation 0, slowest 254 ticks** | fail (best 5/10) |

**Result is still inconsistent.** Physical regressions confirm the ability to climb 0.5 slabs and exclude inadequate headroom, but a clean 3/3 generation-0 result is **not established**. Congestion near turns/approaches and inherited mutated populations still produce large latency variance. The latest experimental run passed only 2/3 test sessions.

Saved: **361 actual physical generations**, raw movement for **all ten entities in every generation**, failed as well as successful, fitness history and the next mutated population. The protected fastest old winner **236 ticks slowest** and original-course memory remain intact. Later successes did not replace the better protected winner.

## Slab and single-door controller fix — 2026-10-26

**Physical result:** the corrected 13-second vanilla course now completes **10/10 real BuilderGolem entities** in three consecutive scored GitHub Actions tests: [run 40343560995](https://github.com/CaszGamerMD/golems_plus/actions/runs/40343560995).

| Test | Generation reaching 10/10 | Slowest real entity |
|---|---:|---:|
| 1 | 5 | 251 ticks (12.55 s) |
| 2 | 1 | 249 ticks (12.45 s) |
| 3 | 1 | 236 ticks (11.8 s) |

The primary bottleneck was **native entity step-up at X≈16.325, Z≈6.5**: feeding a zero-Y delta directly into `Entity.move` sometimes cleared on-ground state before a 0.5-high bottom slab. The controller now includes a small **downward collision movement** when physically grounded, allowing Minecraft's own **0.6-block step height** to resolve the half-slab. It does not raise or teleport entities or allow flight, scaffold, breaking or noclip. The new route planner also checks full 0.65×1.6 collision clearance and physical standing heights, and uses stable perpendicular door offsets when passing a single-width door.

Separate Minecraft GameTests confirmed:
- Real grounded 0.5 slab ascent in **5 ticks**.
- An exposed bottom slab admits the real Builder body; an added roof in the isolated test correctly rejects it.
- A real Builder crosses the original single open birch door in **15 ticks** (chosen lateral offset 0.08).

This branch retains **235 scored real-entity generations**, all actual per-tick traces and a protected alt-course **10/10 champion** with slowest arrival **236 ticks**, mean arrival **220.9 ticks**. The first-course 18-second training data and champion remain separate. A new/unseen course is still necessary to validate generalization; these results demonstrate this track only. 

The official [all-generation HTML viewer](replays/route-viewer.html) embeds the saved live entity tracks and includes the new 12-trial sequence.

---

# Alternate vanilla physical course (13 seconds)

This folder contains the NEW upload **timetrial_alt(2).schem**, SHA-256 `b8a216c4b1a91359c044fa20546b214670b8431991d305fd47f4e2c8a72d1ea4`. Its raw gzip file is saved losslessly under `courses/timetrial_alt_vanilla.schem.base64`.

- Exact Sponge v3 geometry: 27 × 10 × 12 (X×Y×Z).
- Vanilla-only blocks: birch fences, logs, slabs, spruce-free birch door, glass, wool, stone and concrete.
- 20 green-wool start blocks and 20 red-wool finish blocks already exist, untouched; 10 real Builder Golems start on selected green-wool blocks.
- All ten must physically reach red wool within **260 Minecraft ticks (13 seconds)** in the same generation.
- Same collision bounds, 4m/s cap, diagonals, ≤0.6 block step-up, no mining, flight, teleporting, or scaffolding.
- Initial genomes use previous course's best proven DNA. **No original route, blocked-edge memory, fitness history, or prior win is counted on this course.**
- Real entity fitness, offspring genomes, route replays for EVERY generation, best local champion, and collision memory save in `state/` and `replays/live/`. The former course remains in `training/state/`.
- [Offline route viewer](replays/route-viewer.html). Save its raw HTML and open locally; the server workflow embeds all real recorded generations.

Run through `.github/workflows/builder-alt-physical-timetrial.yml`. The GitHub workflow runs three sequential physical Minecraft tests. Failures still save mutations and physical traces. Do not consider a headless simulation a successful test.

## First three REAL-entity tests on corrected vanilla schematic

| Test | Outcome within 260 ticks | Best physical generation | Notes |
|---|---|---|---|
| 1 | FAIL, best 5/10 | 5/10 of 10 | Several physical finishers; mutation and progress saved |
| 2 | FAIL, best 0/10 | 0/10 | Frequent collision/stuck recovery around the 0.5-block slab entries |
| 3 | FAIL, best 0/10 | 0/10 | Same 13-second rule; no complete generation |

All three Minecraft server trials are verified in [the original Actions job](https://github.com/CaszGamerMD/golems_plus/actions/runs/40337985923). The previous trial artifact was recovered and committed: **75 generations of actual per-tick coordinates**, with ten physical entity routes per generation, plus a 75-generation checkpoint and fitness history. There is **no local 10/10 champion** yet, so the separate old-course champion is not misrepresented as a win.

**Training observation:** collision observations around floor half-slabs accumulated strongly (near x16→15 and x9→8), and later populations consistently stalled. Those observations and learned genes are retained for analysis; these results do not justify claiming reliable cross-course generalization.

Further test batches require manual GitHub Actions dispatch so a code or documentation edit does not silently launch another 3 tests.
