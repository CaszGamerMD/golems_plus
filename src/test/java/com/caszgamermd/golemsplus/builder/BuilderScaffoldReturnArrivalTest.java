package com.caszgamermd.golemsplus.builder;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The paid bridge must remain real; transient Minecraft scaffold ground
 * flags cannot freeze a physical return or authorize a fake platform.
 */
class BuilderScaffoldReturnArrivalTest {
    @Test void realPaidReturnCanAdvanceAfterTransientUngroundedContact() {
        assertTrue(BuilderScaffoldPlanA.stablePaidReturnArrival(
                true,true,true,false,false,0.025));
        assertTrue(BuilderScaffoldPlanA.stablePaidReturnArrival(
                true,true,true,true,false,-0.12));
    }
    @Test void neverTreatAirAsReturnedScaffoldOrAdvanceUnsettledFalls(){
        assertFalse(BuilderScaffoldPlanA.stablePaidReturnArrival(
                true,true,false,false,false,0));
        assertFalse(BuilderScaffoldPlanA.stablePaidReturnArrival(
                true,false,true,false,false,0));
        assertFalse(BuilderScaffoldPlanA.stablePaidReturnArrival(
                true,true,true,false,false,-0.25));
        assertFalse(BuilderScaffoldPlanA.stablePaidReturnArrival(
                true,true,true,false,true,0));
    }
    @Test void forwardBridgeStillRequiresFullGroundedTopProof(){
        assertFalse(BuilderScaffoldPlanA.stablePaidReturnArrival(
                false,true,true,false,false,0));
    }
}
