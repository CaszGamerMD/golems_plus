package com.caszgamermd.golemsplus.builder;

import java.util.*;
import net.minecraft.core.BlockPos;

/** Rotation-neutral, bounded ground staging points. No terrain assumptions. */
public final class BuilderNNStageGeometry {
    private BuilderNNStageGeometry(){}
    /** A close-by task can still be far above the depot, under a roof.
     * Stage on the REAL ground toward its exterior before retrying the
     * checked lift/landing planner. This never authorizes flight or work. */
    public static boolean needsElevatedApproach(BlockPos from,BlockPos target){
        double dx=(long)target.getX()-from.getX(),dz=(long)target.getZ()-from.getZ();
        double horizontal=Math.hypot(dx,dz);
        return target.getY()-from.getY()>=10&&horizontal>=8&&horizontal<18;
    }
    public static List<BlockPos> elevatedCandidates(BlockPos from,BlockPos target){
        if(!needsElevatedApproach(from,target))return List.of();
        double dx=(long)target.getX()-from.getX(),dz=(long)target.getZ()-from.getZ();
        double horizontal=Math.hypot(dx,dz);
        var out=new LinkedHashSet<BlockPos>();
        // Short bounded legs; actual terrain, route, collision and escape
        // are still certified by WorkerNavigation at every waypoint.
        for(int nominal:new int[]{10,7,5}){
            double forward=Math.min(nominal,horizontal-4);
            if(forward<4)continue;
            for(int offset:new int[]{0,3,-3}){
                int x=from.getX()+(int)Math.round((dx*forward-dz*offset)/horizontal);
                int z=from.getZ()+(int)Math.round((dz*forward+dx*offset)/horizontal);
                var at=new BlockPos(x,from.getY(),z);
                if(at.equals(from)||at.equals(target))continue;
                double remaining=Math.hypot(target.getX()-x,target.getZ()-z);
                if(at.distSqr(from)<=230&&remaining<horizontal-2&&remaining>=3)
                    out.add(at);
            }
        }
        return List.copyOf(out);
    }
    public static List<BlockPos> candidates(BlockPos from,BlockPos target){
        double dx=target.getX()-from.getX(),dz=target.getZ()-from.getZ();
        double horizontal=Math.hypot(dx,dz);
        if(horizontal<18)return List.of();
        var out=new LinkedHashSet<BlockPos>();
        for(int nominal:new int[]{12,9,6}){
            double forward=Math.min(nominal,horizontal-8);
            if(forward<4)continue;
            for(int offset:new int[]{0,3,-3}){
                int x=from.getX()+(int)Math.round((dx*forward-dz*offset)/horizontal);
                int z=from.getZ()+(int)Math.round((dz*forward+dx*offset)/horizontal);
                var at=new BlockPos(x,from.getY(),z);
                if(at.equals(from)||at.equals(target))continue;
                double remaining=Math.hypot(target.getX()-x,target.getZ()-z);
                if(remaining<horizontal-2&&remaining>=6)out.add(at);
            }
        }
        return List.copyOf(out);
    }
}
