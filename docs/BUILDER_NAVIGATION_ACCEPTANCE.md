# Builder navigation acceptance gates — crane + Dome integration

The next real Minecraft Fabric 26.2 Dome trial is **not considered navigation-ready**
merely because the code compiles or a Golem appears to move. These are required
physical movement behaviors:

1. **Solid block step up and down (one full block):** a real Builder must
   navigate under its own physics from level ground onto the top of a one-block
   stone ledge, then down to solid lower ground. No teleporting or phantom
   route; require the feet to be at the correct Y, the entity on the ground,
   and a supported landing. Tested by
   `BuilderNavigationRequirementsGameTest.walksOneFullBlockUpAndBackDown`.
2. **See and recognize real scaffolding:** the Builder's specialized
   `BuilderScaffolding` controller must discover existing physically placed
   and Planner-tracked scaffold (including towers owned by other Builders),
   reuse that path, and never duplicate blocks or charged materials.
   Vanilla ground A* **does not** independently provide vertical scaffold
   navigation. Tested by
   `BuilderNavigationRequirementsGameTest.recognizesSharedScaffoldAndExitsBothSolidPlatforms`
   and `BuilderSharedTowerGameTest.fourBuildersShareOneTrackedTower`.
3. **Reliable scaffold ⇄ solid transitions:** a real Builder must ascend a
   real scaffold to the top, step off onto a solid elevated platform, return
   to a valid tower landing, physically descend, and step onto solid lower
   ground, without flight, teleportation, or losing the shared tower. Tested
   by `BuilderNavigationRequirementsGameTest` and
   `BuilderScaffoldRingGameTest.climbWalkRingReturnAndDescend`.
4. **Crew & pallet safety:** crane passengers cannot simultaneously run
   ground/scaffold navigation, and ordinary workers must not plan through
   solid or vehicle-sized pallet/cargo obstructions. Real Caszual Additions
   collision integration is still a pending separate GameTest.
5. **Dome milestone:** the full 4-Builder exact uploaded Dome fixture is run
   after these targeted tests. At **1,700+ actual world-state matches** with
   **all four Builder entities alive**, print the verified marker
   `[DOME TEAMWORK] PASS 1700 placed-block`. This is independent of eventual
   3,537/3,537 build completion.

## JAR release rule

If the full Dome still fails *after* the above milestone is logged, CI
will build and upload an **experimental, PR-only, 1,700-checkpoint JAR** under
`golems-plus-crane-nav-1700-experimental`. Never call that a fully tested
Dome build; never overwrite the successful mainline `dev-latest` release.
If the milestone is not reached, **do not** publish a new experimental JAR.
Full all-green CI retains its normal JAR artifact and release policy.
