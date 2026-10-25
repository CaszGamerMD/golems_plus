# Building Crane (experimental branch)

A job-linked transport vehicle, with a builder operating its chassis. Uses a telescoping boom with 30-block reach; two consumed boom extensions raise reach to 60 and 90. Attach either the Builder Box (four passengers plus the separate operator) or Supply Box (two packed Caszual Additions pallets, preserving their component data and contents).

## Setup

1. Craft/place an Operating Chassis with clear space beside it. Attach a Builder Box or Supply Box by right-clicking with that item.
2. Start/select the construction job in its Builder Planner. Right-click the planner using a Crane Controller, then right-click the crane using that controller.
3. With the crane selected, right-click an existing builder from that planner using the controller. It becomes the operator, releases its old build assignments, walks to the chassis and mounts its seat. One operator is required in addition to transported builders.
4. Add Boom Arm Extensions by right-clicking (30 -> 60 -> 90). Right-click with empty hand for status; `/buildingcrane status` also reports the selected crane.

Builders request transport for elevated destinations. The oldest request chooses the direction of the next batch; up to four compatible builders board, then are delivered to validated standing surfaces near their destinations. There must be a clear pickup/landing area for the box. A crane does not unload builders into unsupported air. Blocked pickups are deferred and retried; a blocked landing keeps passengers aboard. `/buildingcrane recall` returns the load to its original landing.

## Rails

Crane Rails attach to a ground, wall, or ceiling face with solid support. Connected rails can extend vertically and horizontally. Place the chassis on a rail to enable travel. When a destination is blocked or outside boom range, the crane retracts, searches connected rails, moves along them, and resumes load movement. Removing a rail or placing an obstacle stops movement. Disconnected rails are not crossed.

Both boom and cargo paths check solids and loaded chunks. Paths are bounded, cached, and revalidated as the crane moves. The planner can report a blocked route even if a complex alternative exists; it never substitutes movement through walls.

## Moving OnSite supplies

- Attach Supply Box only while parked, without builders or pallets loaded.
- Right-click with a packed pallet to load it, maximum two. Pallets require Caszual Additions; the crane itself has no hard dependency on that mod.
- Empty-hand right-click opens a 54-slot page. Shift-empty-hand cycles pages; `/buildingcrane supplies 1` through `8` opens a particular page while nearby. Slots only accept supplies when their corresponding pallet is installed.
- Shears remove one actual packed pallet at a time with its remaining contents.
- `/buildingcrane onsite` designates this box as the linked job's OnSite storage. Coordinates update after parking; builder access is suspended in transit, but the manifest still counts carried stock.
- Builders requesting their next work-order supplies can call the box to a clear spot nearby. With a supply crane selected, controller-click a destination block for manual parking.
- Swapping modules returns the old module. Upgrade items and cargo are never created for free.

## Validation / playtest

Automated coverage checks FIFO direction batching, the four-builder cap, upgrade limits, bounded routing around obstacles, vertical rail connectivity, and missing-rail rejection. The movement planner now checks all four stow directions if the first is blocked, and unloads passengers with reachable platforms before waiting for an obstructed passenger. The nearest standable landing is considered first to avoid unnecessarily evaluating expensive collision volumes. Actual entity motion, client rendering, loading/unloading, restart recovery, and cross-mod pallet interaction require in-game verification.

Test one operator and four builders with jobs above and below ground level; a blocked doorway; an interrupted rail; all four riders dismounting onto a platform; save/reload mid-trip; two different jobs; two partially filled pallets; moving OnSite storage during restocking; cancellation/recall with passengers aboard. This is an experimental build, not a claimed end-to-end construction pass.


## Exact Dome integration and test results (2026-10-25)

The user-provided `Dome.litematic` has been carried into this branch **only as an isolated GameTest fixture**: nine base64 segments in `src/gametest/resources/golems_plus_dome/` unpacked and SHA-256 checked by `BuilderDomeFixture`. This data is not included in the shipping mod JAR.

In a standalone pure-JavaScript geometric analysis using the decoded **actual NBT block palette and packed block arrays**, the fixture yielded a 31×39×34 bounding box, **3,537 non-air cells**, 118 palette entries, and 3,471 approximate collision-solids (plant/soft-material exclusions are approximations in this geometry-only run). The model included the production crane's 1.1-block builder basket radius, 0.65-block chassis radius, 2.7-block load height, 2.9-block boom root, and 0.13-block boom collision radius.

With a west-side chassis 6 blocks outside the Dome and 16 blocks along its Z side:
- Ground-level west pickup: **collision clear**.
- West-side Y30 delivery with 30-block reach: **out of reach**.
- West-side Y30/Y38 with 60-block reach: **collision clear**.
- Far-east roof Y38 from that western location: **straight boom blocked by real Dome shell**, even with 90-block reach. The crane must physically move to the other side (rail movement or repositioning), never phase the load or boom through the building.

`CraneDomeCollisionGameTest` now recreates the schematic as actual Minecraft block states on a full-sized test arena, then exercises real `CraneMovement.pose` against pickup, 30/60/90 upgrade ranges, blocked cross-Dome boom routing, and an east-side reposition. Configured with `gradle --no-daemon runGameTest` on branch CI.

**Verification status:** The fixture decode and standalone geometry assertions were executed and matched the results above. The **in-game GameTest has NOT executed**, because all observed GitHub Actions attempts on this private branch fail **before any job steps begin**, with no usable job logs (e.g. [run 40204765021](https://github.com/CaszGamerMD/golems_plus/actions/runs/40204765021)). No successful Fabric build artifact or full 4-builder/3,537-block construction pass is claimed. Once Actions runners are available, first fix any compilation/GameTest failures, then run a separate actual four-builder crane dispatch + physical unload and end-to-end 3,537 placement test.
