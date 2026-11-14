package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import java.util.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.resources.Identifier;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;

/** Job-scoped material coordination, never moves items or completes cells. */
final class BuilderMaterialNetwork {
    private record Offer(UUID donor,Item item,long until){}
    private static final class State {
        UUID job;
        final Map<Integer,Offer> offers=new HashMap<>();
        final Map<Integer,Long> retryAfter=new HashMap<>();
        final Map<UUID,Long> nextOfferProbe=new HashMap<>();
        final Set<UUID> recall=new HashSet<>();
        Set<Item> needs=Set.of();
        long nextCheck;
        boolean resupplied;
    }
    private static final Map<WorkerStationBlockEntity,State> states=new WeakHashMap<>();
    private static State state(WorkerStationBlockEntity table){
        State s=states.computeIfAbsent(table,k->new State());
        if(!Objects.equals(s.job,table.activeOrder())){
            s.job=table.activeOrder();s.offers.clear();s.recall.clear();
            s.retryAfter.clear();s.nextOfferProbe.clear();
            s.needs=Set.of();s.nextCheck=0;s.resupplied=false;
        }
        return s;
    }
    private static boolean usable(BuilderGolem peer,Item item){
        return peer.isAlive()&&!peer.hasSupplyBox()&&peer.carriedCount(item)>0;
    }
    private static Offer live(ServerLevel level,WorkerStationBlockEntity table,int index){
        State s=state(table);Offer o=s.offers.get(index);
        if(o==null)return null;
        // A claimed offer stays owned until completion or genuine release.
        // Expiry applies only to an unaccepted reservation.
        boolean accepted=table.claimedStep(o.donor())==index;
        if(table.builderStepCompletedForMaterials(index)
                ||!(level.getEntity(o.donor()) instanceof BuilderGolem peer)||!usable(peer,o.item())
                ||!accepted&&level.getGameTime()>=o.until()){
            s.offers.remove(index);return null;
        }
        return o;
    }
    static boolean reservedForOther(ServerLevel level,WorkerStationBlockEntity table,UUID worker,int index){
        Offer o=live(level,table,index);
        return o!=null&&!o.donor().equals(worker);
    }
    static List<Integer> offered(ServerLevel level,WorkerStationBlockEntity table,
            BuilderGolem worker,List<BuilderConstructionPlan.Step> steps){
        State s=state(table);var result=new ArrayList<Integer>();
        for(int index:new ArrayList<>(s.offers.keySet())){
            Offer o=live(level,table,index);
            if(o!=null&&o.donor().equals(worker.getUUID())
                    &&table.builderStepInActivePhase(steps,index)
                    &&table.builderStepReadyForClaim(index)
                    &&!table.builderStepClaimedByOther(worker.getUUID(),index))result.add(index);
        }
        result.sort(Integer::compare);
        return result;
    }
    static boolean offer(ServerLevel level,BuilderGolem requester,WorkerStationBlockEntity table,
            List<BuilderConstructionPlan.Step> steps,int index,Item item){
        if(!table.builderStepInActivePhase(steps,index)||table.claimedStep(requester.getUUID())!=index)return false;
        State s=state(table);long now=level.getGameTime();
        // Never replace a live reservation or re-offer a recently failed
        // step. Throttle even failed physical probes per requester.
        if(live(level,table,index)!=null||now<s.retryAfter.getOrDefault(index,0L)
                ||now<s.nextOfferProbe.getOrDefault(requester.getUUID(),0L))return false;
        s.nextOfferProbe.put(requester.getUUID(),now+20);
        var step=steps.get(index);
        var candidates=new ArrayList<BuilderGolem>();
        for(UUID id:table.builderWorkerIds()){
            if(id.equals(requester.getUUID())||s.recall.contains(id)
                    ||!(level.getEntity(id) instanceof BuilderGolem peer)||!usable(peer,item)
                    ||table.claimedStep(id)>=0||BuilderScaffolding.isControllingMovement(peer)
                    ||BuilderGrappleAccess.active(peer)||BuilderFlight.moving(peer))continue;
            boolean pending=false;
            for(int reserved:new ArrayList<>(s.offers.keySet())){
                Offer existing=live(level,table,reserved);
                if(existing!=null&&existing.donor().equals(id)){pending=true;break;}
            }
            if(!pending)candidates.add(peer);
        }
        candidates.sort(Comparator.comparingDouble(peer->
                peer.distanceToSqr(step.x()+.5,step.y()+.5,step.z()+.5)));
        // At most one expensive approach probe per request. Failure leaves
        // the requester free to use ordinary staging or other stocked work.
        s.retryAfter.put(index,now+200);
        if(candidates.isEmpty())return false;
        BuilderGolem donor=candidates.get(0);
        if(BuilderConstructionPlanner.chooseLookahead(level,donor,table,steps,List.of(index),0,1)==null)
            return false;
        if(!table.releaseBuilderStepForStock(requester.getUUID(),index))return false;
        s.offers.put(index,new Offer(donor.getUUID(),item,now+200));
        // A second transfer cannot occur immediately even if stock changes.
        s.retryAfter.put(index,now+600);
        System.out.println("[MATERIAL NETWORK HANDOFF] step="+index+" "+requester.getUUID()+" -> "+donor.getUUID());
        return true;
    }
    static Item required(BuilderConstructionPlan.Step step){
        if(step.requiredItem()==null)return null;
        Identifier key=Identifier.tryParse(step.requiredItem());
        return key!=null&&BuiltInRegistries.ITEM.containsKey(key)?BuiltInRegistries.ITEM.getValue(key):null;
    }
    static void shortage(ServerLevel level,WorkerStationBlockEntity table,
            List<BuilderConstructionPlan.Step> steps){
        State s=state(table);long now=level.getGameTime();
        if(!s.recall.isEmpty()||now<s.nextCheck)return;
        s.nextCheck=now+40;
        int layer=table.builderActiveLayer(steps);
        var needs=new HashSet<Item>();
        for(int i=0;i<steps.size();i++){
            var step=steps.get(i);
            if(step.y()!=layer||!table.builderStepInActivePhase(steps,i)
                    ||step.ignorePlant()||table.builderStepCompletedForMaterials(i)
                    ||BuilderDeferredState.verification(step.encodedState()))continue;
            Item item=required(step);
            if(item==null)return;
            needs.add(item);
        }
        if(needs.isEmpty())return;
        var crew=new ArrayList<UUID>();
        for(UUID id:table.builderWorkerIds()){
            if(!(level.getEntity(id) instanceof BuilderGolem peer)||!peer.isAlive())return;
            crew.add(id);
            for(Item item:needs)if(peer.carriedCount(item)>0)return;
        }
        if(crew.isEmpty())return;
        s.needs=Set.copyOf(needs);s.resupplied=false;s.recall.addAll(crew);s.offers.clear();
        for(UUID id:crew)table.releaseBuilderWorkOrder(id);
        System.out.println("[MATERIAL NETWORK RECALL] layer="+layer+" materials="+needs.size());
    }
    static boolean recallTick(ServerLevel level,BuilderGolem worker,WorkerStationBlockEntity table){
        State s=state(table);
        if(!s.recall.contains(worker.getUUID()))return false;
        if(!BuilderBuildService.parkAtOnSite(level,worker,table))return true;
        if(!s.resupplied){
            for(Item item:s.needs)if(BuilderSupplyInventory.containsInSources(table,item)){
                s.resupplied=true;System.out.println("[MATERIAL NETWORK RESTOCK] OnSite stock available");break;
            }
        }
        if(!s.resupplied){table.setProblem("Builders waiting at OnSite for current-layer materials");return true;}
        s.recall.remove(worker.getUUID());
        if(s.recall.isEmpty()){s.needs=Set.of();s.nextCheck=level.getGameTime()+100;}
        // Claim fresh work next callback, never continue a released cell
        // with the stale local index from the recall-triggering callback.
        return true;
    }
}
