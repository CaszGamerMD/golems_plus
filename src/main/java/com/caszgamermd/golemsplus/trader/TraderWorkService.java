package com.caszgamermd.golemsplus.trader;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import com.caszgamermd.golemsplus.worker.WorkerPhase;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.phys.AABB;

/** Server-only order dispatch. It never generates synthetic goods. */
public final class TraderWorkService {
    private TraderWorkService(){}
    public static String itemId(ItemStack stack){
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }
    public static Map<String,Integer> totalCost(ItemStack a,ItemStack b){
        Map<String,Integer> amounts=new HashMap<>();
        if(!a.isEmpty())amounts.merge(itemId(a),a.getCount(),Integer::sum);
        if(!b.isEmpty())amounts.merge(itemId(b),b.getCount(),Integer::sum);
        return Map.copyOf(amounts);
    }
    public static boolean withinVillage(ServerLevel level,WorkerStationBlockEntity station,BlockPos p){
        VillageSearchArea area=station.village();
        if(area==null || !level.dimension().identifier().toString().equals(area.dimension()))
            return false;
        long dx=p.getX()-area.x(), dz=p.getZ()-area.z(), dy=p.getY()-area.y();
        return dx*dx+dz*dz<=area.radius()*area.radius() && Math.abs(dy)<=32;
    }
    private static int quantity(WorkerStationBlockEntity depot,ItemStack item){
        if(item.isEmpty())return 0;
        int count=0;
        for(int i=0;i<WorkerStationBlockEntity.INPUT_SLOTS;i++)
            if(ItemStack.isSameItemSameComponents(depot.getItem(i),item))
                count+=depot.getItem(i).getCount();
        return count;
    }
    private static boolean canTakeBoth(WorkerStationBlockEntity station,ItemStack a,ItemStack b){
        if(!station.containsPayment(a)||!station.containsPayment(b))return false;
        if(!b.isEmpty()&&ItemStack.isSameItemSameComponents(a,b))
            return quantity(station,a)>=(long)a.getCount()+b.getCount();
        return true;
    }
    public static boolean canReturnPayments(WorkerStationBlockEntity depot,ItemStack a,ItemStack b){
        // Simulate total available input capacity; combined same-item costs
        // must not count a single free slot twice.
        Map<String,Integer> required=new HashMap<>();
        for(ItemStack s:new ItemStack[]{a,b}){
            if(s.isEmpty())continue;
            required.merge(itemId(s),s.getCount(),Integer::sum);
        }
        for(var e:required.entrySet()){
            ItemStack sample=itemId(a).equals(e.getKey())?a:b;
            int room=0;
            for(int i=0;i<WorkerStationBlockEntity.INPUT_SLOTS;i++){
                ItemStack existing=depot.getItem(i);
                if(existing.isEmpty())room+=sample.getMaxStackSize();
                else if(ItemStack.isSameItemSameComponents(existing,sample))
                    room+=existing.getMaxStackSize()-existing.getCount();
            }
            if(room<e.getValue())return false;
        }
        return true;
    }
    public static void returnPayment(WorkerStationBlockEntity depot,ItemStack payment){
        if(payment.isEmpty())return;
        int left=payment.getCount();
        for(int i=0;i<WorkerStationBlockEntity.INPUT_SLOTS&&left>0;i++){
            ItemStack previous=depot.getItem(i);
            if(!ItemStack.isSameItemSameComponents(previous,payment))continue;
            int n=Math.min(left,previous.getMaxStackSize()-previous.getCount());
            if(n>0){depot.setItem(i,previous.copyWithCount(previous.getCount()+n));left-=n;}
        }
        for(int i=0;i<WorkerStationBlockEntity.INPUT_SLOTS&&left>0;i++){
            if(!depot.getItem(i).isEmpty())continue;
            int n=Math.min(left,payment.getMaxStackSize());
            depot.setItem(i,payment.copyWithCount(n));left-=n;
        }
        if(left!=0)throw new IllegalStateException("Validated refund no longer fits");
    }
    public static void tick(ServerLevel level,WorkerStationBlockEntity station){
        if(level.getGameTime()%20!=0||!station.isTrader()
                ||station.owner()==null)return;
        UUID workerId=station.workerId();
        TraderGolem worker=null;
        if(workerId!=null){
            if(level.getEntity(workerId) instanceof TraderGolem found)worker=found;
            else return; // do NOT spawn duplicates when worker's chunk is unloaded
        }else if(station.activeOrder()!=null) {
            BlockPos p=station.getBlockPos();
            worker=new TraderGolem(WorksiteContent.TRADER_GOLEM,level);
            worker.assign(p,station.owner());
            worker.setPos(p.getX()+.5,p.getY()+1,p.getZ()+.5);
            if(!level.addFreshEntity(worker))return;
            station.setWorkerId(worker.getUUID());
        }
        if(worker==null||!worker.idle()||station.activeOrder()==null
                ||station.phase()==WorkerPhase.PAUSED||station.phase()==WorkerPhase.COMPLETE)
            return;
        if(station.village()==null){
            station.setProblem("Assign village with /golems trader village 32");
            station.setPhase(WorkerPhase.WAITING_FOR_LOCATION);return;
        }
        if(station.budgets().isEmpty()){
            station.setProblem("Set spending budget with /golems trader budget");
            station.setPhase(WorkerPhase.WAITING_FOR_SUPPLIES);return;
        }
        if(!level.dimension().identifier().toString().equals(station.village().dimension())){
            station.setProblem("Village is in a different dimension");
            station.setPhase(WorkerPhase.WAITING_FOR_LOCATION);return;
        }
        var area=station.village();
        var bounds=new AABB(area.x()-area.radius(),area.y()-32,area.z()-area.radius(),
                            area.x()+area.radius()+1,area.y()+33,area.z()+area.radius()+1);
        Villager best=null;MerchantOffer trade=null;int bestOffer=-1;
        double bestDistance=Double.POSITIVE_INFINITY;
        for(Villager villager:level.getEntitiesOfClass(Villager.class,bounds,
                v->v.isAlive()&&!v.isTrading())){
            if(!withinVillage(level,station,villager.blockPosition()))continue;
            var offers=villager.getOffers();
            for(int i=0;i<offers.size();i++){
                var offer=offers.get(i);
                ItemStack result=offer.getResult(),a=offer.getCostA(),b=offer.getCostB();
                if(offer.isOutOfStock()||result.isEmpty()||a.isEmpty()
                        ||!itemId(result).equals(station.wantedItem())
                        ||!station.canReceive(result)||!canTakeBoth(station,a,b))
                    continue;
                boolean funds=true;
                for(var e:totalCost(a,b).entrySet())
                    if(station.budgetRemaining(e.getKey())<e.getValue())funds=false;
                if(!funds)continue;
                double dist=worker.distanceToSqr(villager);
                if(dist<bestDistance){
                    bestDistance=dist;best=villager;trade=offer;bestOffer=i;
                }
            }
        }
        if(best==null){
            station.setProblem("No available villager offer / supplies / output space");
            station.setPhase(WorkerPhase.WAITING_FOR_SUPPLIES);return;
        }
        // Escrow real inputs from the station BEFORE dispatch.
        ItemStack payA=station.takePayment(trade.getCostA());
        ItemStack payB=station.takePayment(trade.getCostB());
        worker.begin(station.activeOrder(),best.getUUID(),bestOffer,payA,payB);
        station.setProblem("");
        station.setPhase(WorkerPhase.WORKING);
    }
}
