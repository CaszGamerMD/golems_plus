# Scaffold AI — physical obstacle course and policy-training contract

Status: **development branch** `feature/scaffold-bridge-planning-262`. The existing released Golems Plus navigation AI stays the only movement planner. No fallback route selector.

## What is implemented

- A real Builder can place actual, inventory-paid scaffolding along a horizontal aerial walkway anchored to its own vertical tower.
- The walkway planner searches **general rules** (adjacent supported cells, collisions, plan geometry, permission, headroom, material cost and reach), not stored routes or hard-coded level coordinates.
- Only cardinal contiguous walkway cells are allowed. Maximum **six** scaffold extensions from the owned vertical support; a planned roof/upper floor must have real headroom. Training **cannot mutate these safety restrictions**.
- Physical staging builds one segment at a time, advances the real Builder entity over it, then **retraces the same real scaffold blocks** before the existing safe full-height descent and ground dismount.
- The old tower selector is reused as appropriate; a scaffold bridge is attempted from an already reached, owned tower only when the target is outside physical placement range.
- `BuilderScaffoldTrialStats.snapshot(worker)` reports selected plans, rejected proposals, physical blocks placed, actual reverse steps, ground returns, turns and longest span. Do not describe a course result as successful without physical proof.
- `BuilderScaffoldBridgePlanner.Weights` describes **trainable policy logic**, not coordinates. `BuilderScaffoldPolicyTrainer.nextGeneration` takes nine measured outcomes and retains the three best unchanged, plus two bounded mutations per elite; safety and course success outrank speed.

## Suggested obstacle-course stations

1. Ground approach to a supported tower; climb it completely without jumping.
2. A short gap requiring a scaffold walkway from an actual anchored tower. Crossing directly is impossible.
3. A turn around a solid wall that prohibits a direct walkway.
4. A future blueprint-block conflict; alternate scaffolding must preserve the construction cells and body headroom.
5. A longer gap (more than six unsupported segments) requiring a **new support/bridge strategy**, not an unsafe cantilever. This is a **future feature**; it should currently be rejected safely.
6. A high work ledge where a full vertical descent and ground dismount are required.
7. Two or more Builders navigating shared access; no physical overlap, rushing or damaging another worker's scaffolds.

## Objective for each measured attempt

Course grading should record **all**: entity survived, physical target placed/reached, full scaffold ascent, no jumping, no unsupported scaffold, return to real ground, no phantom items, number of purchased/retrieved scaffolds, elapsed game ticks, route retries, other-goem obstruction and whether each individual crossed. If a trial stalls or does not return safely, it **fails** regardless of speed.

**One Builder at a time, as defined for this course.** Run the nine candidate policies in NINE SEPARATE attempts, resetting the same physical course, chests, placed scaffolding and door between attempts. Never spawn nine Builders concurrently for course training. Save and grow the best **rules/weights**, never memorized course paths. Repeat controlled trials on new maps/seeds before promoting any policy to the released mod. Store measured checkpoints separately from immutable safety rules.

## How to connect the user-created course

The course can be supplied as an original Litematic/structure or as a real Minecraft test world. The next integration will add scenario definitions and a run recorder; do not pretend this is implemented yet. The current GitHub Action `scaffold-bridge-training.yml` runs unit tests and real Minecraft GameTests, and uploads reports/logs.

Current baseline physical tests use an anchored tower, bridge over a real gap, reverse traversal, and safe descent. They are not a pass for a larger future course.


## User-defined single-golem chest course — required contract

The user is preparing a physical obstacle course for **exactly one Builder Golem per attempt**, with scaffold placements and pickups REQUIRED to reach the end.

- **Start chest:** The Builder must walk within actual chest interaction reach and physically withdraw scaffolding. The chest loses exactly the number of items added to the Builder's real inventory. It starts with a user-configured stock; no preloaded free creative inventory.
- **Course obstacles:** Crossing may require erecting a vertical tower or bridge and reclaiming temporary scaffolds. The Builder must use physical Minecraft movement and the unified trained navigation; no fallback route, teleporting, or jumping on scaffold.
- **Pickup:** Temporary scaffold blocks can only be reclaimed when the Builder is safely supported on ground, near enough to break them physically, and has real pocket capacity. Successful recovery returns a scaffold item to the golem, not a distant Planner inventory.
- **Finish chest:** The Builder must come physically within reach and deposit at least `minimumRequired` real scaffolding items. Only scaffolds deposited **by this particular Builder during its attempt** count, never existing stock left by a previous attempt.
- **Final door:** Keep the course gate locked until the finish chest quota is actually met. The Builder must THEN physically walk through the door onto the finish cell before receiving a pass.
- **Inventory integrity:** Count actual `withdrawn`, `placed`, `recovered`, `delivered` and `crossedDoor`, and check conservation (no item duplication). `BuilderScaffoldPolicyTrainer.CourseTrial.passed()` requires all five, a live grounded Builder, and zero collisions. Time only ranks successful full attempts.
- **Learning:** 9 measured **sequential** single-golem runs per generation; retain top 3 policy rules unchanged and create 2 mutated policy offspring from each. Never mutate physical support, headroom, wall collision, chest reach, or return safety rules.

Code scaffold: `BuilderScaffoldCourseLogistics.Session` configures the two physical chests, the door, and the minimum deposit. The GameTest `BuilderScaffoldCourseChestGameTest` uses a tiny independent proof course with a sample quota of 5. **The user's eventual course and exact minimum have not yet been supplied**; that threshold remains configurable and must not be assumed to be 5 in the user's map.
