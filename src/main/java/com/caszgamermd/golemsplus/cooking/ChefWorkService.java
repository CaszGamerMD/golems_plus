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
        for(int x=-8;x<=8;x++)for(int y=-3;y<=3;y++)for(int z=-8;z<=8;z++){
            BlockPos p=station.offset(x,y,z);
            if(station.distSqr(p)>100||!level.hasChunkAt(p))continue;
            if(level.getBlockEntity(p) instanceof MealCounterBlockEntity found){
                double d=station.distSqr(p);
                if(d<distance){best=found;distance=d;}
            }
        }
        return best;
    }
    public static boolean order(ServerPlayer player,WorkerStationBlockEntity station,
                                String id,int amount){
        if(!station.isCooking()||!station.canConfigure(player.getUUID())
                ||station.chefBusy()||amount<1||amount>16)return false;
        Identifier key=Identifier.tryParse(id);
        if(key==null||!BuiltInRegistries.ITEM.containsKey(key))return false;
        Item item=BuiltInRegistries.ITEM.getValue(key);
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
        KitchenSupplies kitchen=KitchenSupplies.around((ServerLevel)player.level(),station.getBlockPos());
        if(!kitchen.connected()){
            station.setProblem("Connect the kiosk to a Cooking for Blockheads kitchen");return false;
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
        if(!station.startChefOrder(player.getUUID(),key.toString(),amount,steps,escrow)){
            station.setProblem("Could not reserve order");return false;
        }
        station.setProblem("");
        return true;
    }
    public static void tick(ServerLevel level,WorkerStationBlockEntity station){
        if(!station.isCooking())return;
        if(level.getGameTime()%100==0){
            var counts=new LinkedHashMap<String,Integer>();
            KitchenSupplies.around(level,station.getBlockPos()).snapshot()
                    .forEach((item,count)->counts.put(
                            BuiltInRegistries.ITEM.getKey(item).toString(),count));
            station.updateChefStock(counts);
        }
        if(!station.chefBusy()
                ||station.phase()==com.caszgamermd.golemsplus.worker.WorkerPhase.PAUSED)
            return;
        int present=0;
        for(UUID id:station.chefWorkerIds()){
            if(level.getEntity(id) instanceof ChefGolem chef){
                present++;
                if(chef.distanceToSqr(station.getBlockPos().getX()+.5,
                        station.getBlockPos().getY()+1,
                        station.getBlockPos().getZ()+.5)>12)
                    chef.getNavigation().moveTo(station.getBlockPos().getX()+.5,
                            station.getBlockPos().getY()+1,
                            station.getBlockPos().getZ()+.5,.85);
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
        ItemStack meal=new ItemStack(BuiltInRegistries.ITEM.getValue(id),station.chefQuantity());
        if(pickup.serve(meal)){
            station.clearChefOrder();
            station.setProblem("");
        }
    }
}
