package com.caszgamermd.golemsplus.builder;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class BuilderScaffoldGeometryTest {
    @Test void descentRequiresWholeBodyInsideColumn(){
        assertTrue(BuilderScaffoldGeometry.centered(.15,-.15,.65));
        assertFalse(BuilderScaffoldGeometry.centered(.18,0,.65));
        assertFalse(BuilderScaffoldGeometry.centered(0,-.18,.65));
    }
    @Test void descentIsMonotonicAndStopsAtGround(){
        double y=48;
        for(int tick=0;tick<301;tick++){
            double step=BuilderScaffoldGeometry.downStep(y);
            assertTrue(step<=0&&step>=-.16);
            y+=step;
            assertTrue(y>=0);
        }
        assertEquals(0,y,1e-9);
        assertEquals(0,BuilderScaffoldGeometry.downStep(-1),1e-9);
    }
    @Test void brokenOrTooTallTowerIsNotADescentRoute(){
        assertTrue(BuilderScaffoldGeometry.completeColumn(64,74,y->true));
        assertFalse(BuilderScaffoldGeometry.completeColumn(64,74,y->y!=69));
        assertFalse(BuilderScaffoldGeometry.completeColumn(64,64,y->true));
        assertFalse(BuilderScaffoldGeometry.completeColumn(64,113,y->true));
        assertTrue(BuilderScaffoldGeometry.completeColumn(64,112,y->true));
    }
}
