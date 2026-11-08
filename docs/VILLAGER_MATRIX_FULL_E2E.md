# VillagerMatrixClean_V2 — full physical Builder regression

## Exact test target

- Original user file: `VillagerMatrixClean_V2.litematic` (November 8, 2026).
- SHA-256: `77fa9731b11b1b666034a9e155c5a56abc173b9f9a974f963f916d7ef7d11816`.
- Size: 7,906 bytes, GZIP-compressed Litematica NBT.
- Negative X/Z signed region: position (30, 0, 48), size (-31, 20, -49).
- Local coordinates: X 0–30, Z 0–48, region Y 0–19; non-air Y 0–17.
- **15,376 non-air block cells**, **15,004 explicit air cells**, total volume 30,380.
- 225 palette entries; 40 block-entity NBT records.

The original file is stored byte-for-byte (not converted or visually reconstructed) as
five Base64 fragments under `src/gametest/resources/golems_plus_matrix/`.
`BuilderVillagerMatrixFullFixture.unpack()` verifies length and SHA-256.
The parser then imports the real file into the Planner at a four-block-air gap,
with four physical Builders, a real replenished OnSite chest, and paid scaffolding.
It never teleports the builders, preplaces schematic cells, or marks blocked
states complete.

### Acceptance

The dedicated physical workflow is `builder-villager-matrix-physical.yml`.
The release build also selects `BuilderVillagerMatrixFullGameTest`.
A true pass requires all **15,376** schematic non-air states correct in world,
with four real Builders alive, grounded, and paid scaffolding recovered.

The previous `BuilderVillagerMatrixStartGameTest` exercises only the old
27-cell floating starter. Its success is NOT a full Matrix pass. The full dome
is a separate manual regression.

### Current blockers (first complete-file import)

Original importer + block resolver: 107 unsupported palette variants.
After enabling inventory-backed block entities, redstone dust, and correctly
counted bed heads: **17 unsupported generated-state variants**, representing
**759 schematic cells**:

| Category | Cells | Requirement |
| --- | ---: | --- |
| Extended piston / sticky piston | 370 | Place piston body, then power it through vanilla redstone |
| Piston heads | 370 | Result of physical piston extension, never a separate inventory placement |
| Flowing water levels 1–6 | 17 | Result of water sources and fluid updates |
| Soul fire | 2 | Ignite on a valid supporting soul block using real tools |

**Do not remove these cells or fake them with direct world-state writes.**
They remain blocking until real physical mechanics can produce them.

### Block-entity caveat

The original file includes 40 block-entity NBT records (e.g. container
inventories). The current production `LitematicFileReader` intentionally
does not restore block-entity NBT. The physical GameTest covers **block-state
geometry**; a separate block-entity content-restore feature/test is needed
before anyone claims a fully functional redstone machine clone.

### Failure reporting

- `[MATRIX FULL IMPORT]`: integrity, counts, construction step count.
- `[MATRIX FULL UNSUPPORTED]`: exact unsupported state palette.
- `[MATRIX FULL UNSUPPORTED CELLS]`: affected cell count and sample local XYZ.
- `[MATRIX FULL PROGRESS]`: true world-state completion and current Y.
- `[MATRIX FULL STALL]`: worker coordinates and access diagnostics.
- `[MATRIX FULL PASS]`: successful 15,376/15,376 physical construction.
