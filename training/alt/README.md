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
