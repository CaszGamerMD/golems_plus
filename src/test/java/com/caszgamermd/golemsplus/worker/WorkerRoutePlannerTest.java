package com.caszgamermd.golemsplus.worker;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class WorkerRoutePlannerTest {
    private static BlockPos at(int x,int y,int z){
        return new BlockPos(x,y,z);
    }
    private static WorkerRoutePlanner.Route find(BlockPos start,
            List<BlockPos> goals,Set<BlockPos> allowed){
        return WorkerRoutePlanner.plan(start,goals,allowed::contains,
                BlockPos::getY,BlockPos.ZERO);
    }
    @Test void selectsActualShortestRouteNotNearestBlockedGoal(){
        BlockPos from=at(0,64,0),blocked=at(3,64,0),open=at(0,64,5);
        Set<BlockPos> floor=new HashSet<>();
        floor.add(from);floor.add(blocked);
        for(int z=1;z<=5;z++)floor.add(at(0,64,z));
        var route=find(from,List.of(blocked,open),floor);
        assertNotNull(route);
        assertTrue(route.complete());
        assertEquals(open,route.steps().getLast(),
                "The nearest visually adjacent workstation isn't necessarily reachable");
        assertEquals(5,route.steps().size());
    }
    @Test void ranksRouteLengthsRatherThanInputOrder(){
        BlockPos from=at(0,64,0),a=at(0,64,4),b=at(6,64,0);
        Set<BlockPos> floor=new HashSet<>();
        for(int x=0;x<=6;x++)floor.add(at(x,64,0));
        for(int z=0;z<=4;z++)floor.add(at(0,64,z));
        var route=find(from,List.of(b,a),floor);
        assertNotNull(route);assertEquals(a,route.steps().getLast());
        assertEquals(40,route.cost());
    }
    @Test void neverCrossesSolidWallOrTeleportsDiagonally(){
        BlockPos from=at(0,64,0),target=at(4,64,0);
        Set<BlockPos> floor=new HashSet<>();
        for(int x=0;x<=4;x++)for(int z=0;z<=2;z++)
            if(x!=2||z==2)floor.add(at(x,64,z));
        var route=find(from,List.of(target),floor);
        assertNotNull(route);assertTrue(route.complete());
        BlockPos old=from;
        for(BlockPos next:route.steps()){
            assertTrue(floor.contains(next));
            assertEquals(1,Math.abs(old.getX()-next.getX())
                    +Math.abs(old.getZ()-next.getZ()));
            old=next;
        }
        assertEquals(target,old);
        assertEquals(8,route.steps().size());
    }
    @Test void unreachableNearTargetMustNotBeCalledProgress(){
        BlockPos start=at(0,64,0),target=at(3,64,0);
        assertNull(find(start,List.of(target),Set.of(start,target)));
    }
    @Test void longRoutesUseOnlyCheckedPhysicalLegs(){
        BlockPos start=at(0,64,0),target=at(85,64,0);
        Set<BlockPos> dry=new HashSet<>();
        for(int x=0;x<=85;x++)dry.add(at(x,64,0));
        BlockPos current=start;
        int journeys=0;
        while(!current.equals(target)&&journeys++<6){
            var route=find(current,List.of(target),dry);
            assertNotNull(route);
            assertFalse(route.steps().isEmpty());
            for(BlockPos block:route.steps())assertTrue(dry.contains(block));
            BlockPos finish=route.steps().getLast();
            assertTrue(finish.getX()>current.getX());
            current=finish;
        }
        assertEquals(target,current);
    }
    @Test void oneBlockStepIsAllowedButTwoBlockJumpForbidden(){
        BlockPos start=at(0,64,0),end=at(3,64,0);
        Set<BlockPos> dry=Set.of(start,at(1,65,0),at(2,64,0),end);
        assertNotNull(find(start,List.of(end),dry));
        assertNull(find(start,List.of(end),Set.of(
                start,at(1,66,0),at(2,64,0),end)));
    }
    @Test void flatBuildingWithSeveralValidSidesFindsWalkableApproach(){
        // Future schematic block may not itself be a standable cell.
        // A Builder must still find an approach ON the existing ground,
        // without demanding the center of the future block be walkable.
        BlockPos start=at(1,81,0);
        Set<BlockPos> existingFloor=new HashSet<>();
        for(int x=-6;x<=6;x++)for(int z=-6;z<=6;z++)
            existingFloor.add(at(x,81,z));
        BlockPos future=at(0,81,4);
        existingFloor.remove(future);
        var route=find(start,List.of(
                future,at(1,81,4),at(-1,81,4),
                at(0,81,3),at(0,81,5)),existingFloor);
        assertNotNull(route);
        assertTrue(route.complete());
        assertTrue(existingFloor.contains(route.steps().getLast()));
        assertTrue(route.steps().size()<=7);
    }
    @Test void fiveOrSixBlocksOutRequiresWalkingCloserToPlace(){
        // A builder standing six blocks away must reach a valid exact
        // work tile; the formerly accepted two-block-short endpoint is
        // not proof that it can interact with the next assigned step.
        BlockPos start=at(0,81,0),future=at(6,81,0);
        Set<BlockPos> ground=new HashSet<>();
        for(int x=0;x<=6;x++)for(int z=-3;z<=3;z++)
            ground.add(at(x,81,z));
        ground.remove(future);
        var approach=at(5,81,0);
        assertTrue(start.distSqr(future)>14);
        assertTrue(approach.distSqr(future)<=9);
        var route=find(start,List.of(approach),ground);
        assertNotNull(route);
        assertTrue(route.complete());
        assertEquals(approach,route.steps().getLast(),
                "Walking must continue until within work range");
    }
    @Test void existingPlatformAllowsAOneBlockStepDownAndBackUp(){
        Set<BlockPos> surface=Set.of(
                at(0,82,0),at(1,81,0),at(2,81,0),at(3,82,0));
        var result=find(at(0,82,0),List.of(at(3,82,0)),surface);
        assertNotNull(result);
        assertTrue(result.complete());
        assertEquals(at(3,82,0),result.steps().getLast());
    }

    @Test void reroutesAroundNewObstructionWithoutCrossingOccupiedCell(){
        BlockPos start=at(0,64,1),end=at(5,64,1);
        Set<BlockPos> floor=new HashSet<>();
        for(int x=0;x<=5;x++)for(int z=0;z<=2;z++)
            floor.add(at(x,64,z));
        var straight=find(start,List.of(end),floor);
        assertNotNull(straight);
        assertTrue(straight.complete());
        assertEquals(5,straight.steps().size());

        BlockPos newObstacle=at(2,64,1);
        floor.remove(newObstacle); // A player places a wall mid-job.
        var recovered=find(start,List.of(end),floor);
        assertNotNull(recovered);
        assertTrue(recovered.complete());
        assertEquals(end,recovered.steps().getLast());
        assertEquals(7,recovered.steps().size());
        assertFalse(recovered.steps().contains(newObstacle));
        for(BlockPos step:recovered.steps())assertTrue(floor.contains(step));
    }
    @Test void reroutesAroundTemporarilyBlacklistedWaypoint(){
        BlockPos start=at(0,64,1),end=at(5,64,1);
        BlockPos jammed=at(2,64,1);
        Set<BlockPos> floor=new HashSet<>();
        for(int x=0;x<=5;x++)for(int z=0;z<=2;z++)
            floor.add(at(x,64,z));
        var route=WorkerRoutePlanner.plan(start,List.of(end),floor::contains,
                BlockPos::getY,jammed);
        assertNotNull(route);
        assertTrue(route.complete());
        assertFalse(route.steps().contains(jammed));
        assertEquals(end,route.steps().getLast());
    }
    @Test void prioritizesReachablePathCostOverEuclideanGoalProximity(){
        BlockPos start=at(0,64,0),far=at(0,64,5),near=at(3,64,0);
        Set<BlockPos> available=new HashSet<>();
        for(int z=0;z<=5;z++)available.add(at(0,64,z));
        for(int x=1;x<=3;x++)available.add(at(x,64,5));
        for(int z=0;z<=4;z++)available.add(at(3,64,z));
        var route=find(start,List.of(near,far),available);
        assertNotNull(route);
        assertTrue(route.complete());
        assertEquals(far,route.steps().getLast());
        assertEquals(50,route.cost());
    }
    @Test void physicallyDisconnectedRouteNeverReportsArrival(){
        BlockPos start=at(0,64,0),goal=at(5,64,0);
        Set<BlockPos> floor=new HashSet<>();
        for(int x=0;x<=2;x++)floor.add(at(x,64,0));
        for(int x=4;x<=5;x++)floor.add(at(x,64,0));
        assertNull(find(start,List.of(goal),floor));
    }
}
