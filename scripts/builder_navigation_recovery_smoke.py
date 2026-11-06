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
assert "cached.until()>now" in approaches
assert "WorkerNavigation.canStandOn(level,worker,cached.destination())" in approaches
assert "now+75" in approaches
assert "public static boolean canStandOn(ServerLevel level" in navigation
station=(root/"world/WorkerStationBlockEntity.java").read_text()
commands=(root/"builder/BuilderCommands.java").read_text()
network=(root/"world/StationSettingsNetworking.java").read_text()
flag=(root/"builder/BuilderRallyFlagItem.java").read_text()
assert "builder_rally_point" in station and "builder_rally_order" in station
assert "setBuilderRallyPoint(UUID actor,UUID order,BlockPos point)" in station
assert 'Commands.literal("rally")' not in commands
assert 'case "builder_rally_flag"' in network
assert "builder_rally_flag" in flag
assert "WorkerNavigation.canStandOn(level,probe,target)" in flag
assert "activeOrder()" in flag and "dimension" in flag
assert "Get Rally Flag" in (root.parent.parent.parent.parent.parent/"client/java/com/caszgamermd/golemsplus/world/WorkerStationScreen.java").read_text()
assert "canUseRallyPoint(" in building
assert "builderRallyPoint()" in building
assert "routeToExact(level,worker,rally,1.0)" in building
assert "worker.distanceToSqr(landing.getX()+.5" in scaffolds
assert "return Result.NOT_NEEDED;" in scaffolds
assert "routeToExact(level,worker,landing,1.0)" in scaffolds
print("PASS: crowd-tolerant block-collision routes, broader route candidates, "
      "retry diagnostics and no platform climbing loop")
