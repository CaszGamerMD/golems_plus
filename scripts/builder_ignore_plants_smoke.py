#!/usr/bin/env python3
"""Schematic plants are optional placement only; physical site clearing stays on."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]
server=root/"src/main/java/com/caszgamermd/golemsplus"
client=root/"src/client/java/com/caszgamermd/golemsplus"
classifier=(server/"builder/BuilderVegetation.java").read_text()
plan=(server/"builder/BuilderConstructionPlan.java").read_text()
service=(server/"builder/BuilderBuildService.java").read_text()
clearing=(server/"builder/BuilderSiteClearance.java").read_text()
station=(server/"world/WorkerStationBlockEntity.java").read_text()
network=(server/"world/StationSettingsNetworking.java").read_text()
gui=(client/"builder/BuilderPlannerScreen.java").read_text()
assert 'block instanceof BushBlock' in classifier
assert 'path.equals("peony")' in classifier
assert 'path.equals("wheat")' in classifier
assert 'path.equals("carrots")' in classifier
assert 'path.equals("tall_grass")' in classifier
# Non-solid plants should include low-height decor and vines without
# accidentally removing full building blocks from material plans.
for path in ("moss_carpet", "pale_moss_carpet", "hanging_roots",
             "glow_lichen", "spore_blossom", "small_dripleaf",
             "big_dripleaf", "big_dripleaf_stem", "sea_pickle",
             "nether_sprouts", "crimson_roots", "warped_roots",
             "dead_bush", "short_dry_grass", "tall_dry_grass",
             "leaf_litter"):
    assert f'path.equals("{path}")' in classifier, path
assert 'path.startsWith("potted_")' in classifier
assert 'path.equals("grass_block")' not in classifier
assert 'path.equals("moss_block")' not in classifier
assert 'public static Result create(LitematicFileReader.Schematic schematic,' in plan
assert "boolean ignored=ignorePlants&&BuilderVegetation.ignoreSchematicState(b.state())" in plan
assert "if(resolved==null&&!ignored)unsupported.add(b.state())" in plan
assert "item,ignored" in plan
assert "if(step.ignorePlant()){" in service
assert "table.completeBuilderStep(worker.getUUID(),index);" in service
assert "table.builderIgnorePlants()" in service
assert "public static String refreshPlantFilter(" in service
assert "table.reconcileBuilderMaterials(materials);" in service
assert "builderIgnorePlants==enabled" in station
assert '"builder_ignore_plants"' in station
assert 'key+"ignore_plants"' in station
assert "requestBuilderShipmentCancellation();" in station
assert "public boolean setBuilderIgnorePlants(" in station
assert "job.ignorePlants()" in station
assert 'case "builder_ignore_plants" -> {' in network
assert "IGNORE CROPS/PLANTS: ON" in gui
assert "IGNORE CROPS/PLANTS: OFF" in gui
# Do not delete construction clearing or ignore obstructing plants during
# the CLEAR phase: vegetation cells are treated like desired air while on.
assert "BuilderSiteClearance.targets(schematic,site)" in service
assert "if(!ignoredPlant&&!step.state().equals(" in clearing
assert "if(existing.isAir()){" in clearing
assert "BuilderClearance.clear(" in clearing
assert "BuilderVegetation.ignoreSchematicState(step.state())" in clearing
assert "BuilderChunkTickets.destination(world,worker,pos);" in clearing
print("PASS: per-job toggle, no-op plant steps, live order reconciliation, and unchanged clearing")
