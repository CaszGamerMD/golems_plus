package com.caszgamermd.golemsplus.builder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BuilderWorkStallPolicyTest {
    @Test void continuesARealDetourThatIsStillMoving() {
        // Moving away from the goal can be the only safe route around a wall.
        assertFalse(BuilderBuildService.workApproachStalled(400,10,350));
        assertFalse(BuilderBuildService.workApproachStalled(299,250,250));
        assertFalse(BuilderBuildService.workApproachStalled(350,125,140));
    }

    @Test void releasesIdleClaimsBeforeTheyExhaustAWholeDomeLayer() {
        assertTrue(BuilderBuildService.workApproachStalled(300,120,200));
        assertTrue(BuilderBuildService.workApproachStalled(450,400,400));
    }

    @Test void retainsTheHardDeadlineForCirclingWithoutProgress() {
        assertFalse(BuilderBuildService.workApproachStalled(900,5,880));
        assertTrue(BuilderBuildService.workApproachStalled(901,5,881));
    }
}
