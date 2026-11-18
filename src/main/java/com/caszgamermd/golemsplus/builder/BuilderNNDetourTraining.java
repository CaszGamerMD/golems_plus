package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * NN-only evidence for REAL detours. Straight-line distance to the intended
 * block is not a progress measure around walls, roof lips or over a ridge.
 * Movement is credited only if it traverses NEW supported physical cells on
 * a continuing valid ground / supported multi-leg route.
 *
 * Neither this observer nor the neural network is allowed to create routes,
 * teleport, invent foot support, or skip any collision validation. Bounded
 * timers and unique-cell evidence prevent endless circling.
 */
final class BuilderNNDetourTraining {
    static final boolean ENABLED=Boolean.parseBoolean(System.getProperty(
            "golems.nn.detourTraining",
            System.getenv().getOrDefault("GOLEMS_NN_DETOUR_TRAINING","false")));
    static final long MAX_DETOUR_TICKS=1600;
    static final long MAX_NO_NEW_CELL_TICKS=120;
    static final int MAX_SEEN=2048;
    private static final Map<UUID,Episode> episodes=new HashMap<>();
    private static int successes,failures,credited;
    private static final class Episode {
        final BlockPos target;
        final Vec3 origin;
        final long start;
        final Set<BlockPos> unique=new HashSet<>();
        Vec3 previous;
        long lastUnique;
        double totalDistance,maxRise,maxGoalDistance;
        int overshootX,overshootZ,routeSteps;
        boolean credited;
        Episode(BlockPos target,Vec3 from,long now){
            this.target=target.immutable();origin=from;previous=from;
            start=now;lastUnique=now;
            maxGoalDistance=from.distanceTo(Vec3.atCenterOf(target));
        }
    }
    record Snapshot(long ticks,int unique,double distance,double maxRise,
            boolean beyondX,boolean beyondZ,boolean fartherFromGoal,
            int certifiedObservations){}
    private static boolean beyond(double start,double target,double current){
        return (start<target-0.75&&current>target+0.75)
                ||(start>target+0.75&&current<target-0.75);
    }
    static boolean mayContinue(long now,long start,long lastNew,int unique,
            boolean physicalRoute){
        return physicalRoute&&unique>=2
                &&now>=start&&now-start<=MAX_DETOUR_TICKS
                &&now>=lastNew&&now-lastNew<MAX_NO_NEW_CELL_TICKS;
    }
    /**
     * This observes only the worker's ACTUAL motion. It never assumes an
     * unexecuted path is progress. Physical-route certification is read only.
     * Passing the target X/Z and climbing above its Y are both legitimate.
     */
    static boolean observe(ServerLevel level,BuilderGolem worker,BlockPos target){
        if(!ENABLED||target==null)return false;
        long now=level.getGameTime();
        Episode e=episodes.get(worker.getUUID());
        if(e==null||!e.target.equals(target)){
            e=new Episode(target,worker.position(),now);
            episodes.put(worker.getUUID(),e);
        }
        Vec3 here=worker.position();
        double moved=here.distanceTo(e.previous);
        if(Double.isFinite(moved)&&moved>.01)e.totalDistance+=moved;
        e.previous=here;
        double dist=here.distanceTo(Vec3.atCenterOf(target));
        if(Double.isFinite(dist))e.maxGoalDistance=Math.max(e.maxGoalDistance,dist);
        e.maxRise=Math.max(e.maxRise,here.y-(target.getY()+.5));
        if(beyond(e.origin.x,target.getX()+.5,here.x))e.overshootX++;
        if(beyond(e.origin.z,target.getZ()+.5,here.z))e.overshootZ++;
        BlockPos tile=worker.blockPosition();
        if(e.unique.size()<MAX_SEEN&&e.unique.add(tile))e.lastUnique=now;
        BlockPos goal=WorkerNavigation.observedDestination(worker);
        boolean groundedRoute=goal!=null
                &&WorkerNavigation.hasPlannedGroundRoute(worker,goal)
                &&!worker.getNavigation().isDone();
        boolean multiLeg=BuilderMultiLegTravel.working(worker,target);
        // Flight has its own collision-verified commitment. Do not erase
        // work-progress evidence just because it temporarily rises above Y.
        boolean physical=groundedRoute||multiLeg
                ||BuilderFlight.roofAccessProgress(level,worker,target)
                ||BuilderFlight.moving(worker);
        if(physical)e.routeSteps++;
        boolean eligible=mayContinue(now,e.start,e.lastUnique,e.unique.size(),physical);
        if(eligible&&!e.credited){
            e.credited=true;credited++;
            System.out.println("[NN DETOUR ACCEPT] worker="+worker.getUUID()
                    +" target="+target+" hasVerifiedRoute="+physical
                    +" unique="+e.unique.size()+" walked="+e.totalDistance);
        }
        return eligible;
    }
    static Snapshot snapshot(ServerLevel level,BuilderGolem worker){
        Episode e=episodes.get(worker.getUUID());
        if(e==null)return null;
        return new Snapshot(Math.max(0,level.getGameTime()-e.start),
                e.unique.size(),e.totalDistance,e.maxRise,
                e.overshootX>0,e.overshootZ>0,
                e.maxGoalDistance>e.origin.distanceTo(Vec3.atCenterOf(e.target))+1.5,
                e.routeSteps);
    }
    /** Explicit handoff without an available entity reference. The
     * observed episode is labeled only if actual motion was sampled. */
    static void failed(UUID worker,long now,String reason){
        if(!ENABLED)return;
        Episode e=episodes.remove(worker);
        if(e==null)return;
        failures++;
        System.out.println("[NN DETOUR OUTCOME] worker="+worker+" target="+e.target
                +" success=false reason="+reason
                +" elapsedTicks="+Math.max(0,now-e.start)
                +" unique="+e.unique.size()+" traveled="+e.totalDistance
                +" climbedAbove="+(e.maxRise>1)
                +" pastX="+(e.overshootX>0)+" pastZ="+(e.overshootZ>0)
                +" movedAway="+(e.maxGoalDistance>
                    e.origin.distanceTo(Vec3.atCenterOf(e.target))+1.5)
                +" physicalRouteTicks="+e.routeSteps+" successes="+successes
                +" failures="+failures);
    }
    static void finish(ServerLevel level,BuilderGolem worker,
            BlockPos target,boolean success,String reason){
        if(!ENABLED)return;
        Episode e=episodes.remove(worker.getUUID());
        if(e==null||target!=null&&!target.equals(e.target))return;
        long ticks=Math.max(0,level.getGameTime()-e.start);
        if(success)successes++;else failures++;
        System.out.println("[NN DETOUR OUTCOME] worker="+worker.getUUID()
                +" target="+e.target+" success="+success
                +" reason="+reason+" elapsedTicks="+ticks
                +" unique="+e.unique.size()+" traveled="+e.totalDistance
                +" climbedAbove="+(e.maxRise>1)
                +" maxAboveObjective="+e.maxRise
                +" pastX="+(e.overshootX>0)+" pastZ="+(e.overshootZ>0)
                +" movedAway="+(e.maxGoalDistance>
                    e.origin.distanceTo(Vec3.atCenterOf(e.target))+1.5)
                +" physicalRouteTicks="+e.routeSteps
                +" detoursAccepted="+credited
                +" successes="+successes+" failures="+failures);
    }
    private BuilderNNDetourTraining(){}
}
