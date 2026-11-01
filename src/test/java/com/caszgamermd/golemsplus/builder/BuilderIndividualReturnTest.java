package com.caszgamermd.golemsplus.builder;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Individual Builder completion must not depend on a teammate's roof work. */
final class BuilderIndividualReturnTest {
    @Test void finishedZoneWithoutLiveClaimReturnsBeforeRoofAssistance(){
        assertTrue(BuilderBuildService.shouldParkFinishedZone(true,-1),
                "Builder with no remaining own coordinates should go OnSite");
    }

    @Test void futureLayerWorkAndActiveRescueClaimsRemainEligible(){
        assertFalse(BuilderBuildService.shouldParkFinishedZone(false,-1),
                "An empty current layer does not finish future assignments");
        assertFalse(BuilderBuildService.shouldParkFinishedZone(true,27),
                "A claimed teammate rescue block must finish first");
    }
}
