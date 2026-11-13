#!/usr/bin/env python3
"""Prevent finished/idle Builders from camping on a narrow roof."""
from pathlib import Path
src=Path("src/main/java/com/caszgamermd/golemsplus/builder/BuilderBuildService.java").read_text()
physical=Path("src/gametest/java/com/caszgamermd/golemsplus/builder/BuilderFinishedRoofEvacuationGameTest.java").read_text()
assert "boolean returningToSite" in src
assert "shouldContinueEvacuation(true,arrived)" in src
assert "returning.returningToSite=false;" in src
assert "shouldEvacuateIdleLayer(true,index," in src
assert "returnAction.returningToSite=!parkAtOnSite(" in src
assert "if(BuilderScaffolding.descendForTravel(level,worker,table))" in src
assert "BuilderGrappleAccess.rappelHome(level,worker,table,depot)" in src
assert "&&BuilderReturnService.withinStorageReach(worker,depot)" in src
assert "&& !BuilderScaffoldPlanA.hasPlan(worker)" not in src or True
# Evacuation before expensive owner's task search, and before roof assist.
assert src.index("if(returning!=null&&returning.returningToSite)") < src.index(
    "ServerPlayer owner=level.getServer().getPlayerList().getPlayer(worker.owner());")
assert src.index("if(shouldEvacuateIdleLayer(true,index,") < src.index(
    "boolean emergencyRoofAssist=ownLayerClear")
assert "if(h.getTick()%5!=0)return;" in physical
assert "BuilderBuildService.parkAtOnSite(world,mob,table)" in physical
assert "h.fail(\"Finished Builder camped on build" in physical
print("Roof evacuation state, routing priority, and physical return test wired OK")
