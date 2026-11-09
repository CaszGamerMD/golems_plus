package com.caszgamermd.golemsplus.builder;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BuilderCrewScheduleTest {
    @Test void layerStaffingScalesWithoutCrowdingTinyPlatforms(){
        assertEquals(0,BuilderCrewSchedule.workersForLayer(0,8));
        for(int blocks=1;blocks<=5;blocks++)
            assertEquals(1,BuilderCrewSchedule.workersForLayer(blocks,8));
        for(int blocks=6;blocks<=8;blocks++)
            assertEquals(2,BuilderCrewSchedule.workersForLayer(blocks,8));
        for(int blocks=9;blocks<=11;blocks++)
            assertEquals(3,BuilderCrewSchedule.workersForLayer(blocks,8));
        assertEquals(4,BuilderCrewSchedule.workersForLayer(12,8));
        assertEquals(8,BuilderCrewSchedule.workersForLayer(24,8));
        assertEquals(8,BuilderCrewSchedule.workersForLayer(100,8));
        assertEquals(2,BuilderCrewSchedule.workersForLayer(100,2));
        assertEquals(0,BuilderCrewSchedule.workersForLayer(10,0));
    }
    @Test void selectedCrewCanShareEntireCompactLayer(){
        var crew=List.of(new java.util.UUID(0,1),new java.util.UUID(0,2),
                new java.util.UUID(0,3),new java.util.UUID(0,4));
        assertEquals(List.of(crew.get(2),crew.get(0)),
                BuilderCrewSchedule.staffForLayer(crew,6,new int[]{1,0,4,1}));
        assertEquals(List.of(crew.get(1),crew.get(2),crew.get(3)),
                BuilderCrewSchedule.staffForLayer(crew,9,new int[]{1,3,3,2}));
        assertEquals(List.of(crew.get(0),crew.get(1),crew.get(2),crew.get(3)),
                BuilderCrewSchedule.staffForLayer(crew,12,new int[]{3,3,3,3}));
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
    @Test void frontierSearchesRotateAcrossWholeCrewWithoutColliding(){
        for(int crew=1;crew<=8;crew++){
            int[] counts=new int[crew];
            // Each worker runs once per five ticks; no one is starved.
            for(long tick=4;tick<crew*5L;tick+=5){
                int grants=0;
                for(int worker=0;worker<crew;worker++)
                    if(BuilderCrewSchedule.frontierTurn(tick,worker,crew)){
                        counts[worker]++;
                        grants++;
                    }
                assertEquals(1,grants,"Only one full frontier search per crew window");
            }
            for(int worker=0;worker<crew;worker++)
                assertEquals(1,counts[worker],"Every worker eventually thinks");
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
