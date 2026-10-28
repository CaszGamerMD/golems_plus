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


## Physical door-congestion fix and controlled replay (Oct 28)

- **Isolated physical control (run [40643642712](https://github.com/CaszGamerMD/golems_plus/actions/runs/40643642712))**: 4/4 real Builders crossed one fence gate in **205 ticks**, and 4/4 crossed one framed oak door in **258 ticks**. Separate physical Entity GameTests, no movement simulations or teleporting.
- Fix: stall-released owner near the door after 45 ticks of actual no-progress (never evict the body inside the threshold); 25-tick queue cooldown; lateral drift tolerance for oak-door egress; exact-route yielding respects courtesy wait; current positions of nearby real workers are *soft A* cost*, rather than blacklisted terrain.
- Production-only PR [#102](https://github.com/CaszGamerMD/golems_plus/pull/102) includes these verified shared navigation changes and crowd tests. It excludes the incomplete Dome path, vegetation, and manual storage changes.
- **Isolated original Dome replay (run [40643793833](https://github.com/CaszGamerMD/golems_plus/actions/runs/40643793833))**: completed clearing 41,106/41,106, physically correct **823/3,537** at tick 7,860. A queued door did yield (`doorYields=1`), but the crew still stalled on a mix of scaffolded high-layer work-position routing and two workers attempting resupply from near a constructed wall. Full Dome completion **NOT confirmed**, so the experiment remains unmerged.
- Previous combined run [40643324837](https://github.com/CaszGamerMD/golems_plus/actions/runs/40643324837) recorded 855 matched blocks but its crowd test rooms overlapped the Dome footprint; **not valid as a clean dome performance comparison**.
- The isolated full-Dome run still exposes missing *physical scaffold/walkway work access* and access to OnSite chest after surrounding cells are completed. Those are separate follow-ups and should not weaken the verified worker door fix.
