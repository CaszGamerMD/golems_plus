package com.caszgamermd.golemsplus.builder;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A reduced-layer crew is a normal efficiency preference, not an
 * absolute ban on physically present workers helping a stalled teammate.
 */
class BuilderStandbyCrewRescueTest {
    @Test void parkedTeammateMayRescueOnlyAFailedLayerWithReadyWork(){
        assertTrue(BuilderBuildService.standbyCanRescue(
                false,true,true,true));
        assertFalse(BuilderBuildService.standbyCanRescue(
                false,false,true,true)); // routine building
        assertFalse(BuilderBuildService.standbyCanRescue(
                false,true,false,true)); // cannot recruit mid-climb
        assertFalse(BuilderBuildService.standbyCanRescue(
                false,true,true,false)); // every cell claimed or complete
        assertFalse(BuilderBuildService.standbyCanRescue(
                true,true,true,true)); // already staffed, no standby override
    }
}
