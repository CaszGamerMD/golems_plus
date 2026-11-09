package com.caszgamermd.golemsplus.builder;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A stalled dome crew must scatter to OTHER sides before re-planning. */
final class BuilderAlternateSideTest {
    @Test void everyDiversionAvoidsTheSideThatAlreadyFailed(){
        for(int failed=0;failed<4;failed++)
            for(int worker=0;worker<8;worker++)
                for(int attempt=0;attempt<3;attempt++){
                    int side=BuilderBuildService.alternateSide(failed,worker,attempt);
                    assertTrue(side>=0&&side<4);
                    assertNotEquals(failed,side);
                }
    }

    @Test void retriesVisitAllThreeOtherSides(){
        for(int failed=0;failed<4;failed++){
            java.util.Set<Integer> explored=new java.util.HashSet<>();
            for(int attempt=0;attempt<3;attempt++)
                explored.add(BuilderBuildService.alternateSide(failed,0,attempt));
            assertEquals(3,explored.size());
        }
    }

    @Test void rooftopRecoveryTriesCurrentFeetElevationBeforeDepot(){
        assertArrayEquals(new int[]{72,65},
                BuilderBuildService.alternateStagingHeights(72,65));
        assertArrayEquals(new int[]{65},
                BuilderBuildService.alternateStagingHeights(65,65));
        assertArrayEquals(new int[]{61,65},
                BuilderBuildService.alternateStagingHeights(61,65));
    }

    @Test void realPlacementResetsPerObstacleEscapeBudget(){
        var activity=new BuilderBuildService.WorkerAction();
        activity.alternateSideAttempts=3;
        activity.alternateSideRequested=true;
        activity.alternateSideTarget=new net.minecraft.core.BlockPos(10,75,10);
        activity.alternateSideStarted=500;
        activity.failedApproachFrom=new net.minecraft.core.BlockPos(9,75,9);
        activity.lastFailedStep=42;
        activity.retreatUntil=600;
        // A new claim does NOT reset this budget for the same obstacle.
        activity.setStep(43);
        assertEquals(3,activity.alternateSideAttempts);
        // An actual physical placement unlocks recovery for later cells.
        activity.notePhysicalPlacement();
        assertEquals(0,activity.alternateSideAttempts);
        assertFalse(activity.alternateSideRequested);
        assertNull(activity.alternateSideTarget);
        assertEquals(-1,activity.alternateSideStarted);
        assertNull(activity.failedApproachFrom);
        assertEquals(-1,activity.lastFailedStep);
        assertEquals(-1,activity.retreatUntil);
    }

    @Test void watchdogAllowsLongDetoursButShortensStationaryWait(){
        assertEquals(120,BuilderBuildService.STATIONARY_STALL_TICKS);
        assertEquals(240,BuilderBuildService.TARGET_PROGRESS_STALL_TICKS);
        assertTrue(BuilderBuildService.TARGET_PROGRESS_STALL_TICKS
                >BuilderBuildService.STATIONARY_STALL_TICKS);
    }

    @Test void fourWorkersDoNotAllChooseTheSameSide(){
        java.util.Set<Integer> sides=new java.util.HashSet<>();
        for(int worker=0;worker<4;worker++)
            sides.add(BuilderBuildService.alternateSide(0,worker,0));
        assertEquals(3,sides.size());
    }
}
