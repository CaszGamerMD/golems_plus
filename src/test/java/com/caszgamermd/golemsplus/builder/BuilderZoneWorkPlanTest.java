package com.caszgamermd.golemsplus.builder;

import java.util.HashSet;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuilderZoneWorkPlanTest {
    @Test void entireSchematicIsAssignedExactlyOnceAcrossFourZones(){
        List<BuilderConstructionPlan.Step> steps=new ArrayList<>();
        for(int y=70;y<75;y++)
            for(int x=0;x<12;x++)
                for(int z=0;z<12;z++)
                    steps.add(new BuilderConstructionPlan.Step(x,y,z,
                            "minecraft:stone","minecraft:stone"));
        var crew=List.of(UUID.randomUUID(),UUID.randomUUID(),
                UUID.randomUUID(),UUID.randomUUID());
        var tasks=BuilderZoneWorkPlan.compile(steps,BuilderZones.footprint(steps),crew);
        assertEquals(crew,tasks.roster());
        var seen=new HashSet<Integer>();
        int total=0;
        for(UUID worker:crew){
            var assigned=tasks.steps(worker);
            assertFalse(assigned.isEmpty());
            int lastY=Integer.MIN_VALUE;
            for(int index:assigned){
                assertTrue(seen.add(index),"A build cell cannot belong to two golems");
                assertTrue(steps.get(index).y()>=lastY);
                lastY=steps.get(index).y();
                total++;
            }
        }
        assertEquals(steps.size(),total);
        assertEquals(steps.size(),seen.size());
    }
    @Test void localRowsSnakeInsteadOfCrossingTheZoneRepeatedly(){
        var steps=new ArrayList<BuilderConstructionPlan.Step>();
        // Intentionally backwards and out of layer order.
        for(int y=65;y>=64;y--)
            for(int z=5;z>=0;z--)
                for(int x=1;x>=0;x--)
                    steps.add(new BuilderConstructionPlan.Step(x,y,z,"x","x"));
        var id=UUID.randomUUID();
        var plan=BuilderZoneWorkPlan.compile(
                steps,BuilderZones.footprint(steps),List.of(id));
        int lastY=Integer.MIN_VALUE,lastX=Integer.MIN_VALUE,lastZ=0;
        for(int i:plan.steps(id)){
            var step=steps.get(i);
            assertTrue(step.y()>=lastY);
            if(step.y()==lastY){
                assertTrue(step.x()>=lastX);
                if(step.x()==lastX)
                    assertTrue((step.x()%2==0 ? step.z()>=lastZ :
                            step.z()<=lastZ));
            }
            lastY=step.y();lastX=step.x();lastZ=step.z();
            if(step.y()>lastY)lastX=Integer.MIN_VALUE;
        }
    }
    @Test void oneBuilderKeepsEveryTaskAndRejectsDuplicateCrew(){
        var steps=List.of(
                new BuilderConstructionPlan.Step(1,64,1,"a","a"),
                new BuilderConstructionPlan.Step(2,65,1,"b","b"));
        var id=UUID.randomUUID();
        var layout=BuilderZones.footprint(steps);
        var plan=BuilderZoneWorkPlan.compile(steps,layout,List.of(id));
        assertEquals(List.of(0,1),plan.steps(id));
        assertThrows(IllegalArgumentException.class,()->
                BuilderZoneWorkPlan.compile(steps,layout,List.of(id,id)));
    }
}
