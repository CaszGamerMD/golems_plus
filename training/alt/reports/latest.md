# Builder Golem physical training — latest results

**GitHub Actions:** https://github.com/CaszGamerMD/golems_plus/actions/runs/40605605348
**Run ID:** 40605605348
**Mode:** evolve | **Course:** existing alt course | **Real entities:** 9 per generation

## Results

- **Best 13s (260 ticks):** 9/9; **best 11.2s (224 ticks):** 4/9.
- **First → last 13s:** 9/9 → 8/9. Latest within 11.2s: 2/9.
- **Physical generations scored in logs:** 72 across 3 trial logs.
- **Checkpoint generation change:** 72 → 144 (72).
- **Recorded strategy observations change:** 1296 → 1944 (648).
- **Personal-best improvements this run:** golem 3, golem 7.
- **Shared top three:** golem 4 (100945.0), golem 0 (100825.0), golem 5 (100482.0); pool changed: yes.
- **Historical champion:** unavailable.

## Physical movement signals

- Slab ascents: **1247**; slab-entry stalls: **0**.
- Door plans: **597**; door crossing attempts: **489**.
- Door alignment ticks: **669**; door-entry stalls: **560**.
- Collision events: **563**; jump events: **0**; traffic replans: **89**.

## Trial breakdown

| Trial | Generations | Best ≤260t | Best ≤224t | Final ≤260t |
|---|---:|---:|---:|---:|
| real-builder-trial-1.log | 24 | 9/9 | 4/9 | 5/9 |
| real-builder-trial-2.log | 24 | 9/9 | 4/9 | 9/9 |
| real-builder-trial-3.log | 24 | 9/9 | 4/9 | 8/9 |

## Notes for improving the process

1. **Slabs are physically accessible:** observed real ascents. Compare entry stalls and completion rates before changing slab route costs.
2. **Door-entry hesitation observed:** check collision-safe alignment and congestion at door lips; require the real-controller no-jump regression to pass.
3. **Door routes were attempted:** compare completion times of door users versus other routes before assigning a larger door preference.
4. **Final generation regressed from the session best.** Keep the protected champion and individual top-three pool, then test gene changes against repeated identical conditions.
5. **Speed target not met:** optimize reliable 9/9 completion under 260 ticks before accepting faster individual records as team-wide improvement.

_Based on scored real-entity logs and checkpoint evidence only. No stored route or headless simulation counts as a physical pass. Historical results are retained separately; the HTML viewer remains paused._
