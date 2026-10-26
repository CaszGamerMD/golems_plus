package com.caszgamermd.golemsplus.builder;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class ScaffoldVerticalRouteTest {
    @Test void buildsToAnyRequestedHeightInSmallExtensions(){
        int ground=63,target=310,existing=ground-1,segments=0;
        while(existing<target){
            int next=ScaffoldVerticalRoute.stageTop(ground,existing,target);
            assertTrue(next>existing);
            assertTrue(next-existing<=ScaffoldVerticalRoute.EXTENSION);
            existing=next;
            assertTrue(++segments<30);
        }
        assertEquals(target,existing);
        assertTrue(segments>10);
    }
    @Test void extendingExistingTowerNeverForgetsItsTop(){
        assertEquals(79,ScaffoldVerticalRoute.stageTop(64,63,145));
        assertEquals(95,ScaffoldVerticalRoute.stageTop(64,79,145));
        assertEquals(145,ScaffoldVerticalRoute.stageTop(64,143,145));
    }
    @Test void shortWorkNeverOverbuilds(){
        assertEquals(68,ScaffoldVerticalRoute.stageTop(64,63,68));
        assertEquals(68,ScaffoldVerticalRoute.stageTop(64,68,68));
    }
    @Test void neverStopsOneBlockTooHighOrLow(){
        assertFalse(ScaffoldVerticalRoute.reached(72,73,false));
        assertTrue(ScaffoldVerticalRoute.reached(72.94,73,false));
        assertFalse(ScaffoldVerticalRoute.reached(65,64,true));
        assertTrue(ScaffoldVerticalRoute.reached(64.09,64,true));
    }
    @Test void bottomScaffoldIsNotSafeGround(){
        assertTrue(ScaffoldVerticalRoute.onBottomScaffold(65,64,.05,.05));
        assertFalse(ScaffoldVerticalRoute.onBottomScaffold(64,64,0,0));
        assertFalse(ScaffoldVerticalRoute.onBottomScaffold(65,64,1,0));
        assertTrue(ScaffoldVerticalRoute.needsDescent(66,64));
    }
    @Test void lowTowerStillRequiresDismount(){
        assertTrue(ScaffoldVerticalRoute.needsDescent(65,63));
        assertFalse(ScaffoldVerticalRoute.needsDescent(64.05,63));
        assertTrue(ScaffoldVerticalRoute.needsReturn(1.2,0));
        assertFalse(ScaffoldVerticalRoute.needsReturn(.3,.2));
    }
}

