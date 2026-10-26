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

`CraneDomeCollisionGameTest` now recreates the schematic as actual Minecraft block states on a full-sized test arena, then exercises real `CraneMovement.pose` against pickup, 30/60/90 upgrade ranges, blocked cross-Dome boom routing, an east-side reposition, and a connected, physically supported crane-rail detour around the outside perimeter. Configured with `gradle --no-daemon runGameTest` on branch CI.

**Verification status:** The fixture decode and standalone geometry assertions were executed and matched the results above. The **in-game GameTest has NOT executed**, because all observed GitHub Actions attempts on this private branch fail **before any job steps begin**, with no usable job logs (e.g. [run 40204765021](https://github.com/CaszGamerMD/golems_plus/actions/runs/40204765021)). No successful Fabric build artifact or full 4-builder/3,537-block construction pass is claimed. Once Actions runners are available, first fix any compilation/GameTest failures, then run a separate actual four-builder crane dispatch + physical unload and end-to-end 3,537 placement test.


## Full Dome + crane construction GameTest (2026-10-25 continuation)

`BuilderDomeConstructionGameTest.constructsExactDomeWithCraneAndFourBuilders` is now registered in the **test-only** `golems_plus_gametest` mod alongside the original fixture/collision test and `BuilderInfiniteOnsiteSupplyGameTest`. This is a real Minecraft server test, **not a mock-block builder simulation**.

Test initialization:
1. Decode checksum-verified original `Dome.litematic` and plan 3,537 non-air schematic positions with Ignore Crops/Plants enabled.
2. Create a 48×48×48 arena, real Builder Planner, and ordinary OnSite chest. Set the test owner to an actual server player. Link a test-only warehouse assignment to satisfy the normal Planner start guard.
3. Spawn and register **four** distinct builder entities plus a **fifth separate operator**. Configure `crewTarget=4` after registering the fifth builder so that only the first four receive actual construction zones.
4. Place and link a real `BuildingCrane` chassis, equip builder box, consume two actual boom upgrades for 90-block reach, and assign the fifth golem to the operator seat.
5. Replenish physical OnSite chest slots with materials for current work orders every five ticks. Workers must walk there and withdraw the real inventory. No direct gifts to worker pouches and no free block placements.
6. Observe every rider who physically boards and exits; verify passenger cap, operator seat, the intermediate 1,700-block milestone, all **required** non-vegetation block states, four builders grounded and scaffolding recovered. A complete Dome with **zero actual crane boardings/unloadings is a failure**.
7. Detect 2,400 ticks without clearing/placement progress and print planner cursors, all worker debugging status, and crane passenger/route status instead of reporting a false pass.

A separate `CraneDomeCollisionGameTest` validates cross-Dome boom collision rejection and supported outside-rail bypass. All tests run under `gradle --no-daemon runGameTest`, with logs uploaded by CI.

**Present status:** GitHub Actions for this private branch fails before launching a runner/job step (latest checks visible under [PR #79](https://github.com/CaszGamerMD/golems_plus/pull/79/checks)). No Java 25/Gradle build, actual Minecraft GameTest execution, or all-block construction result is available. The locally available environment has Java 21 but not Minecraft 26.2 Gradle dependencies or network access, so it cannot replace a real Fabric runtime. When Actions becomes runnable, inspect any compile or in-game failures first and iterate until the actual server test succeeds. The Caszual Additions JAR is only needed for a **separate**, complete warehouse pallet-transfer test, not for this isolated builder/crane/Dome construction test.

**Source recovery:** The crane branch's `WorkerStationBlockEntity.java` had a literal truncated marker replacing about 27.5k of source code. Its intact corresponding code was restored from the separate Dome branch, retaining the crane's dedicated OnSite integration. This blocker was addressed before adding the full test.


### 2026-10-25 — resumed testing and physically demanded delivery

- Full Dome GameTest now places **supported 83-segment outside-Dome crane rails** in a 48³ test arena. The rails go from a western docking point around the north boundary to an eastern dock; no rails are positioned inside the schematic.
- The real builder basket starts with a north-perimeter-compatible stowed offset rather than extending out of arena bounds on the eastern run.
- Test creates a **real raised 3×3 exterior stone platform** for a 15-block-up ride. Immediately after the job is linked, it explicitly requests a ride for an existing builder, so no crane-use code path can be skipped by a successful scaffolding-only build.
- An actual physical passenger boarding, unloading and **at least a 6-block rise from boarding elevation** are mandatory before an end-to-end success. Failure diagnostics now include the number of elevated passenger deliveries.
- Standalone Java 21 test compiled the *unchanged production `CranePath` and `CraneDispatch` code* and passed **13 assertions**: connected 83-segment perimeter route, no clipping via rail nodes through the 31×34 Dome footprint, physically missing rail fails closed, denied swept edges fail closed, budget cutoff fails closed, direction batching/4-seat cap, request deduplication, reachable passenger preference, 30/60/90 upgrade limits.
- Added `CraneDomeRouteTest` with these algorithm-level scenarios to permanent JUnit suite. The rail/path/dispatch checks **are not a Minecraft GameTest or a completed construction run**.
- The latest Actions run still fails within seconds without executing steps ([CI](https://github.com/CaszGamerMD/golems_plus/actions/runs/40232539884)); no Minecraft 26.2 Fabric JAR or 3,537-block end-to-end pass can be verified yet. Keep this PR in draft.


### Crew-isolation correction (2026-10-25)
The crane operator is an independent fifth physical Builder entity linked to the same Planner and owner, **not** a fifth registered construction/courier worker. The Planner retains exactly four builder UUIDs and a crew target of four. `CraneService.track` is called immediately after placing the test crane to protect the operator on the first tick. This avoids operator UUIDs interfering with normal builder zoning, warehouse courier selection, and physical construction tests.


## Chassis and auto-connecting rails (2026-10-25)

- **Cabin/chassis:** a visible, collidable **2×2×2 block cube** with compact glass panels and yellow hazard stripes. The boom roots from the top of this cube. Physical collision checks sweep the entire chassis, basket and boom; EntityType is sized 2.0×2.0.
- **Rails:** like redstone dust **in connectivity**, not redstone power. Each rail attaches to a sturdy support face (floor, ceiling, north/south/east/west wall). It automatically grows/removes up to **four** branch arms in its own plane when supported neighboring rails appear or disappear.
- Floor/ceiling: north, east, south, west; walls: up, down and the two directions across that wall. Junctions can form crosses, T-junctions, corners, straight runs or disconnected hubs. Model variants show only their connected arms; collision outlines mirror the branches.
- **Ground-to-wall bends:** a diagonal pair connects floor-to-wall (or ceiling-to-wall) via opposite-facing supports. The rail graph uses the actual connected neighbors, not arbitrary six-axis nearest rails. The 2×2×2 chassis offsets ½ block outward on floor-to-wall bends, then smoothly follows the outside of the wall instead of overlapping the wall-mounted track. Every moved segment still passes the complete swept-collision check. Unsupported/removed rails never count as connections.
- **Verified server GameTest:** `CraneRailConnectionsGameTest.redstoneLikeCrossesWallsAndCornerClimbs` validates a four-arm floor cross, four-arm wall cross, branch removal, bidirectional diagonal floor-to-wall connection, a traversable graph route, and **physically collision-safe 2×2×2 chassis + boom + basket sweep across the vertical rail route**. It passed in [GitHub Actions run 40256605576](https://github.com/CaszGamerMD/golems_plus/actions/runs/40256605576). The dedicated four-builder up-and-down crane transport test passed in that same run.
- The **full 3,537-block Dome GameTest remains unpassed**, and is a separate navigation/materials/scaffolding integration effort. The 2×2 chassis and connected rail branch **does compile** and the new geometry/transport tests pass; use the experimental prerelease in a backed-up world.
