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
    @Test void localCrewZonesContainOnlyActiveOriginalIndices(){
        var steps=List.of(s(0,0,0),s(20,0,0),s(0,1,0),s(20,1,0));
        var members=BuilderNNStructures.compile(steps).members(0);
        var workers=List.of(new UUID(0,1),new UUID(0,2));
        var footprint=BuilderZones.footprint(members.stream().map(steps::get).toList());
        var plan=BuilderZoneWorkPlan.compile(steps,footprint,workers,false,members);
        var actual=new ArrayList<Integer>();
        for(var w:workers)actual.addAll(plan.steps(w));
        assertEquals(new HashSet<>(members),new HashSet<>(actual));
        assertEquals(members.size(),actual.size());
    }
    @Test void smallestFirstCountsPhysicalCellsNotPassesOrAir(){
        var steps=List.of(s(0,0,0),s(20,0,0),s(0,1,0),s(0,2,0),
            s(20,0,0),new BuilderConstructionPlan.Step(20,1,0,"minecraft:air",null,true));
        var p=BuilderNNStructures.compile(steps,true);
        assertEquals(List.of(1,4,5),p.members(0));
        assertEquals(1,BuilderNNStructures.volume(steps,p.members(0)));
        assertEquals(List.of(1,4,5,0,2,3),p.order());
        assertEquals(3,BuilderNNStructures.nextOffset(p,0,0,Set.of(1,4,5)));
    }
    @Test void preparedPlanRetainsOriginalIndicesAndSafeYOrder(){
        var steps=List.of(s(0,0,0),s(20,0,0),s(0,1,0),s(20,1,0),s(21,1,0));
        var p=BuilderNNStructures.compile(steps,true);
        var roster=List.of(new UUID(0,1),new UUID(0,2));
        var prepared=BuilderNNStructurePipeline.prepare(steps,p.members(1),roster,net.minecraft.core.BlockPos.ZERO);
        var actual=new ArrayList<Integer>();
        for(var id:roster){
            var indices=prepared.tasks().steps(id);actual.addAll(indices);
            for(int i=1;i<indices.size();i++)assertTrue(steps.get(indices.get(i-1)).y()<=steps.get(indices.get(i)).y());
        }
        assertEquals(new HashSet<>(p.members(1)),new HashSet<>(actual));
        assertEquals(p.members(1).size(),actual.size());
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
