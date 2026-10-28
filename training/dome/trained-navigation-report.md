# Original Dome end-to-end, merged trained navigation — physical results

**Source:** Original user's Dome.litematic, SHA-256 `5a3f8b8f5dba9766838b79f238bfa0802aea3cca4ca03f19a8c567408ebeaa54`, original source bytes 6,488, 3,537 non-air schematic cells, 37,569 air cells.

**Test:** `BuilderDomeConstructionGameTest.constructsExactDomeWithRealBuilders` (Minecraft 26.2). Four actual `BuilderGolem` entities, production `BuilderBuildService`, actual chest supplies, 48³ full arena, ground and scaffold collision, no teleporting or instant building. The GameTest used the unified trained navigation committed to main as `b0294a9d741b45fb28865a1ae38fe4cefa01b004`; no previous navigation fallback.

**Acceptance:** The world must match all non-ignored schematic block states, the Planner must reach COMPLETE, all Builders must be grounded, temporary scaffolds must be retrieved. **Not yet passed.** All runs stopped at the 2,400-tick no-progress watchdog with no full-dome completion; a GameTest failure does not qualify as a pass.

| GitHub run | Physically matched cells | Clearing | Primary blocker |
|---|---:|---|---|
| [40639082593](https://github.com/CaszGamerMD/golems_plus/actions/runs/40639082593) | N/A | N/A | Old test API `builderCompletedCount()` no longer exists; fixture compiled after porting |
| [40639218708](https://github.com/CaszGamerMD/golems_plus/actions/runs/40639218708) | 0 / 3,537 | 0 | 'Saved schematic missing' despite present file |
| [40639395761](https://github.com/CaszGamerMD/golems_plus/actions/runs/40639395761) | 0 / 3,537 | 0 | Normalization defect confirmed: relative config directory contains `./`; file exists (6,488 bytes), file-side alone normalized |
| [40639542189](https://github.com/CaszGamerMD/golems_plus/actions/runs/40639542189) | 268 / 3,537 | 41,106 / 41,106 | Ignore Crops/Plants missed vanilla azalea; four builders wait for support |
| [40639748314](https://github.com/CaszGamerMD/golems_plus/actions/runs/40639748314) | 661 / 3,537 | 41,106 / 41,106 | No safe scaffold column available at selected dome rim cell; broader tower search tested |
| [40639983842](https://github.com/CaszGamerMD/golems_plus/actions/runs/40639983842) | 661 / 3,537 | 41,106 / 41,106 | Pink tulip incorrectly demanded despite Ignore Crops/Plants |
| [40640183119](https://github.com/CaszGamerMD/golems_plus/actions/runs/40640183119) | **828 / 3,537** | 41,106 / 41,106 | Test harness fake warehouse causes 'Install Caszual Additions with Builder warehouse support'; head-on doorway + resupply stalls |
| [40640423783](https://github.com/CaszGamerMD/golems_plus/actions/runs/40640423783) | 817 / 3,537 | 41,106 / 41,106 | **Real OnSite chest-only:** multiple workers idle around door and placement-space yield. Door queue `CROSS q#1` / `WAIT q#1`; 2 Builders staging, 1 moving to work, 1 on high scaffold. |

Notes:
- Counts are **actual world block-state matches** across all zones, not Planner's contiguous cursor. The test checks every 20 ticks, so numbers are sampled.
- The two most recent trials produced different worker names and start conditions; this is not proof of a performance regression from 828 to 817.
- The full 41,106-cell site-clear process completed successfully in all runs after path fix.
- Last real chest-only stall: game tick **8,020**; `contiguous=831`, `physical=817`; three workers are stationary around a narrow gate/door queue; main worker approach `nav=idle` with retries >100. The test's per-run watchdog is 2,400 ticks since any genuine build progress.
- Production-level corrections **only on this test branch**, pending full-dome verification: normalizing the saved schematic directory before security containment check, classifying azalea and ordinary flowers for Ignore Crops/Plants, allowing chest-only Builder job start, extending collision-validated scaffold search radius within placing reach.
- Original trained navigation build on `main` is **unchanged** by this test branch. No failed experiments were merged to production.

**Next diagnosis:** Replace the workers' door FIFO / one-tile crossing with physical progress-based reservation expiration and same-direction convoy right-of-way. Preserve the active goal and avoid marking occupied tiles as permanently unwalkable. Instrument worker door timestamps, requested/approach tiles, actual movement per tick, waiting-on-other golem identities. Re-run exactly this Dome fixture and require progress beyond 817/828, then ultimately 3,537/3,537 with scaffold retrieval.
