#!/usr/bin/env python3
"""Safely accept slab/stair geometry without weakening solid-cube avoidance."""
from pathlib import Path
f=Path(__file__).resolve().parents[1]/"src/main/java/com/caszgamermd/golemsplus/worker/WorkerNavigation.java"
s=f.read_text()
assert "import net.minecraft.world.level.block.SlabBlock;" in s
assert "import net.minecraft.world.level.block.StairBlock;" in s
assert "import net.minecraft.world.level.block.state.properties.SlabType;" in s
assert "state.getBlock() instanceof StairBlock" in s
assert "state.getValue(SlabBlock.TYPE)!=SlabType.DOUBLE" in s
assert "if(partialFloor(state)&&!inside.isEmpty())" in s
assert "if(height>.6001)return Double.NaN;" in s
assert "boolean architecturalStep=partialFloor(" in s
assert "?Math.max(a,b):a+(b-a)*t" in s
assert "if(!standingTile(level,mob,feet))return false;" in s
assert "world.noCollision(mob,body)" in s
assert "||b-a>1.05||a-b>1.50" in s
assert "getCollisionShape(level,feet)" in s
print("PASS partial-height slab/stair paths preserve solid blockers, safe swept clearance and climbing limits")
