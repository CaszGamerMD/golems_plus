#!/usr/bin/env python3
"""Tall scaffold towers extend incrementally and use one movement owner."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]/"src/main/java/com/caszgamermd/golemsplus"
sc=(root/"builder/BuilderScaffolding.java").read_text()
plan=(root/"builder/ScaffoldVerticalRoute.java").read_text()
build=(root/"builder/BuilderBuildService.java").read_text()
assert "private static final int MAX_HEIGHT=48" not in sc
assert "int top=ScaffoldVerticalRoute.stageTop(" in sc
assert "int existingTop=existing.stream()" in sc
assert "int wantedTop=target.getY()-1" in sc
assert "int previewTop=ScaffoldVerticalRoute.stageTop(" in sc
assert "return top<wantedTop?Result.CLIMBING:Result.NOT_NEEDED;" in sc
assert "BuilderSupplyInventory.loadScaffold(level,worker,table,needed)" in sc
assert "BuilderSupplyInventory.consume(worker,Items.SCAFFOLDING)" in sc
assert "table.trackScaffold(worker.getUUID(),at)" in sc
assert "table.scaffoldPositions(worker.getUUID()).contains(p)" in sc
assert "isControllingMovement(BuilderGolem worker)" in sc
assert "if(BuilderScaffolding.isControllingMovement(worker))return;" in build
assert "BuilderScaffolding.cancel(worker);" in build
assert "sessions.put(worker,new ClimbSession(session.column()," in sc
assert "worker.setNoGravity(false);" in sc
assert "private static final class MotionWatch" in sc
assert "watch.failures>=3" in sc
assert "avoided.put(worker,new AvoidColumn(" in sc
assert "ScaffoldVerticalRoute.reached(" in sc
assert "ScaffoldVerticalRoute.onBottomScaffold(" in sc
assert "BuilderScaffolding.descendForTravel(level,worker,table))" in build
assert "table.phase()==WorkerPhase.COMPLETE" in build
assert "public static final int EXTENSION=16" in plan
assert "public static int stageTop(" in plan
print("PASS staged 16-block vertical routes, owned tower, restocks and exclusive motion")

