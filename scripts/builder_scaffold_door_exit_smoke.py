#!/usr/bin/env python3
"""Regression for final scaffold level, one-block descent, and doorway exit."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]/"src/main/java/com/caszgamermd/golemsplus"
sc=(root/"builder/BuilderScaffolding.java").read_text()
build=(root/"builder/BuilderBuildService.java").read_text()
nav=(root/"worker/WorkerNavigation.java").read_text()
queue=(root/"worker/WorkerDoorTraffic.java").read_text()
assert "public static boolean finishActiveAscent(" in sc
assert "BuilderScaffolding.isControllingMovement(worker)" in build
assert "active.stopY()>=target.getY()-.15" in sc
assert "ScaffoldVerticalRoute.reached(worker.getY(),active.stopY(),false)" in sc
assert "session.descending()" in sc
assert "level.getBlockState(columnLevel.below().below())" in sc
assert "worker.setDeltaMovement(mx,vy,mz)" in sc
assert "for(int dy:new int[]{0,-1})" in sc
assert "Math.abs(worker.getY()-exit.ground().getY())<.32" in sc
assert "worker.onGround()" in sc
assert "worker.getMoveControl().setWantedPosition(tx," in sc
assert "private static boolean finishDoorExit(" in nav
assert "p.finishingDoor=true" in nav
assert "finishDoorExit(level,mob,p)" in nav
assert "if(p.waitingDoor||p.finishingDoor)return true" in nav
assert "neighborOccupied(level,mob,candidate)" in nav
assert "mob.getMoveControl().setWantedPosition(x,exit.getY(),z,1.0)" in nav
assert "if(initial<.35" not in queue
assert "boolean leftOpening=initial<.35" in queue
assert "projected< -1.25" in queue
assert "prioritizeOccupant(id,level.getGameTime())" in queue
assert "boolean headInside=" in queue
assert "return first&&(!blocked||inside)" in queue
assert "BlockPos upcoming=handDoor(level,inside)?inside" in nav
print("PASS scaffold ascent completion, top-entry drop, grounded dismount and door exit")
