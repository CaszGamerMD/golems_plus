# Builder diagnosis protocol

## Evidence before changes
1. Reproduce in the controlled Matrix environment; use the same commit, fixture and crew configuration for comparisons.
2. Inspect the last 384 events for each builder immediately BEFORE crew retreat and at terminal stall. Sample actual fractional feet, velocity, displacement, ground contact, floor, fluid, current motion controller, flight path leg, work claim, material stage, return state and crew barrier. Route decisions record the actual accepting/rejecting branch.
3. Reconstruct a causal sequence: intended task -> selected route -> executable first leg -> real motion -> arrival/handoff -> placement or return. Separate lack of material, route-planning rejection, physical execution failure, controller conflict and recovery failure. A station-wide problem string is not proof of any one builder's cause.
4. Identify the general invariant violated (e.g. accepted routes require physical progress; failed routes cannot retain exclusive ownership; a recovery must actually land and unload). Create a small physical reproduction and assert the invariant, not just the exact original coordinates.
5. Run isolated tests AND the same three Matrix trials. Compare minimum/mean correct blocks, layer, wall time AND game ticks, actual completed recovery counts, and regression failures. Do not call a partial improvement full completion. A failed test fixture is not a passing regression.
6. Retain the last verified baseline, change one behavioral subsystem at a time, and revert or revise regressions. The first differing observed checkpoint is evidence to investigate, not proof of causation.

## Recorder
Opt in on server with GOLEMS_BUILDER_TRACE=true or -Dgolems.builder.trace=true. Default off. The controlled CI enables it. Each worker has a bounded 384-event ring, samples at 10-tick intervals and on observed controller changes; repeated decisions are coalesced. Dumps occur before crew retreat and at Matrix termination. JSON trace-report artifacts contain worker histories, recent decisions and motion samples. Early checkpoints aid comparison by elapsed simulation ticks. This is instrumentation, not a behavior fix; sampling is not a full deterministic replay and not every branch is instrumented yet. No path planning is invoked by the recorder. Debugging overhead can affect wall time.
