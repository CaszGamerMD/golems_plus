package com.caszgamermd.golemsplus.builder;

/** Bounded watchdog for motion toward a fixed phase endpoint. */
final class BuilderRouteProgress {
    private final double minimumProgress;
    private double best;
    private int stalledTicks;

    BuilderRouteProgress(double minimumProgress){
        this.minimumProgress=minimumProgress;
    }
    void reset(double distance){best=distance;stalledTicks=0;}
    void sample(double distance){
        if(distance<best-minimumProgress){
            best=distance;
            stalledTicks=0;
        }else stalledTicks++;
    }
    int stalledTicks(){return stalledTicks;}
}
