## Required testing policy: real BuilderGolem entities only

**Every future scored navigation trial must execute in a Minecraft server
using actual `BuilderGolem` entities.** The former Python grid simulator
may remain as reference material for the genetic algorithm, but its
successes are NOT navigation test results, do not satisfy fitness
acceptance, and must not be used to declare a winning generation.

The normal Fabric GameTest manifest registers
`BuilderEvolutionTimeTrialGameTest` as a **mandatory** test. Its
10 real Minecraft entities start on green wool (five from each start column), have an actual
0.65 x 0.65 x 1.6 collision box, and must all physically touch
red wool by tick **360**. At timeout the test discards all remaining
trial entities. Movement uses actual block/entity collision, doors,
slabs and gravity. Never allow breaking, scaffolding or flight.

The real-entity workflow
`.github/workflows/builder-physical-timetrial-probe.yml` runs
automatically on navigation branch pushes and pull requests.
The previous CI workflows that awarded success based on simulated
Python movement have been removed.

**Current in-game acceptance: PASSED, 10/10 real BuilderGolem entities
within 360 ticks (18 seconds).** The winning Minecraft GameTest uses
physical mobs, an in-server evolutionary controller, pathfinding weights,
actual collision-derived fitness, ground-only walking and jumping,
and probes open double-door collision shapes. Test seed: `20261026`.
The initial generation in the latest physical run already passed;
earlier versions exercised real mutation across 24 generations and
improved to 7/10 before door alignment was corrected.

**Scope:** This experiment lives in Minecraft's GameTest source set.
It demonstrates learning/testing using *real entities on this course*,
but is not yet a trained production navigation component installed
into normal Builder Golem behavior. The same policy should pass
additional unseen obstacle courses before a production merge.

# Builder Navigation Evolution Lab (Fabric 26.2)

The **Minecraft GameTest is the sole accepted navigation test**.
The old Python code below is preserved only for offline algorithm
inspection and format reference; its completion statistics cannot
qualify a physical golem.

This **headless Python training prototype is not a Minecraft movement test**.
It evolves route-selection weights and records replayable paths using a deterministic
top-down, course-tile movement model. The real game engine must later verify
every winner before using its genes in Builder Golems.

## Non-negotiable movement constraints

- Training bot has **0.65 x 0.65 block footprint and 1.6-block height**.
- Horizontal speed cap is **4 blocks/meters per second** (20 tps, at least
  **5 game ticks for one horizontal block**). For jumps/drops, the
  simulator also caps the full 3D displacement per tick. No sprint boosts.
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
python3 scripts/builder_nav_evolution.py \
  training/examples/door_corridor.json \
  training/examples/deadend_maze.json \
  --population 32 --generations 50 --timeout 1200 --target 130 --seed 42 \
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

## Your uploaded timetrial.schem — strict 18-second generations

The exact uploaded Sponge v3 schematic has been preserved as
`training/courses/timetrial.schem.base64` (lossless; SHA-256:
`3c5da8fc97f8f8cf244e8f76f40ed37958d572e363d6c441106de4c7fd32591e`).

- Dimensions: **27 x 12 x 6 blocks**
- **20 green-wool spawn tiles**, **20 red-wool finish tiles**
- Two open spruce doors, **3 step-up half slabs**, and **4 overhead slabs**
  that a 1.6-block-tall bot cannot pass beneath.
- Original geometry, solids, and markers are parsed from compressed NBT.
- One competing ground bot starts on **each distinct green tile**. All
  red tiles are valid goals.
- Every generation ends at precisely **360 game ticks (18 seconds)**.
  All unfinished bots are reset; successful genomes survive, and new
  genomes inherit, cross, or mutate traits.
- **Stop only when all 20 bots complete in the same generation**. There
  is no best-of-generation shortcut. Run caps prevent CI jobs looping
  forever; an unmet cap is explicitly reported as a failure to meet
  the target, never mislabeled as victory.
- The trainer never breaks/places/scaffolds, teleports, or flies. Max
  speed is 4 blocks/s and the body is 0.65 x 0.65 x 1.6 blocks.

```bash
python3 scripts/train_schem_timetrial.py \
  training/courses/timetrial.schem.base64 \
  --generations 300 --wall-seconds 150 --seed 42 \
  --out build/timetrial-training
```

Use your original uploaded `.schem` directly instead of the archived
`.base64` path if desired. The game-tick limit remains 360 even when
the compute budget differs. Replays, full generation fitness,
uncompleted runners, winner genes and the original schematic source
hash are saved to `results.json` and `history.csv`.

### Current in-game acceptance and previous experiments

- **Real 10-golem Minecraft trial:** 10/10 completed within **360 ticks
  (18 seconds)**. This is a real BuilderGolem entity GameTest against
  actual Minecraft slabs, double spruce doors, walls, other golems and
  gravity, not headless replay. GitHub Actions: workflow
  `builder-physical-timetrial-probe.yml`.
- The genetic controller now lives in
  `BuilderEvolutionTimeTrialGameTest.java`, selecting per-generation
  navigation genes from **physical movement, timed arrivals and observed
  block collisions only**. After the physical experiments progressed
  from 1/10 to 7/10, physically correct doorway alignment enabled the
  complete 10/10 run. Its initial genetic population already succeeded.
- **Legacy Python headless results are archived but not accepted.**
  The previous 18-second simulated run got 20/20 after 4 generations,
  but these results must not be substituted for a real Minecraft test.
- The test source set is **not** included in the playable mod JAR.
  The next step is to extract learned walking/doorway behavior into a
  production Builder movement component, after verifying it on an
  **unseen physical Minecraft obstacle course** and different seeds.

### Time trial reproduction

The required workflow spawns ten actual Builder Golems, races each
generation for 360 Minecraft game ticks and replaces unsuccessful
generations with crossover/mutation of physically measured successful
genomes. It fails unless **one whole generation** reaches red wool
within the deadline.

The workflow saves Minecraft server output including physical arrival
ticks and each successful golem's exact genome and route, under the
artifact `builder-18-second-physical-entity-diagnostics`.

No breaking, scaffolding, teleporting, noclip or flying is permitted.
The test preserves the exact 0.65 x 0.65 x 1.6 entity dimensions and a
4 blocks-per-second ground-movement speed cap.
