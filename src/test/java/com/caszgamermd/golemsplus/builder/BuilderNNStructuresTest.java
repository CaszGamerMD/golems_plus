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
    @Test void deferredPhysicsStayInGroupAndFollowAllPhysicalWork(){
        var flow=new BuilderConstructionPlan.Step(0,0,0,
            BuilderDeferredState.flowStep("minecraft:water[level=1]"),null);
        var steps=List.of(flow,s(20,0,0),s(0,1,0),s(0,2,0),s(20,1,0));
        var p=BuilderNNStructures.compile(steps,true);
        assertEquals(List.of(1,4,2,3,0),p.order());
        assertEquals(p.group(0),p.group(2));
        assertEquals(4,BuilderNNStructures.nextOffset(p,0,0,Set.of(1,4,2,3)));
    }
    @Test void elevatedPlatformsAttachOnlyToOverlappingLargerParent(){
        var steps=new ArrayList<BuilderConstructionPlan.Step>();
        for(int x=0;x<10;x++)for(int z=0;z<10;z++)steps.add(s(x,0,z));
        int platform=steps.size();
        for(int x=2;x<5;x++)for(int z=2;z<5;z++)steps.add(s(x,30,z));
        int independent=steps.size();
        for(int x=30;x<33;x++)for(int z=0;z<3;z++)steps.add(s(x,30,z));
        var p=BuilderNNStructures.compile(steps,true);
        assertEquals(2,p.groups().size());
        assertEquals(p.group(0),p.group(platform));
        assertNotEquals(p.group(0),p.group(independent));
        assertEquals(9,BuilderNNStructures.volume(steps,p.members(0)));
        assertEquals(109,BuilderNNStructures.volume(steps,p.members(1)));
    }
    @Test void manualChecksNeverBlockNextPhysicalProject(){
        var flow=new BuilderConstructionPlan.Step(0,0,0,
            BuilderDeferredState.flowStep("minecraft:water[level=1]"),null);
        var unsupported=new BuilderConstructionPlan.Step(0,1,0,"minecraft:moving_piston",null);
        var steps=List.of(flow,unsupported,s(20,0,0),s(20,1,0),s(20,2,0),s(0,2,0));
        var p=BuilderNNStructures.compile(steps,true);
        assertEquals(List.of(5,2,3,4,0,1),p.order());
        assertTrue(BuilderNNStructures.manualTail(flow));
        assertTrue(BuilderNNStructures.manualTail(unsupported));
        assertFalse(BuilderNNStructures.manualTail(steps.get(5)));
    }
    @Test void exhaustedProjectCanYieldWithoutLosingItsUnfinishedIndices(){
        var steps=List.of(s(0,0,0),s(20,0,0),s(40,0,0),
            s(0,1,0),s(20,1,0),s(40,1,0));
        var plan=BuilderNNStructures.compile(steps,true);
        assertEquals(List.of(0,3,1,4,2,5),plan.order());
        var finished=new HashSet<Integer>();
        finished.add(0); // first project has a stuck remaining block #3
        int blocked=BuilderNNStructures.nextOffset(plan,0,0,finished);
        assertEquals(1,blocked);
        int next=BuilderNNStructures.nextOrdinaryProject(plan,steps,blocked,0,finished);
        assertEquals(2,next);
        assertEquals(1,plan.order().get(next));
        finished.add(1);finished.add(4);finished.add(2);finished.add(5);
        // All other projects are complete, but the original #3 MUST
        // still be selected before any completion/review is possible.
        assertEquals(1,BuilderNNStructures.resumeOrdinaryBeforeReview(
                plan,steps,6,0,finished));
        assertFalse(finished.contains(3));
        assertEquals(-1,BuilderNNStructures.nextOrdinaryProject(
                plan,steps,1,0,finished));
    }
    @Test void blockedPhysicalProjectIsRevisitedBeforeDynamicReview(){
        var flow=new BuilderConstructionPlan.Step(0,0,0,
            BuilderDeferredState.flowStep("minecraft:water[level=1]"),null);
        var steps=List.of(flow,s(0,1,0),s(20,0,0),s(40,0,0));
        var plan=BuilderNNStructures.compile(steps,true);
        assertEquals(List.of(2,3,1,0),plan.order());
        assertEquals(1,BuilderNNStructures.nextOrdinaryProject(
                plan,steps,0,0,Set.of()));
        // The source index 0 is NOT considered complete just because
        // its derived fluid state is deferred to the very end.
        assertEquals(0,BuilderNNStructures.resumeOrdinaryBeforeReview(
                plan,steps,3,0,Set.of(3)));
        assertEquals(2,BuilderNNStructures.resumeOrdinaryBeforeReview(
                plan,steps,3,0,Set.of(2,3)));
        assertEquals(3,BuilderNNStructures.resumeOrdinaryBeforeReview(
                plan,steps,3,0,Set.of(1,2,3)));
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
