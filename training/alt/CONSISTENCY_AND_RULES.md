# Matched consistency experiments and local navigation rules

Default training mode is now `consistency`. The former genetic exploration
is available explicitly as `GOLEMS_EVOLUTION_MODE=evolve`; old genes,
offspring, top-three pool, course memory and champion remain preserved.

## Controlled comparisons

A session uses 24 physical rounds: four blocks of three baseline/challenger
pairs. Each pair runs in the same arena with the same ten saved full-cohort
genomes, starting slots, initial route-policy choices and blocked-edge map.
The exact original fixture is rebuilt BETWEEN rounds. There is no change
to the fixture during a race. The 260-tick reference, 224-tick speed goal,
native collision checks, 0.6 step-up and 4 m/s cap remain.

Genetic breeding and route-strategy exploration are paused. The frozen
cohort and route-policy choices survive checkpoints separately from saved
experimental offspring. The learned local policy is frozen for the six
rounds in a block; only the challenger recovery primitive changes. Each
round records a SHA-256 fingerprint of its actual initial routes, genes,
static memory and arena origin. Mismatched fingerprints cannot prove a rule.

The challengers test stable right-of-way, yielding into a legal holding
space, and reverting to baseline behavior. Baseline rounds use only rules
promoted before the block. New rules can first affect a subsequent block.
All rounds are retained, including failed experiments. The session does
not stop after a single lucky finish.

## Local outcomes and generated rule bindings

An obstacle episode starts at a step, turn, door, or nearby traffic. Its
key contains the obstacle type and traffic relationship (clear, following,
crossing), never a coordinate or a remembered path. Success requires
physically clearing two original route edges, rather than merely reaching
a temporary holding spot. Each episode lasts at most 60 ticks and records
elapsed time, stationary time and collisions. Unexecuted challenger
primitives are labeled BASELINE so they cannot claim credit.

The evidence generates bindings of the form:

`obstacle=step;traffic=crossing -> STABLE_MERGE`

That line is an illustration, NOT a claim that this rule has been learned
on the track. A binding is created only after matched local improvements
appear in all three repeat pairs, with at least three successful clearances,
no reduction in clearance count and a reward improvement above 0.03.
At most one observation per worker/context/repeat earns a vote.

The challenger must also preserve the crew's median and minimum completion
counts and full-crew pass count; mean arrival time, counting nonfinishers
as 260 ticks, may worsen by at most one tick. Incomplete comparisons,
different cohorts and later failed experiments cannot erase a proven rule.
The checkpoint stores immutable physical trials; restore derives the same
bindings. The merger unions trial IDs and rejects conflicting evidence.

## Recovery primitives

STABLE_MERGE retains pairwise right-of-way while the owner progresses.
Near-equal remaining distances cannot flip priority every tick. After
20 ticks without progress, the lease permits the peer to clear; a separated
pair gets a fresh lease on its next encounter.

YIELD_ASIDE adds a search for a nearby physically reachable holding cell.
Candidate holding paths avoid teammates and their next two route cells,
use the existing collision-checked planner and native movement, and remain
subject to ordinary traffic collision checks. The golem waits briefly for
the lane to clear, then replans toward the actual finish. Two attempts per
trip bound recovery; no flight, teleportation, block edits or noclip.

The new real-Minecraft holding-space regression checks that an actual
Builder moves aside without overlapping the stationary Builder in its lane.
Unit tests execute the real Java rule/lease logic and check rule creation,
restore/deduplication, cohort mismatch, tail regression and bounded ownership.
Unit fixtures are not movement passes.

## Reviewing results

Mobile replay metadata labels the arm, repeat, tested primitive, remembered
rule count and local obstacle clearance totals. Raw traces retain separate
initial and final routes so a runtime detour cannot masquerade as the
original plan. History retains trial metadata, while full growing evidence
ledgers live only in checkpoints. Logs contain `ALT MATCHED TRIAL`,
`ALT RULE BINDINGS` and `ALT CONSISTENCY REPORT`.

These are learned context/action bindings over supplied safe primitives.
They do not synthesize arbitrary Java code or establish generalization to
unseen terrain. Three repeats provide an initial retention gate, not a
statistical guarantee. Matched baseline results and physical regressions
must establish whether these changes improve movement.
