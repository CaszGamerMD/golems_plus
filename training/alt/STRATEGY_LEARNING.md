# What the trainer retains and uses

Before this change, successes retained chromosomes (twelve bounded settings).
The A* and traffic algorithms were fixed code. Replays alone did not teach
the controller any strategy. That was evolutionary parameter tuning, not
learned navigation rules.

The test controller now also keeps **contextual strategy experience** in
`checkpoint.json.strategyMemory`, independently of the genetic top-three
pool. Each physical trip records:

* abstract situation: relative door detour, slab share, turn density and
  whether earlier entrants reserved approaches;
* selected strategy: ordinary A*, a route with more emphasis on fewer
  turns, or a discovered legal door route;
* actual arrival tick (or failure), stationary ticks, collisions and
  distance walked;
* unique session/generation/worker provenance so a restored or merged
  episode cannot teach the policy twice.

No route coordinates, starting slot or course identity is part of the
situation key. These are deliberately coarse feature buckets; their
usefulness on unseen layouts is not established.

## How success changes a subsequent decision

Before spawning each entrant, the controller builds legal route alternatives
against real collision geometry, describes the situation, and consults the
saved observations. Four out of five decisions use the current preference;
one is an explicit exploration of a less-tested available alternative.
Validation mode does not explore.

An alternative needs at least three recorded trips, at least two finishes,
a finish rate no worse than the baseline, and a mean reward improvement
above 0.02. The baseline also needs at least three observations in that
context. A successful completed trip scores 0.6–1.0 before small stationary
and collision penalties; a nonfinish scores zero. No simulated arrival or
route-relative waypoint progress earns strategy success.

The most recent 32 trips per situation/strategy determine the preference.
Repeated later failures can therefore overturn an older preference, while
the full evidence remains saved. The next generation consults this updated
memory. A process restart restores it before route selection; GitHub merges
independent observations by unique episode ID and rejects conflicts.

Logs and physical replay metadata name the context, strategy, reason
(`baseline`, `exploration`, or `repeated-outcomes`) and prior evidence count.
`ALT STRATEGY OUTCOME` records explain which trips contributed to a decision.
The complete ledger lives in the checkpoint, rather than being duplicated
inside every history line.

## What this does not establish

This is a learned selector over **three human-written route strategies**.
It does not generate new code, invent movement primitives, learn collision
physics, or yet learn new yielding/steering/recovery strategies. Genetic
motor tuning remains alongside it. Outcomes collected with different genes
and neighboring crews are empirical evidence, not controlled causal proof.

Older checkpoints retain all their genes and obstacle memory. They have
no annotated strategy episodes, so none are fabricated from old wins.
`context-strategy-v1` marks the new policy revision. Physical tests must
establish its effectiveness; unit fixtures are not Minecraft passes.

A unit regression executes the real Java selector: one lucky success does
not promote a strategy; repeated better outcomes do; restoring the evidence
preserves that choice; unavailable routes are never selected; and repeated
failures can overturn the learned choice. Separate tests cover evidence
merge/deduplication. Broader strategy libraries, paired evaluations and
unseen layouts remain future work.
