package com.caszgamermd.golemsplus.builder;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BuilderNNStageGeometryTest {
    @Test void worksEquallyForXAndZLongApproaches(){
        var start=new BlockPos(0,70,0);
        var a=BuilderNNStageGeometry.candidates(start,new BlockPos(64,84,0));
        var b=BuilderNNStageGeometry.candidates(start,new BlockPos(0,84,64));
        assertFalse(a.isEmpty());assertEquals(a.size(),b.size());
        assertEquals(new BlockPos(12,70,0),a.getFirst());
        assertEquals(new BlockPos(0,70,12),b.getFirst());
        assertEquals(new HashSet<>(a.stream().map(p->new BlockPos(p.getZ(),p.getY(),p.getX())).toList()),new HashSet<>(b));
    }
    @Test void repeatedShortLegsConvergeWithoutBacktrackingOrLoops(){
        var target=new BlockPos(96,75,0);
        var current=new BlockPos(0,70,0);
        var visited=new HashSet<BlockPos>();visited.add(current);
        int legs=0;
        while(!BuilderNNStageGeometry.candidates(current,target).isEmpty()&&legs<8){
            var next=BuilderNNStageGeometry.candidates(current,target).getFirst();
            assertTrue(visited.add(next),"staging must never revisit a waypoint");
            assertEquals(70,next.getY(),"ground-stage does not fake a vertical lift");
            assertTrue(Math.hypot(next.getX()-target.getX(),next.getZ()-target.getZ())+5
                    <Math.hypot(current.getX()-target.getX(),current.getZ()-target.getZ()),
                    "every leg must bring the worker measurably closer");
            current=next;legs++;
        }
        assertTrue(legs>1&&legs<=8);
        assertTrue(Math.hypot(current.getX()-target.getX(),current.getZ()-target.getZ())<18);
    }
    @Test void elevatedNearRoofCanStageWithoutInventingVerticalTravel(){
        var from=new BlockPos(100,-55,100);
        var xTarget=from.offset(15,24,0);
        var zTarget=from.offset(0,24,15);
        assertTrue(BuilderNNStageGeometry.candidates(from,xTarget).isEmpty(),
                "long-distance geometry must remain unchanged");
        assertTrue(BuilderNNStageGeometry.needsElevatedApproach(from,xTarget));
        var x=BuilderNNStageGeometry.elevatedCandidates(from,xTarget);
        var z=BuilderNNStageGeometry.elevatedCandidates(from,zTarget);
        assertFalse(x.isEmpty());
        assertEquals(x.size(),z.size());
        assertEquals(new HashSet<>(x.stream()
                    .map(p->new BlockPos(100+p.getZ()-100,p.getY(),100+p.getX()-100))
                    .toList()),new HashSet<>(z));
        for(var point:x){
            assertEquals(from.getY(),point.getY());
            assertTrue(point.distSqr(from)<=230);
            assertTrue(Math.hypot(point.getX()-xTarget.getX(),
                        point.getZ()-xTarget.getZ())<15);
        }
        assertTrue(BuilderNNStageGeometry.elevatedCandidates(
                    from,from.offset(15,9,0)).isEmpty(),
                "small height differences must not trigger a roof stage");
        assertTrue(BuilderNNStageGeometry.elevatedCandidates(
                    from,from.offset(20,24,0)).isEmpty(),
                "long approaches still use the original geometry");
    }
    @Test void noDestinationTeleportOrUnnecessaryNearStaging(){
        var from=new BlockPos(100,50,100);
        assertTrue(BuilderNNStageGeometry.candidates(from,new BlockPos(110,72,102)).isEmpty());
        var target=new BlockPos(171,63,100);
        for(var waypoint:BuilderNNStageGeometry.candidates(from,target)){
            assertEquals(50,waypoint.getY());
            assertTrue(waypoint.distSqr(from)<=230);
            assertTrue(Math.hypot(target.getX()-waypoint.getX(),target.getZ()-waypoint.getZ())>=6);
            assertFalse(waypoint.equals(target));
        }
    }
}
