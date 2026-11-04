package com.caszgamermd.golemsplus.breakroom;

/**
 * Small, infrequent break-room animation accents shared by profession models.
 */
public final class BreakRoomAnimation {
    private static final int BITE_CYCLE_TICKS = 120;
    private static final float BITE_CYCLE_RADIANS =
            (float)(Math.PI * 2.0 / BITE_CYCLE_TICKS);
    private static final float NOD_THRESHOLD = 0.965f;
    private static final float NOD_RANGE = 1.0f - NOD_THRESHOLD;
    private static final float NOD_ANGLE = 0.10f;

    private BreakRoomAnimation() {}

    /**
     * Returns one brief, subtle nod every 120 ticks (six seconds), with the
     * rest of the eating animation keeping the head still. The smooth pulse
     * avoids a constant forward tilt or rapid bobbing on every render frame.
     */
    public static float eatingHeadNod(float ageInTicks) {
        float wave = (float)Math.cos(ageInTicks * BITE_CYCLE_RADIANS);
        if (wave <= NOD_THRESHOLD) return 0.0f;

        float amount = (wave - NOD_THRESHOLD) / NOD_RANGE;
        float eased = amount * amount * (3.0f - 2.0f * amount);
        return NOD_ANGLE * eased;
    }
}
