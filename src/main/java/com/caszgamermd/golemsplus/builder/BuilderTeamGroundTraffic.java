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

    /** Validate every intermediate physical stance before steering from
     * the center of a completed 3x3/5x5 upper platform toward its edge.
     * The Builder may stand on same-level terrain or take a single
     * ordinary one-block drop, never a void gap or a two-block fall.
     * Checking the entire swept body prevents diagonal wall clipping.
     */
    private static boolean shallowWalk(ServerLevel level,BuilderGolem worker,
            BlockPos destination){
        double dx=destination.getX()+.5-worker.getX();
        double dz=destination.getZ()+.5-worker.getZ();
        int samples=Math.max(1,(int)Math.ceil(Math.hypot(dx,dz)*4));
        int y=worker.blockPosition().getY();
        for(int i=1;i<=samples;i++){
            double f=i/(double)samples;
            double x=dx*f,z=dz*f;
            if(!level.noCollision(worker,worker.getBoundingBox().move(x,0,z)))
                return false;
            BlockPos step=new BlockPos(
                    (int)Math.floor(worker.getX()+x),y,
                    (int)Math.floor(worker.getZ()+z));
            if(!level.hasChunkAt(step)||!level.hasChunkAt(step.below())
                    ||!level.hasChunkAt(step.below(2))
                    ||!WorkerNavigation.canStandOn(level,worker,step)
                        &&!WorkerNavigation.canStandOn(level,worker,step.below()))
                return false;
        }
        return true;
    }

    public static boolean recover(ServerLevel level,BuilderGolem worker,
            BlockPos depot){
        if(drops.containsKey(worker))return true;
        BlockPos here=worker.blockPosition();
        if(!worker.onGround()||!level.hasChunkAt(here)
                ||!level.getFluidState(here).isEmpty())return false;
        BlockPos best=null;
        double score=Double.POSITIVE_INFINITY;
        // The golem may stand in the MIDDLE of a finished 3-5-wide
        // raised deck, so an immediate adjacent drop is impossible.
        // Search a small radius for a one-block-lower safe landing;
        // never attempt a direct cut-through-wall to reach it.
        for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++){
            if(dx==0&&dz==0||dx*dx+dz*dz>20)continue;
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
            if(!level.noCollision(worker,worker.getBoundingBox().move(vx,0,vz))
                    ||!shallowWalk(level,worker,at))continue;
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
