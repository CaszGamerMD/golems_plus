package com.caszgamermd.golemsplus.builder;

/**
 * Optional, bounded planner heuristics for the physical dome learning harness.
 * These values ONLY rank already-valid, collision-checked construction poses.
 * They cannot disable reach, safe exit, line-of-sight, Y-layer gating or A*.
 * Unset or malformed environment variables preserve the existing behavior.
 */
final class BuilderFrontierTuning {
    static final int SCAN_LIMIT = integer("GOLEMS_DOME_SCAN_LIMIT",32,8,128);
    static final int FEASIBLE_LIMIT = integer("GOLEMS_DOME_FEASIBLE_LIMIT",5,1,16);
    static final double SKY_PENALTY = decimal("GOLEMS_DOME_SKY_PENALTY",12,0,24);
    static final double GRAPPLE_PENALTY = decimal("GOLEMS_DOME_GRAPPLE_PENALTY",24,0,48);
    static final double TARGET_WEIGHT = decimal("GOLEMS_DOME_TARGET_WEIGHT",.65,0,2);

    static {
        if ("1".equals(System.getenv("GOLEMS_DOME_TRAINING")))
            System.out.println("[DOME TUNING] scan="+SCAN_LIMIT
                    +" feasible="+FEASIBLE_LIMIT+" sky="+SKY_PENALTY
                    +" grapple="+GRAPPLE_PENALTY+" target="+TARGET_WEIGHT);
    }

    private BuilderFrontierTuning(){}

    private static int integer(String key,int fallback,int low,int high) {
        try {
            int value=Integer.parseInt(System.getenv(key));
            return value>=low&&value<=high?value:fallback;
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private static double decimal(String key,double fallback,double low,double high) {
        try {
            double value=Double.parseDouble(System.getenv(key));
            return Double.isFinite(value)&&value>=low&&value<=high?value:fallback;
        } catch (RuntimeException ex) {
            return fallback;
        }
    }
}
