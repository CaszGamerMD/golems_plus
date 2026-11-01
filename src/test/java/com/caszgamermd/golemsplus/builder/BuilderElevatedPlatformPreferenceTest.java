package com.caszgamermd.golemsplus.builder;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A grapple can move a worker but cannot invent unsupported work footing. */
final class BuilderElevatedPlatformPreferenceTest {
    @Test void unsupportedPlacementGetsFirstScaffoldOpportunity(){
        assertTrue(BuilderConstructionPlanner.preferScaffoldForUnsupportedWork(false,true),
                "Air under an unapproachable target must offer a paid scaffold first");
    }
    @Test void existingGroundedPoseOrSupportKeepsNormalNavigation(){
        assertFalse(BuilderConstructionPlanner.preferScaffoldForUnsupportedWork(true,true));
        assertFalse(BuilderConstructionPlanner.preferScaffoldForUnsupportedWork(false,false));
        assertFalse(BuilderConstructionPlanner.preferScaffoldForUnsupportedWork(true,false));
    }
}
