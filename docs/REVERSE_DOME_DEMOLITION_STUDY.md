# Reverse Dome demolition / movement training experiment

Use the exact 6,488-byte, SHA-256-verified original 3,537-block Dome.litematic to prebuild the site. This is explicitly a **test fixture**, not a production block-placement shortcut.

One real Builder Golem, not four simulated agents:
- 3-block line-of-sight reach, one physical block removed per deliberate 8-tick mining action
- trained A* with 1-block ascents and **up to 2-block descents** enabled **only** for the test golem
- real carried Grapple Hook for higher obstacles, checked anchors, physical rope motion and recovery
- its existing nine-slot container: eight material slots, one tool slot; maximum **192 total blocks** regardless of stack types
- can only empty its pouch while physically beside the collection chest; test-owned depot ledger stores unloaded quantities without limited 27-slot inventory blocking the long experiment
- every route selection, break, unload, move distance, vertical movement, and grapple event is recorded in the downloadable CSV
- no teleportation, creative flight, setPos after spawn, work-layer gate from the separate construction planner, or saved hardcoded routes
- **the prebuilt fixture** uses setBlock; the actual golem removes blocks with destroyBlock, physically reaches each block and receives one collectible item per removed block

The demo is intentionally independent of the normal four-Builder construction job. It studies physical accessibility from the reverse task. A complete run requires 3,537 manually removed blocks and 3,537 physically collected/deposited items. A partial run is a useful failure with per-block trace, **not** proof of learning or autonomous complete construction. Actual reusable navigation policy changes are kept isolated until confirmed by physical GameTests.

Run: `gradle runGameTest` on `test/dome-reverse-demolition-262`.
CI: `.github/workflows/dome-reverse-demolition-physical.yml`.
