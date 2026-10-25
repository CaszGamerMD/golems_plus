package com.caszgamermd.golemsplus.builder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuilderZonesTest {
    @Test void dividesFourWorkersIntoDistinctTwoByTwoAreas(){
        List<BuilderConstructionPlan.Step> steps=new ArrayList<>();
        for(int x=0;x<16;x++)for(int z=0;z<16;z++)
            steps.add(new BuilderConstructionPlan.Step(x,0,z,"stone","stone"));
        var layout=BuilderZones.footprint(steps);
        assertEquals(2,layout.columns(4));
        var zones=new HashSet<Integer>();
        for(var step:steps)zones.add(layout.zone(step.x(),step.z(),4));
        assertEquals(java.util.Set.of(0,1,2,3),zones);
        assertEquals(layout.zone(4,4,4),layout.zone(4,4,4));
        assertNotEquals(layout.zone(1,1,4),layout.zone(14,14,4));
    }
}
