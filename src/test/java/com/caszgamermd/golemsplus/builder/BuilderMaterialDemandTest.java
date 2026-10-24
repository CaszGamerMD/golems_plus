package com.caszgamermd.golemsplus.builder;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BuilderMaterialDemandTest {
    @Test void requestsOutstandingMaterialForThreeLayersWithoutOneStackCap(){
        var list=new ArrayList<BuilderConstructionPlan.Step>();
        for(int i=0;i<300;i++)list.add(new BuilderConstructionPlan.Step(
                i,70,0,"minecraft:stone","minecraft:stone"));
        var requested=BuilderMaterialDemand.batch(list,0,Map.of("minecraft:stone",30));
        assertEquals(270,requested.get("minecraft:stone"));
        assertEquals(Map.of(),BuilderMaterialDemand.batch(list,300,Map.of()));
    }
    @Test void capsTypeCountAndOmitsMaterialsAlreadyOwned(){
        var list=new ArrayList<BuilderConstructionPlan.Step>();
        for(int i=0;i<20;i++)list.add(new BuilderConstructionPlan.Step(i,0,0,
                "example:item"+i,"example:item"+i));
        var requested=BuilderMaterialDemand.batch(list,0,
                Map.of("example:item0",1));
        assertEquals(15,requested.size());
        assertFalse(requested.containsKey("example:item0"));
        assertFalse(requested.containsKey("example:item18"));
    }
    @Test void threeLayerWindowExcludesFutureMaterialsUntilNeeded(){
        var steps=List.of(
                new BuilderConstructionPlan.Step(0,4,0,"a","example:a"),
                new BuilderConstructionPlan.Step(1,5,0,"a","example:a"),
                new BuilderConstructionPlan.Step(2,6,0,"a","example:a"),
                new BuilderConstructionPlan.Step(3,7,0,"b","example:b"));
        assertEquals(Map.of("example:a",3),
                BuilderMaterialDemand.batch(steps,0,Map.of()));
        assertEquals(Map.of("example:b",1),
                BuilderMaterialDemand.batch(steps,3,Map.of()));
    }
    @Test void neverRequestsMaterialsAlreadyStagedAndPreservesWholeOrder(){
        var steps=new ArrayList<BuilderConstructionPlan.Step>();
        for(int n=0;n<130;n++)
            steps.add(new BuilderConstructionPlan.Step(n,60,0,"block","example:brick"));
        assertEquals(Map.of("example:brick",130),
                BuilderMaterialDemand.batch(steps,0,Map.of()));
        assertEquals(Map.of("example:brick",20),
                BuilderMaterialDemand.batch(steps,0,Map.of("example:brick",110)));
        assertEquals(Map.of(),
                BuilderMaterialDemand.batch(steps,0,Map.of("example:brick",130)));
    }

}
