package com.caszgamermd.golemsplus.builder;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class BuilderWorkReachTest {
    @Test void highDomeCapDoesNotSelectAFalseThreeTileGroundStance(){
        BlockPos cap=new BlockPos(15,31,16);
        // The older integer preview measured sqrt(3^2+2^2) and claimed
        // this pose was within reach. The actual target center is Y31.5.
        assertEquals(15.25,BuilderConstructionPlanner.workReachFromFeet(
                new BlockPos(15,29,13),cap),1.e-9);
        assertTrue(BuilderConstructionPlanner.workReachFromFeet(
                new BlockPos(15,29,13),cap)>=14);
        // One tile closer is a legitimate place-from-below candidate,
        // subject to the separate line-of-sight and exit checks.
        assertEquals(11.25,BuilderConstructionPlanner.workReachFromFeet(
                new BlockPos(16,29,14),cap),1.e-9);
    }
}
