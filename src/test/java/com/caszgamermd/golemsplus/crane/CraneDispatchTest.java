package com.caszgamermd.golemsplus.crane;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
final class CraneDispatchTest {
    @Test void oldestDirectionWinsAndBatchNeverExceedsFour(){
        var d=new CraneDispatch();UUID down=UUID.randomUUID();d.request(down,80,64,1);
        UUID up=UUID.randomUUID();d.request(up,64,80,2);
        for(int i=0;i<5;i++)d.request(UUID.randomUUID(),90,64,3+i);
        assertEquals(4,d.batch().size());assertEquals(down,d.batch().getFirst());assertFalse(d.batch().contains(up));
        d.request(up,64,90,20);assertEquals(down,d.batch().getFirst());
        for(UUID id:d.batch())d.remove(id);
        assertEquals(List.of(up),d.batch());
    }
    @Test void repeatedCallsCannotDuplicatePassengers(){
        var d=new CraneDispatch();UUID id=UUID.randomUUID();for(int i=0;i<20;i++)d.request(id,0,10,i);
        assertEquals(1,d.size());d.remove(id);assertTrue(d.batch().isEmpty());
    }
    @Test void boomStopsAtNinety(){assertEquals(30,CraneDispatch.reach(0));assertEquals(60,CraneDispatch.reach(1));assertEquals(90,CraneDispatch.reach(2));assertEquals(90,CraneDispatch.reach(20));}
    @Test void blockedFirstRiderDoesNotStarveReachablePassengers(){
        UUID blocked=UUID.randomUUID(),ready=UUID.randomUUID(),far=UUID.randomUUID();
        var docks=new LinkedHashMap<UUID,Double>();
        docks.put(blocked,Double.POSITIVE_INFINITY);
        docks.put(far,100.0);
        docks.put(ready,16.0);
        assertEquals(ready,CraneDispatch.nearestReachable(docks));
        docks.remove(ready);
        assertEquals(far,CraneDispatch.nearestReachable(docks));
        docks.remove(far);
        assertNull(CraneDispatch.nearestReachable(docks));
    }
    @Test void nearestReachableKeepsStableOrderOnEqualDistances(){
        UUID first=UUID.randomUUID(),second=UUID.randomUUID();
        var docks=new LinkedHashMap<UUID,Double>();
        docks.put(first,9.0);docks.put(second,9.0);
        assertEquals(first,CraneDispatch.nearestReachable(docks));
        docks.clear();docks.put(first,Double.NaN);docks.put(second,-1.0);
        assertNull(CraneDispatch.nearestReachable(docks));
    }
}
