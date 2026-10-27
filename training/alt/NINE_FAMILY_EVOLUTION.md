# Builder Golem nine-worker family evolution

**Active experimental protocol:** nine real Minecraft BuilderGolem entities, three parent families, 260-tick course deadline and 224-tick stretch target. Original 10-worker course data is archived unchanged.

## Reproduction every physical generation

1. Move and score all **nine real BuilderGolem entities** on the existing alternate course. Rank by measured fitness (finishing quickly dominates; remaining distance/progress breaks unfinished cases).
2. Select the **three highest-scoring distinct golems from this exact generation**. If fewer than three finish, the best actual unfinished performers fill the remaining parent slots; do **not** invent successes.
3. Preserve each parent's **exact 12-gene controller**, **route-selection policy** and **initial route plan** at its own spawning slot.
4. For each of these three parents, create **two mutated children** from that *one* parent's complete chromosome. A child modifies at most one numeric navigation gene and inherits its family route corridor. A controlled route policy mutation occasionally explores a different legal corridor. **No unrelated random chromosomes or mixing with other families** is allowed.
5. Each child physically starts from its **own** original lane. It can follow the parent's validated slab corridor by independently planning a legal approach to the first slab, or replan if the corridor is inaccessible. The parent reuses its saved route only after validating all grid steps and Minecraft collision geometry. **Routes are never teleports, scripted entity movements or bypasses of doors/blocks.**
6. Observe new arrivals/stalls/door/slab interactions and repeat. The next generation draws parents from its **own** scored nine, not from a stale historical top-three, while personal best records remain independently protected.

## Family structure

- 3 unchanged parents (at their original slot indices);
- 6 mutants: two per parent in the remaining six slots;
- all 9 move simultaneously as **real Minecraft entities**;
- each saved generation contains `nextPopulation` (9 genomes),
  `nextStrategies` (9 route policies), `nextFamilyRoutes` (9 route templates),
  `nextFamilyOrigins` (each parent appears exactly 3 times),
  `knowledgePool` (3 real current-generation parents) and
  `familyModel=three-parents-two-mutated-children-v1`.
- Replays record `familyRole` and `familyParentSlot` so parent vs descendant movement can be compared directly on the phone viewer.

## Isolation and retention

The old ten-worker physical course and its best 10/10 champion are **not directly comparable** with nine-worker runs and must never overwrite nine-worker scores or checkpoints.

- Original 10-worker learning: `training/alt/state/`, `training/alt/replays/live/`
- New 9-worker learning: `training/alt/state-nine/`, `training/alt/replays/live-nine/`
- New results: `scripts/report_alt_nine_results.py`
- New merge with strict 3-parent/6-mutant lineage validation: `scripts/merge_alt_nine_state.py`
- Latest-three-bundle phone viewer: `scripts/export_alt_nine_mobile.py`, `training/alt/replays/mobile-nine-template.html`

First nine-worker cohort is seeded from real physical genes of the old ten-worker test (where available), but old ten-worker success is **not** represented as a nine-worker qualification. Nine-worker generation count starts fresh. Current physical game tests, including real slab ascent and door controller regressions, still apply.
