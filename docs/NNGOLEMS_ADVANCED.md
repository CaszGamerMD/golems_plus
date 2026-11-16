# Advanced build recovery experiment

Baseline (43833897214): Froglights 537/3102 (gate facing reversal),
Farm Demos 11/13143 (unsupported barrel at layer zero plus other unreachable
frontier cells), Chibi 128/32061 (recovery loop, no unsupported source states).
This experimental change preserves native fence gate clicks while temporarily
orienting the interaction proxy to the desired gate facing, then restoring yaw.
It adds empty barrel/crafter placement, lit campfires and legacy chain alias
resolution if the old registry ID is absent. Barrel open and crafter output
flags are runtime properties, not copied inventory data. Spawners and moving
pistons stay unsupported; no fabricated machinery state or source NBT.

The advanced course workflow isolates three real physical builds and enables
bounded worker trace diagnostics plus terminal per-worker status for claim,
work frontier, movement owner, and recovery. Its separate native regression
job validates four gate facings and the new paid-item block resolution.
No neural policy steering change. Chibi's actual long-range failure is not
yet diagnosed; gather trace before altering pathfinding. The advanced builds
are no longer blind holdouts if used for further fitting.

## Distant exact work poses

Run 43836653836 passed farm regressions and produced Froglights 599/3102,
Farm 12/13143 (one unsupported transient piston), Chibi 505/32061.
Farm and Chibi terminal traces show real standable poses, native exact routes,
and trained search_partial at 1400 nodes. The existing local 40-block A*
correctly returns a bounded advancing segment, but exact work selection
rejected it. New distant preflight accepts only an executable partial trained
leg plus a complete, safe native read-only survey of an actual final standing
pose (at most 121 nodes; four candidate probes). Native routing never chooses
or executes long motion. Exact travel retains the actual target and executes
bounded trained legs, then demands complete local routing near arrival.
Placement still checks actual reach, clearance and support. This is not an
unlimited reachability certificate: destinations beyond native survey reach
or around complex obstacles can still need hierarchical planning. The new
72-block real-worker regression and full advanced courses test this change.

## Explicit ceiling attachment dependency

Run 43837677153: distant exact 72-block regression passed at tick 239;
Farm advanced from 12 to 172, Chibi 505 to 1679, Froglights 598.
Farm stopped at a ceiling lever needing the permanent schematic cell above.
Allow only the exact dry item-backed support of a pending ceiling lever or
button to join the preceding dry phase. Keep original persisted indices,
material cost and world validation; unrelated upper cells remain gated.
Small-crew assignments include this explicit dependency. New real-builder
regression checks that the support is built before the lever and an unrelated
upper cell waits. This is deliberately not a general dependency solver:
unsupported/missing support, multi-level chains and other attachment types
still need separate analysis. Chibi supply travel and Froglights body occupancy
remain unresolved.
