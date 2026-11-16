package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Opt-in, bounded staging before claiming a distant frontier cell.
 * Only traverses currently supported, collision-checked real terrain.
 * Neither marks construction complete nor moves cargo or claims.
 * All state is transient and belongs to the server thread. */
final class BuilderNNFrontierStaging {
    private static final long STAGE_DEADLINE=780, LEG_DEADLINE=160;
    private static final int MAX_STAGING_CREW=2;
    private static final Map<BuilderGolem,Leg> active=new WeakHashMap<>();
    private static final Map<BuilderGolem,Window> windows=new WeakHashMap<>();
    private static final class Window {
        final UUID job;
        final int structure,layer;
        final long began;
        long nextTry,lastNote=-1000000L;
        int legs;
        Window(UUID job,int structure,int layer,long began){
            this.job=job;this.structure=structure;this.layer=layer;this.began=began;
        }
    }
    private static final class Leg {
        final WorkerStationBlockEntity table;
        final UUID job;
        final int structure,layer,targetIndex;
        final BlockPos waypoint;
        final long expires;
        long lastAdvance;
        double best;
        Leg(WorkerStationBlockEntity table,UUID job,int structure,int layer,int targetIndex,
                BlockPos waypoint,long now,double distance){
            this.table=table;this.job=job;this.structure=structure;this.layer=layer;
            this.targetIndex=targetIndex;this.waypoint=waypoint.immutable();
            this.expires=now+LEG_DEADLINE;this.lastAdvance=now;this.best=distance;
        }
    }
    static boolean enabled(){
        return Boolean.parseBoolean(System.getProperty("golems.nn.frontierStaging",
                System.getenv().getOrDefault("GOLEMS_NN_FRONTIER_STAGING","false")));
    }
    static void clear(BuilderGolem worker){
        active.remove(worker);windows.remove(worker);
    }
    static boolean physicallyAdvancing(BuilderGolem worker,long now){
        Leg leg=active.get(worker);
        return leg!=null&&now<leg.expires&&now-leg.lastAdvance<=25
                &&worker.onGround();
    }
    private static boolean valid(WorkerStationBlockEntity table,List<BuilderConstructionPlan.Step> steps,Leg leg){
        return enabled()&&Objects.equals(leg.job,table.activeOrder())
                &&leg.layer==table.builderActiveLayer(steps)
                &&leg.structure==table.builderNNStructure(steps);
    }
    private static void note(Window window,long now,BuilderGolem worker,String reason){
        if(now-window.lastNote>=120){
            window.lastNote=now;
            System.out.println("[NN FRONTIER STAGE DIAG] worker="+worker.getUUID()
                    +" "+reason+" legs="+window.legs);
        }
    }
    /** Called before the routine work-claim search so a real walking
     * waypoint is not immediately cancelled by an idle return. */
    static boolean follow(ServerLevel level,BuilderGolem worker,WorkerStationBlockEntity table,
            List<BuilderConstructionPlan.Step> steps){
        Leg leg=active.get(worker);
        if(leg==null)return false;
        long now=level.getGameTime();
        if(!valid(table,steps,leg)||BuilderFlight.active(worker)
                ||BuilderGrappleAccess.active(worker)||BuilderScaffolding.isControllingMovement(worker)){
            active.remove(worker);return false;
        }
        // Ordinary path-following hops temporarily leave the ground;
        // do not cancel the entire staging leg halfway through a jump.
        if(!worker.onGround()){
            if(now-leg.lastAdvance<=90&&now<leg.expires)return true;
            active.remove(worker);return false;
        }
        double distance=worker.distanceToSqr(leg.waypoint.getX()+.5,
                leg.waypoint.getY(),leg.waypoint.getZ()+.5);
        if(distance<1.0){
            active.remove(worker);
            Window window=windows.get(worker);
            if(window!=null){window.legs++;window.nextTry=now;}
            System.out.println("[NN FRONTIER STAGE ARRIVED] worker="+worker.getUUID()
                    +" targetStep="+leg.targetIndex+" waypoint="+leg.waypoint);
            return false;
        }
        if(distance<leg.best-.25){leg.best=distance;leg.lastAdvance=now;}
        if(now>=leg.expires||now-leg.lastAdvance>90
                ||!WorkerNavigation.canStandOn(level,worker,leg.waypoint)
                ||!WorkerNavigation.routeToExact(level,worker,leg.waypoint,1.0)){
            active.remove(worker);
            Window window=windows.get(worker);
            if(window!=null)window.nextTry=now+60;
            System.out.println("[NN FRONTIER STAGE STOP] worker="+worker.getUUID()
                    +" targetStep="+leg.targetIndex+" waypoint="+leg.waypoint
                    +" bestSqr="+leg.best+" elapsed="+(LEG_DEADLINE-(leg.expires-now)));
            return false;
        }
        return true;
    }
    /** A near local waypoint, not a fake distant route certificate. */
    static boolean start(ServerLevel level,BuilderGolem worker,WorkerStationBlockEntity table,
            List<BuilderConstructionPlan.Step> steps,List<Integer> zone){
        if(!enabled()||zone.isEmpty()||!worker.onGround()||BuilderFlight.active(worker)
                ||BuilderGrappleAccess.active(worker)||BuilderScaffolding.isControllingMovement(worker)
                ||worker.hasSupplyBox()||BuilderCrewRecovery.sidelined(table,worker.getUUID())
                ||table.claimedStep(worker.getUUID())>=0)return false;
        long now=level.getGameTime();int layer=table.builderActiveLayer(steps);
        int structure=table.builderNNStructure(steps);
        Window window=windows.get(worker);
        if(window==null||!Objects.equals(window.job,table.activeOrder())
                ||window.layer!=layer||window.structure!=structure){
            window=new Window(table.activeOrder(),structure,layer,now);
            windows.put(worker,window);
        }
        if(now<window.nextTry||now-window.began>=STAGE_DEADLINE)return false;
        int staged=0;
        for(var leg:active.values())if(leg.table==table&&now<leg.expires)staged++;
        if(staged>=MAX_STAGING_CREW)return false;
        BlockPos from=worker.blockPosition();
        var pending=new ArrayList<Integer>();
        for(int i:zone){
            if(i<0||i>=steps.size()||!table.builderStepInActivePhase(steps,i)
                    ||!table.builderStepReadyForClaim(i))continue;
            var s=steps.get(i);
            if(s.ignorePlant()||BuilderNNStructures.manualTail(s))continue;
            double dx=(long)s.x()-from.getX(),dz=(long)s.z()-from.getZ();
            if(dx*dx+dz*dz>=18*18)pending.add(i);
        }
        if(pending.isEmpty()){
            note(window,now,worker,"no distant unfinished work in assigned active phase"
                    +" zone="+zone.size()+" Y="+layer);
            return false;
        }
        pending.sort(Comparator.comparingDouble(i->{
            var s=steps.get(i);
            double dx=(long)s.x()-from.getX(),dz=(long)s.z()-from.getZ();
            return dx*dx+dz*dz;
        }));
        int routeAttempts=0;
        for(int k=0;k<Math.min(3,pending.size());k++){
            int index=pending.get(k);var step=steps.get(index);
            var work=new BlockPos(step.x(),step.y(),step.z());
            for(BlockPos seed:BuilderNNStageGeometry.candidates(from,work)){
                for(int dy:new int[]{0,1,-1}){
                    BlockPos candidate=seed.offset(0,dy,0);
                    if(!level.hasChunkAt(candidate)
                            ||!WorkerNavigation.canStandOn(level,worker,candidate))continue;
                    if(++routeAttempts>4)break;
                    if(!WorkerNavigation.routeToExact(level,worker,candidate,1.0))continue;
                    double distance=worker.distanceToSqr(candidate.getX()+.5,
                            candidate.getY(),candidate.getZ()+.5);
                    active.put(worker,new Leg(table,table.activeOrder(),structure,layer,
                            index,candidate,now,distance));
                    window.nextTry=now+30;
                    System.out.println("[NN FRONTIER STAGE START] worker="+worker.getUUID()
                            +" project="+(structure+1)+" layer="+layer+" targetStep="+index
                            +" source="+from+" waypoint="+candidate
                            +" remaining="+String.format(java.util.Locale.ROOT,"%.1f",
                                Math.hypot(work.getX()-candidate.getX(),
                                    work.getZ()-candidate.getZ())));
                    return true;
                }
                if(routeAttempts>=4)break;
            }
            if(routeAttempts>=4)break;
        }
        window.nextTry=now+60;
        note(window,now,worker,"no safely executable short waypoint"
                +" phase="+layer+" pending="+pending.size()+" tested="+routeAttempts);
        return false;
    }
}
