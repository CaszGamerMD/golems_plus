package com.caszgamermd.golemsplus.worker;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class WorkerProgressWatchTest {
    @Test void oscillationCannotKeepWorkerMovingForever(){
        var watch=new WorkerProgressWatch();
        watch.sample(1,2,3,2,5);
        for(int i=0;i<8;i++)
            watch.sample(1,2,3,i%2==0?2.3:2,5);
        assertEquals(40,watch.sample(1,2,3,2.2,0));
    }
    @Test void smallForwardStepsAccumulate(){
        var watch=new WorkerProgressWatch();
        for(int i=0;i<100;i++)
            assertTrue(watch.sample(1,2,3,5-i*.02,5)<40);
    }
    @Test void newNodeAndIntentionalPauseStartFresh(){
        var watch=new WorkerProgressWatch();
        watch.sample(1,2,3,2,5);
        assertEquals(40,watch.sample(1,2,3,2,40));
        assertEquals(0,watch.sample(2,2,3,3,5));
        watch.sample(2,2,3,3,40);
        watch.reset();
        assertEquals(0,watch.sample(2,2,3,3,5));
    }
}
