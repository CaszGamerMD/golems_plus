package com.caszgamermd.golemsplus.builder;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuilderNNCeilingOrderTest {
    private static BuilderConstructionPlan.Step step(int y,String encoded,String item){
        return new BuilderConstructionPlan.Step(3,y,1,encoded,item);
    }
    @Test void paidUpLichenNeverStallsOrdinaryFutureLayers(){
        var lower=step(14,"minecraft:glow_lichen[down=false,east=false,north=false,south=false,up=true,waterlogged=false,west=false]",
                       "minecraft:glow_lichen");
        var side=step(14,"minecraft:glow_lichen[down=false,east=false,north=false,south=false,up=false,waterlogged=false,west=true]",
                      "minecraft:glow_lichen");
        var support=step(15,"minecraft:stone","minecraft:stone");
        var water=step(15,"minecraft:water[level=0]","minecraft:water_bucket");
        var upper=step(19,"minecraft:stone","minecraft:stone");
        var steps=new ArrayList<>(List.of(lower,water,support,side,upper));
        BuilderConstructionPlan.sortForBuild(steps,true);
        assertEquals(List.of(side,support,water,upper,lower),steps);
        assertTrue(BuilderConstructionPlan.lateCeilingLichen(lower));
        assertFalse(BuilderConstructionPlan.lateCeilingLichen(side));
    }
    @Test void defaultMainScheduleIsUnchanged(){
        var lower=step(14,"minecraft:glow_lichen[up=true]","minecraft:glow_lichen");
        var upper=step(15,"minecraft:stone","minecraft:stone");
        var steps=new ArrayList<>(List.of(upper,lower));
        BuilderConstructionPlan.sortForBuild(steps,false);
        assertEquals(List.of(lower,upper),steps);
    }
    @Test void ignoredOrUnpaidAttachmentIsNotSilentlyPromoted(){
        var ignored=new BuilderConstructionPlan.Step(0,14,0,
                "minecraft:glow_lichen[up=true]",null,true);
        var unsupported=step(14,"minecraft:glow_lichen[up=true]",null);
        assertFalse(BuilderConstructionPlan.lateCeilingLichen(ignored));
        assertFalse(BuilderConstructionPlan.lateCeilingLichen(unsupported));
    }
}
