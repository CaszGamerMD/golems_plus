package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import java.util.*;

/** Physical retreat barrier: no completed cells, cargo or workers are lost. */
final class BuilderCrewRecovery {
    private static final int STALL=240, RETURN_BUDGET=1800, RESUME_GRACE=400;
    private static final class State {
        UUID job;
        int layer=Integer.MIN_VALUE,best=-1,attempts;
        long lastProgress,started,graceUntil;
        boolean recalling;
        List<UUID> reduced=List.of();
        final Set<UUID> unloaded=new HashSet<>();
        final Map<UUID,Long> retry=new HashMap<>(), lastReturnProgress=new HashMap<>();
        final Map<UUID,Double> bestReturnDistance=new HashMap<>();
        final Map<UUID,BlockPos> parking=new HashMap<>();
    }
    private static final Map<WorkerStationBlockEntity,State> states=new WeakHashMap<>();
    private static State state(WorkerStationBlockEntity table){
        State s=states.computeIfAbsent(table,k->new State());
        if(!Objects.equals(s.job,table.activeOrder())){
            s=new State();s.job=table.activeOrder();states.put(table,s);
        }
        return s;
    }
    static String traceStatus(WorkerStationBlockEntity table,BuilderGolem worker){
        State s=states.get(table);
        if(s==null)return "none";
        return "recall="+s.recalling+" attempt="+s.attempts+" layer="+s.layer
                +" unloaded="+s.unloaded.contains(worker.getUUID())
                +" barrier="+s.unloaded.size()+"/"+table.builderWorkerIds().size()
                +" reduced="+s.reduced.contains(worker.getUUID());
    }
    static void clear(WorkerStationBlockEntity table){states.remove(table);}
    static boolean recalling(WorkerStationBlockEntity table){return state(table).recalling;}
    static boolean assessmentGrace(ServerLevel level,WorkerStationBlockEntity table){
        State s=state(table);long now=level.getGameTime();
        return s.recalling&&now-s.started<RETURN_BUDGET||now<s.graceUntil;
    }
    static List<UUID> staff(WorkerStationBlockEntity table,int layer,List<UUID> normal){
        State s=state(table);
        return s.layer==layer&&!s.reduced.isEmpty()?s.reduced:normal;
    }
    static boolean sidelined(WorkerStationBlockEntity table,UUID worker){
        State s=state(table);
        return !s.recalling&&!s.reduced.isEmpty()&&!s.reduced.contains(worker);
    }
    static int remaining(WorkerStationBlockEntity table,List<BuilderConstructionPlan.Step> steps,int layer){
        int count=0;
        for(int i=0;i<steps.size();i++){
            var step=steps.get(i);
            if(step.y()==layer&&!step.ignorePlant()
                    &&!BuilderDeferredState.verification(step.encodedState())
                    &&!table.builderStepCompletedForMaterials(i))count++;
        }
        return count;
    }
    static void begin(ServerLevel level,WorkerStationBlockEntity table,List<BuilderConstructionPlan.Step> steps){
        State s=state(table);if(s.recalling)return;
        s.layer=table.builderActiveLayer(steps);s.best=table.builderWorkDone();
        s.lastProgress=level.getGameTime();s.started=s.lastProgress;
        s.attempts++;s.recalling=true;s.graceUntil=0;
        s.unloaded.clear();s.retry.clear();s.parking.clear();
        s.lastReturnProgress.clear();s.bestReturnDistance.clear();
        BuilderTrace.dump(level,table,"before crew retreat");
        table.beginBuilderZonePlan();
        BuilderMaterialNetwork.resetForCrew(table);
        BuilderBuildService.resetCrewActions(level,table);
        table.setProblem("Crew recovery: all Builders return to OnSite and unload");
        System.out.println("[CREW RETREAT START] layer="+s.layer+" attempt="+s.attempts
                +" remaining="+remaining(table,steps,s.layer)+" crew="+table.builderWorkerIds().size());
    }
    static boolean tick(ServerLevel level,BuilderGolem worker,WorkerStationBlockEntity table,
            List<BuilderConstructionPlan.Step> steps){
        State s=state(table);long now=level.getGameTime();
        int layer=table.builderActiveLayer(steps),work=table.builderWorkDone();
        if(!s.recalling){
            if(layer!=s.layer){
                s.layer=layer;s.attempts=0;s.reduced=List.of();
                s.parking.clear();s.best=work;s.lastProgress=now;s.graceUntil=0;
            }
            if(work>s.best){s.best=work;s.lastProgress=now;}
            if(layer==Integer.MAX_VALUE)return false;
            if(s.attempts<2&&now>=s.graceUntil-40&&now-s.lastProgress>=STALL
                    &&remaining(table,steps,layer)>0){
                begin(level,table,steps);s=state(table);
            }
        }
        if(s.recalling){
            s.unloaded.retainAll(table.builderWorkerIds());
            if(!s.unloaded.contains(worker.getUUID())){
                if(BuilderFlight.moving(worker)||BuilderGrappleAccess.active(worker)
                        ||BuilderScaffolding.isControllingMovement(worker))return true;
                if(!BuilderBuildService.parkAtOnSite(level,worker,table)){
                    BlockPos depot=table.builderUnloadingStorage();
                    UUID id=worker.getUUID();
                    if(depot!=null){
                        double distance=worker.distanceToSqr(depot.getX()+.5,depot.getY(),depot.getZ()+.5);
                        if(distance<s.bestReturnDistance.getOrDefault(id,Double.POSITIVE_INFINITY)-.25){
                            s.bestReturnDistance.put(id,distance);s.lastReturnProgress.put(id,now);
                        }
                        long last=s.lastReturnProgress.computeIfAbsent(id,k->now);
                        // Do not replace a healthy return path with an
                        // escape probe merely because 80 ticks elapsed.
                        if(!BuilderEscapeBox.escaping(worker)&&now-last>=160&&now>=s.retry.getOrDefault(id,0L)){
                            s.retry.put(id,now+120);
                            BuilderReturnService.resetApproach(worker);
                            // Return service selects escapes using its persistent landing history.
                        }
                    }
                    if(now-s.started>=RETURN_BUDGET)
                        table.setProblem("Crew recovery: waiting for trapped Builder "+worker.getName().getString()+" to reach OnSite");
                    return true;
                }
                BuilderScaffolding.retrieve(level,worker,table,false);
                if(worker.hasSupplyBox()){
                    if(!BuilderSupplyInventory.depositBox(table,worker.supplyBox())){
                        table.setProblem("Crew recovery: make room for returned shulker");return true;
                    }
                    worker.clearSupplyBox();
                }
                if(!BuilderDroppedBlockRecovery.unloadRecoveredAtSite(worker,table)
                        ||!BuilderSupplyInventory.returnPouch(table,worker)){
                    table.setProblem("Crew recovery: make room in OnSite for returned pockets");return true;
                }
                if(!worker.isEmpty()||worker.hasSupplyBox())return true;
                BuilderTrace.decision(level,worker,"CREW_UNLOADED",table.builderUnloadingStorage(),"physical return and empty inventory verified");
                s.unloaded.add(worker.getUUID());
                System.out.println("[CREW RETREAT UNLOADED] "+worker.getUUID()+" at="+worker.blockPosition());
            }
            List<UUID> live=table.builderWorkerIds();
            if(!s.unloaded.containsAll(live)){
                parkAway(level,worker,table,s);return true;
            }
            int active=table.builderActiveLayer(steps),count=remaining(table,steps,active);
            int cap=s.attempts>=2||count<=5?1:2;
            int n=Math.min(Math.min(cap,table.crewTarget()),live.size());
            List<UUID> chosen=new ArrayList<>();
            if(active==s.layer&&n>0){
                int start=s.attempts>=2&&live.size()>1?1:0;
                for(int i=0;i<n;i++)chosen.add(live.get((start+i)%live.size()));
            }
            s.reduced=List.copyOf(chosen);s.recalling=false;
            s.best=table.builderWorkDone();s.lastProgress=now;s.graceUntil=now+RESUME_GRACE;
            table.beginBuilderZonePlan();
            BuilderMaterialNetwork.resetForCrew(table);
            BuilderBuildService.resetCrewActions(level,table);
            table.setProblem("Crew recovery: resuming remaining layer with "+(chosen.isEmpty()?live.size():chosen.size())+" Builders");
            System.out.println("[CREW RETREAT RESUME] layer="+active+" remaining="+count
                    +" active="+chosen+" unloaded="+s.unloaded.size());
            return true;
        }
        if(!s.reduced.isEmpty()&&!s.reduced.contains(worker.getUUID())){
            parkAway(level,worker,table,s);return true;
        }
        return false;
    }
    private static void parkAway(ServerLevel level,BuilderGolem worker,WorkerStationBlockEntity table,State s){
        BlockPos depot=table.builderUnloadingStorage();
        if(depot==null)return;
        BlockPos stand=s.parking.get(worker.getUUID());
        if(stand==null||!WorkerNavigation.canStandOn(level,worker,stand)){
            var choices=new ArrayList<BlockPos>();
            for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++){
                if(Math.max(Math.abs(dx),Math.abs(dz))!=3)continue;
                BlockPos at=depot.offset(dx,0,dz);
                if(!s.parking.containsValue(at)&&WorkerNavigation.canStandOn(level,worker,at))choices.add(at);
            }
            stand=WorkerNavigation.selectExecutableStand(level,worker,choices);
            if(stand==null){WorkerNavigation.stop(worker);return;}
            s.parking.put(worker.getUUID(),stand);
        }
        if(worker.distanceToSqr(stand.getX()+.5,stand.getY(),stand.getZ()+.5)<.16){
            WorkerNavigation.stop(worker);return;
        }
        WorkerNavigation.routeToExact(level,worker,stand,.8);
    }
}
