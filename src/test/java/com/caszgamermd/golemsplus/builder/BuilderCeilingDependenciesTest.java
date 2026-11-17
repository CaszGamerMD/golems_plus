package com.caszgamermd.golemsplus.builder;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Regression for Matrix Y14 lichen requiring a real Y15 roof support. */
class BuilderCeilingDependenciesTest {
    private static BuilderConstructionPlan.Step step(int x,int y,int z,
            String state,String item){
        return new BuilderConstructionPlan.Step(x,y,z,state,item);
    }
    @Test void upFacingMatrixLichenAdmitsOnlyExactHigherSupport(){
        var lichen="minecraft:glow_lichen[down=false,east=false,north=false,"
                +"south=false,up=true,waterlogged=false,west=false]";
        var steps=List.of(
                step(3,14,1,lichen,"minecraft:glow_lichen"),
                step(4,14,1,lichen.replace("up=true","up=false"),"minecraft:glow_lichen"),
                step(3,15,1,"minecraft:stone","minecraft:stone"),
                step(4,15,2,"minecraft:stone","minecraft:stone"),
                step(4,15,1,"minecraft:stone","minecraft:stone"));
        assertEquals(Map.of(2,0),BuilderCeilingDependencies.compile(steps));
        assertTrue(BuilderCeilingDependencies.ceilingAttachment(lichen));
        assertFalse(BuilderCeilingDependencies.ceilingAttachment(
                lichen.replace("up=true","up=false")));
        assertFalse(BuilderCeilingDependencies.ceilingAttachment("minecraft:stone"));
    }
    @Test void noFutureYOrUnpaidOrFakeSupport(){
        String up="minecraft:glow_lichen[up=true,down=false]";
        assertTrue(BuilderCeilingDependencies.compile(List.of(
                step(0,14,0,up,"minecraft:glow_lichen"),
                step(0,16,0,"minecraft:stone","minecraft:stone"))).isEmpty());
        assertTrue(BuilderCeilingDependencies.compile(List.of(
                step(0,14,0,up,"minecraft:glow_lichen"),
                step(0,15,0,"minecraft:moving_piston",null))).isEmpty());
        assertTrue(BuilderCeilingDependencies.compile(List.of(
                step(0,14,0,up,"minecraft:glow_lichen"),
                step(0,15,0,"minecraft:stone","minecraft:stone"),
                step(0,15,0,"minecraft:water[level=0]","minecraft:water_bucket")))
                .containsKey(1));
    }
    @Test void existingCeilingButtonsRemainSupported(){
        assertTrue(BuilderCeilingDependencies.ceilingAttachment(
                "minecraft:stone_button[face=ceiling,facing=north,powered=false]"));
        assertTrue(BuilderCeilingDependencies.ceilingAttachment(
                "minecraft:lever[face=ceiling,facing=north,powered=false]"));
        assertFalse(BuilderCeilingDependencies.ceilingAttachment(
                "minecraft:lever[face=floor,facing=north,powered=false]"));
    }
}
