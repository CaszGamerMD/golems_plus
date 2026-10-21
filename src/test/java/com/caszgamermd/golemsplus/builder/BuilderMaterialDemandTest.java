package com.caszgamermd.golemsplus.builder;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class BuilderMaterialDemandTest {
    @Test void requestsOnlyMissingItemsFromNextTwoHundredFiftySixBlocks(){
        var list=new ArrayList<BuilderConstructionPlan.Step>();
        for(int i=0;i<300;i++)list.add(new BuilderConstructionPlan.Step(
                i,70,0,"minecraft:stone","minecraft:stone"));
        var requested=BuilderMaterialDemand.batch(list,0,Map.of("minecraft:stone",30));
        assertEquals(34,requested.get("minecraft:stone"));
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
}
