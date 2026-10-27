# Builder Golem physical training — latest results

**GitHub Actions:** https://github.com/CaszGamerMD/golems_plus/actions/runs/40582003719
**Run ID:** 40582003719
**Mode:** evolve | **Course:** existing alt course | **Real entities:** 10 per generation

## Results

- **Best 13s (260 ticks):** 7/10; **best 11.2s (224 ticks):** 3/10.
- **First → last 13s:** 4/10 → 6/10. Latest within 11.2s: 3/10.
- **Physical generations scored in logs:** 72 across 3 trial logs.
- **Checkpoint generation change:** 2208 → 2280 (72).
- **Recorded strategy observations change:** 4320 → 5040 (720).
- **Personal-best improvements this run:** none verified.
- **Shared top three:** golem 0 (100840.0), golem 4 (100840.0), golem 1 (100795.0); pool changed: no.
- **Protected historical champion:** 236 ticks (10/10) — historical, NOT evidence of this run.

## Physical movement signals

- Slab ascents: **1270**; slab-entry stalls: **1**.
- Door plans: **84**; door crossing attempts: **80**.
- Collision events: **3**; jump events: **85**; traffic replans: **170**.

## Trial breakdown

| Trial | Generations | Best ≤260t | Best ≤224t | Final ≤260t |
|---|---:|---:|---:|---:|
| real-builder-trial-1.log | 24 | 7/10 | 3/10 | 7/10 |
| real-builder-trial-2.log | 24 | 7/10 | 3/10 | 6/10 |
| real-builder-trial-3.log | 24 | 7/10 | 3/10 | 6/10 |

## Notes for improving the process

1. **Slabs are physically accessible:** observed real ascents. Compare entry stalls and completion rates before changing slab route costs.
2. **Door routes were attempted:** compare completion times of door users versus other routes before assigning a larger door preference.
3. **Final generation regressed from the session best.** Keep the protected champion and individual top-three pool, then test gene changes against repeated identical conditions.
4. **Speed target not met:** optimize reliable 10/10 completion under 260 ticks before accepting faster individual records as team-wide improvement.
5. **Knowledge pool unchanged:** new physical generations did not beat the retained personal-best leaders. Prefer controlled motor/strategy experiments instead of counting generation increments as improvement.

_Based on scored real-entity logs and checkpoint evidence only. No stored route or headless simulation counts as a physical pass. Historical results are retained separately; the HTML viewer remains paused._
