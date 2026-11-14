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
        if(level.getGameTime()>=o.until()||table.builderStepCompletedForMaterials(index)
                ||!(level.getEntity(o.donor()) instanceof BuilderGolem peer)||!usable(peer,o.item())){
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
        State s=state(table);BuilderGolem donor=null;double best=Double.POSITIVE_INFINITY;
        var step=steps.get(index);
        for(UUID id:table.builderWorkerIds()){
            if(id.equals(requester.getUUID())||s.recall.contains(id)
                    ||!(level.getEntity(id) instanceof BuilderGolem peer)||!usable(peer,item))continue;
            if(BuilderScaffolding.isControllingMovement(peer)||BuilderGrappleAccess.active(peer)
                    ||BuilderFlight.moving(peer))continue;
            double score=peer.distanceToSqr(step.x()+.5,step.y()+.5,step.z()+.5)
                    +(table.claimedStep(id)>=0?10000:0);
            if(score<best){best=score;donor=peer;}
        }
        if(donor==null)return false;
        // Existing Planner release preserves the unfinished cell and
        // exclusive claim; the short retry delay lets the crew regroup.
        table.waitForBuilderMaterial(requester.getUUID(),index,level.getGameTime());
        s.offers.put(index,new Offer(donor.getUUID(),item,level.getGameTime()+400));
        System.out.println("[MATERIAL NETWORK HANDOFF] step="+index+" "+requester.getUUID()+" -> "+donor.getUUID());
        return true;
    }
    private static Item required(BuilderConstructionPlan.Step step){
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
            if(step.y()!=layer||step.ignorePlant()||table.builderStepCompletedForMaterials(i)
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
        return false;
    }
}
