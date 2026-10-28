#!/usr/bin/env python3
"""Builder doorway clearance, returning to tower, descending, dismounting."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]
src=root/"src/main/java/com/caszgamermd/golemsplus"
entity=(src/"world/WorksiteContent.java").read_text()
sc=(src/"builder/BuilderScaffolding.java").read_text()
build=(src/"builder/BuilderBuildService.java").read_text()
supplies=(src/"builder/BuilderSupplyInventory.java").read_text()
assert entity.count(".sized(.65f")==5, "All five worker hitboxes should be 0.65 blocks wide"
assert ".sized(.85f" not in entity
assert "private record ReturnRoute(" in sc
assert "private record ExitRoute(" in sc
assert "private record Tower(BlockPos base,int topY)" in sc
assert "table.scaffoldPositions(worker.getUUID())" in sc
assert "level.getBlockState(pos).is(Blocks.SCAFFOLDING)" in sc
assert "returnLanding(level,worker,tower)" in sc
assert "returnLanding(level,worker,new Tower(base,high))" in sc
assert "worker.blockPosition().getY()" in sc
assert "canReachExact(level,worker,p)" in sc
assert "routeToExact(level,worker,landing,1.0)" in sc
assert "return dismountAtBase(level,worker,base);" in sc
assert "planned.groundExit()" in sc
assert "?planned.groundExit():exitGround(level,worker,column)" in sc
assert "exiting.put(worker,new ExitRoute(" in sc
assert "worker.getMoveControl().setWantedPosition(tx," in sc
assert "sessions.put(worker,new ClimbSession(active.column()," in sc
assert "level.getGameTime()+30" in sc
assert "if(!climbing)" in (src/"builder/BuilderGolem.java").read_text()
assert "BuilderScaffolding.descendForTravel(level,worker,table)" in build
assert "BuilderScaffolding.descendForTravel(level,worker,table)" in supplies
print("PASS 0.65 worker hitboxes, physical scaffold return at matching elevation, long descent and safe exit")
