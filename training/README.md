# Builder Navigation Evolution Lab (Fabric 26.2)

This **headless Python training prototype is not a Minecraft movement test**.
It evolves route-selection weights and records replayable paths using a deterministic
top-down, course-tile movement model. The real game engine must later verify
every winner before using its genes in Builder Golems.

## Non-negotiable movement constraints

- Training bot has **0.65 x 0.65 block footprint and 1.6-block height**.
- Horizontal speed cap is **4 blocks/meters per second** (20 tps, at least
  **5 game ticks for one horizontal block**). No sprint boosts.
- **No breaking**, **no building**, **no scaffolding**, **no flying**, and
  **no diagonals**. Obstacles must be bypassed on foot.
- Existing vanilla doors may be opened; opening costs 8 simulated ticks.
- One-block climbs cost extra jump ticks (5); drops up to two blocks are allowed
  in the simplified headless physics. Live Minecraft jump and fall constraints
  must be independently tested before deployment.
- No bot sees undiscovered tiles behind opaque walls. All bots start with
  only local line-of-sight observations (radius 4).
- Results are measured in **game ticks**, not variable real-world duration.
- No bots are literally killed in the trainer: timed-out trials are reset.

## Run a first training session

From the repo root, with Python 3.10+ and no third-party libraries:

```bash
python3 scripts/builder_nav_evolution.py training/examples/door_corridor.json \
  --population 32 --generations 50 --timeout 1200 --target 60 --seed 42 \
  --out build/builder-training
```

Outputs: `champion.json` with genes, course hashes, physical constraints,
the final generation's replay paths (tick, x, z), and explicit
`simulated_only:true`; `history.csv` with per-generation fitness, completed
trials and best/worst successful tick counts.

Run regression tests:

```bash
python3 -m unittest discover -s scripts/tests -p 'test_builder_nav_evolution.py' -v
```

## Course JSON interface

```json
{
  "name": "test-01",
  "rows": [
    "#########",
    "#S..D..G#",
    "#########"
  ]
}
```

Exactly one `S` (start) and `G` (goal). A `#` is solid, `.` walkable,
`D` an existing door, and digits `0`–`9` are walkable floor heights
in blocks. Adjacent walkable tiles can climb up by one block or descend
by two in this simplified simulator.

By default all walkable cells are 1 block wide and have 3 blocks of
headroom. To describe narrow openings or low ceilings, you may provide
`widths` and `clearances`: two numeric matrices matching the size of
`rows` (including wall cells). A bot cannot enter a tile when its
opening is narrower than **0.65** or lower than **1.6**. The trainer also
sweeps its 0.65-wide collision footprint between adjacent cell centers
to reject intersections with full solid walls. The 1.6-high profile is
checked against clearance at the traversed cells.

Example: the doorway at row index 1 / x index 4 has an opening of 0.7
blocks and headroom of 1.7 blocks; all other cells have the defaults.
For this particular 3x9 example, the arrays would be:

```json
{
  "widths": [
    [1,1,1,1,1,1,1,1,1],
    [1,1,1,1,0.7,1,1,1,1],
    [1,1,1,1,1,1,1,1,1]
  ],
  "clearances": [
    [3,3,3,3,3,3,3,3,3],
    [3,3,3,3,1.7,3,3,3,3],
    [3,3,3,3,3,3,3,3,3]
  ]
}
```

## Genetic training strategy

Each bot's genome contains A* route-selection parameters: how strongly
it prefers short paths, explores unseen space, avoids walls or revisits,
penalizes doors, climbing, and turning, plus tie-break randomness.
A generation runs all bots on **the same** courses and seeds. The strongest
elites survive intact; most offspring use crossover and bounded mutation;
10% are fresh random genomes. Failed trials earn partial credit based on
a held-out shortest-path **fitness evaluator** that the bot itself cannot
access. Finishing always dominates unfinished progress. Training stops
only after every trial of the leading genome beats the chosen target
time, or the generation cap is reached.

## What your in-world obstacle course should include

Mark **Start** and **Finish**, build solid walls, ordinary doors, narrow
corridors, turns, dead ends, stairs, 1-block jumps, safe drops, and a
roofed escape section. No flight, no scaffolding, and no destructive
shortcuts. Keep at least one navigable route for a 0.65-wide,
1.6-tall entity.

When the course is ready, upload the Litematica schematic and mark
start/finish coordinates. The next integration phase can read the actual
Minecraft structure (including block shapes, door states, headroom,
and real AABB collision), run multiple test entities in isolated world
instances, collect per-tick trajectories, and validate any champion.
A 2D course JSON is currently the accepted **headless input only**;
a `.litematic` is NOT yet directly runnable by this script.

### Acceptance before connecting to the real builder

The best genome must beat the time target across multiple random
course variations and an **unseen validation course**, and the full
Minecraft server GameTest must prove all forbidden actions remain
unavailable. Do not conflate headless success with live game performance.
