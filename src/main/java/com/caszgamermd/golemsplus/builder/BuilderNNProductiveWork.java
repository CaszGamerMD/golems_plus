package com.caszgamermd.golemsplus.builder;

/**
 * Opt-in construction throughput rules. Navigation success is useful only
 * after the golem can physically place and verify the next schematic block.
 * These policies never authorize a route, placement, material debit, or claim.
 */
final class BuilderNNProductiveWork {
    static final boolean ENABLED =
            "true".equalsIgnoreCase(System.getenv("GOLEMS_NN_PRODUCTIVE_BUILD"));

    /**
     * A fully checked candidate that can be built from the CURRENT pose
     * costs no travel. Rank those by hand distance, with a small locality
     * term to keep a continuous strip rather than hopping across the roof.
     */
    static double samePoseScore(double handDistanceSquared,
            double previousHorizontalDistanceSquared) {
        return handDistanceSquared + .05 * previousHorizontalDistanceSquared;
    }

    /**
     * Exact navigation already enforces its short route retry interval.
     * Four independently rejected requests over at least ten ticks warrant
     * handing the unchanged cell to another worker rather than holding up
     * the crew until the general 120/240-tick work watchdog expires.
     */
    static boolean exhaustedRejectedRoute(int attempts,long elapsedTicks) {
        return attempts >= 4 && elapsedTicks >= 10;
    }

    /**
     * Reaching an approximate location is not a usable construction pose.
     * Once safe exact work-stance and paid access alternatives have failed
     * for five seconds of game time, hand the unchanged cell to another
     * worker. The normal watchdog handles real moving routes separately.
     */
    static boolean missingWorkPoseTimedOut(long elapsedTicks) {
        return elapsedTicks >= 100;
    }

    private BuilderNNProductiveWork() {}
}
