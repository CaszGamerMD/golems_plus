# Adaptive zone strategy and drone logistics

Preserve Y as the conventional baseline; compare X and Z as alternate
bottom-first column orders. Candidate features use complete source plans
and the same four-zone production footprint. Coordinate Manhattan travel,
largest transition, lower-cell order, cross-zone lower support, and four
horizontal previously built neighbors are geometry proxies only. They do
not validate actual walk/flight, attachment dependencies, temporary access,
water sequencing, or escape. Never select live work from these previews.
A real strategy controller must authorize only physically ready cells,
keep persistent original indices, reserve one worker per step, maintain
safe ingress/egress and switch only uncommitted work when a route fails.

The survey emits 60 candidate rows from five exact verified courses,
and shows the first two disjoint 32-step windows and exact nominal item
demand. Counts for 10/32/64-step batches are idealized, not inventory-capacity
or round-trip results. The preview deduplicates reusable items per batch;
no stock is actually reserved, debited or transferred.

Next live experiment: restrict initial alternate ordering to simple
columnar zones (Chibi) with checked support and exit, compare three fresh
servers per strategy at matched supplies, worker count and tick budgets.
Record actual completion, distance, travel/placement/recovery, and stock trips.
Mixed policies need explicit cross-zone dependency coordination, not merely
different sort order. Keep unseen future schematics for model evaluation.

Drone design: one physical carried/docked companion per builder, a current
committed batch and separately reserved future batch. Drone acquires real
inventory at OnSite, travels with unrestricted horizontal/vertical motion
but collision-checked sweeps and loaded chunk boundaries, and transfers
only at safe rendezvous. Reservations are job+worker+batch revision bound;
cancel/reassign returns actual cargo and releases unused reservations.
No double payment, duplication, remote stock teleport or material generation.
Missing/failed drone returns to normal builder collection; pause/finish
returns leftovers, crash/reload retains cargo and reservation identity.
Drone implementation and runtime batch capacity changes are not in this
survey. Build policy learning requires physical comparison labels.

## Physical strip pilot

An isolated test-only opt-in now gates a single builder's 12 stone cells
by X or Z strip, then Y within that strip. The normal Y baseline uses
the unchanged gate. Original task indices, stock, actual claims, collision
checks and placement/escape checks are retained. The pilot refuses ordinary
jobs, multi-worker crews and non-stone plans; it is not a saved gameplay
setting. Nine independent server runs (three per axis) use the same fixture,
seed, crate and worker. The test checks actual world order and reports
completion, elapsed game ticks and sampled physical movement distance.
This is not yet a Chibi or mixed-zone result; stone columns do not cover
attachment, fluid, gravity, multiblock, or cross-zone dependencies.

## Dragon benchmark
Nine fresh physical runs use the original hash-verified 1,032-block Dragon
fixture, one worker, same seed, same Creative Crate, 72,000 game-tick and
600-second wall limits, and the existing 1,200-tick no-work watchdog.
Y is unmodified; X/Z use dry strips then global bottom-up water, then
derived-flow verification. The additional fluid policy is a deliberate
safety difference, so results compare complete policies, not axis alone.
Only Dragon's allowlisted palette is enabled; unknown states fail closed.
Original indices and real placement accounting are preserved. Run success
means a terminal assessment was captured; it is not necessarily a complete
build. Compare correct/total, outcome, ticks, distance and terminal worker
diagnostics. No mixed zones, drone logistics or neural strategy training.

## Adaptive Dragon recovery
The opt-in Dragon-only policy permits one bounded adjacent-strip dry
window (up to 24 unfinished source tasks no higher than the stalled
target) after 600 game ticks without completion progress. It then
retries the original strip once. If that window or the retried strip
stalls, or no adjacent work exists, switch monotonically to globally
bottom-up dry Y, then water and derived verification. This is a
geometric access hypothesis, not a proof that a bridge exists.
Switches occur only at safe movement boundaries; existing claims and
staging are reset, not completed cells, paid cargo or world blocks.
The ordinary physical placement and escape checks still gate all work.
The recovery watchdog is reset at the switch, not the global benchmark
limit. Log NN STRATEGY SWITCH with the mode and progress. The adaptive
workflow repeats all nine Dragon trials against the ordinary Y baseline.
This is a deterministic recovery policy for generating learning labels,
not a trained neural policy or a general multi-worker mixed-zone planner.
