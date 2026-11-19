package com.caszgamermd.golemsplus.builder;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuilderNNProductiveWorkTest {
    @Test void closestVerifiedHandPoseWinsRegardlessOfAxis() {
        assertEquals(BuilderNNProductiveWork.samePoseScore(4,16),
                BuilderNNProductiveWork.samePoseScore(4,16),0.0);
        assertTrue(BuilderNNProductiveWork.samePoseScore(2,9)
                <BuilderNNProductiveWork.samePoseScore(4,9));
        assertTrue(BuilderNNProductiveWork.samePoseScore(3,1)
                <BuilderNNProductiveWork.samePoseScore(3,16));
        // A candidate that is not reachable from the current stance is
        // NEVER made legal by this cost; the caller prefilters actual reach.
    }

    @Test void briefExactRouteRejectionCannotEvictWork() {
        assertFalse(BuilderNNProductiveWork.exhaustedRejectedRoute(1,0));
        assertFalse(BuilderNNProductiveWork.exhaustedRejectedRoute(3,100));
        assertFalse(BuilderNNProductiveWork.exhaustedRejectedRoute(4,5));
        assertTrue(BuilderNNProductiveWork.exhaustedRejectedRoute(4,15));
        assertTrue(BuilderNNProductiveWork.exhaustedRejectedRoute(8,60));
    }
}
