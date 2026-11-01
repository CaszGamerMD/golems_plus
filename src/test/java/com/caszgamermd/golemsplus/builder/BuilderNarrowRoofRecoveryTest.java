package com.caszgamermd.golemsplus.builder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** A no-route roof perch must not pin a Builder for the whole build. */
final class BuilderNarrowRoofRecoveryTest {
    @Test void elevatedPerchIsAllowedAShortRecoveryWindow(){
        assertTrue(BuilderBuildService.shouldHoldRoofFrontier(0,true));
        assertTrue(BuilderBuildService.shouldHoldRoofFrontier(80,true));
        assertTrue(BuilderBuildService.shouldHoldRoofFrontier(160,true));
        assertFalse(BuilderBuildService.shouldHoldRoofFrontier(161,true),
                "A stalled high Builder must physically reposition instead of waiting forever");
    }

    @Test void groundLevelWaitRemainsBounded(){
        assertTrue(BuilderBuildService.shouldHoldRoofFrontier(80,false));
        assertFalse(BuilderBuildService.shouldHoldRoofFrontier(81,false));
        assertFalse(BuilderBuildService.shouldHoldRoofFrontier(10000,true));
    }
}
