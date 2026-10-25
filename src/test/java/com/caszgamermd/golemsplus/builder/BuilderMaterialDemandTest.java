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

    @Test void warehouseRequestsWholeBuildAndPrioritizesPickableStock(){
        var steps=new ArrayList<BuilderConstructionPlan.Step>();
        for(int i=0;i<20;i++)
            for(int j=0;j<3;j++)
                steps.add(new BuilderConstructionPlan.Step(i,j*10,0,
                        "minecraft:stone","test:block"+i));
        var manifest=BuilderMaterialDemand.outstanding(steps,0,
                Map.of("test:block1",2),Map.of("test:block19",20));
        assertEquals(16,manifest.size());
        assertEquals(3,manifest.get("test:block19"));
        assertEquals(1,manifest.get("test:block1"));
        assertEquals("test:block19",manifest.keySet().iterator().next());
        assertTrue(manifest.containsKey("test:block0"));
        assertEquals(Map.of(),BuilderMaterialDemand.outstanding(steps,
                steps.size(),Map.of(),Map.of()));
    }

}
