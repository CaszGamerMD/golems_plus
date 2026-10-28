# Builder Golem physical training — latest results

**GitHub Actions:** https://github.com/CaszGamerMD/golems_plus/actions/runs/40619594503
**Run ID:** 40619594503
**Mode:** evolve | **Course:** existing alt course | **Real entities:** 9 per generation

## Results

- **Best 13s (260 ticks):** 9/9; **best 11.2s (224 ticks):** 4/9.
- **First → last 13s:** 9/9 → 9/9. Latest within 11.2s: 4/9.
- **Physical generations scored in logs:** 72 across 3 trial logs.
- **Checkpoint generation change:** 288 → 360 (72).
- **Recorded strategy observations change:** 3240 → 3888 (648).
- **Personal-best improvements this run:** golem 5, golem 7.
- **Shared top three:** golem 4 (100945.0), golem 0 (100840.0), golem 5 (100720.0); pool changed: no.
- **Historical champion:** unavailable.

## Physical movement signals

- Slab ascents: **1278**; slab-entry stalls: **0**.
- Door plans: **617**; door crossing attempts: **555**.
- Door alignment ticks: **630**; door-entry stalls: **0**.
- Four-tick door state retries: **0**.
- Collision events: **0**; jump events: **4**; traffic replans: **49**.

## Trial breakdown

| Trial | Generations | Best ≤260t | Best ≤224t | Final ≤260t |
|---|---:|---:|---:|---:|
| real-builder-trial-1.log | 24 | 9/9 | 4/9 | 9/9 |
| real-builder-trial-2.log | 24 | 9/9 | 4/9 | 9/9 |
| real-builder-trial-3.log | 24 | 9/9 | 4/9 | 9/9 |

## Notes for improving the process

1. **Slabs are physically accessible:** observed real ascents. Compare entry stalls and completion rates before changing slab route costs.
2. **Door routes were attempted:** compare completion times of door users versus other routes before assigning a larger door preference.
3. **Speed target not met:** optimize reliable 9/9 completion under 260 ticks before accepting faster individual records as team-wide improvement.

_Based on scored real-entity logs and checkpoint evidence only. No stored route or headless simulation counts as a physical pass. Historical results are retained separately; the HTML viewer remains paused._
