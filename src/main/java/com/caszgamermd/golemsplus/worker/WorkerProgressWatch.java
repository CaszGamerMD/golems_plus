package com.caszgamermd.golemsplus.worker;

/** Measures progress toward the current physical path node, not body motion.
 * Oscillation and jumping against a wall must eventually trigger replanning.
 * This tracker is transient and independent for each worker.
 */
final class WorkerProgressWatch {
    private boolean tracking;
    private int x,y,z,stalled;
    private double bestDistance;

    int sample(int nodeX,int nodeY,int nodeZ,double distance,int elapsed){
        if(!tracking||x!=nodeX||y!=nodeY||z!=nodeZ){
            tracking=true;
            x=nodeX;y=nodeY;z=nodeZ;
            bestDistance=distance;stalled=0;
        }else if(distance<=bestDistance-.08){
            // Accumulate small forward steps until they are meaningful;
            // never move the baseline backwards when the body bounces.
            bestDistance=distance;stalled=0;
        }else stalled+=elapsed;
        return stalled;
    }

    void reset(){tracking=false;stalled=0;}
}
