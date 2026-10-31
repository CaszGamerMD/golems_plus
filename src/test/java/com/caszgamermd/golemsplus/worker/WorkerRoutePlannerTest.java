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
            // Eight-direction policy permits swept, corner-safe
            // diagonals, but each actual coordinate still advances no
            // more than one horizontal tile per step.
            int dx=Math.abs(old.getX()-next.getX());
            int dz=Math.abs(old.getZ()-next.getZ());
            assertTrue(dx<=1&&dz<=1&&dx+dz>=1);
            if(dx==1&&dz==1){
                assertTrue(floor.contains(at(next.getX(),64,old.getZ())));
                assertTrue(floor.contains(at(old.getX(),64,next.getZ())));
            }
            old=next;
        }
        assertEquals(target,old);
        assertTrue(route.steps().size()<=8);
    }
    @Test void usesFastDiagonalsAcrossPhysicallyClearSlabSizedOpenings(){
        BlockPos from=at(0,64,0),goal=at(3,64,3);
        Set<BlockPos> flat=new HashSet<>();
        for(int x=0;x<=3;x++)for(int z=0;z<=3;z++)
            flat.add(at(x,64,z));
        var plan=find(from,List.of(goal),flat);
        assertNotNull(plan);assertTrue(plan.complete());
        assertEquals(3,plan.steps().size());
        assertEquals(42,plan.cost(),"Three collision-valid diagonals beat six straight moves");
    }
    @Test void neverCornersThroughSolidBlocksEvenWhenDiagonalGoalIsFree(){
        BlockPos from=at(0,64,0),goal=at(1,64,1);
        assertNull(find(from,List.of(goal),Set.of(from,goal)),
                "Both cardinal clearance cells must be walkable for a diagonal");
    }
    @Test void physicallyCertifiedDiagonalMayBypassBlockedTileCenters(){
        BlockPos start=at(0,64,0),goal=at(1,64,1);
        Set<BlockPos> floor=Set.of(start,goal);
        assertNull(find(start,List.of(goal),floor));
        var rejected=WorkerRoutePlanner.plan(start,List.of(goal),
                floor::contains,BlockPos::getY,null,unused->0,
                null,Set.of(),(a,b)->false);
        assertNull(rejected,"An uncertified diagonal stays forbidden");
        var certified=WorkerRoutePlanner.plan(start,List.of(goal),
                floor::contains,BlockPos::getY,null,unused->0,
                null,Set.of(),(a,b)->a.equals(start)&&b.equals(goal));
        assertNotNull(certified);
        assertEquals(List.of(goal),certified.steps());
        assertEquals(14,certified.cost());
    }
    @Test void trainedDoorCostSelectsCollisionValidLongerRoute(){
        BlockPos from=at(0,64,0),goal=at(4,64,0);
        Set<BlockPos> floor=new HashSet<>();
        for(int x=0;x<=4;x++)for(int z=0;z<=1;z++)
            floor.add(at(x,64,z));
        BlockPos door=at(2,64,0);
        var plan=WorkerRoutePlanner.plan(from,List.of(goal),floor::contains,
                BlockPos::getY,BlockPos.ZERO,
                p->p.equals(door)?2.5:0.0);
        assertNotNull(plan);assertTrue(plan.complete());
        assertFalse(plan.steps().contains(door),
                "An open slab-side alternative should beat a time-consuming door");
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
    @Test void climbsLongPhysicalStaircaseBeyondEightLayerSearchWindow(){
        Set<BlockPos> stairs=new HashSet<>();
        for(int step=0;step<=12;step++)stairs.add(at(step,64+step,0));
        var route=find(at(0,64,0),List.of(at(12,76,0)),stairs);
        assertNotNull(route,"Y9+ must not be hidden by an artificial vertical window");
        assertTrue(route.complete());
        assertEquals(at(12,76,0),route.steps().getLast());
        BlockPos previous=at(0,64,0);
        for(BlockPos next:route.steps()){
            assertEquals(1,next.getY()-previous.getY(),
                    "A taller search must still use real one-block climbs");
            previous=next;
        }
    }
    @Test void admittedComponentNeverIncludesDisconnectedGoal(){
        BlockPos start=at(0,64,0),a=at(1,64,0),b=at(2,64,0),gap=at(4,64,0);
        Set<BlockPos> floor=Set.of(start,a,b,gap);
        WorkerRoutePlanner.SearchStats[] stats={null};
        var route=WorkerRoutePlanner.plan(start,List.of(gap),floor::contains,
                BlockPos::getY,null,unused->0,s->stats[0]=s);
        assertNull(route);
        assertNotNull(stats[0]);
        assertEquals(Set.of(start,a,b),stats[0].reachable());
        assertFalse(stats[0].budgetExhausted());
    }
}
