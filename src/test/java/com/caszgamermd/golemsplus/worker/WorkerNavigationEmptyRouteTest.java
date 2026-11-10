package com.caszgamermd.golemsplus.worker;

import java.util.List;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Terrain/corner recovery can synchronously retire the current path. */
final class WorkerNavigationEmptyRouteTest {
    @Test void retiredRouteCannotExposeWaypointZero(){
        assertFalse(WorkerNavigation.hasWaypoint(List.of(),0));
        assertFalse(WorkerNavigation.hasWaypoint(List.of(),-1));
        assertFalse(WorkerNavigation.hasWaypoint(null,0));
        assertFalse(WorkerNavigation.hasWaypoint(List.of(BlockPos.ZERO),1));
    }
    @Test void validCursorIsAcceptedOnlyWithinCurrentRoute(){
        var steps=List.of(BlockPos.ZERO,BlockPos.ZERO.above());
        assertTrue(WorkerNavigation.hasWaypoint(steps,0));
        assertTrue(WorkerNavigation.hasWaypoint(steps,1));
        assertFalse(WorkerNavigation.hasWaypoint(steps,2));
        // The old second recovery call could replace the route with an
        // empty List while still trying to read steps.get(0).
        steps=List.of();
        assertFalse(WorkerNavigation.hasWaypoint(steps,0));
    }
    @Test void interruptedLegCannotSkipItsUnvisitedEndpoint(){
        var a=new BlockPos(1,0,0);
        var b=new BlockPos(2,0,0);
        var c=new BlockPos(2,0,1);
        var steps=List.of(a,b,c);
        assertEquals(1,WorkerNavigation.unfinishedLegCursor(steps,2,b,false));
        assertEquals(2,WorkerNavigation.unfinishedLegCursor(steps,2,b,true));
        assertEquals(2,WorkerNavigation.unfinishedLegCursor(steps,3,c,false));
        assertEquals(0,WorkerNavigation.unfinishedLegCursor(List.of(),0,b,false));
        assertEquals(1,WorkerNavigation.unfinishedLegCursor(steps,1,c,false));
    }
}
