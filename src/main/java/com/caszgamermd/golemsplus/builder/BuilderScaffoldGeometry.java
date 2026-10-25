package com.caszgamermd.golemsplus.builder;

import java.util.function.IntPredicate;

/** Geometry shared by column validation and the physical descent controller. */
public final class BuilderScaffoldGeometry {
    private BuilderScaffoldGeometry() {}
    public static boolean centered(double dx,double dz,double width){
        double margin=Math.max(0,(1-width)*.5-.02);
        return Math.abs(dx)<=margin&&Math.abs(dz)<=margin;
    }
    public static double downStep(double distanceToGround){
        return -Math.min(.16,Math.max(0,distanceToGround));
    }
    public static boolean completeColumn(int baseY,double feetY,IntPredicate scaffoldAt){
        int last=(int)Math.ceil(feetY)-1;
        if(last<baseY||last-baseY>=48)return false;
        for(int y=baseY;y<=last;y++)if(!scaffoldAt.test(y))return false;
        return true;
    }
}
