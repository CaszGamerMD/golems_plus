package com.caszgamermd.golemsplus.builder;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.phys.Vec3;

/** Classifies actual movement across one complete, uninterrupted sample window. */
final class MatrixMovementWindow {
    enum Stall { MOVING, STATIONARY, BACK_AND_FORTH }

    private static final double STATIONARY_RADIUS_SQUARED=.25*.25;
    private static final double REPEATED_PATH_RADIUS_SQUARED=.65*.65;
    private final int sampleTicks,windowTicks;
    private final List<Vec3> samples=new ArrayList<>();
    private long lastTick=Long.MIN_VALUE;

    MatrixMovementWindow(int sampleTicks,int windowTicks){
        this.sampleTicks=sampleTicks;
        this.windowTicks=windowTicks;
    }

    void sample(long tick,Vec3 position,boolean present){
        if(!present||lastTick!=tick-sampleTicks)samples.clear();
        lastTick=tick;
        if(!present)return;
        samples.add(position);
        if(samples.size()>windowTicks/sampleTicks+1)samples.removeFirst();
    }

    Stall stall(){
        if(samples.size()<=windowTicks/sampleTicks)return Stall.MOVING;
        Vec3 first=samples.getFirst();
        Vec3 far=first;
        for(Vec3 point:samples)
            if(point.distanceToSqr(first)>far.distanceToSqr(first))far=point;
        if(far.distanceToSqr(first)<=STATIONARY_RADIUS_SQUARED)
            return Stall.STATIONARY;

        // Look for repeated traversals between opposite ends of the
        // route. Compare spatial paths so different walking speeds or
        // brief pauses at either end do not hide a back-and-forth stall.
        Vec3 axis=far.subtract(first).normalize();
        double min=0,max=0;
        for(Vec3 point:samples){
            double along=point.subtract(first).dot(axis);
            min=Math.min(min,along);
            max=Math.max(max,along);
        }
        double margin=(max-min)*.25;
        int previousEnd=0,crossings=0;
        for(Vec3 point:samples){
            double along=point.subtract(first).dot(axis);
            int end=along<=min+margin?-1:along>=max-margin?1:0;
            if(end==0||end==previousEnd)continue;
            if(previousEnd!=0)crossings++;
            previousEnd=end;
        }
        if(crossings<3)return Stall.MOVING;
        int middle=samples.size()/2;
        return repeats(0,middle,middle,samples.size()-1)
                &&repeats(middle,samples.size()-1,0,middle)
                ?Stall.BACK_AND_FORTH:Stall.MOVING;
    }

    private boolean repeats(int start,int end,int otherStart,int otherEnd){
        for(int i=start;i<=end;i++){
            Vec3 point=samples.get(i);
            boolean seen=false;
            for(int j=otherStart;j<otherEnd&&!seen;j++){
                Vec3 base=samples.get(j);
                Vec3 segment=samples.get(j+1).subtract(base);
                double length=segment.lengthSqr();
                double fraction=length==0?0:Math.clamp(
                        point.subtract(base).dot(segment)/length,0.0,1.0);
                seen=point.distanceToSqr(base.add(segment.scale(fraction)))
                        <=REPEATED_PATH_RADIUS_SQUARED;
            }
            if(!seen)return false;
        }
        return true;
    }
}
