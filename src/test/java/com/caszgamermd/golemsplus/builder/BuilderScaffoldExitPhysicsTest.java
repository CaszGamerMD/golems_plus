package com.caszgamermd.golemsplus.builder;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Minecraft physics always owns movement; the scaffold preflight must
 * distinguish an overlap with PAID climb-through material from a wall.
 */
class BuilderScaffoldExitPhysicsTest {
    @Test void realOwnedScaffoldMayAskNativePhysicsToResolveSideExit(){
        assertTrue(BuilderScaffolding.exitMovePreflight(
                false,true,true));
    }
    @Test void noPermissionToStepIntoUnsupportedLandingOrUnownedObstruction(){
        assertFalse(BuilderScaffolding.exitMovePreflight(
                false,false,true));
        assertFalse(BuilderScaffolding.exitMovePreflight(
                true,false,false));
        assertFalse(BuilderScaffolding.exitMovePreflight(
                false,true,false));
    }
    @Test void ordinaryCollisionFreeExitStillWorks(){
        assertTrue(BuilderScaffolding.exitMovePreflight(
                true,false,true));
    }
}
