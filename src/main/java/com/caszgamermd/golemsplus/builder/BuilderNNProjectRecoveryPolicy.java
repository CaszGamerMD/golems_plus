package com.caszgamermd.golemsplus.builder;

/** Safety gate for the opt-in partial crew handoff experiment.
 * A timed-out route search is not evidence of impossible geometry.
 * Never advance an unfinished block or abandon cargo. */
public final class BuilderNNProjectRecoveryPolicy {
    public static final long MIN_RETURN_TICKS=600;
    private BuilderNNProjectRecoveryPolicy(){}
    public static boolean enabled(){
        return Boolean.parseBoolean(System.getProperty("golems.nn.partialYield",
            System.getenv().getOrDefault("GOLEMS_NN_PARTIAL_YIELD","false")));
    }
    public static boolean ready(boolean pending,boolean all,long elapsed,int unloaded){
        return pending&&(all||elapsed>=MIN_RETURN_TICKS&&unloaded>0);
    }
}
