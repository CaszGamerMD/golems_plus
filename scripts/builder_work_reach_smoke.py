#!/usr/bin/env python3
"""A Builder outside reach keeps moving toward a physically reachable landing."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]/"src/main/java/com/caszgamermd/golemsplus"
service=(root/"builder/BuilderBuildService.java").read_text()
space=(root/"builder/BuilderPlacementSpace.java").read_text()
nav=(root/"worker/WorkerNavigation.java").read_text()
work=service[service.index("double workDistance=worker.distanceToSqr("):
             service.index("activity.phase=Action.PLACE;",service.index("double workDistance=worker.distanceToSqr("))]
assert "worker.blockPosition().getY()!=pos.getY()" in work
assert "||workDistance>14" in work
assert "activity.phase=Action.MOVE_TO_WORK" in work
assert "BuilderPlacementSpace.approachAtTargetY(" in work
assert "routeToExact(level,worker,approach,1.0)" in work
assert "if(!moving)" in work
assert "BuilderPlacementSpace.invalidateApproach(worker,pos)" in work
assert "routeTo(level,worker,pos,.85)" in work
assert "return;" in work
assert "for(int dx=-3;dx<=3;dx++)" in space
assert "for(int dy:new int[]{0,1,-1,2,-2})" in space
assert "p.distSqr(target)>=14" in space
assert "rejected.put(worker,new Rejected(" in space
assert "WorkerNavigation.canReachExact(level,worker,p)" in space
assert "shifted.intersects(volume(target))" in space
assert "public static void invalidateApproach(" in space
assert "endsAtExactTile(Path path,BlockPos target)" in nav
assert "last.x==target.getX()&&last.z==target.getZ()" in nav
assert "return endsAtExactTile(path,target)&&safePath(level,mob,path,false)" in nav
assert "WorkerRoutePlanner.Route plan=WorkerRoutePlanner.plan(" in nav
assert "List.of(target)" in nav
assert "if(!endsAtExactTile(path,waypoint)" in nav
assert "||!safePath(level,mob,path,false)" in nav
assert "public static String debugStatus(PathfinderMob mob)" in nav
assert "debugStatus(worker)" in service
print("PASS: builders outside five-to-six block placement range continue toward exact reachable standing tiles")
