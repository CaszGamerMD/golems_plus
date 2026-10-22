package com.caszgamermd.golemsplus.cooking;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import java.util.*;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Shared-kitchen dispatcher. The recipe graph is resolved bottom-up and the
 * exact leaf ingredients are removed atomically from connected kitchen
 * providers before work starts. No free food or automatic resource creation.
 */
public final class ChefWorkService{
    private ChefWorkService(){}
    private static MealCounterBlockEntity counter(ServerLevel level,BlockPos station){
        MealCounterBlockEntity best=null;
        double distance=Double.MAX_VALUE;
        for(int x=-10;x<=10;x++)for(int y=-8;y<=8;y++)for(int z=-10;z<=10;z++){
            BlockPos p=station.offset(x,y,z);
            if(station.distSqr(p)>100||!level.hasChunkAt(p))continue;
            if(level.getBlockEntity(p) instanceof MealCounterBlockEntity found){
                double d=station.distSqr(p);
                if(d<distance){best=found;distance=d;}
            }
        }
        return best;
    }
    /** Rebind to the nearest Cooking for Blockheads storage block. */
    public static boolean linkKitchen(ServerPlayer player,WorkerStationBlockEntity station){
        if(!station.isCooking()||!station.canConfigure(player.getUUID()))return false;
        if(!FabricLoader.getInstance().isModLoaded("cookingforblockheads")){
            station.setProblem("Cooking for Blockheads is not installed");
            return false;
        }
        BlockPos target=KitchenSupplies.findAnchor(
                (ServerLevel)player.level(),station.getBlockPos());
        if(target==null){
            station.setProblem("No Cooking for Blockheads storage within 16 blocks");
            return false;
        }
        if(!station.setKitchenLink(player.getUUID(),target))return false;
        station.setProblem("");
        return true;
    }

    private static KitchenSupplies kitchen(ServerLevel level,WorkerStationBlockEntity station){
        BlockPos anchor=station.kitchenLink();
        return anchor!=null
                ?KitchenSupplies.around(level,station.getBlockPos(),anchor)
                :KitchenSupplies.around(level,station.getBlockPos());
    }

