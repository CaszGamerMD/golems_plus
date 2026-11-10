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
    @Test void stageReusableWaterForNearTermWorkBeforeClimbing(){
        var steps=java.util.List.of(
                new BuilderConstructionPlan.Step(0,20,0,"minecraft:mangrove_roots[waterlogged=false]",
                        "minecraft:mangrove_roots"),
                new BuilderConstructionPlan.Step(0,20,0,
                        BuilderWaterPass.waterlog("minecraft:mangrove_roots[waterlogged=true]"),
                        BuilderWaterPass.WATER_BUCKET),
                new BuilderConstructionPlan.Step(1,21,0,"minecraft:stone",
                        "minecraft:stone"),
                new BuilderConstructionPlan.Step(2,25,0,"minecraft:water[level=0]",
                        BuilderWaterPass.WATER_BUCKET));
        assertTrue(BuilderSupplyInventory.hasNearTermWaterWork(
                steps,java.util.List.of(0,1,2),20));
        assertTrue(BuilderSupplyInventory.hasNearTermWaterWork(
                steps,java.util.List.of(1),21));
        assertFalse(BuilderSupplyInventory.hasNearTermWaterWork(
                steps,java.util.List.of(0,2),20));
        assertFalse(BuilderSupplyInventory.hasNearTermWaterWork(
                steps,java.util.List.of(3),20),
                "Do not carry a bucket for a water job five levels away");
        assertTrue(BuilderSupplyInventory.hasNearTermWaterWork(
                steps,java.util.List.of(3),24));
        assertFalse(BuilderSupplyInventory.hasNearTermWaterWork(
                steps,java.util.List.of(-1,999),20));
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
