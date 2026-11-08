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
    @Test void generatedFlowNeverCountsAsAPlaceableSource(){
        assertFalse(BuilderWaterPass.isNaturalFlowingWater("minecraft:water[level=0]"));
        assertTrue(BuilderWaterPass.isNaturalFlowingWater("minecraft:water[level=1]"));
        assertTrue(BuilderWaterPass.isNaturalFlowingWater("minecraft:water[level=6]"));
        assertTrue(BuilderWaterPass.isNaturalFlowingWater("minecraft:water[level=8]"));
        assertFalse(BuilderWaterPass.isNaturalFlowingWater("minecraft:lava[level=5]"));
        assertFalse(BuilderWaterPass.isNaturalFlowingWater("minecraft:soul_fire"));
        assertFalse(BuilderWaterPass.isNaturalFlowingWater("minecraft:water"));
        assertEquals(0,BuilderWaterPass.layerOrder("minecraft:stone"));
        assertEquals(1,BuilderWaterPass.layerOrder("minecraft:water[level=0]"));
        assertEquals(2,BuilderWaterPass.layerOrder("minecraft:water[level=6]"));
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