    public static boolean order(ServerPlayer player,WorkerStationBlockEntity station,
                                String id,int amount){
        if(!station.isCooking()||!station.canConfigure(player.getUUID())
                ||station.chefBusy()||amount<1||amount>16)return false;
        Identifier key=Identifier.tryParse(id);
        if(key==null||!BuiltInRegistries.ITEM.containsKey(key))return false;
        Item item=BuiltInRegistries.ITEM.getValue(key);
        if(amount>new ItemStack(item).getMaxStackSize()){
            station.setProblem("Requested quantity exceeds one plate; order fewer");return false;
        }
        if(new ItemStack(item).get(DataComponents.FOOD)==null){
            station.setProblem("That item is not edible");return false;
        }
        if(station.chefWorkerIds().isEmpty()){
            station.setProblem("Place a crafted Chef Golem by the kiosk first");return false;
        }
        if(counter((ServerLevel)player.level(),station.getBlockPos())==null){
            station.setProblem("Place a Meal Pickup Counter within 10 blocks");return false;
        }
        if(!FabricLoader.getInstance().isModLoaded("cookingforblockheads")){
            station.setProblem("Install Cooking for Blockheads and Balm");return false;
        }
        if(station.kitchenLink()==null&&!linkKitchen(player,station))return false;
        KitchenSupplies kitchen=kitchen((ServerLevel)player.level(),station);
        if(!kitchen.connected()){
            station.setProblem("Kitchen link is disconnected. Press LINK KITCHEN to reconnect");
            return false;
        }
        var available=kitchen.snapshot();
        var plan=ChefRecipePlanner.plan((ServerLevel)player.level(),item,amount,available);
        if(plan==null){
            station.setProblem("Missing ingredients or a supported crafting/cooking recipe");
            return false;
        }
        // If the finished item is already in kitchen storage, the chef still
        // performs a plating step instead of conjuring new items.
        List<String> steps=plan.steps().isEmpty()?List.of("Plate ready food"):plan.steps();
        var escrow=new LinkedHashMap<String,Integer>();
        plan.ingredients().forEach((k,v)->escrow.put(BuiltInRegistries.ITEM.getKey(k).toString(),v));
        if(!kitchen.consume(plan.ingredients())){
            station.setProblem("Kitchen stock changed; check supplies and order again");
            return false;
        }
        var surplus=new LinkedHashMap<String,Integer>();
        plan.leftovers().forEach((k,v)->{
            if(v>0)surplus.put(BuiltInRegistries.ITEM.getKey(k).toString(),v);
        });
        if(!station.startChefOrder(player.getUUID(),key.toString(),amount,steps,escrow,surplus)){
            station.setProblem("Could not reserve order");return false;
        }
        station.setProblem("");
        return true;
    }
    public static void tick(ServerLevel level,WorkerStationBlockEntity station){
        if(!station.isCooking())return;
        if(level.getGameTime()%100==0){
            var counts=new LinkedHashMap<String,Integer>();
            kitchen(level,station).snapshot()
                    .forEach((item,count)->counts.put(
                            BuiltInRegistries.ITEM.getKey(item).toString(),count));
            station.updateChefStock(counts);
        }
        if(!station.chefBusy()
                ||station.phase()==com.caszgamermd.golemsplus.worker.WorkerPhase.PAUSED)
            return;
        if(level.getGameTime()%40==0){
            KitchenSupplies connected=kitchen(level,station);
            if(!connected.connected()){
                station.setProblem("Kitchen disconnected; press LINK KITCHEN");
                return;
            }
            BlockPos target=connected.workPoint()==null?
                    station.getBlockPos():connected.workPoint();
            for(UUID id:station.chefWorkerIds()){
                if(level.getEntity(id) instanceof ChefGolem chef)
                    ChefFloorNavigation.work(level,chef,target);
            }
        }
        int present=0;
        for(UUID id:station.chefWorkerIds()){
            if(level.getEntity(id) instanceof ChefGolem chef){
                present++;
                // Keep route planning on a 40-tick interval; don't overwrite
                // the workstation navigation with an idle-home command.
            }
        }
        if(present==0){
            station.setProblem("Chef workers are not loaded");return;
        }
        if(!station.chefStepsFinished()){
            // A team of chefs finishes preparation faster, never skipping
            // prerequisite order.
            for(int i=0;i<present;i++)station.tickChefStep();
            return;
        }
        MealCounterBlockEntity pickup=counter(level,station.getBlockPos());
        if(pickup==null){station.setProblem("Meal Pickup Counter missing");return;}
        if(!pickup.food().isEmpty()){
            station.setProblem("Pickup plate occupied; collect the previous meal");return;
        }
        Identifier id=Identifier.tryParse(station.chefFood());
        if(id==null||!BuiltInRegistries.ITEM.containsKey(id)){
            station.setProblem("Ordered item is no longer registered");return;
        }
        // A single output inventory must accommodate every extra batch result.
        // Simulate insertion together first so partial deposits cannot create
        // items again on a retry (e.g. cookies craft eight per recipe).
        var simulated=new ArrayList<ItemStack>();
        for(int i=WorkerStationBlockEntity.INPUT_SLOTS;
                i<WorkerStationBlockEntity.SLOTS;i++)simulated.add(station.getItem(i).copy());
        var extras=new ArrayList<ItemStack>();
        for(var e:station.chefSurplus().entrySet()){
            Identifier leftover=Identifier.tryParse(e.getKey());
            if(leftover==null||!BuiltInRegistries.ITEM.containsKey(leftover))continue;
            var item=BuiltInRegistries.ITEM.getValue(leftover);
            int left=e.getValue();
            while(left>0){
                int n=Math.min(left,new ItemStack(item).getMaxStackSize());
                extras.add(new ItemStack(item,n));left-=n;
            }
        }
        for(ItemStack extra:extras){
            int left=extra.getCount();
            for(ItemStack inSlot:simulated){
                if(left==0)break;
                if(!inSlot.isEmpty()&&ItemStack.isSameItemSameComponents(inSlot,extra)){
                    int space=inSlot.getMaxStackSize()-inSlot.getCount();
                    int added=Math.min(left,Math.max(0,space));
                    inSlot.grow(added);left-=added;
                }
            }
            for(int i=0;i<simulated.size()&&left>0;i++){
                if(simulated.get(i).isEmpty()){
                    int added=Math.min(left,extra.getMaxStackSize());
                    simulated.set(i,extra.copyWithCount(added));left-=added;
                }
            }
            if(left>0){
                station.setProblem("Return inventory full; clear space for recipe leftovers");
                return;
            }
        }
        ItemStack meal=new ItemStack(BuiltInRegistries.ITEM.getValue(id),station.chefQuantity());
        if(pickup.serve(meal)){
            for(ItemStack extra:extras)station.deposit(extra);
            station.clearChefOrder();
            station.setProblem("");
        }
    }
}
