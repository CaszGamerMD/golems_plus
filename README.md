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

## Chef execution and Litematica placements (0.3.8-dev)

The Builder's Planning Table reads the actual Litematica 26.2
`getAllSchematicsPlacements()` manager method, selecting the true
`getOrigin()` for each player placement. Optional Litematica reflection
is retained so Golems Plus still starts without Litematica.

Chef orders select exactly one recipe path and reserve its **leaf**
ingredients from connected Cooking for Blockheads storage. The player
inventory is explicitly excluded. Every plan step records its selected
input items and batch output; these are persisted with the active order.

Chef workers approach the linked pantry and show the actual planned
ingredient in their hands. They go to the kitchen table for assembly and
move the reserved inputs into the actual intermediate output only as each
step finishes. Cooking, smoking, and campfire recipes require a connected
Cooking for Blockheads oven: the Chef loads its real input slots and waits
for its *actual* cooking output, including a usable fuel supply. If the
oven is busy, out of fuel or not connected the job waits with a
diagnostic rather than inventing food. Only completed items in the job's
reserved work buffer may be plated. After an order completes, remaining
intermediates and unused inputs return to the connected pantry.

Preexisting 0.3.7 jobs resume via the legacy reserved-item path; create
a new order to test the structured physical crafting stages.

## Golems Plus 0.3.9-dev — Beekeepers & Fishermen

**Beekeeper Tool Bag:** The existing Beekeeper Station block keeps its ID
but now looks like a cloth equipment bag with a copper smoker. The smoker
emits occasional smoke particles. Open the workstation to store shears,
empty glass bottles and flowers directly in its input slots. The separate
Beekeeper Toolbox block is retained for old worlds but no longer used by
workers or added to the creative tab. Use **LINK OUTPUT CHEST** in the
Beekeeper work menu to select where honey bottles and comb go.

**Fisher Cooler Station:** The existing Fisher Station block keeps its ID
but now looks like two ice coolers, drinks on one side, fish head on the
other. The fish head occasionally winks. Stock its built-in inventory with
fishing rods and craftable bait (wheat seeds + string yields eight bait).
Use **LINK OUTPUT CHEST** for harvested fish.

Set the saved fishing coordinate with **SAVE WAYPOINT / FLAG** in the
Fisher job menu, or right-click your own Fisher cooler with the reusable
Fishing Spot Marker to bind it, then go to a safe shoreline or source-water
surface and shift-right-click. The marker remembers the chosen cooler during
the session; the actual fishing location persists in the station. The marker places a visible flag
on land and a floating buoy in water. The Golem finds reachable solid
footing near the waypoint and sits in a folding campchair while idle or
fishing. It waits for variable fishing intervals, uses one bait and rod
durability per catch, carries its own nine-slot fish inventory, returns
when its pockets fill or bait runs out, unloads into the linked output
chest and returns to the job station to restock. A blocked route, missing
shore platform or full chest stops delivery instead of deleting fish.

Fishing currently produces weighted vanilla cod, salmon, tropical fish
and pufferfish rather than the full vanilla fishing loot table. External
modded loot tables remain a future integration. Models and marker state
are saved, and previously held catches are migrated on load.

## 0.3.10-dev — Croptopia Cheeseburger recipes and kiosk tooltips

* Chef's recursive recipe planner now enumerates actual accepted item
  holders from every tag ingredient. Items such as raw beef, salt, dairy,
  seeds and modded crops no longer disappear just because they are not
  already in the pantry or craftable through another recipe.
* The planner uses the ShapelessRecipe result API directly, and ranks
  kitchen-stocked ingredients first. Ingredient alternatives no longer
  need to be in inventory to appear in recipe previews.
* The top-level requested meal is **never** treated as a missing raw
  ingredient when no valid crafting path was discovered. The kiosk
  displays a recipe error instead of misleadingly listing RAW 1x
  croptopia:cheeseburger. Complete meals can only be plated directly
  when they exist in linked kitchen storage.
* Tooltips now render in the GUI's tooltip pass, anchored to the actual
  cursor rather than to coordinates translated for the screen's labels.
* Ingredient search uses bounded alternatives and fewer full-recipe
  scans to reduce stalls in large modpacks.

