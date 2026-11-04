package com.caszgamermd.golemsplus.breakroom;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class BreakRoomAnimationTest {
    @Test void eatingHeadNodIsBriefInsteadOfContinuous() {
        assertTrue(BreakRoomAnimation.eatingHeadNod(0.0f) > 0.09f,
                "The bite can have a small nod at its peak");
        assertEquals(0.0f, BreakRoomAnimation.eatingHeadNod(7.0f), 0.0001f,
                "The head should settle between bites");
        assertEquals(0.0f, BreakRoomAnimation.eatingHeadNod(15.0f), 0.0001f,
                "The head should be still on the opposite side of the cycle");
    }

    @Test void eatingHeadNodRepeatsAtTheBiteCadenceAndStaysSubtle() {
        assertEquals(BreakRoomAnimation.eatingHeadNod(0.0f),
                BreakRoomAnimation.eatingHeadNod(30.0f), 0.0001f,
                "Each bite cycle is 30 ticks");
        for (int tick = 0; tick < 30; tick++) {
            float nod = BreakRoomAnimation.eatingHeadNod(tick);
            assertTrue(nod >= 0.0f && nod <= 0.10f,
                    "Nod angle must stay small at tick " + tick + ": " + nod);
        }
    }
}
