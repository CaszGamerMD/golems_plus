package com.caszgamermd.golemsplus.builder;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BuilderNNStructuresTest {
    private static BuilderConstructionPlan.Step s(int x,int y,int z){
        return new BuilderConstructionPlan.Step(x,y,z,"minecraft:stone","minecraft:stone");
    }
    @Test void completesOneStructureBeforeStartingNext(){
        var p=BuilderNNStructures.compile(List.of(s(0,0,0),s(20,0,0),s(0,1,0),s(20,1,0)));
        assertEquals(List.of(List.of(0,2),List.of(1,3)),p.groups());
        assertEquals(List.of(0,2,1,3),p.order());
        assertEquals(1,BuilderNNStructures.nextOffset(p,0,1,Set.of()));
        assertEquals(2,BuilderNNStructures.nextOffset(p,1,1,Set.of(2)));
        assertEquals(3,BuilderNNStructures.nextOffset(p,2,3,Set.of()));
        assertEquals(4,BuilderNNStructures.nextOffset(p,3,4,Set.of()));
    }
    @Test void rotationAndNearbyDetailsPreserveGrouping(){
        var a=BuilderNNStructures.compile(List.of(s(0,0,0),s(20,0,0),s(2,2,2),s(20,2,0)));
        var b=BuilderNNStructures.compile(List.of(s(0,0,0),s(0,0,20),s(-2,2,2),s(0,2,20)));
        assertEquals(a.groups(),b.groups());assertEquals(2,a.groups().size());
    }
    @Test void companionAndSameCellPassNeverSplit(){
        var p=BuilderNNStructures.compile(List.of(
            new BuilderConstructionPlan.Step(0,0,0,"minecraft:stone","minecraft:stone",false,
                new BuilderConstructionPlan.Companion(1,0,0,"minecraft:stone")),
            s(3,0,0),s(0,0,0),s(20,0,0)));
        assertEquals(List.of(List.of(0,1,2),List.of(3)),p.groups());
    }
    @Test void connectedGroundStaysOneAndEveryIndexIsRetained(){
        var steps=new ArrayList<BuilderConstructionPlan.Step>();
        for(int x=0;x<=20;x++)steps.add(s(x,0,0));
        steps.add(s(0,1,0));steps.add(s(20,1,0));
        var p=BuilderNNStructures.compile(steps);
        assertEquals(1,p.groups().size());assertEquals(steps.size(),new HashSet<>(p.order()).size());
        assertTrue(BuilderNNStructures.compile(List.of()).groups().isEmpty());
    }
}