## Golem navigation overhaul (0.3.11-dev)

All five worker types (Builder, Trader, Beekeeper, Chef and Fisher)
share a single door-aware server-side pathing controller. Workers keep
their intended route instead of constantly restarting it, and detect
stalls when their bodies make no progress. After a stall they try
another walkable approach rather than continuing into a wall. Doors
are opened before workers walk into them, and closed only after they
have fully cleared the doorway, if no other entities are nearby.

Workers now choose adjacent, reachable stand positions near solid
job blocks. Close-proximity worker congestion triggers deterministic
yielding so two golems cannot both shove one another through the same
door. Chef's existing avoidance of countertop surfaces and its 0.6
block maximum climb remain enforced; all worker entity collision
widths remain 0.85 blocks with unchanged models.

## 0.3.12-dev — Chefs / doorway clearance

The Croptopia Cheeseburger uses the normal shapeless crafting recipe with
bread, a cheese-tag ingredient, a frying pan and beef. The Chef planner now
accepts recipes that have compatible result templates or a registered
recipe ID matching the actual food item, and avoids exhausting its recipe
search budget evaluating every alternative. It still requires a real
recipe; if the finished food is stored in the *linked kitchen only*, it
may be served directly. Player inventories never supply food.

A single open Minecraft wooden door has less than 0.85 blocks of usable
clearance because its hinge panel has collision. Golems remain 0.85 blocks
wide normally, but temporarily use a 0.70-block collision width within
two blocks of a wooden doorway while routing through it. Their 3D models
remain unchanged. Doors are opened before path calculation where possible,
and the normal collision width is restored after the Golem clears them.
This applies to all five professions; it does not modify door models or
change the Chef's maximum step height.

## 0.3.13-dev — Builder Litematica selection fix

Litematica 26.2 loads its list of placements through
`getAllSchematicsPlacements()`, but uses different accessors when an
individual placement is imported:
`getAllSubRegionsPlacements()` returns the complete subregion collection
and `getEnabledRelativeSubRegionPlacements()` returns a map of enabled
subregions. The former Golems Plus selection handler incorrectly called
`getAllSubRegions()` and `getEnabledSubRegions()`, which do not exist in
this version. It could list placements successfully, then throw
`NoSuchMethodException` when one was clicked.

The client bridge now reads the correct method names, accepts either
Map or Collection when checking the total/enabled counts, and reports
how many subregions need enabling. If a separate file error occurs
(e.g. the placement has never been saved as .litematic, is larger than
the configured 2 MiB transfer limit, or isn't on disk), the full error
is shown in chat rather than truncated in the Builder Planner GUI.
Wait for a server-side '[Builder] Imported placed...' confirmation
before pressing Preview or Start build.

## Golems Plus 0.3.14-dev — Fisher camp chair and Chef recipe recognition

* The Fisher's former simple rectangular placeholder has been replaced by a
  detailed red camping chair based on the reference photo: red canvas
  seat/back/headrest, black sewn bindings, two fabric armrests, mesh
  cupholder, crossed steel folding legs, hinge blocks and four rubber feet.
  The pixel-art chair material is now baked into the Fisher's entity texture
  atlas; it is not transparent. The model appears when a Fisher sits at a
  fishing spot, waits without supplies, or is paused near the cooler.
  The chair is hidden while walking. This changes existing Fisher models
  in place and does not spawn a separate chair entity.
* The Chef recipe catalog is built independently from what the connected
  kitchen has in stock. Inventory only influences which ingredient variant
  is selected and which items are marked missing. In particular, an empty
  kitchen should still show a known recipe and its missing leaf materials.
* For modded recipes, the catalog now finds output stacks by Java type,
  including ItemStackTemplate, instead of relying on a few assumed field
  names. It also reads ingredient alternatives through the 26.2
  PlacementInfo API when a shapeless recipe does not store an accessible
  direct ingredients list. Preview permits missing leaf resources without
  treating them as a finished meal in the pantry.
* When a preview truly fails it now distinguishes 'recipe not registered'
  from 'registered recipe with unsupported intermediate or processing
  method'. Actual orders still consume only real available kitchen items,
  and completed food can only be picked directly from the kitchen's
  inventory (never from a player's carried inventory).
