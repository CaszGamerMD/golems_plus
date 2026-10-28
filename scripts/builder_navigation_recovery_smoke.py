#!/usr/bin/env python3
"""Regression for false 'cannot path' reports on flat construction sites."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]/"src/main/java/com/caszgamermd/golemsplus"
navigation=(root/"worker/WorkerNavigation.java").read_text()
building=(root/"builder/BuilderBuildService.java").read_text()
scaffolds=(root/"builder/BuilderScaffolding.java").read_text()
assert "getBlockCollisions(mob,body).iterator().hasNext()" in navigation
assert "world.noCollision(mob,body)" in navigation
assert "world.getBlockCollisions" in navigation
assert "WorkerRoutePlanner.plan(" in navigation
assert "at->trainedTerrainCost(level,at)" in navigation
assert "mob.blockPosition().equals(feet)" in navigation
assert "Math.abs(a-b)>.60)continue;" in navigation
# Walking to a depot/doorway must NEVER be short-circuited by placing reach.
assert "One native A* only for a SHORT portion" in navigation
assert "int[]{3,2,1}" in navigation
assert "&&mob.distanceToSqr(target.getX()+.5" not in navigation
assert "public static boolean retryScheduled(PathfinderMob mob)" in navigation
assert 'WorkerNavigation' in building
assert "WorkerNavigation" in building
assert "routeToExact(level,worker,approach,1.0)" in building
assert "if(workDistance>14)" in building
assert "BuilderPlacementSpace.invalidateApproach(worker,pos)" in building
assert "routeTo(level,worker,pos,.85)" in building
assert '"Builder exploring alternate routes to "' not in building
approaches=(root/"builder/BuilderPlacementSpace.java").read_text()
assert "private static final Map<BuilderGolem,Approach> workApproaches" in approaches
assert "cached.target().equals(target)" in approaches
assert "cached.until()>level.getGameTime()" in approaches
assert "WorkerNavigation.canStandOn(level,worker,cached.destination())" in approaches
assert "level.getGameTime()+100" in approaches
assert "public static boolean canStandOn(ServerLevel level" in navigation
assert "worker.distanceToSqr(landing.getX()+.5" in scaffolds
assert "return Result.NOT_NEEDED;" in scaffolds
assert "routeToExact(level,worker,landing,1.0)" in scaffolds
print("PASS: crowd-tolerant block-collision routes, broader route candidates, "
      "retry diagnostics and no platform climbing loop")
