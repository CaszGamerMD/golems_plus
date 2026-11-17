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

## Rerun diagnosis and correction (2026-11-16)

GitHub Actions attempt 2 at https://github.com/CaszGamerMD/golems_plus/actions/runs/43956250427/attempts/2 showed roof ON=472/361 and roof OFF=309/1164 correct schematic cells. All four runs ended with NO_WORK_PROGRESS, and roof ON recorded ZERO NN ROOF PROBE events. Upper layers continued to stall with valid stand candidates but no executable routes; scaffold cells without support chains also remained pending. This is not evidence against roof access because the experimental path never activated.

Code inspection found that the planner evaluated `BuilderFlight.canAccessWork` before `BuilderNNRoofFrontier.canApproach` using Java short-circuit `||`. The generic check may return true for a local hover pose without proving the supported perimeter travel needed to get there, suppressing the bounded roof preflight completely. We now evaluate the opt-in roof preflight first and retain the generic fallback and full physical movement safeguards. The change only affects NNGolems when the opt-in flag is enabled, and roof checks are still gated to nearby, above-ground targets on large unfinished layers and throttled every 30 ticks per worker. Each roof candidate rejection now emits a bounded, low-rate `NN ROOF GATE` reason, which distinguishes too-distant work, ungrounded workers, busy motion and disabled equipment. The comparison workflow now **fails** a roof-ON test if it never reaches any `NN ROOF PROBE` event.

Repeats are deliberately reported as separate trials: a fixed Minecraft seed did not yield stable outcomes, and neither better raw movement nor a one-off correct-block spike will be presented as validated learning.
