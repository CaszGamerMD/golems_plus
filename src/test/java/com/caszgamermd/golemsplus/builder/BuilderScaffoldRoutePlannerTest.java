package com.caszgamermd.golemsplus.builder;

import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class BuilderScaffoldRoutePlannerTest {
    private static BlockPos at(int x,int y,int z){
        return new BlockPos(x,y,z);
    }

    @Test void prefersExistingCrewScaffoldOverShorterFreshBridge(){
        BlockPos start=at(0,0,0);
        BlockPos goal=at(3,0,0);
        Set<BlockPos> open=Set.of(
                at(1,0,0),at(2,0,0),goal,
                at(0,0,1),at(1,0,1),at(2,0,1),at(3,0,1));
        Set<BlockPos> existing=Set.of(
                at(0,0,1),at(1,0,1),at(2,0,1),at(3,0,1));

        var route=BuilderScaffoldRoutePlanner.find(start,Set.of(goal),6,
                (from,to)->open.contains(to),existing::contains);

        assertNotNull(route);
        assertEquals(List.of(at(0,0,1),at(1,0,1),at(2,0,1),
                at(3,0,1),goal),route.cells());
        assertEquals(2.20,route.cost(),1.0e-9);
    }

    @Test void usesOnlyCardinalTransitions(){
        BlockPos start=at(0,0,0);
        BlockPos diagonal=at(1,0,1);
        var route=BuilderScaffoldRoutePlanner.find(start,Set.of(diagonal),6,
                (from,to)->to.equals(diagonal),ignored->false);
        assertNull(route);
    }

    @Test void neverExceedsThePhysicalBridgeCellLimit(){
        BlockPos start=at(0,0,0);
        BlockPos goal=at(5,0,0);
        Set<BlockPos> open=Set.of(at(1,0,0),at(2,0,0),
                at(3,0,0),at(4,0,0),goal);

        assertNull(BuilderScaffoldRoutePlanner.find(start,Set.of(goal),4,
                (from,to)->open.contains(to),ignored->false));
        var allowed=BuilderScaffoldRoutePlanner.find(start,Set.of(goal),5,
                (from,to)->open.contains(to),ignored->false);
        assertNotNull(allowed);
        assertEquals(5,allowed.cells().size());
    }

    @Test void workTowersStayWithinFiveBlocksOfTheirBuildFace(){
        assertEquals(5,BuilderScaffoldPlanA.MAX_WORK_TOWER_DISTANCE);
        assertTrue(BuilderScaffoldPlanA.MAX_WORK_TOWER_DISTANCE
                <BuilderScaffoldPlanA.MAX_HORIZONTAL_SCAFFOLD_SPAN+2);
    }

    @Test void rejectsInvalidAndEmptyRequests(){
        assertNull(BuilderScaffoldRoutePlanner.find(
                null,Set.of(at(1,0,0)),6,(a,b)->true,p->false));
        assertNull(BuilderScaffoldRoutePlanner.find(
                at(0,0,0),Set.of(),6,(a,b)->true,p->false));
        assertNull(BuilderScaffoldRoutePlanner.find(
                at(0,0,0),Set.of(at(1,0,0)),0,(a,b)->true,p->false));
    }
}
