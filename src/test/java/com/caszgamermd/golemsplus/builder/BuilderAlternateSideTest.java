package com.caszgamermd.golemsplus.builder;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** A stalled dome crew must scatter to OTHER sides before re-planning. */
final class BuilderAlternateSideTest {
    @Test void everyDiversionAvoidsTheSideThatAlreadyFailed(){
        for(int failed=0;failed<4;failed++)
            for(int worker=0;worker<8;worker++)
                for(int attempt=0;attempt<3;attempt++){
                    int side=BuilderBuildService.alternateSide(failed,worker,attempt);
                    assertTrue(side>=0&&side<4);
                    assertNotEquals(failed,side);
                }
    }

    @Test void retriesVisitAllThreeOtherSides(){
        for(int failed=0;failed<4;failed++){
            java.util.Set<Integer> explored=new java.util.HashSet<>();
            for(int attempt=0;attempt<3;attempt++)
                explored.add(BuilderBuildService.alternateSide(failed,0,attempt));
            assertEquals(3,explored.size());
        }
    }

    @Test void fourWorkersDoNotAllChooseTheSameSide(){
        java.util.Set<Integer> sides=new java.util.HashSet<>();
        for(int worker=0;worker<4;worker++)
            sides.add(BuilderBuildService.alternateSide(0,worker,0));
        assertEquals(3,sides.size());
    }
}
