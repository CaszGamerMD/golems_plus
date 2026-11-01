package com.caszgamermd.golemsplus.builder;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Individual Builder completion must not depend on a teammate's roof work. */
final class BuilderIndividualReturnTest {
    @Test void finishedZoneWithoutLiveClaimReturnsBeforeRoofAssistance(){
        assertTrue(BuilderBuildService.shouldParkFinishedZone(true,-1),
                "Builder with no remaining own coordinates should go OnSite");
    }

    @Test void idleLayerWorkersReturnOnSiteWithoutCancelingFutureWork(){
        assertTrue(BuilderBuildService.shouldParkWaitingForLayer(true,false,-1),
                "Only a teammate has unfinished cells on the active Y");
        assertFalse(BuilderBuildService.shouldParkWaitingForLayer(false,false,-1),
                "An unblocked worker must keep searching its own layer");
        assertFalse(BuilderBuildService.shouldParkWaitingForLayer(true,true,-1),
                "A requested rescue may claim neighboring unfinished cells");
        assertFalse(BuilderBuildService.shouldParkWaitingForLayer(true,false,7),
                "An already claimed rescue must not be abandoned");
    }
    @Test void futureLayerWorkAndActiveRescueClaimsRemainEligible(){
        assertFalse(BuilderBuildService.shouldParkFinishedZone(false,-1),
                "An empty current layer does not finish future assignments");
        assertFalse(BuilderBuildService.shouldParkFinishedZone(true,27),
                "A claimed teammate rescue block must finish first");
    }
}
