package com.caszgamermd.golemsplus.builder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BuilderNNProjectRecoveryPolicyTest {
    @Test void cannotStartBeforePhysicalUnload(){
        assertFalse(BuilderNNProjectRecoveryPolicy.ready(true,false,400,0));
        assertFalse(BuilderNNProjectRecoveryPolicy.ready(true,false,600,0));
        assertFalse(BuilderNNProjectRecoveryPolicy.ready(true,false,599,3));
        assertFalse(BuilderNNProjectRecoveryPolicy.ready(false,true,800,8));
    }
    @Test void partialUnloadedCrewCanTakeOverAfterBoundedReturn(){
        assertTrue(BuilderNNProjectRecoveryPolicy.ready(true,false,600,1));
        assertTrue(BuilderNNProjectRecoveryPolicy.ready(true,false,900,5));
        assertTrue(BuilderNNProjectRecoveryPolicy.ready(true,true,10,8));
    }
}
