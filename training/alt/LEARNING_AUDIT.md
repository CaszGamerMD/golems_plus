# Navigation learning audit — October 27, 2026

Audited source/state: `a001b23`, 1,605 saved physical generations.

The first 100 historical generations averaged 1.20 finishers; the last
100 averaged 4.05. The last saved generation finished 7/10. The latest
complete cohort was generation 1,284, 321 generations earlier. These
windows include different controller revisions and are descriptive,
not a controlled demonstration of learning or a causal improvement claim.

## Corrected experiment defects

* Top-three parents were assigned to slots 0–2 instead of the starting
  slots in which they earned their scores. Preserve each elite in its
  actual slot, including checkpoint restore. Seven other slots remain
  descendants of the three saved parents.
* Two door probes rotated by slot and arena tick, independently of DNA.
  Door exploration now follows the inherited `doorBias > .075` rule.
  Opening an operable wooden door still requires physical approach.
* Every mutation changed all twelve traits. Mutations now adjust at
  most one trait. Two thirds of children refine one complete parent;
  one third recombine parents before the local mutation. No unrelated
  random genomes replace saved knowledge.

The course, collision rules, native movement, speed cap, ten entities,
260-tick reference and 224-tick qualification remain the scoring fixture.
The old champion and checkpoint are preserved. These changes require new
physical evaluation; passing genetics unit tests is not a navigation pass.

## Remaining limitations that must not be called solved

* Unfinished fitness divides waypoint progress by the selected route's
  length. Routes and reroutes do not provide a common progress scale.
  Replace this with remaining traversable distance under a common neutral
  geometry metric. Version that fitness schema and retain old results
  separately; never silently compare incompatible archived scores.
* The top three reflect single best historical outcomes, not repeated
  reliability. A lucky completion must not count as a reliable policy.
  Evaluate each challenger and incumbent over matched repeated trials.
  Retain unchanged incumbent genes and promote only on aggregate results.
* A preserved elite slot has changing neighbors and congestion. Retaining
  its genes and slot does not guarantee identical physical arrival time.
* This controller is in the GameTest source set and is not packaged in
  the playable mod. Normal `WorkerNavigation` does not load these genes.
  Extract reusable movement decisions into shared production code before
  claiming training improves workers in a user's world.
* Learned blocked edges contain absolute course coordinates. They are
  course memory, not general navigation logic. Do not transfer this map
  to unseen terrain or count it as cross-world learning.
* Fixture-specific reservations and global schematic knowledge remain.
  Generalization requires unseen layouts, mirrored/rotated geometry,
  changed start/goal positions and real entity traffic, using the same
  movement implementation and physical rules.

Report actual finish count, complete-crew pass rate, median and slowest
arrival, stationary time and collisions for repeated incumbent/challenger
trials. Keep exploration results separate from promoted-policy validation.
Exploration can regress; a best-ever score alone is not an upward trend.
