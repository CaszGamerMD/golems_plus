package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Four-Builder supply traffic recovery. If native A* cannot start from a
 * completed wall/ledge one block ABOVE the floor, a worker may step off
 * toward an adjacent verified landing and let normal gravity lower it.
 * It does not use the scaffold controller or move through world blocks.
 */
public final class BuilderTeamGroundTraffic {
    private record Drop(BlockPos landing,long deadline){}
    private static final Map<BuilderGolem,Drop> drops=new WeakHashMap<>();
    private BuilderTeamGroundTraffic(){}

    public static boolean controlling(BuilderGolem worker){
        return drops.containsKey(worker);
    }
    public static void cancel(BuilderGolem worker){drops.remove(worker);}

    public static boolean recover(ServerLevel level,BuilderGolem worker,
            BlockPos depot){
        if(drops.containsKey(worker))return true;
        BlockPos here=worker.blockPosition();
        if(!worker.onGround()||!level.hasChunkAt(here)
                ||!level.getFluidState(here).isEmpty())return false;
        BlockPos best=null;
        double score=Double.POSITIVE_INFINITY;
        // Moving to the open tile must be a genuine <=1-block DROP,
        // with a safe, loaded support floor and 2-block head clearance.
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
            if(dx==0&&dz==0)continue;
            BlockPos at=here.offset(dx,-1,dz);
            if(!level.hasChunkAt(at)||!level.hasChunkAt(at.below())
                    ||!level.hasChunkAt(at.above())
                    ||!level.getFluidState(at).isEmpty())continue;
            if(!WorkerNavigation.canStandOn(level,worker,at))continue;
            BlockState upper=level.getBlockState(at.above());
            if(!upper.getCollisionShape(level,at.above()).isEmpty()
                    ||!level.getBlockState(at.above().above())
                            .getCollisionShape(level,at.above().above()).isEmpty())
                continue;
            // The first sideways movement must be collision free at the
            // actual current Y; no lateral push through an overhang.
            double x=at.getX()+.5-worker.getX();
            double z=at.getZ()+.5-worker.getZ();
            double dist=Math.sqrt(x*x+z*z);
            if(dist<.01)continue;
            double vx=x/dist*Math.min(.12,dist);
            double vz=z/dist*Math.min(.12,dist);
            if(!level.noCollision(worker,worker.getBoundingBox().move(vx,0,vz)))
                continue;
            double value=at.distSqr(depot)*.10+dist;
            if(value<score){score=value;best=at;}
        }
        if(best==null)return false;
        WorkerNavigation.stop(worker); // release any obsolete door reservation
        drops.put(worker,new Drop(best,level.getGameTime()+90));
        return true;
    }

    /** Entity-tick physical movement with gravity. Never jump, teleport,
     * suppress collisions or repeatedly replan an oscillating landing.
     */
    public static boolean tickMotion(ServerLevel level,BuilderGolem worker){
        Drop drop=drops.get(worker);
        if(drop==null)return false;
        BlockPos p=drop.landing();
        if(level.getGameTime()>drop.deadline()
                ||!WorkerNavigation.canStandOn(level,worker,p)){
            drops.remove(worker);return false;
        }
        double x=p.getX()+.5-worker.getX();
        double z=p.getZ()+.5-worker.getZ();
        if(x*x+z*z<.10&&worker.getY()<=p.getY()+.12){
            drops.remove(worker);
            worker.getNavigation().stop();
            return false;
        }
        worker.getNavigation().stop();
        worker.setNoGravity(false);
        double length=Math.sqrt(x*x+z*z);
        double vx=length<.001?0:x/length*Math.min(.12,length);
        double vz=length<.001?0:z/length*Math.min(.12,length);
        if(level.noCollision(worker,worker.getBoundingBox().move(vx,0,vz))){
            var previous=worker.getDeltaMovement();
            worker.setDeltaMovement(vx,previous.y,vz);
        }
        return true;
    }
}
