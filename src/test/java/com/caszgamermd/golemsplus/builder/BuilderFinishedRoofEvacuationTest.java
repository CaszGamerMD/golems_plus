package com.caszgamermd.golemsplus.builder;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A finished worker must vacate the roof before another rescue request
 * can recruit it. The return state ends only at a physical OnSite arrival.
 */
final class BuilderFinishedRoofEvacuationTest {
    @Test void currentLayerIsFinishedButBuilderRemainsOnNarrowRoof(){
        assertTrue(BuilderBuildService.shouldEvacuateIdleLayer(true,-1,false),
                "Idle Builder should leave the roof before it may assist elsewhere");
        assertTrue(BuilderBuildService.shouldContinueEvacuation(true,false),
                "A failed walk, scaffold descent or grapple must not release the return latch");
    }

    @Test void returnLatchClearsOnlyOnRealArrival(){
        assertFalse(BuilderBuildService.shouldContinueEvacuation(true,true));
        assertFalse(BuilderBuildService.shouldContinueEvacuation(false,false));
        assertFalse(BuilderBuildService.shouldEvacuateIdleLayer(true,-1,true),
                "Returning Builder may become a helper only AFTER it is at OnSite");
    }

    @Test void claimedRescueAndRemainingWorkAreNotAbandoned(){
        assertFalse(BuilderBuildService.shouldEvacuateIdleLayer(true,19,false),
                "Do not cancel a rescue already claimed and being worked");
        assertFalse(BuilderBuildService.shouldEvacuateIdleLayer(false,-1,false),
                "Own-layer Builder with work remaining can search from the roof");
        assertTrue(BuilderBuildService.shouldParkFinishedZone(true,-1),
                "Permanently completed zone must stay out of teammates' roof work");
    }
}
