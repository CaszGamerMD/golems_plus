# Builder Golem physical training — latest results

**GitHub Actions:** https://github.com/CaszGamerMD/golems_plus/actions/runs/40589130589
**Run ID:** 40589130589
**Mode:** evolve | **Course:** existing alt course | **Real entities:** 9 per generation

## Results

- **Best 13s (260 ticks):** 9/9; **best 11.2s (224 ticks):** 4/9.
- **First → last 13s:** 6/9 → 6/9. Latest within 11.2s: 2/9.
- **Physical generations scored in logs:** 72 across 3 trial logs.
- **Checkpoint generation change:** 0 → 72 (72).
- **Recorded strategy observations change:** 0 → 648 (648).
- **Personal-best improvements this run:** none verified.
- **Shared top three:** golem 4 (100915.0), golem 0 (100810.0), golem 5 (100452.0); pool changed: unknown.
- **Historical champion:** unavailable.

## Physical movement signals

- Slab ascents: **1256**; slab-entry stalls: **1**.
- Door plans: **585**; door crossing attempts: **492**.
- Door alignment ticks: **627**; door-entry stalls: **519**.
- Collision events: **503**; jump events: **1**; traffic replans: **93**.

## Trial breakdown

| Trial | Generations | Best ≤260t | Best ≤224t | Final ≤260t |
|---|---:|---:|---:|---:|
| real-builder-trial-1.log | 24 | 9/9 | 4/9 | 8/9 |
| real-builder-trial-2.log | 24 | 9/9 | 4/9 | 9/9 |
| real-builder-trial-3.log | 24 | 9/9 | 4/9 | 6/9 |

## Notes for improving the process

1. **Slabs are physically accessible:** observed real ascents. Compare entry stalls and completion rates before changing slab route costs.
2. **Door-entry hesitation observed:** check collision-safe alignment and congestion at door lips; require the real-controller no-jump regression to pass.
3. **Door routes were attempted:** compare completion times of door users versus other routes before assigning a larger door preference.
4. **Final generation regressed from the session best.** Keep the protected champion and individual top-three pool, then test gene changes against repeated identical conditions.
5. **Speed target not met:** optimize reliable 9/9 completion under 260 ticks before accepting faster individual records as team-wide improvement.

_Based on scored real-entity logs and checkpoint evidence only. No stored route or headless simulation counts as a physical pass. Historical results are retained separately; the HTML viewer remains paused._
