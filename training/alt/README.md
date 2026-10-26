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
