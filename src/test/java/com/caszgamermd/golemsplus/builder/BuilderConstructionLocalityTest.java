package com.caszgamermd.golemsplus.builder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Locality ranking must never override physical frontier safety. */
final class BuilderConstructionLocalityTest {
    @Test void nearestSafeConnectedWorkWinsOverRemoteMultiContactCell(){
        var steps=List.of(
                new BuilderConstructionPlan.Step(3,64,0,"stone","stone"),
                new BuilderConstructionPlan.Step(30,64,0,"stone","stone"),
                new BuilderConstructionPlan.Step(4,64,0,"stone","stone"));
        var pending=new ArrayList<>(List.of(1,2,0));
        BuilderConstructionPlanner.orderConnectedFront(pending,steps,
                Map.of(0,1,1,3,2,1),
                Map.of(0,0,1,0,2,0),new BlockPos(2,64,0));
        assertEquals(List.of(0,2,1),pending,
                "More contacts cannot force a long walk within the safe tier");
    }

    @Test void dangerousClosureStillLosesToSafeFrontierEvenWhenCloser(){
        var steps=List.of(
                new BuilderConstructionPlan.Step(1,64,0,"stone","stone"),
                new BuilderConstructionPlan.Step(20,64,0,"stone","stone"));
        var pending=new ArrayList<>(List.of(0,1));
        BuilderConstructionPlanner.orderConnectedFront(pending,steps,
                Map.of(0,2,1,1),
                Map.of(0,2,1,0),new BlockPos(0,64,0));
        assertEquals(List.of(1,0),pending,
                "Unsafe pocket closure must remain below safe supported work");
    }


    @Test void immediatelyAdjacentBlockBeatsRemoteConnectedRun(){
        var steps=List.of(
                new BuilderConstructionPlan.Step(1,64,0,"stone","stone"),
                new BuilderConstructionPlan.Step(18,64,0,"stone","stone"));
        var pending=new ArrayList<>(List.of(1,0));
        BuilderConstructionPlanner.orderConnectedFront(pending,steps,
                Map.of(0,0,1,3),Map.of(0,0,1,0),
                new BlockPos(0,64,0));
        assertEquals(List.of(0,1),pending,
                "A directly adjacent safe task should beat a distant connected strip");
    }

    @Test void compactLocalPatchBeatsACloserConnectedCellOutsideThePatch(){
        var steps=List.of(
                new BuilderConstructionPlan.Step(3,64,0,"stone","stone"),
                new BuilderConstructionPlan.Step(4,64,0,"stone","stone"));
        var pending=new ArrayList<>(List.of(1,0));
        BuilderConstructionPlanner.orderConnectedFront(pending,steps,
                Map.of(0,0,1,3),Map.of(0,0,1,0),
                new BlockPos(0,64,0));
        assertEquals(List.of(0,1),pending,
                "The same compact patch should be completed before a new remote run");
    }

    @Test void dangerousClosureStillOverridesLocalAdjacency(){
        var steps=List.of(
                new BuilderConstructionPlan.Step(1,64,0,"stone","stone"),
                new BuilderConstructionPlan.Step(20,64,0,"stone","stone"));
        assertTrue(BuilderConstructionPlanner.workSelectionPriority(
                        steps.get(0),new BlockPos(0,64,0),0,2)
                >BuilderConstructionPlanner.workSelectionPriority(
                        steps.get(1),new BlockPos(0,64,0),1,0));
    }

    @Test void localWorkBandsPreferDirectNeighborsThenNearbyPatch(){
        BlockPos anchor=new BlockPos(0,64,0);
        assertEquals(0,BuilderConstructionPlanner.localWorkBand(
                new BuilderConstructionPlan.Step(1,64,0,"stone","stone"),anchor));
        assertEquals(1,BuilderConstructionPlanner.localWorkBand(
                new BuilderConstructionPlan.Step(2,64,0,"stone","stone"),anchor));
        assertEquals(2,BuilderConstructionPlanner.localWorkBand(
                new BuilderConstructionPlan.Step(4,64,0,"stone","stone"),anchor));
    }

    @Test void lookaheadTreatsCurrentPlacementAsNextFrontierContact(){
        BlockPos finishing=new BlockPos(10,64,10);
        assertEquals(1,BuilderConstructionPlanner.upcomingConnection(
                0,new BlockPos(11,64,10),finishing));
        assertEquals(2,BuilderConstructionPlanner.upcomingConnection(
                1,new BlockPos(10,64,9),finishing));
        assertEquals(0,BuilderConstructionPlanner.upcomingConnection(
                0,new BlockPos(11,64,11),finishing));
        assertEquals(0,BuilderConstructionPlanner.upcomingConnection(
                0,new BlockPos(11,65,10),finishing));
        assertEquals(0,BuilderConstructionPlanner.upcomingConnection(
                0,new BlockPos(11,64,10),null));
    }
}
