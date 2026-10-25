#!/usr/bin/env python3
"""Regression: no damage to already-built connected blocks or missing-support cells."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]/"src/main/java/com/caszgamermd/golemsplus"
matcher=(root/"builder/BuilderPlacementState.java").read_text()
build=(root/"builder/BuilderBuildService.java").read_text()
clear=(root/"builder/BuilderSiteClearance.java").read_text()
station=(root/"world/WorkerStationBlockEntity.java").read_text()

# Auto-generated connections and stair corner shapes must not be mistaken
# for a different item or a block requiring removal.
assert "public static boolean matches(BlockState actual,BlockState schematic)" in matcher
assert "if(actual.getBlock()!=schematic.getBlock())return false;" in matcher
assert "instanceof WallBlock" in matcher
assert "instanceof FenceBlock" in matcher
assert "instanceof IronBarsBlock" in matcher
assert "instanceof StairBlock" in matcher
assert "StairBlock.FACING" in matcher
assert "StairBlock.HALF" in matcher
assert "BuilderPlacementState.matches(existing,state.state())" in build
assert "BuilderPlacementState.matches(existing,expected)" in clear

# Do not destroy the wrong existing block before confirming the desired
# replacement is supported. A blocked target must defer its claim.
i=build.index("if(BuilderPlacementState.matches(existing,state.state()))")
support=build.index("if(!state.state().canSurvive(level,pos))",i)
clearance=build.index("if(!existing.isAir())",i)
assert i < support < clearance
assert "table.waitForBuilderSupport(worker.getUUID(),index," in build
assert "Waiting for support:" in build
assert "other Builders continuing this layer" in build
assert "public void waitForBuilderSupport(UUID worker,int step,long tick)" in station
assert "builderMissingRetries.put(step,tick+40)" in station
assert "builderClaims.remove(worker);" in station[station.index("public void waitForBuilderSupport"):station.index("public UUID builderWorkerId()")]
assert "BuilderClearance.clear(level,worker,table,owner,pos,existing);" in build
print("PASS connected block preservation, support checked before destruction, 40-tick claim defer")
