# Full Dome Physical GameTest — released scaffold navigation (2026-10-28)

**Outcome: FAILED (full dome incomplete).** Real Minecraft 26.2 GameTest, no teleportation, no simulated movement, four actual Builder Golem entities. 3,537 schematic cells in the original user's Dome; staged from original segments; no concurrent miniature movement arenas.

- [GitHub Actions run 40653541336](https://github.com/CaszGamerMD/golems_plus/actions/runs/40653541336)
- Branch: `test/dome-main-safe-scaffold-262`
- Navigation/scaffolding source: released Golems Plus `main` at commit `60da74d05c75a645c9e43baf62d597c43d214461`. Additional isolated test-setup fixes: schematic path normalization, actual chest-only Planner start, plants/flowers ignore classification, wall sign item mapping. These are not merged into main by this test.
- Physical site clearing: **41,106 / 41,106** cells.
- Physical blueprint matches at failure: **833 / 3,537** blocks.
- Planner contiguous cursor: **831**.
- Stalled / GameTest failed at tick **7,940**; issue: `Waiting for safe standing space near 8287564, -52, 2215170`.
- Schematic file staging succeeded and physical file was 6,488 bytes.

## Worker states at stall

| Worker | Phase / step | Location and key evidence |
|---|---|---|
| Blockson | MOVE_TO_WORK / 907 | Distance 4 from target, `nav=idle`, 142 attempts, 3 tracked scaffolds, staged with mossy cobblestone |
| Level Larry | PLACE / 895 | Distance 1 from target, 3 scaffolds; Planner says `Waiting for safe standing space` |
| Blueprint Bea | MOVE_TO_WORK / 945 | Distance 4 from target, `nav=idle`, 97 attempts, 3 scaffolds, staged with cobblestone |
| Chisel Chip | STAGE_MATERIALS / 876 | Distance 27 from task, 81 door reservation yields, doorway `CROSS q#1`, 86 navigation attempts, no tracked scaffolds |

## Assessment

The newest real scaffold tower ascent/return code is present; the run does not prove the entire dome can be constructed. Remaining bottlenecks are work-stance occupancy / physical clearance on raised rim, A* work approach retry loops, and congested resupply door. The log is evidence of a bottleneck, not proof that safe descent itself failed.

Earlier experimental `879/3,537` run is on a different branch/configuration and should not be used as a controlled performance comparison.

**Next prioritized test:** isolate one builder's exact PLACE target alongside a second builder and planned scaffold at elevated rim, assert collision-safe standing room / yielding; follow with a four-builder dome run on an unchanged fixture and same seed.
