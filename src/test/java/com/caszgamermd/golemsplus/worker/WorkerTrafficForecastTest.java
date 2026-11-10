package com.caszgamermd.golemsplus.worker;

import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Cooperative traffic is a preference, not a permanent wall. */
final class WorkerTrafficForecastTest {
    private static BlockPos at(int x,int z){return new BlockPos(x,64,z);}
    @Test void forecastsTeammatesUpcomingStepsButNotOwnPlan(){
        var board=new WorkerTrafficForecast.Board();
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        board.publish(a,at(0,0),
                List.of(at(1,0),at(2,0),at(3,0)),100);
        var cost=board.snapshot(b,110);
        assertTrue(cost.get(at(1,0))>cost.get(at(2,0)));
        assertTrue(cost.get(at(2,0))>cost.get(at(3,0)));
        assertFalse(cost.containsKey(at(0,0)));
        assertTrue(board.snapshot(a,110).isEmpty(),
                "A worker must not avoid its own route");
    }
    @Test void forecastsExpireAndAreReplacedNotAccumulated(){
        var board=new WorkerTrafficForecast.Board();
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        board.publish(a,at(0,0),List.of(at(1,0)),100);
        assertTrue(board.snapshot(b,119).containsKey(at(1,0)));
        assertTrue(board.snapshot(b,120).isEmpty());
        board.publish(a,at(0,0),List.of(at(0,1)),130);
        assertFalse(board.snapshot(b,132).containsKey(at(1,0)));
        assertTrue(board.snapshot(b,132).containsKey(at(0,1)));
        board.release(a);
        assertTrue(board.snapshot(b,133).isEmpty());
    }
    @Test void trafficIsBoundedToSixForwardPositionsAndNeverAHardWall(){
        var board=new WorkerTrafficForecast.Board();
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        for(int i=0;i<10;i++){
            var id=UUID.randomUUID();
            board.publish(id,at(0,i),
                    List.of(at(1,1),at(2,1),at(3,1),at(4,1),
                            at(5,1),at(6,1),at(7,1)),100);
        }
        var cost=board.snapshot(b,101);
        assertEquals(6,cost.size(),"Only a short rolling horizon is forecast");
        assertEquals(16.0,cost.get(at(1,1)),.00001,
                "Traffic stays a finite cost, not a forbidden cell");
        assertFalse(cost.containsKey(at(7,1)));
    }
    @Test void coordinatedAStarPrefersFreeParallelAisle(){
        var board=new WorkerTrafficForecast.Board();
        UUID a=UUID.randomUUID(),b=UUID.randomUUID();
        board.publish(a,at(0,0),List.of(at(1,0),at(2,0),
                at(3,0),at(4,0)),100);
        java.util.Set<BlockPos> floor=new java.util.HashSet<>();
        for(int x=0;x<=4;x++)for(int z=0;z<=1;z++)
            floor.add(at(x,z));
        var costs=board.snapshot(b,101);
        var route=WorkerRoutePlanner.plan(at(0,0),List.of(at(4,0)),
                floor::contains,BlockPos::getY,null,
                pos->costs.getOrDefault(pos,0.0));
        assertNotNull(route);
        assertTrue(route.complete());
        assertTrue(route.steps().stream().anyMatch(p->p.getZ()==1),
                "Traffic-aware route should avoid a peer's reserved straight aisle");
        var singleLane=new java.util.HashSet<BlockPos>();
        for(int x=0;x<=4;x++)singleLane.add(at(x,0));
        var narrow=WorkerRoutePlanner.plan(at(0,0),List.of(at(4,0)),
                singleLane::contains,BlockPos::getY,null,
                pos->costs.getOrDefault(pos,0.0));
        assertNotNull(narrow);
        assertTrue(narrow.complete(),
                "An unavoidable teammate is not a permanent solid obstacle");
    }
}
