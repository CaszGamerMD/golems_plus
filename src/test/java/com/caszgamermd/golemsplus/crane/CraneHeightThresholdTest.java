package com.caszgamermd.golemsplus.crane;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class CraneHeightThresholdTest {
    @Test void belowFifteenUsesScaffoldingEvenWithCraneAvailable(){
        assertFalse(CraneService.shouldUseCrane(62,62,76));
        assertFalse(CraneService.shouldUseCrane(62,70,76));
    }
    @Test void fifteenAndHigherUseCraneForRealVerticalTrips(){
        assertTrue(CraneService.shouldUseCrane(62,62,77));
        assertTrue(CraneService.shouldUseCrane(62,79,62));
        assertTrue(CraneService.shouldUseCrane(-50,-50,-35));
    }
    @Test void shortSameLevelTripsNeverCallTheCrane(){
        assertFalse(CraneService.shouldUseCrane(0,20,20));
        assertFalse(CraneService.shouldUseCrane(0,20,21));
        assertTrue(CraneService.shouldUseCrane(0,20,18));
    }
}