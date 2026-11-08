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
assert "WorkerNavigation.routeToExact(level,worker,hold.destination(),.9)" in space
assert "WorkerNavigation.routeToExact(level,blocker,destination,.9)" in space
assert "WorkerNavigation.canReachExact(level,worker,p)" in space
navigation=(root.parent/"worker/WorkerNavigation.java").read_text()
assert "public static boolean routeToExact(" in navigation
assert "public static boolean canReachExact(" in navigation
exact=navigation[navigation.index("public static boolean routeToExact("):
                 navigation.index("public static boolean routeTo(",navigation.index("public static boolean routeToExact("))]
# Exact work evacuation uses the shared executable-route helper; it
# reaches the same trained A* without duplicating planning in routeToExact.
assert "planExecutableRoute(" in exact
assert "List.of(target)" in exact
planner=navigation[navigation.index("private static WorkerRoutePlanner.Route planExactRoute("):
                   navigation.index("private static WorkerRoutePlanner.Route planExecutableRoute(")]
assert "WorkerRoutePlanner.plan(" in planner
assert "at->trainedTerrainCost(level,at)" in planner
assert "createPath(waypoint,0)" in exact
assert "safePath(level,mob,path,false)" in exact
assert "moveTo(path,speed)" in exact
assert "candidateGoals(" not in exact
assert "private static BlockPos escape(" in space
assert "level.noCollision(worker,shifted)" in space
assert "return false;" in space[space.index("public static boolean ready("):]
assert 'if(BuilderPlacementSpace.yieldTick(level,worker))return;' in build
start=build.index("// Never place into any Builder's body.")
payment=build.index("BuilderSupplyInventory.consume(worker,state.item())",start)
placed=build.index("boolean placed=level.setBlock(pos,toPlace,3);",payment)
guard=build.index("if(!BuilderPlacementSpace.ready(level,worker,table,pos)){",start)
assert start < guard < payment < placed
assert "BlockState toPlace=bubble" in build
assert "Blocks.WATER.defaultBlockState()" in build
assert "if(bubble&&!BuilderPlacementState.matches(" in build
assert "BuilderSupplyInventory.loadRailJig(" in build
assert "BuilderPlacementSpace.ready(level,worker,table,raisedRail)" in build
assert "return;" in build[guard:payment]
assert "yielding.put(blocker,new Yield(target,destination," in space
assert "getGameTime()+60" in space
assert "!level.getBlockState(hold.target()).isAir()" in space
assert "hold.placingWorker()" in space
assert "public static BlockPos approach(" in space
assert "if(++attempted>16)break;" in space
# Reach checks must use the same target-center height as physical placement.
assert "private static final double STANCE_REACH_SQ=12.5;" in space
assert "double dy=stance.getY()-(target.getY()+.5);" in space
assert "||reachSq(p,target)>=STANCE_REACH_SQ" in space
assert "&&reachSq(cached.destination(),target)<STANCE_REACH_SQ" in space
# Every narrow aisle with usable straight ground must be considered, not
# just diagonal tiles. Retain the blocked-entity and real-path safeguards.
for direction in ("{radius,0}", "{-radius,0}", "{0,radius}", "{0,-radius}",
                  "{radius,radius}", "{radius,-radius}",
                  "{-radius,radius}", "{-radius,-radius}"):
    assert direction in space, f"missing safe sidestep direction: {direction}"
assert "!path.canReach()" in navigation
assert "boolean" not in space[space.index("private record Yield"):space.index("private static final Map")] or "placingWorker" in space
assert "workDistance<14" in build
assert "BuilderPlacementSpace.horizontalClear(pos,worker)" in build
assert "BuilderPlacementSpace.approachWithRules(" in build
assert "BuilderConstructionPlanner.safeStand(" in build
table=(root.parent/"world/WorkerStationBlockEntity.java").read_text()
supplies=(root/"BuilderSupplyInventory.java").read_text()
assert "public boolean reassignBuilderStep(UUID requester,UUID stocked,int index)" in table
assert "if(destination.size()>=10)" in table
assert "destination.remove(Integer.valueOf(exchange))" in table
assert "destination.add(index)" in table
assert "if(!reserved)order.add(i)" in table
assert "table.reassignBuilderStep(worker.getUUID(),teammateId,index)" in supplies
assert "carrier.carriedCount(target)>0" in supplies
assert "shifted.intersects(volume(obstructed))" in space
assert 'Waiting for safe standing space' in space
print("PASS: physically vacate prospective placement cell before payment, no forced relocation through obstacles")
