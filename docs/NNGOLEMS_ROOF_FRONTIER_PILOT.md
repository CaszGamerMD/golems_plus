# Farm Demo roof-frontier pilot

Recent verified Farm Demo runs (2026-11-16):
- Staging scout A/B: 411 correct with staging versus 321 without. Staging recruited 6 scouts, started 9 short ground legs, reached 8 waypoints.
- The following chaining A/B proved continuous staging travel (six consecutive physical waypoints, five transitions, no stage stops) but ended with **300** correct against **727** in the no-staging control. Both failed with NO_WORK_PROGRESS. The no-staging controls alone ranged from 321 to 727 despite fixed world seed 0. These single runs do **not** demonstrate a reliable construction gain.

Evidence: reaching a safe point on the ground near an unfinished upper structure did not consistently produce an executable work stance. The builder's existing physical roof-flight preflight was restricted to tiny layers (24 or fewer unfinished tasks), whereas Farm Demo's blocked layers have 90-360+ tasks.

New opt-in `GOLEMS_NN_ROOF_FRONTIER=true`:
- For larger unfinished layers, allow **one expensive roof preflight every 30 server ticks per grounded worker**, ONLY after normal work-stance search and direct flight checks fail, target lies within 24 horizontal blocks, and the target is 2-64 blocks above the worker. This bound keeps the cost manageable and avoids rejecting all large layers purely by work count.
- The preflight delegates exclusively to `BuilderFlight.canAccessRoofWork`, which requires real supported launch and landing, collision-free lift, a safe grounded path to a valid work pose, and a safe exit. The normal execution path then uses `approachRoofWork`. The extra check neither grants movement through a block nor marks any schematic cell complete.
- The existing conservative small-layer fallback is unchanged. This experiment remains OFF by default and isolated to NNGolems.
- Four separate physical Farm Demo assessments (two with roof pilot ON, two OFF) use eight builders, seeded world 0 and staging ON in both modes. Report actual roof probe and approval counts, staging activity, exact correct blocks, ordinary completion, and run-to-run spread, with an aggregate median comparison. A workflow PASS only means the tests executed; not a completed farm or proof of learning.

If this pilot finds no approved roof paths, the next problem is reachable flight launch / supported landing geometry, not the size of the roof-search budget.
