# NNGolems user-build curriculum

The user supplied four builds on 2026-11-16. This experimental branch preserves
the original Dragon and Froglights Litematica files and a block-state-only
conversion of Home Demo (Sponge schematic v3). Chibi's original structure NBT
was too sparse for the 300,000-air limit as one region: it is stored as a
lossless compact contiguous-column archive and palette, reconstructed into 171
nonoverlapping regions by prepare_curriculum.py. It preserves all 32,061
non-air coordinates and states. Air within these regions is inferred from
omitted cells, so use on an empty training site, not for clearing a live build.

| Course | Source selection dimensions X/Y/Z | Blocks | Air in fixture | Role |
|---|---|---:|---:|---|
| Home Demo | 34/21/29 | 620 | 20,086 | stairs, doors, trapdoors, glass, bubbles, pistons |
| Dragon | 27/27/15 | 1,032 | 9,903 | stairs/slabs, irregular shape, flowing water |
| Froglights | 21/24/15 | 3,102 | 4,458 | redstone, pistons, hoppers, water, bubble columns |
| Casz Chibi | 128/129/129 | 32,061 | 276,058 | endurance; import/planning only initially |

The normal importer deliberately ignores entity and block entity payloads.
Dragon has 15 entity records; Froglights has 12 entity and 55 block-entity
records. Neither physical course claims functional machine operation or copied
inventories/entities. Source state counts are separate from grouped plan steps.
Home Demo retains its original signed offset. Dragon and Froglights retain
original signed region extents. Canonical coordinate/state hashes verify each
fixture after production LitematicFileReader parsing.

The new workflow isolates each course on a fresh server. Three small builds
use four conventional builders, real Creative Crate, deterministic initial
arena, scaffold-free movement and a 300-second assessment or no-work timeout.
Chibi is import/plan-only until a dedicated endurance arena is ready. Physical
source match counts and observations are retained even on a legitimate stall.
These first runs are new-build baselines, not neural policy wins. Keep
cross-build evaluation separate from same-build repetition; do not train on a
course and call that same course unseen.

## Farm Demos (added 2026-11-16)

Source Sponge v3: 83×43×43, offset (-82,0,-43), 13,143 non-air cells,
140,324 air cells, 186 palette states. Compact archive retains all block
states and coordinates. Preparation and production import each verify the
canonical source hash. The course uses an enlarged 103×47×70 arena with
four conventional builders, Creative Crate and a 300-second limit.
The 114 block-entity records are intentionally not reconstructed; machinery
function, inventory contents, and transient moving piston behavior are not
validated by block-state matching. Preserve source scaffolding as construction
material; temporary builder scaffolding remains disabled. First run is a
baseline, not a neural policy evaluation.

## Adaptive physical arena

All five user courses now run physical assessments, including full Chibi.
The test arena derives width, height and depth from the complete source bounds;
no arbitrary 100×60×100 shape restriction, cropping or partial job. Actual
Minecraft dimension height and explicit configurable CI resource budgets
(default 8 million arena cells and 625 ticket-square chunks) are checked before
setup. A bigger runner can raise budgets; production limits are separate.
Large-course storage is placed outside the nearest foundation perimeter.
Chibi uses a five-minute assessment like the others, not a completion promise.
Its original sparse fixture still preserves all 32,061 solid positions; inferred
region air is used only on the empty training site. No model steering changes.
Production importer/planner/snapshot limits (100,000 non-air/steps, 300,000 air
cells, file/palette limits) remain and require separate scalable-job work.
