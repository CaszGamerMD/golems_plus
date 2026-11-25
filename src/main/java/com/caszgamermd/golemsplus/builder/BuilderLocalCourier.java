package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

/** One optional, temporary local supply hat per crew. All cargo lives in
 * ordinary saved pockets. No request, timeout or reload fabricates items. */
final class BuilderLocalCourier {
    private static final boolean ENABLED=Boolean.parseBoolean(System.getProperty("golems.builder.localCourier",
            System.getenv().getOrDefault("GOLEMS_BUILDER_LOCAL_COURIER","true")));
    private record Request(Item item,int desired,long until,int layer){}
    private static final class State {
        UUID job,courier;
        long deadline,nextDispatch,nextProbe;
        boolean loaded;
        int delivered,trips;
        final Map<UUID,Request> requests=new LinkedHashMap<>();
        final Map<UUID,Long> urgentUntil=new HashMap<>(),retryAfter=new HashMap<>();
        final Map<UUID,Request> batch=new LinkedHashMap<>();
    }
    private static final Map<WorkerStationBlockEntity,State> states=new WeakHashMap<>();
    private static State state(WorkerStationBlockEntity table){
        State s=states.computeIfAbsent(table,k->new State());
        if(!Objects.equals(s.job,table.activeOrder())){
            s=new State();s.job=table.activeOrder();states.put(table,s);
        }
        return s;
    }
    static void clear(WorkerStationBlockEntity table){states.remove(table);}
    static boolean active(WorkerStationBlockEntity table,UUID id){
        return ENABLED&&id.equals(state(table).courier);
    }
    private static boolean eligible(ServerLevel level,WorkerStationBlockEntity table){
        if(!ENABLED||table.activeOrder()==null||table.builderWorkerIds().size()<4
                ||table.builderUnloadingStorage()==null||table.supplyRequest()!=null)return false;
        int count=0;
        for(UUID id:table.builderWorkerIds())
            if(level.getEntity(id) instanceof BuilderGolem hat&&hat.isAlive()&&BuilderFlight.creative(hat)&&!hat.hasSupplyBox())count++;
        return count>=4;
    }
    static void noteDemand(ServerLevel level,BuilderGolem worker,WorkerStationBlockEntity table,
            List<BuilderConstructionPlan.Step> steps,List<Integer> zone,Item item){
        if(!BuilderFlight.creative(worker)||!eligible(level,table)||item==null||active(table,worker.getUUID())
                ||worker.carriedCount(item)>4||BuilderSupplyInventory.courierExcluded(item))return;
        BlockPos depot=table.builderUnloadingStorage();
        if(worker.distanceToSqr(depot.getX()+.5,depot.getY()+.5,depot.getZ()+.5)<64)return;
        State s=state(table);long now=level.getGameTime();UUID id=worker.getUUID();
        if(now<s.retryAfter.getOrDefault(id,0L))return;
        Request prior=s.requests.get(id);
        if(prior!=null&&prior.item()==item&&prior.until()>now)return;
        if(!BuilderSupplyInventory.containsInSources(table,item))return;
        int layer=table.builderActiveLayer(steps),demand=0;
        for(int idx:zone){
            if(idx<0||idx>=steps.size()||!table.builderStepInActivePhase(steps,idx)
                    ||table.builderStepCompletedForMaterials(idx))continue;
            if(BuilderMaterialNetwork.required(steps.get(idx))==item&&++demand>=32)break;
        }
        // Avoid sending a hat across the whole build for a single rare item.
        // Such a block still uses the ordinary individual resupply path.
        if(demand<6)return;
        int desired=Math.min(new ItemStack(item).getMaxStackSize(),demand);
        if(desired<=worker.carriedCount(item))return;
        s.requests.put(id,new Request(item,desired,now+180,layer));
    }
    static boolean waitForDelivery(ServerLevel level,BuilderGolem worker,WorkerStationBlockEntity table,Item item){
        State s=state(table);UUID id=worker.getUUID();long now=level.getGameTime();
        Request r=s.batch.get(id);
        if(!s.loaded||s.courier==null||r==null||r.item()!=item||now>=s.deadline
                ||!(level.getEntity(s.courier) instanceof BuilderGolem courier)
                ||courier.carriedCount(item)<=0||courier.distanceToSqr(worker)>225)return false;
        long until=s.urgentUntil.computeIfAbsent(id,k->now+60);
        return now<until;
    }
    private static void finish(ServerLevel level,WorkerStationBlockEntity table,State s,boolean timeout){
        if(s.courier!=null){
            if(level.getEntity(s.courier) instanceof BuilderGolem hat){
                hat.setDeliveryRole(false);
                // Never leave a stale material rendezvous owning the next
                // construction route after a cancelled mission.
                BuilderFlight.stop(hat);
            }
            System.out.println("[HAT COURIER END] trips="+s.trips+" delivered="+s.delivered+" timeout="+timeout);
        }
        long now=level.getGameTime();
        for(UUID id:s.batch.keySet()){s.requests.remove(id);s.retryAfter.put(id,now+100);}
        s.courier=null;s.batch.clear();s.urgentUntil.clear();s.loaded=false;s.nextDispatch=now+40;
    }
    static boolean tick(ServerLevel level,BuilderGolem worker,WorkerStationBlockEntity table,
            List<BuilderConstructionPlan.Step> steps){
        State s=state(table);long now=level.getGameTime();UUID id=worker.getUUID();
        if(!eligible(level,table)){if(s.courier!=null)finish(level,table,s,true);return false;}
        int layer=table.builderActiveLayer(steps);
        s.requests.entrySet().removeIf(e->e.getValue().until()<=now||e.getValue().layer()!=layer);
        if(s.courier!=null){
            if(now>=s.deadline||!(level.getEntity(s.courier) instanceof BuilderGolem current)||!current.isAlive()){
                finish(level,table,s,true);return false;
            }
            if(!id.equals(s.courier))return false;
        }else{
            if(now<s.nextDispatch||s.requests.isEmpty()||!BuilderFlight.creative(worker)
                    ||worker.hasSupplyBox()||BuilderFlight.moving(worker))return false;
            BlockPos depot=table.builderUnloadingStorage();
            // Recruit from hats already at/near OnSite (including one with
            // a just-staged claim), or an otherwise idle nearby hat. Never
            // pull an active stocked builder away from a productive roof.
            boolean atDepot=BuilderReturnService.withinStorageReach(worker,depot);
            if(!atDepot&&(table.claimedStep(id)>=0||worker.distanceToSqr(depot.getX()+.5,depot.getY()+.5,depot.getZ()+.5)>36))return false;
            var candidates=new ArrayList<Map.Entry<UUID,Request>>();
            for(var e:s.requests.entrySet()){
                if(e.getKey().equals(id)||!(level.getEntity(e.getKey()) instanceof BuilderGolem recipient)
                        ||!recipient.isAlive()||recipient.carriedCount(e.getValue().item())>=e.getValue().desired())continue;
                candidates.add(e);
            }
            // Only spend a worker on useful combined deliveries, or one
            // substantial request. Low demand leaves all four building.
            int units=candidates.stream().mapToInt(e->e.getValue().desired()).sum();
            if(candidates.isEmpty()||units<16)return false;
            candidates.sort(Comparator.comparingInt((Map.Entry<UUID,Request> e)->e.getValue().desired()).reversed());
            for(var e:candidates.subList(0,Math.min(3,candidates.size())))s.batch.put(e.getKey(),e.getValue());
            table.releaseBuilderWorkOrder(id);
            BuilderBuildService.resetFlyingWorker(worker,table);
            s.courier=id;s.loaded=false;s.deadline=now+300;s.nextProbe=now;s.trips++;
            worker.setDeliveryRole(true);
            System.out.println("[HAT COURIER START] worker="+id+" requests="+s.batch.size()+" units="+units);
        }
        worker.setDeliveryRole(true);
        if(!s.loaded){
            BlockPos depot=table.builderUnloadingStorage();
            if(BuilderReturnService.approach(level,worker,depot,.83))return true;
            var wanted=new LinkedHashMap<Item,Integer>();
            for(var e:s.batch.entrySet()){
                if(!(level.getEntity(e.getKey()) instanceof BuilderGolem target)||!target.isAlive())continue;
                Request r=e.getValue();
                wanted.merge(r.item(),Math.max(0,r.desired()-target.carriedCount(r.item())),Integer::sum);
            }
            BuilderSupplyInventory.loadCourier(table,worker,wanted);
            s.loaded=true;
        }
        s.batch.entrySet().removeIf(e->{
            Request r=e.getValue();
            return r.layer()!=layer||!(level.getEntity(e.getKey()) instanceof BuilderGolem target)
                    ||!target.isAlive()||target.carriedCount(r.item())>=r.desired()||worker.carriedCount(r.item())<=0;
        });
        if(s.batch.isEmpty()){finish(level,table,s,false);return false;}
        BuilderGolem recipient=null;Request request=null;double distance=Double.POSITIVE_INFINITY;
        for(var e:s.batch.entrySet()){
            if(!(level.getEntity(e.getKey()) instanceof BuilderGolem target))continue;
            double d=worker.distanceToSqr(target);
            if(d<distance){distance=d;recipient=target;request=e.getValue();}
        }
        if(recipient==null){finish(level,table,s,false);return false;}
        int moved=BuilderSupplyInventory.deliverCourier(worker,recipient,request.item(),
                Math.max(0,request.desired()-recipient.carriedCount(request.item())));
        if(moved>0){
            s.delivered+=moved;s.batch.remove(recipient.getUUID());s.requests.remove(recipient.getUUID());
            s.retryAfter.put(recipient.getUUID(),now+80);
            System.out.println("[HAT COURIER DELIVERY] worker="+id+" to="+recipient.getUUID()+" items="+moved);
            if(s.batch.isEmpty()){finish(level,table,s,false);return false;}
            return true;
        }
        if(now<s.nextProbe)return true;
        s.nextProbe=now+10;
        var candidates=new ArrayList<BlockPos>();BlockPos at=recipient.blockPosition();
        for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
            if(dx==0&&dy==0&&dz==0)continue;
            BlockPos pos=at.offset(dx,dy,dz);
            Vec3 feet=new Vec3(pos.getX()+.5,pos.getY(),pos.getZ()+.5);
            if(feet.distanceToSqr(recipient.position())<=4&&feet.distanceToSqr(recipient.position())>=.8
                    &&BuilderFlight.clear(level,worker,feet))candidates.add(pos);
        }
        BlockPos pose=BuilderFlight.select(level,worker,candidates);
        if(pose!=null)BuilderFlight.request(level,worker,pose);
        return true;
    }
}
