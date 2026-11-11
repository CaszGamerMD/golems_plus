package com.caszgamermd.golemsplus.builder;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuilderRouteProgressTest {
    @Test void repeatedMotionCannotKeepResettingRecovery(){
        var watch=new BuilderRouteProgress(.04);
        watch.reset(2);
        for(int tick=1;tick<=300;tick++)watch.sample(tick%2==0?2:3);
        assertEquals(300,watch.stalledTicks());
    }
    @Test void slowCumulativeProgressCounts(){
        var watch=new BuilderRouteProgress(.04);
        watch.reset(5);
        for(int tick=1;tick<=300;tick++){
            watch.sample(5-tick*.005);
            assertTrue(watch.stalledTicks()<10);
        }
    }
    @Test void newPhaseAndNewBestRestartWatchdog(){
        var watch=new BuilderRouteProgress(.04);
        watch.reset(2);
        for(int tick=0;tick<45;tick++)watch.sample(2);
        assertEquals(45,watch.stalledTicks());
        watch.sample(1.9);
        assertEquals(0,watch.stalledTicks());
        watch.sample(2);
        watch.reset(8);
        assertEquals(0,watch.stalledTicks());
    }
}
