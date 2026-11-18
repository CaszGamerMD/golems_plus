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
    @Test void longSuccessfulWorldPlacementIsStrongPositiveNotFailure(){
        assertEquals(1,BuilderNeuralFrontierPolicy.successfulTraversalReward(0),1e-12);
        assertTrue(BuilderNeuralFrontierPolicy.successfulTraversalReward(5000)>=.70);
        assertTrue(BuilderNeuralFrontierPolicy.successfulTraversalReward(5000)
                <BuilderNeuralFrontierPolicy.successfulTraversalReward(100));
    }
}