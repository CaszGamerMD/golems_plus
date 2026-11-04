package com.caszgamermd.golemsplus.builder;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class BuilderStuckRecoveryTest {
    @Test void perimeterEscapeTriesCurrentHeightBeforeOnSite(){
        assertArrayEquals(new int[]{82,65},
                BuilderBuildService.alternateStagingHeights(82,65));
    }

    @Test void doesNotDuplicateCurrentHeightWhenAlreadyAtOnSite(){
        assertArrayEquals(new int[]{65},
                BuilderBuildService.alternateStagingHeights(65,65));
    }

    @Test void repeatedRecoveryCyclesTryEverySideOtherThanTheFailedOne(){
        Set<Integer> tried=new HashSet<>();
        for(int attempt=0;attempt<3;attempt++){
            int side=BuilderBuildService.alternateSide(1,0,attempt);
            assertNotEquals(1,side);
            assertTrue(tried.add(side),"Recovery should not repeat a side");
        }
        assertEquals(3,tried.size());
    }
}
