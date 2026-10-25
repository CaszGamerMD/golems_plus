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
}
