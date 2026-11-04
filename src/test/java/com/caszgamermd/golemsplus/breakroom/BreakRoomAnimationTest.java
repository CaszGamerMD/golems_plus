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

    @Test void eatingHeadNodRepeatsEverySixSecondsAndStaysSubtle() {
        assertEquals(BreakRoomAnimation.eatingHeadNod(0.0f),
                BreakRoomAnimation.eatingHeadNod(120.0f), 0.0001f,
                "The head nod repeats every 120 ticks");
        assertEquals(0.0f,BreakRoomAnimation.eatingHeadNod(30.0f),0.0001f);
        assertEquals(0.0f,BreakRoomAnimation.eatingHeadNod(60.0f),0.0001f);
        assertEquals(0.0f,BreakRoomAnimation.eatingHeadNod(90.0f),0.0001f);
        for (int tick = 0; tick < 120; tick++) {
            float nod = BreakRoomAnimation.eatingHeadNod(tick);
            assertTrue(nod >= 0.0f && nod <= 0.10f,
                    "Nod angle must stay small at tick " + tick + ": " + nod);
        }
    }
}
