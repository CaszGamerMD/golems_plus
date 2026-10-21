package com.caszgamermd.golemsplus.builder;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BuilderCrewScheduleTest {
    @Test void fourWorkersGetDistinctStepsAndRetireOutOfOrder(){
        int next0=BuilderCrewSchedule.next(0,6,0,List.of());
        int next1=BuilderCrewSchedule.next(0,6,0,List.of(next0));
        int next2=BuilderCrewSchedule.next(0,6,0,List.of(next0,next1));
        int next3=BuilderCrewSchedule.next(0,6,0,List.of(next0,next1,next2));
        assertEquals(List.of(0,1,2,3),List.of(next0,next1,next2,next3));
        var p=BuilderCrewSchedule.finish(0,6,0,next2);
        assertEquals(0,p.cursor());
        // Steps 0, 1 and 3 remain in flight; step 4 is next.
        assertEquals(4,BuilderCrewSchedule.next(p.cursor(),6,p.finishedAhead(),
                List.of(0,1,3)));
        p=BuilderCrewSchedule.finish(p.cursor(),6,p.finishedAhead(),next0);
        assertEquals(1,p.cursor());
        p=BuilderCrewSchedule.finish(p.cursor(),6,p.finishedAhead(),next1);
        assertEquals(3,p.cursor());
        p=BuilderCrewSchedule.finish(p.cursor(),6,p.finishedAhead(),next3);
        assertEquals(4,p.cursor());
    }
    @Test void disallowsCompletionsOutsideWindowOrAlreadyFinished(){
        assertThrows(IllegalArgumentException.class,()->
                BuilderCrewSchedule.finish(10,80,0,42));
        var p=BuilderCrewSchedule.finish(0,8,0,2);
        assertThrows(IllegalArgumentException.class,()->
                BuilderCrewSchedule.finish(0,8,p.finishedAhead(),2));
    }
}
