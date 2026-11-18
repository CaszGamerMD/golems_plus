package com.caszgamermd.golemsplus.builder;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuilderNNDetourTrainingTest {
    @Test void physicalNewCellsExtendARealDetourEvenWhileMovingAwayFromGoal(){
        assertTrue(BuilderNNDetourTraining.mayContinue(200,0,190,8,true));
        assertTrue(BuilderNNDetourTraining.mayContinue(1300,0,1290,30,true));
        assertFalse(BuilderNNDetourTraining.mayContinue(200,0,190,8,false),
                "a fabricated route or wandering worker never gets credited");
        assertFalse(BuilderNNDetourTraining.mayContinue(200,0,80,8,true),
                "a worker circling the same cells is not progressing");
        assertFalse(BuilderNNDetourTraining.mayContinue(1800,0,1799,30,true),
                "even novel cells cannot excuse infinite construction detours");
        assertFalse(BuilderNNDetourTraining.mayContinue(50,0,49,1,true));
    }
    @Test void highAndPastTargetLandingsAreLegalForCertifiedMultilegSearch(){
        BlockPos from=new BlockPos(0,10,0),target=new BlockPos(4,10,4);
        assertTrue(BuilderMultiLegTravel.usefulPartial(40,65,from,target,
                new BlockPos(2,13,2),true),"climb above Y to access the roof");
        assertTrue(BuilderMultiLegTravel.usefulPartial(40,65,from,target,
                new BlockPos(6,10,5),true),"walking past X/Z is sometimes needed");
        assertFalse(BuilderMultiLegTravel.usefulPartial(40,65,from,target,
                new BlockPos(2,13,2),false),"opt-in only");
        assertFalse(BuilderMultiLegTravel.usefulPartial(40,250,from,target,
                new BlockPos(6,12,6),true),"detour is still bounded");
        assertFalse(BuilderMultiLegTravel.usefulPartial(40,65,from,target,
                new BlockPos(2,10,2),true),"no gratuitous indefinite wandering");
        assertTrue(BuilderMultiLegTravel.usefulPartial(40,35,from,target,
                new BlockPos(2,10,2),false),"ordinary forward progress stays legal");
    }
    @Test void multiLegRoofRouteCanProbeNearbyElevatedAndDiagonalLaunchSites(){
        var from=new BlockPos(10,20,10);
        var roof=BuilderMultiLegTravel.nearbyLaunchOffsets(from,true);
        assertTrue(roof.contains(from.offset(1,1,0)),"launch from higher roof step");
        assertTrue(roof.contains(from.offset(-1,-1,0)),"launch from lower roof step");
        assertTrue(roof.contains(from.offset(1,1,1)),"launch around roof corner");
        assertFalse(roof.contains(from.offset(6,4,0)),
                "never pretend an unreachable giant jump is nearby");
        var ordinary=BuilderMultiLegTravel.nearbyLaunchOffsets(from,false);
        assertFalse(ordinary.contains(from.offset(1,1,0)),
                "traditional build modes retain old grounded takeoff candidates");
    }
    @Test void slopeFallbackNeverDisplacesCompletedPhysicalRoute(){
        assertFalse(BuilderMultiLegTravel.preferSlopeFallback(
                true,false,true,100,0),
                "A traditional completed route outranks an experimental roof shortcut");
        assertTrue(BuilderMultiLegTravel.preferSlopeFallback(
                false,true,false,Double.POSITIVE_INFINITY,20),
                "No traditional path: try physically checked slope candidate");
        assertTrue(BuilderMultiLegTravel.preferSlopeFallback(
                false,false,true,120,80),
                "A complete slope route can supersede an incomplete route");
        assertFalse(BuilderMultiLegTravel.preferSlopeFallback(
                false,false,false,120,116),
                "Reject partial changes with no substantial progress");
        assertTrue(BuilderMultiLegTravel.preferSlopeFallback(
                false,false,false,120,105));
    }
    @Test void stoppedPhysicalFlightMustBeRejectedBeforeWholeWaypointTimesOut(){
        assertFalse(BuilderMultiLegTravel.staleFlightLeg(true,200),
                "a genuinely moving flight retains its certified destination");
        assertFalse(BuilderMultiLegTravel.staleFlightLeg(false,24),
                "brief landing transition is not a failed route");
        assertTrue(BuilderMultiLegTravel.staleFlightLeg(false,25),
                "a non-moving flight must be replanned rather than wasting 400 ticks");
    }
    @Test void longSuccessfulWorldPlacementIsStrongPositiveNotFailure(){
        assertEquals(1,BuilderNeuralFrontierPolicy.successfulTraversalReward(0),1e-12);
        assertTrue(BuilderNeuralFrontierPolicy.successfulTraversalReward(5000)>=.70);
        assertTrue(BuilderNeuralFrontierPolicy.successfulTraversalReward(5000)
                <BuilderNeuralFrontierPolicy.successfulTraversalReward(100));
    }
}