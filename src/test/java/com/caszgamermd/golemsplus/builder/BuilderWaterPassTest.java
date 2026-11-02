package com.caszgamermd.golemsplus.builder;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuilderWaterPassTest {
    @Test void convertsAnyWaterloggedPropertyOrderIntoDryBlockThenWater(){
        String wet="minecraft:oak_stairs[facing=west,waterlogged=true,half=top]";
        assertTrue(BuilderWaterPass.isWaterlogged(wet));
        assertEquals("minecraft:oak_stairs[facing=west,waterlogged=false,half=top]",
                BuilderWaterPass.dryState(wet));
        assertEquals(wet,BuilderWaterPass.originalState(BuilderWaterPass.waterlog(wet)));
        assertTrue(BuilderWaterPass.isWaterStep(BuilderWaterPass.waterlog(wet)));
        assertFalse(BuilderWaterPass.isWaterStep(BuilderWaterPass.dryState(wet)));
        assertFalse(BuilderWaterPass.samePhase(BuilderWaterPass.dryState(wet),
                BuilderWaterPass.waterlog(wet)));
    }
    @Test void sourceWaterIsFinishingPhaseButUnrelatedBlocksAreNot(){
        assertTrue(BuilderWaterPass.isWaterStep("minecraft:water[level=0]"));
        assertTrue(BuilderWaterPass.isWaterStep("minecraft:water"));
        assertTrue(BuilderWaterPass.isWaterStep("minecraft:bubble_column[drag=false]"));
        assertTrue(BuilderWaterPass.isBubbleColumn("minecraft:bubble_column[drag=true]"));
        assertFalse(BuilderWaterPass.isWaterStep("minecraft:lava[level=0]"));
        assertFalse(BuilderWaterPass.isWaterStep("minecraft:water_cauldron[level=3]"));
        assertFalse(BuilderWaterPass.isWaterlogged("minecraft:stone"));
        assertFalse(BuilderWaterPass.isWaterlogged(
                "minecraft:oak_stairs[waterlogged=false]"));
    }
}
