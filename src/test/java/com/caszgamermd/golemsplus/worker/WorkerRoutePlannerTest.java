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
}
