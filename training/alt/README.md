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
