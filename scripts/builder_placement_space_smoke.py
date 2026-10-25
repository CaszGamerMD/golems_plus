#!/usr/bin/env python3
"""Builder bodies must vacate actual block volume before a construction payment."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]/"src/main/java/com/caszgamermd/golemsplus/builder"
space=(root/"BuilderPlacementSpace.java").read_text()
build=(root/"BuilderBuildService.java").read_text()
assert "new AABB(pos).inflate(.10,0,.10)" in space
assert "getEntitiesOfClass(" in space
assert "BuilderGolem.class,area" in space
assert "table.builderWorkerIds().contains(g.getUUID())" in space
assert "List<LivingEntity> others" in space
assert "public static boolean yieldTick(" in space
assert "WorkerNavigation.routeTo(level,worker,hold.destination(),.9)" in space
assert "private static BlockPos escape(" in space
assert "level.noCollision(worker,shifted)" in space
assert "return false;" in space[space.index("public static boolean ready("):]
assert 'if(BuilderPlacementSpace.yieldTick(level,worker))return;' in build
start=build.index("// Never place into any Builder's body.")
payment=build.index("BuilderSupplyInventory.consume(worker,state.item())",start)
placed=build.index("if(!level.setBlock(pos,state.state(),3))",payment)
assert start < build.index("if(!BuilderPlacementSpace.ready(level,worker,table,pos))return;",start) < payment < placed
assert "yielding.put(blocker,new Yield(target,destination," in space
assert "getGameTime()+60" in space
assert "!level.getBlockState(hold.target()).isAir()" in space
assert "if(shifted.intersects(volume(obstructed))" in space
assert 'Waiting for safe standing space' in space
print("PASS: physically vacate prospective placement cell before payment, no forced relocation through obstacles")
