# Golems Plus

Minecraft **26.2 Fabric**, Java **25** — Caszual Suite

Specialist golems with real workstations and finite inventories. Development
is directly on `main`.

## Craft and assign real workers

Place a job block, then craft a **Builder, Trader, Beekeeper or Chef Golem**
(three iron ingots, a carved pumpkin and that profession's item). Right-click
an adjacent floor/block with the crafted worker within **10 blocks** of its
matching job block. The nearest matching, unclaimed or player-owned workstation
is assigned to that physical entity; deployment consumes the item unless the
player is in creative mode. Existing station workers remain registered after
reloading the world. Up to four workers serve Builder, Trader and Beekeeper
stations; Chef supports one.

**No workstation generates free Golems.** Workers walk around a bounded
10-block area until given work, then normal task routing takes over. Increasing
the crew target in a job block does not manufacture golems: place additional
crafted workers to fill a crew. Fisher remains a prototype without a physical
worker, so there is no Fisher craftable item yet.

## Builder placement picker

The Planning Table's **Choose Litematica placement** button now opens a
paginated list of every placement currently managed by Litematica, with
names and origins. Select one to upload its real file and world origin to that
exact Planning Table; **Preview** and **Start build** still require confirmation.
The picker does not depend on Litematica's preselected placement and no
slash commands are necessary. The optional `/golems_use_placement` command
remains available for legacy testing. The client bridge checks
`getSchematic()` instead of a missing `isSchematicLoaded()` method.

## Job-block settings (new)

**No slash commands needed for normal setup.** Empty-hand right-click an
owned **Builder's Planning Table** or **Trader Counter** to open its inventory
and integrated control panel.

- **Builder controls:** import the currently selected Litematica placement,
  preview required materials, start/pause/resume/cancel the build, set the
  1–4 Golem crew size, rotate a not-yet-imported plan and check the selected
  warehouse connection. The existing warehouse-scanner right-click remains
  the preferred way to link a warehouse.
- **Trader controls:** set desired items and quantity, authorize payment
  budgets, enter village location/radius, pause/resume/cancel orders and
  adjust the 1–4 Golem crew size.

All changes are validated on the server for the exact open, player-owned
workstation. Existing commands remain usable as fallback shortcuts. Other
specialties retain prototype interfaces until their Golem AI is ready.

Read [workstation settings and GUI instructions](docs/WORKSTATION_SETTINGS.md)
for details.

## Profession models (Minecraft-style)

Builder, Trader and Chef now share a **small worker-golem skeleton**,
professional texture UVs and smooth walk/arm/head animations. Their
outfits are actual cuboid geometry, not painted-on flat hats or accessories;
the Trader's briefcase is attached to its right arm and follows the arm's
walk animation. No modded resources are needed to view the uniforms.
See [profession models](docs/PROFESSION_MODELS.md).

## Current development status

**Trader Golem — in-game implementation (experimental):** A persistent,
pathfinding Trader Golem uses actual vanilla villager offers and actual
payment items withdrawn from the owned Trader Counter's 27 stock slots. It
returns purchased items to nine output slots, refunds unspent payments,
checks per-order spending limits, and waits when villagers are unavailable,
offers change, or storage fills. The Trader now has its own **compact
professional uniform model**: a tailored navy suit, white shirt, red tie,
dress shoes and animated leather briefcase. Runtime trading still needs
live multiplayer testing.

Configure from the **Trader Counter's Order, Budget and Village pages**.
The old `/golems trader` commands are still available for compatibility.

**Builder Golem — placed Litematica construction (experimental):**
Select a placed, enabled, file-backed schematic in the Litematica client
and click **Import Litematica placement** inside the Planning Table.
Golems Plus sends a bounded snapshot
with the placement's origin, rotation and mirror to the server and saves it
at your claimed Builder's Planning Table. Click **Preview**, then **Start
build** to explicitly authorize the Builder to place blocks.
A compact Builder Golem with a **yellow 3D hard hat, orange hi-vis vest
with raised reflective stripes, utility belt, gloves and work boots**
moves to target positions, uses materials from the
table's 27 input slots, saves a work cursor across reloads, skips existing
matching blocks, and refuses to destroy occupied locations. Status,
pause/resume/cancel and crew size are managed from the Planner's GUI.

Litematica is an **optional client-only integration**; it is not needed
on dedicated servers. The bridge currently requires all subregions enabled
and unmodified, files ≤2 MiB compressed and builds ≤100,000 blocks.
Selected position/rotation are captured only when importing; moving the
hologram later requires reimport. This is a development implementation and
has not been playtested end-to-end in Minecraft yet.

To link a warehouse, **right-click the Builder's Planning Table with a
Caszual Additions Warehouse Scanner already linked to a Rack Computer**.
The Table copies and saves that warehouse assignment without changing the
scanner. Right-click again with a differently linked scanner to change it.
The older `/golembuilder warehouse` selection command remains available,
but scanner interaction is now the preferred workflow. Warehouse fulfillment is now implemented experimentally: when a required
block is missing from the Builder Table, the Builder submits a bounded
materials manifest to its assigned Rack Computer. The warehouse reserves an
empty pallet-equipped OUTPUT PTP with a connected Shulker Station, sends its
own Storage Golems to collect real stock, packs it into shulker boxes, and
deposits the boxes at that PTP. The Builder physically walks to collect each
box and returns it to the Planning Table. Building consumes actual stacks
inside delivered shulker boxes; all cargo and request state are persisted.
Missing rack stock or empty shulker boxes generates an owner message and a
Planning Table status. This requires the matching Caszual Additions update,
loaded warehouse chunks, available warehouse golems, connected racks, and
empty packing shulkers. Empty boxes stay in the Planning Table afterward
and currently need to be returned to the warehouse manually. Some blocks (block
entities, fluids, doors, etc.) are intentionally refused until safe
placement rules are added. The previous server-side `inspect` command
remains available for read-only planning.

**Multi-Golem workstations and scaffolding (experimental):** Builder
Planning Tables now support crews of **1–4** with
`/golembuilder crew 4`. Multiple physical Builder Golems claim separate
schematic steps from a single saved, shared progress ledger: finishing steps
out of order never skips incomplete blocks. One of the crew acts as the
warehouse pickup courier while others can keep building from existing stock.
Trader Counters similarly support 1–4 workers using
`/golems trader crew 4`, sharing the same goal and spending limits with
in-flight escrows counted before additional trades depart.

Builders require **real minecraft:scaffolding** for high, unreachable work.
They create owned temporary scaffold towers, climb instead of teleporting,
and reclaim their own scaffolding quickly when safely finished. They
request more scaffolding from the linked warehouse if necessary. Never
remove or refund untracked/player scaffolding.

Supply shulker boxes are **kept at the Builder Planning Table** after
contents run out: empty shulker containers move into the Table's nine output
slots so the first 27 input slots remain available for future deliveries.
They are not sent back to the warehouse automatically. If the output is
full, an empty shulker stays in its original slot until room is available.

**Beekeeper Golem — first playable harvesting implementation:**
A new **Beekeeper Job Block** works with a dedicated 27-slot
**Beekeeper Toolbox** and a nearby chest/barrel or other container for
collected materials. Both link directly from the job block's right-side
settings panel: click **Find TOOLBOX** and **Find OUTPUT**. Use
**Mode: Both / Comb / Honey**, **Range −/+**, **Pause / Resume** and
**CREW −/+** to adjust 1–4 workers. The toolbox accepts shears and
glass bottles; the golems physically borrow them and return used shears.
A small suited golem (Storage Golem proportions) with a modeled hood,
veil, jacket, gloves and boots visits full beehives and bee nests,
producing exactly three honeycomb per shearing or one honey bottle per
bottle. No harvesting occurs without loaded hives, actual tools or output
space. The beekeeper preserves its tools and cargo across world saves,
and waits if the assigned output is full.

The first version uses a compact vanilla-shaped cabinet for the Toolbox
and temporary block textures for the job block; texture/model finishing
and in-game testing are ongoing. See
[Beekeeper setup](docs/BEEKEEPER.md) for details.

**Chef Golem (visual groundwork):** Place a crafted **Chef Golem** near an owned **Chef's Kitchen Station**
to assign one permanent small Chef worker with a **tall white 3D toque,
double-breasted chef coat, apron, rolled sleeves and non-slip shoes**. Its
uniform is implemented and it idles at the claimed kitchen job block.
Recipe execution, cooking inventories and food-order AI are **not**
implemented yet. The chef cannot fabricate food.

**Fishing Golem:** event-driven state machine remains groundwork only.

Read [placed Litematica build instructions](docs/LITEMATICA_PLACEMENTS.md),
[development playtest instructions](docs/PLAYTEST.md) and
[implementation foundations](docs/FOUNDATIONS.md) for details and limits.

## Planned golems

- **Cooking Golem** — Chef outfit/model and cooking job block implemented;
  ordering kiosk and real recipe preparation remain future milestones.
- **Beekeeper Golem** — Experimental harvest worker with own toolbox and
  output container. Bee breeding and expanded activities remain future work.
- **Builder Golem** — Litematica plans and linked warehouse materials:
  [issue #1](https://github.com/CaszGamerMD/golems_plus/issues/1).
- **Fishing Golem** — Supply a rod, fish and deposit catches:
  [issue #2](https://github.com/CaszGamerMD/golems_plus/issues/2).
- **Trader Golem** — Experimental autonomous villager courier, needs live QA:
  [issue #3](https://github.com/CaszGamerMD/golems_plus/issues/3).

## Development

Run `gradle clean test build` with Java 25 and Gradle 9.7 or later.
The GitHub Actions workflow checks `main`. No release JAR should be
published until the build and tests succeed.

## Chef Kiosk / Cooking for Blockheads shared kitchen

The old Chef's Kitchen Station is now a touchscreen food-order kiosk.
**The kiosk has no item slots or player-inventory view.** Food ingredients,
inventory counts, and all extra recipe products use the linked Cooking for
Blockheads kitchen storage. The Meal Pickup Counter is the only destination
for completed plated orders. Any items left in an older kiosk inventory
remain saved and are returned when that old kiosk block is broken.

Chefs favor colored Cooking for Blockheads kitchen floor tiles for both
idle walking and recipe preparation. Pathfinding rejects routes across
kitchen appliances and counter tops; on kitchens without those tiles, chefs
fall back to safe ordinary flooring. Keep a walking aisle clear between
the kitchen and work stations. A
**Meal Pickup Counter** is the counter where finished meals appear on a white
plate. Right-click to claim the actual food; the visible plate does not drop.

Install **Cooking for Blockheads** and **Balm** on Fabric 26.2. Place a
Chef's Ordering Kiosk within 16 blocks of a Cooking for Blockheads fridge or
cabinet. Open the kiosk and press **LINK** to bind it to the nearest kitchen
storage. The pantry tab reports contents from that linked kitchen. The kiosk
no longer needs to touch the kitchen blocks directly.

Connect the
kiosk directly to Cooking for Blockheads kitchen blocks (counters, fridge,
cabinets, etc.). The kiosk scans the connected kitchen as a unit and shows
the quantities of stocked ingredients on its **PANTRY** tab. Ordinary chests
outside that kitchen are never accessed.

In the **ORDER** tab, search across registered edible items. Ordering uses a
bounded, server-authoritative recipe graph for Minecraft shaped/shapeless
crafting and cooking/smoking/campfire/blasting recipes. Intermediate recipes
run before their parents. If a resource or compatible recipe cannot be found,
the order is rejected with an explanation, not fulfilled for free.

Place one or more crafted Chef Golems nearby (maximum four). The kiosk
reserves leaf ingredients from connected kitchen storage before starting the
order. Chefs perform the ordered steps and deliver a saved meal to the nearest
unoccupied Meal Pickup Counter within ten blocks. Inventory quantities,
orders, recipe steps and prepared meals survive reloads. Collect the previous
order before preparing another. Some modded cooking recipes with custom
machines or virtual inputs are intentionally unsupported for safety.

## Golems Plus 0.3.4-dev maintenance

* Beekeepers can now use real flowers placed in their Toolbox (at least two
  matching flowers together in a stack) when no full-hive harvest is ready,
  and two unpaired adult bees are ready. One worker visits the toolbox, takes
  two flowers, approaches the pair, and enables normal vanilla bee mating.
  Flowers are refunded when a pair becomes ineligible.
* Builder Planner placement discovery supports both modern and legacy
  Litematica manager methods, skips invalid placements without hiding valid
  ones and supports optional file paths when importing.
* All deliberately non-cube workstation models now have non-occluding block
  properties, preventing them from hiding neighboring solid block faces.
* Crafted worker item icons are individual 3D models: hard hat, briefcase,
  beekeeper veil, chef's hat and fishing vest.

## Trader counter and movement maintenance (0.3.5-dev)

The trader's business desk now has four feet, an extended brass lamp base,
a correctly textured lamp bulb / shade underside, and emits block light
(level 12). The previous placed block item and station contents are retained
when upgrading.

All five Golem entities use a collision width of exactly 0.85 blocks;
their model and rendered size are unchanged. Trader paths are reused rather
than restarted every twelve ticks, doors are opened ahead and closed after
the trader has cleared them, nearby traders yield by deterministic priority,
and traders seek walkable cells next to the desk on their return instead of
pathing onto the desktop.

## Kitchen order reliability (0.3.6-dev)

* Chef Golems have a 0.6-block step height. Their path planner refuses
  any route with a rise above 0.6 blocks, even if Minecraft's normal mob
  pathfinder would jump onto a full-height counter. Slabs and lower steps
  remain valid.
* Cooking kiosks now have a wide, adaptive menu featuring a searchable
  icon grid with hover tooltips. The ingredient list supports mouse-wheel
  scrolling and on-screen arrows. Missing raw materials show in red.
* While any Golems Plus job-block screen is open, world/hotbar shortcuts
  are consumed instead of being forwarded to vanilla container hotkeys.
  Text entry, Tab focus, and Escape continue to work.
* The Chef planner picks a single recipe option per item. It scores
  alternatives by missing kitchen supplies first, then number of operations
  and real pantry material usage. It does not attempt every candidate
  recipe or ingredient variant. Ordering rejects missing stock; previews
  highlight unmet raw ingredients.
* Kiosk block models now explicitly texture every face and underside,
  with a new custom casing texture and the existing animated menu art.

## Kitchen ingredient source fixes (0.3.7-dev)

* The Chef kiosk now sees real stored fluid in connected Cooking for
  Blockheads Cow in a Jar and Milk Jar blocks. Each 1000 mB provides one
  milk ingredient, including Croptopia's milk bottle through CFB's official
  `cookingforblockheads:milk` item tag. Orders withdraw milk using CFB's
  ingredient provider rather than silently generating soy milk or new jars.
* A kitchen ingredient already in a fridge, cabinet, or counter is supplied
  directly before any crafting alternative is considered. Combined
  cabinets/fridges are counted once per real inventory, preventing phantom
  stock or double-withdrawals.
* Kitchen discovery includes tagged third-party kitchen item providers and
  jar attachments two blocks above kitchen equipment, matching CFB's
  multiblock scanning conventions.
* The ingredient preview reports milk portions supplied by Cow/Milk Jars
  so unexpected supply or recipe choices are easy to diagnose.
* Jars only count as milk suppliers when physically linked to the kitchen
  and holding enough milk. No free ingredients are generated.
