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
    @Test void skipsWaypointsAlreadyPassedOnThePlannedRoute(){
        var route=List.of(new BlockPos(1,64,0),new BlockPos(2,64,0),
                new BlockPos(3,64,0),new BlockPos(4,64,0),
                new BlockPos(5,64,0));
        assertEquals(4,WorkerNavigation.advanceCursorPastCurrentRouteNode(
                route,1,new BlockPos(4,64,0)));
        assertEquals(1,WorkerNavigation.advanceCursorPastCurrentRouteNode(
                route,1,new BlockPos(2,64,1)),
                "Being merely beside a waypoint is not enough to skip it");
    }

    @Test void recognizesProgressBeyondAStaleWaypointTowardTheGoal(){
        BlockPos goal=new BlockPos(10,64,0);
        BlockPos waypoint=new BlockPos(4,64,0);
        assertTrue(WorkerNavigation.progressedPastWaypoint(
                new BlockPos(7,64,0),goal,waypoint));
        assertFalse(WorkerNavigation.progressedPastWaypoint(
                new BlockPos(2,64,0),goal,waypoint));
        assertFalse(WorkerNavigation.progressedPastWaypoint(
                new BlockPos(4,64,2),goal,waypoint),
                "A legitimate detour away from the goal is not a passed waypoint");
    }
}
