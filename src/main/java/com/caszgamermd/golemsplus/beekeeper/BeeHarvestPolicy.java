package com.caszgamermd.golemsplus.beekeeper;

/** Pure decision for which real tool and output a worker should reserve. */
public final class BeeHarvestPolicy {
    public enum Choice { COMB, BOTTLE, WAIT }
    private BeeHarvestPolicy(){}
    /** 0 = both, 1 = comb, 2 = honey bottle. Never manufacture stock. */
    public static Choice choose(int mode,boolean hasShears,boolean hasBottle,
            boolean combFits,boolean bottleFits){
        if(mode<0||mode>2)throw new IllegalArgumentException("Unknown harvest mode");
        if(mode!=2&&hasShears&&combFits)return Choice.COMB;
        if(mode!=1&&hasBottle&&bottleFits)return Choice.BOTTLE;
        return Choice.WAIT;
    }
}
