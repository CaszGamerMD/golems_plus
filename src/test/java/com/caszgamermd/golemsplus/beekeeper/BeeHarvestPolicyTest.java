package com.caszgamermd.golemsplus.beekeeper;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BeeHarvestPolicyTest {
    @Test void bothPrefersCombWhenShearsAndStorageAreReady(){
        assertEquals(BeeHarvestPolicy.Choice.COMB,
                BeeHarvestPolicy.choose(0,true,true,true,true));
    }
    @Test void bothFallsBackToHoneyIfCombCannotFit(){
        assertEquals(BeeHarvestPolicy.Choice.BOTTLE,
                BeeHarvestPolicy.choose(0,true,true,false,true));
    }
    @Test void modesCannotConsumeUnauthorizedTools(){
        assertEquals(BeeHarvestPolicy.Choice.WAIT,
                BeeHarvestPolicy.choose(1,false,true,true,true));
        assertEquals(BeeHarvestPolicy.Choice.WAIT,
                BeeHarvestPolicy.choose(2,true,false,true,true));
        assertEquals(BeeHarvestPolicy.Choice.BOTTLE,
                BeeHarvestPolicy.choose(2,true,true,true,true));
    }
    @Test void noToolOrOutputCapacityMeansWaiting(){
        assertEquals(BeeHarvestPolicy.Choice.WAIT,
                BeeHarvestPolicy.choose(0,false,false,true,true));
        assertEquals(BeeHarvestPolicy.Choice.WAIT,
                BeeHarvestPolicy.choose(0,true,true,false,false));
        assertThrows(IllegalArgumentException.class,()->
                BeeHarvestPolicy.choose(3,true,true,true,true));
    }
}
