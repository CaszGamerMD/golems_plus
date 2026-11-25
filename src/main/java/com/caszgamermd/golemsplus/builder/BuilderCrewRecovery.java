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
        int layer=Integer.MIN_VALUE,best=-1,attempts,epochWork=-1;
        final Set<UUID> tried=new HashSet<>();
        long lastProgress,started,graceUntil;
        boolean recalling;
        List<UUID> reduced=List.of();
        final Set<UUID> unloaded=new HashSet<>();
        final Set<UUID> recovering=new HashSet<>();
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
                +" reduced="+s.reduced.contains(worker.getUUID())
                +" independentRecovery="+s.recovering.contains(worker.getUUID());
    }
    static void clear(WorkerStationBlockEntity table){states.remove(table);}
    static boolean recalling(WorkerStationBlockEntity table){return state(table).recalling;}
    static boolean assessmentGrace(ServerLevel level,WorkerStationBlockEntity table){
        State s=state(table);long now=level.getGameTime();
        return s.recalling&&now-s.started<RETURN_BUDGET||now<s.graceUntil;
    }
    static List<UUID> staff(WorkerStationBlockEntity table,int layer,List<UUID> normal){
        State s=state(table);
        List<UUID> eligible=s.layer==layer&&!s.reduced.isEmpty()?s.reduced:normal;
        if(s.recovering.isEmpty())return eligible;
        var ready=new ArrayList<UUID>();
        for(UUID id:eligible)if(!s.recovering.contains(id))ready.add(id);
        // Normal small-layer staffing may name only the stranded worker.
        // Fill that slot from the returned roster, never from recovery.
        for(UUID id:table.builderWorkerIds()){
            if(ready.size()>=eligible.size())break;
            if(!s.recovering.contains(id)&&!ready.contains(id))ready.add(id);
        }
        return List.copyOf(ready);
    }
    static boolean sidelined(WorkerStationBlockEntity table,UUID worker){
        State s=state(table);
        return !s.recalling&&(s.recovering.contains(worker)
                ||!s.reduced.isEmpty()&&!s.reduced.contains(worker));
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
    // A bounded recovery epoch tries distinct workers, not the same
    // single worker forever. Real construction progress renews the budget.
    static List<UUID> chooseRecoveryStaff(List<UUID> ready,Set<UUID> tried,int cap){
        var chosen=new ArrayList<UUID>();
        for(UUID id:ready)if(!tried.contains(id)&&chosen.size()<cap)chosen.add(id);
        if(chosen.isEmpty()&&tried.isEmpty())
            for(UUID id:ready)if(chosen.size()<cap)chosen.add(id);
        return List.copyOf(chosen);
    }
    static boolean recoveryBudgetAvailable(int attempts,int crewSize,boolean alternative){
        return attempts<Math.max(2,crewSize+1)&&(attempts<2||alternative);
    }
    static void begin(ServerLevel level,WorkerStationBlockEntity table,List<BuilderConstructionPlan.Step> steps){
        State s=state(table);if(s.recalling)return;
        s.layer=table.builderActiveLayer(steps);s.best=table.builderWorkDone();
        s.lastProgress=level.getGameTime();s.started=s.lastProgress;
        if(s.epochWork<0)s.epochWork=s.best;
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
        if(BuilderFlight.creative(worker)){
            // Reset a single stalled hat, not the entire crew or material network.
            // Normal pause/return and material accounting remain unchanged.
            if(s.layer!=layer||work>s.best){s.layer=layer;s.best=work;s.lastProgress=now;}
            if(layer==Integer.MAX_VALUE||now-s.lastProgress<STALL||now<s.graceUntil
                    ||BuilderFlight.moving(worker)||worker.hasSupplyBox()
                    ||BuilderDroppedBlockRecovery.busy(worker))return false;
            int claim=table.claimedStep(worker.getUUID());
            if(claim<0||claim>=steps.size())return false;
            var step=steps.get(claim);
            BlockPos target=new BlockPos(step.x(),step.y(),step.z());
            table.waitForBuilderAccess(worker.getUUID(),claim,now,"individual flight recovery");
            table.releaseBuilderWorkOrder(worker.getUUID());
            BuilderBuildService.resetFlyingWorker(worker,table);
            BuilderFlight.reposition(level,worker,target);
            s.graceUntil=now+80;
            System.out.println("[HAT INDIVIDUAL RECOVERY] worker="+worker.getUUID()+" target="+target+" layer="+layer);
            return true;
        }
        if(!s.recalling&&s.recovering.contains(worker.getUUID())){
            // This worker remains registered, retains its inventory and
            // completes its physical return while the safe crew works.
            if(tryUnload(level,worker,table,s)){
                s.recovering.remove(worker.getUUID());
                s.unloaded.add(worker.getUUID());
                BuilderTrace.decision(level,worker,"CREW_INDEPENDENT_RETURN",table.builderUnloadingStorage(),
                        "returned and unloaded; eligible next layer");
                parkAway(level,worker,table,s);
            }
            return true;
        }
        if(!s.recalling){
            if(layer!=s.layer){
                s.layer=layer;s.attempts=0;s.reduced=List.of();
                s.tried.clear();s.epochWork=work;
                s.parking.clear();s.best=work;s.lastProgress=now;s.graceUntil=0;
            }
            if(work>s.best){
                s.best=work;s.lastProgress=now;
                // Only renew after meaningful completed work; a single
                // incidental block must not cause an endless retreat loop.
                if(s.epochWork<0||work-s.epochWork>=32){
                    s.epochWork=work;s.attempts=0;s.tried.clear();
                    s.tried.addAll(s.reduced);
                }
            }
            if(layer==Integer.MAX_VALUE)return false;
            boolean alternative=false;
            for(UUID id:table.builderWorkerIds())
                if(!s.tried.contains(id)&&!s.recovering.contains(id)){alternative=true;break;}
            if(recoveryBudgetAvailable(s.attempts,table.builderWorkerIds().size(),alternative)
                    &&now>=s.graceUntil&&now-s.lastProgress>=STALL
                    &&remaining(table,steps,layer)>0){
                begin(level,table,steps);s=state(table);
            }
        }
        if(s.recalling){
            s.unloaded.retainAll(table.builderWorkerIds());
            if(!s.unloaded.contains(worker.getUUID())&&tryUnload(level,worker,table,s)){
                s.unloaded.add(worker.getUUID());
                s.recovering.remove(worker.getUUID());
            }
            List<UUID> live=table.builderWorkerIds();
            boolean all=s.unloaded.containsAll(live);
            // Only relax the barrier after the original full timeout,
            // and only if someone actually returned with empty pockets.
            // Missing or trapped workers remain in independent recovery.
            if(!all&&(now-s.started<RETURN_BUDGET||s.unloaded.isEmpty())){
                if(s.unloaded.contains(worker.getUUID()))parkAway(level,worker,table,s);
                return true;
            }
            s.recovering.clear();
            for(UUID id:live)if(!s.unloaded.contains(id))s.recovering.add(id);
            List<UUID> ready=live.stream().filter(s.unloaded::contains).toList();
            int active=table.builderActiveLayer(steps),count=remaining(table,steps,active);
            int cap=s.attempts>=2||count<=5?1:2;
            int n=Math.min(Math.min(cap,table.crewTarget()),ready.size());
            List<UUID> chosen=active==s.layer?chooseRecoveryStaff(ready,s.tried,n):List.of();
            // If no untried worker actually returned, retain a real
            // returned worker rather than assigning a stranded one.
            if(chosen.isEmpty()&&active==s.layer&&n>0)
                chosen=List.of(ready.getFirst());
            s.tried.addAll(chosen);
            s.reduced=List.copyOf(chosen);s.recalling=false;
            s.best=table.builderWorkDone();s.lastProgress=now;s.graceUntil=now+RESUME_GRACE;
            table.beginBuilderZonePlan();
            BuilderMaterialNetwork.resetForCrew(table);
            BuilderBuildService.resetCrewActions(level,table,s.recovering);
            table.setProblem("Crew recovery: resuming remaining layer with "+(chosen.isEmpty()?ready.size():chosen.size())
                    +" Builders; "+s.recovering.size()+" still returning");
            System.out.println("[CREW RETREAT RESUME] layer="+active+" remaining="+count
                    +" active="+chosen+" unloaded="+s.unloaded.size()+" independent="+s.recovering
                    +" tried="+s.tried+" epochWork="+s.epochWork);
            return true;
        }
        if(!s.reduced.isEmpty()&&!s.reduced.contains(worker.getUUID())){
            parkAway(level,worker,table,s);return true;
        }
        return false;
    }
    private static boolean tryUnload(ServerLevel level,BuilderGolem worker,WorkerStationBlockEntity table,State s){
        if(BuilderFlight.moving(worker)||BuilderGrappleAccess.active(worker)
                ||BuilderScaffolding.isControllingMovement(worker))return false;
        if(!BuilderBuildService.parkAtOnSite(level,worker,table)){
            long now=level.getGameTime();
            BlockPos depot=table.builderUnloadingStorage();
            UUID id=worker.getUUID();
            if(depot!=null){
                double distance=worker.distanceToSqr(depot.getX()+.5,depot.getY(),depot.getZ()+.5);
                if(distance<s.bestReturnDistance.getOrDefault(id,Double.POSITIVE_INFINITY)-.25){
                    s.bestReturnDistance.put(id,distance);s.lastReturnProgress.put(id,now);
                }
                long last=s.lastReturnProgress.computeIfAbsent(id,k->now);
                if(!BuilderEscapeBox.escaping(worker)&&now-last>=160&&now>=s.retry.getOrDefault(id,0L)){
                    s.retry.put(id,now+120);BuilderReturnService.resetApproach(worker);
                }
            }
            return false;
        }
        BuilderScaffolding.retrieve(level,worker,table,false);
        if(worker.hasSupplyBox()){
            if(!BuilderSupplyInventory.depositBox(table,worker.supplyBox()))return false;
            worker.clearSupplyBox();
        }
        if(!BuilderDroppedBlockRecovery.unloadRecoveredAtSite(worker,table)
                ||!BuilderSupplyInventory.returnPouch(table,worker)
                ||!worker.isEmpty()||worker.hasSupplyBox())return false;
        BuilderTrace.decision(level,worker,"CREW_UNLOADED",table.builderUnloadingStorage(),"physical return and empty inventory verified");
        System.out.println("[CREW RETREAT UNLOADED] "+worker.getUUID()+" at="+worker.blockPosition());
        return true;
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
