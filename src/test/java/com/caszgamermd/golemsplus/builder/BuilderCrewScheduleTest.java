package com.caszgamermd.golemsplus.builder;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BuilderCrewScheduleTest {
    @Test void targetsFourPlacementsPerActiveWorkerUpToEight(){
        assertEquals(0,BuilderCrewSchedule.workersForLayer(0,8));
        assertEquals(1,BuilderCrewSchedule.workersForLayer(1,8));
        assertEquals(1,BuilderCrewSchedule.workersForLayer(4,8));
        assertEquals(2,BuilderCrewSchedule.workersForLayer(5,8));
        assertEquals(4,BuilderCrewSchedule.workersForLayer(16,8));
        assertEquals(7,BuilderCrewSchedule.workersForLayer(25,8));
        assertEquals(8,BuilderCrewSchedule.workersForLayer(29,8));
        assertEquals(8,BuilderCrewSchedule.workersForLayer(100,8));
        assertEquals(2,BuilderCrewSchedule.workersForLayer(5,2));
        assertEquals(0,BuilderCrewSchedule.workersForLayer(10,0));
    }
    @Test void tinyLayerSelectsOwnerWithMostCellsAndReusesWholeLayer(){
        var crew=List.of(new java.util.UUID(0,1),new java.util.UUID(0,2),
                new java.util.UUID(0,3),new java.util.UUID(0,4));
        assertEquals(List.of(crew.get(2),crew.get(0),crew.get(1)),
                BuilderCrewSchedule.staffForLayer(crew,9,new int[]{1,1,6,1}));
        assertEquals(List.of(crew.get(1),crew.get(2),crew.get(3),crew.get(0)),
                BuilderCrewSchedule.staffForLayer(crew,17,new int[]{1,7,7,2}));
        assertEquals(List.of(crew.get(0),crew.get(1),crew.get(2),crew.get(3)),
                BuilderCrewSchedule.staffForLayer(crew,40,new int[]{10,10,10,10}));
        assertEquals(List.of(crew.get(0)),
                BuilderCrewSchedule.staffForLayer(crew,0,new int[]{0,0,0,0}));
    }
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
    @Test void everyStaggeredWorkerCanSelectItsOwnFrontierWithoutCrewDelay(){
        for(int crew=1;crew<=8;crew++){
            for(long tick=0;tick<crew*5L;tick++){
                for(int worker=0;worker<crew;worker++)
                    assertTrue(BuilderCrewSchedule.frontierTurn(tick,worker,crew),
                            "The entity's existing five-tick stagger is the throttle");
            }
        }
        assertFalse(BuilderCrewSchedule.frontierTurn(10,-1,4));
        assertFalse(BuilderCrewSchedule.frontierTurn(10,4,4));
        assertFalse(BuilderCrewSchedule.frontierTurn(10,0,0));
    }
    @Test void disallowsCompletionsOutsideWindowOrAlreadyFinished(){
        assertThrows(IllegalArgumentException.class,()->
                BuilderCrewSchedule.finish(10,80,0,42));
        var p=BuilderCrewSchedule.finish(0,8,0,2);
        assertThrows(IllegalArgumentException.class,()->
                BuilderCrewSchedule.finish(0,8,p.finishedAhead(),2));
    }
}
