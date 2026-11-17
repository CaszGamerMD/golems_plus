package com.caszgamermd.golemsplus.builder;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BuilderNNScaffoldChainTest {
    @Test void realSupportFirstNeverSelectsFloatingScaffold(){
        assertEquals(0,BuilderNNScaffoldChain.priority(true,false,false,false));
        assertEquals(0,BuilderNNScaffoldChain.priority(true,false,true,true));
        assertEquals(0,BuilderNNScaffoldChain.priority(false,true,true,true));
        assertEquals(3,BuilderNNScaffoldChain.priority(true,true,true,false));
        assertEquals(2,BuilderNNScaffoldChain.priority(true,true,false,true));
        assertEquals(1,BuilderNNScaffoldChain.priority(true,true,false,false));
    }
}
