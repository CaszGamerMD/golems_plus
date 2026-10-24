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
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.particles.ParticleTypes;

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

    /** Client requests a detailed preview, never ingredients or crafting authority. */
    public static boolean preview(ServerPlayer player,WorkerStationBlockEntity station,
                                  String itemId,int count){
        if(!station.isCooking()||!station.canConfigure(player.getUUID())
                ||count<1||count>16)return false;
        Identifier key=Identifier.tryParse(itemId);
        if(key==null||!BuiltInRegistries.ITEM.containsKey(key))return false;
        Item item=BuiltInRegistries.ITEM.getValue(key);
        if(new ItemStack(item).get(DataComponents.FOOD)==null)return false;
        KitchenSupplies linked=kitchen((ServerLevel)player.level(),station);
        Map<Item,Integer> stocked=linked.snapshot();
        var plan=ChefRecipePlanner.preview((ServerLevel)player.level(),item,count,stocked,
                linked.hasCooker((ServerLevel)player.level()));
        if(plan==null){
            boolean recipe=ChefRecipePlanner.hasRecipe((ServerLevel)player.level(),item,
                    linked.hasCooker((ServerLevel)player.level()));
            station.setChefPreview(player.getUUID(),itemId,List.of(
                    recipe
                        ?"Recipe exists, but a required ingredient or preparation method could not be resolved"
                        :"No supported crafting or cooking recipe registered for this food",
                    "Finished meals may be served directly ONLY from linked kitchen storage"));
            return false;
        }
        List<String> lines=new ArrayList<>();
        if(linked.milkPortions()>0)
            lines.add("COW / MILK JAR: "+linked.milkPortions()+" milk portions available");
        lines.add("INGREDIENTS / PANTRY");
        plan.available().entrySet().stream()
                .sorted(java.util.Comparator.comparing(e->BuiltInRegistries.ITEM.getKey(e.getKey()).toString()))
                .limit(75).forEach(e->lines.add("OK "+e.getValue()+" x "+
                        BuiltInRegistries.ITEM.getKey(e.getKey())));
        plan.missing().entrySet().stream()
                .sorted(java.util.Comparator.comparing(e->BuiltInRegistries.ITEM.getKey(e.getKey()).toString()))
                .limit(75).forEach(e->lines.add("MISSING "+e.getValue()+" x "+
                        BuiltInRegistries.ITEM.getKey(e.getKey())));
        lines.add("SELECTED RECIPE (ONE PATH ONLY)");
        for(String step:plan.steps()){
            if(lines.size()>=179)break;
            lines.add(step);
        }
        station.setChefPreview(player.getUUID(),itemId,lines);
        return true;
    }

    public static boolean order(ServerPlayer player,WorkerStationBlockEntity station,
                                String id,int amount){
        if(!station.isCooking()||!station.canConfigure(player.getUUID())
                ||station.chefBusy()||amount<1||amount>16)return false;
        if(!station.chefReserved().isEmpty()){
            station.setProblem("Return unused Chef ingredients to storage before a new order");
            return false;
        }
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
        var plan=ChefRecipePlanner.plan((ServerLevel)player.level(),item,amount,available,
                kitchen.hasCooker((ServerLevel)player.level()));
        if(plan==null){
            station.setProblem("Missing ingredients or a supported crafting/cooking recipe");
            return false;
        }
        // If the finished item is already in kitchen storage, the chef still
        // performs a plating step instead of conjuring new items.
        List<String> steps=plan.work().isEmpty()?List.of("Plate ready food"):
                plan.work().stream().map(ChefRecipePlanner.WorkStep::encode).toList();
        var escrow=new LinkedHashMap<String,Integer>();
        plan.ingredients().forEach((k,v)->escrow.put(BuiltInRegistries.ITEM.getKey(k).toString(),v));
        // Establish a saved order before taking ingredients from storage.
        // This closes the previous consume-then-start failure window which
        // could remove stock without any persisted recipe reservation.
        var surplus=new LinkedHashMap<String,Integer>();
        if(!station.startChefOrder(player.getUUID(),key.toString(),amount,steps,escrow,surplus)){
            station.setProblem("Could not reserve order");return false;
        }
        List<KitchenSupplies.Origin> withdrawn=kitchen.withdraw(plan.ingredients());
        if(withdrawn==null){
            station.clearChefOrder();
            station.setProblem("Kitchen stock changed; check supplies and order again");
            return false;
        }
        station.setChefOrigins(withdrawn);
        station.setProblem("");
        return true;
    }
    /**
     * Real work: chef walks to pantry to collect reserved components, carries
     * the actual ingredient, approaches a worktop or CFB oven, and executes
     * exactly one selected recipe. Cooking waits for the oven's real output.
     * Player inventory is never read, searched, or used as a source.
     */
    public static void tick(ServerLevel level,WorkerStationBlockEntity station){
        if(!station.isCooking())return;
        if(level.getGameTime()%100==0){
            var stock=new LinkedHashMap<String,Integer>();
            kitchen(level,station).snapshot().forEach((item,count)->
                    stock.put(BuiltInRegistries.ITEM.getKey(item).toString(),count));
            station.updateChefStock(stock);
        }
        List<ChefGolem> chefs=new ArrayList<>();
        for(UUID id:station.chefWorkerIds())
            if(level.getEntity(id) instanceof ChefGolem chef)chefs.add(chef);
        if(!station.chefBusy()
                ||station.phase()==com.caszgamermd.golemsplus.worker.WorkerPhase.PAUSED){
            for(ChefGolem chef:chefs)chef.setJobAppearance(false,ItemStack.EMPTY);
            // Paused recipes retain reserved ingredients for safe resume.
            // An idle kiosk with orphaned escrow returns it to storage.
            if(!station.chefBusy()&&!station.chefReserved().isEmpty()){
                KitchenSupplies pantry=kitchen(level,station);
                List<ItemStack> unused=reservedStacks(station.chefReserved());
                if(unused==null){
                    station.setProblem("Unrecognized idle Chef item: restore the mod before returning stock");
                    return;
                }
                if(pantry.connected()&&pantry.insertAll(unused,station.chefOrigins())){
                    station.clearChefOrder();station.setProblem("");
                }else station.setProblem("Kitchen storage full/disconnected: cannot return idle Chef stock");
            }
            return;
        }
        if(chefs.isEmpty()){
            station.setProblem("No Chef Golems loaded to fulfill this order");
            return;
        }
        KitchenSupplies pantry=kitchen(level,station);
        if(!pantry.connected()){
            station.setProblem("Linked Cooking for Blockheads kitchen disconnected");
            return;
        }
        if(!station.chefStepsFinished()){
            String raw=station.chefStepData();
            ChefRecipePlanner.WorkStep step;
            try{step=ChefRecipePlanner.WorkStep.decode(raw);}
            catch(IllegalArgumentException ex){
                // Compatible with orders already active in worlds saved by
                // older versions. Their ingredients were already reserved.
                BlockPos point=pantry.stationFor(level,"Assemble");
                if(!approachChefs(level,chefs,point,ItemStack.EMPTY,true))return;
                station.progressChef();
                if(station.chefWorkTicks()>=65)station.finishChefNonRecipe();
                return;
            }
            int ticks=station.chefWorkTicks();
            boolean heated=!step.method().equals("Assemble");
            BlockPos workplace=pantry.stationFor(level,step.method());
            if(workplace==null){
                station.setProblem("No connected oven for "+step.method()+" step");
                return;
            }
            BlockPos source=pantry.workPoint()==null?workplace:pantry.workPoint();
            BlockPos target=ticks<25?source:workplace;
            ItemStack carried=step.inputs().isEmpty()?ItemStack.EMPTY:
                    new ItemStack(step.inputs().keySet().iterator().next());
            if(!approachChefs(level,chefs,target,carried,ticks>=25)){
                if(level.getGameTime()%80==0)
                    station.setProblem("Chefs need a walkable kitchen floor near "+step.method());
                return;
            }
            station.setProblem("");
            if(ticks<25){
                // Travel to pantry and visibly pick up the planned item.
                station.progressChef();
                return;
            }
            if(heated){
                String problem=ChefOvenWork.progress(level,workplace,station,step);
                if(problem!=null){
                    if(level.getGameTime()%60==0)station.setProblem(problem);
                }else station.setProblem("");
                return;
            }
            // Recipe assembly occurs at a real kitchen work surface. Each
            // intermediate is materialized in the sealed job buffer and is
            // then used by later steps instead of being generated at pickup.
            station.progressChef();
            // Work physically occurs beside the linked kitchen's crafting
            // table/counter; emit sounds and visible preparation particles.
            if(level.getGameTime()%16==0){
                level.playSound(null,workplace,SoundEvents.WOODEN_BUTTON_CLICK_ON,
                        SoundSource.BLOCKS,.28f,.84f+level.getRandom().nextFloat()*.2f);
                level.sendParticles(ParticleTypes.POOF,workplace.getX()+.5,
                        workplace.getY()+1.08,workplace.getZ()+.5,
                        2,.18,.08,.18,.012);
            }
            if(station.chefWorkTicks()>=75){
                if(!station.completeChefWork(step))
                    station.setProblem("Planned ingredient unavailable in reserved job buffer");
            }
            return;
        }

        if(station.chefMealServed()){
            // The meal has already been physically placed and the order-up
            // bell sounded. Do not require that counter to stay empty while
            // returning utensils. Retry pantry returns without serving again.
            List<ItemStack> held=reservedStacks(station.chefReserved());
            if(held==null){
                station.setProblem("Cannot return a missing mod's cookware yet");
                return;
            }
            if(!pantry.insertAll(held,station.chefOrigins())){
                station.setProblem("Meal served; make room for cookware/leftovers in kitchen or oven");
                return;
            }
            for(ChefGolem chef:chefs)chef.setJobAppearance(false,ItemStack.EMPTY);
            station.clearChefOrder();
            station.setProblem("");
            return;
        }
        MealCounterBlockEntity pickup=counter(level,station.getBlockPos());
        if(pickup==null){station.setProblem("Meal Pickup Counter missing");return;}
        if(!pickup.food().isEmpty()){
            station.setProblem("Pickup counter occupied. Collect the previous meal");
            return;
        }
        Identifier id=Identifier.tryParse(station.chefFood());
        if(id==null||!BuiltInRegistries.ITEM.containsKey(id)){
            station.setProblem("Ordered food is no longer registered");return;
        }
        var reserved=new LinkedHashMap<>(station.chefReserved());
        int available=reserved.getOrDefault(id.toString(),0);
        if(available<station.chefQuantity()){
            station.setProblem("Prepared meal missing from job buffer; refusing free output");
            return;
        }
        int remain=available-station.chefQuantity();
        if(remain==0)reserved.remove(id.toString());
        else reserved.put(id.toString(),remain);
        List<ItemStack> leftovers=reservedStacks(reserved);
        if(leftovers==null){
            station.setProblem("Unknown reserved ingredient: keep the missing item's mod installed");
            return;
        }
        ItemStack plated=new ItemStack(BuiltInRegistries.ITEM.getValue(id),
                station.chefQuantity());
        // A finished plate is carried to the actual pickup counter first.
        if(!deliverPlate(level,chefs,station,pantry,pickup.getBlockPos(),plated)){
            if(level.getGameTime()%80==0)
                station.setProblem("Chef needs a clear floor route to the serving counter");
            return;
        }
        // Do not make a finished meal hostage to an unavailable cookware
        // return slot. Serve the real reserved meal first, then enter a
        // persistent return phase which retries independently.
        if(pickup.serve(plated)){
            station.markChefMealServed();
            level.playSound(null,pickup.getBlockPos(),SoundEvents.NOTE_BLOCK_BELL.value(),
                    SoundSource.BLOCKS,1.2f,1.65f);
            for(ChefGolem chef:chefs)
                chef.setJobAppearance(false,ItemStack.EMPTY);
            station.setProblem(leftovers.isEmpty()?"Meal ready for pickup":
                    "Meal ready; returning pots, pans and spare ingredients");
        }
    }

    private static List<ItemStack> reservedStacks(Map<String,Integer> reserved){
        List<ItemStack> stacks=new ArrayList<>();
        for(var entry:reserved.entrySet()){
            Identifier id=Identifier.tryParse(entry.getKey());
            if(id==null||!BuiltInRegistries.ITEM.containsKey(id))return null;
            Item item=BuiltInRegistries.ITEM.getValue(id);
            int count=entry.getValue();
            if(count<0||count>4096)return null;
            while(count>0){
                int portion=Math.min(count,Math.max(1,new ItemStack(item).getMaxStackSize()));
                stacks.add(new ItemStack(item,portion));count-=portion;
            }
        }
        return stacks;
    }

    private static boolean deliverPlate(ServerLevel level,List<ChefGolem> chefs,
            WorkerStationBlockEntity station,KitchenSupplies kitchen,
            BlockPos counter,ItemStack food){
        // Exactly ONE worker is the courier; other Chefs never magically
        // materialize the same plate while standing elsewhere.
        ChefGolem courier=chefs.get(0);
        for(int i=1;i<chefs.size();i++)
            chefs.get(i).setJobAppearance(false,ItemStack.EMPTY);
        BlockPos prep=kitchen.stationFor(level,"Assemble");
        if(prep==null)prep=kitchen.workPoint();
        if(!station.chefDeliveryStarted()){
            // Collect the prepared plate from the kitchen work surface
            // before walking it over. Persist this phase on the workstation.
            if(prep==null)return false;
            double distance=courier.distanceToSqr(prep.getX()+.5,
                    prep.getY()+.5,prep.getZ()+.5);
            courier.setJobAppearance(false,ItemStack.EMPTY);
            if(distance>5.3){
                if(level.getGameTime()%20==0||courier.getNavigation().isDone())
                    ChefFloorNavigation.work(level,courier,prep);
                return false;
            }
            station.setChefDeliveryStarted(true);
            return false;
        }
        courier.setJobAppearance(false,food);
        boolean arrived=courier.distanceToSqr(counter.getX()+.5,
                counter.getY()+.5,counter.getZ()+.5)<=10.0;
        if(!arrived&&(level.getGameTime()%20==0||courier.getNavigation().isDone()))
            ChefFloorNavigation.work(level,courier,counter);
        return arrived;
    }

    private static boolean approachChefs(ServerLevel level,List<ChefGolem> chefs,
                                         BlockPos target,ItemStack carrying,boolean preparing){
        if(target==null)return false;
        boolean ready=false;
        for(ChefGolem chef:chefs){
            double dist=chef.distanceToSqr(target.getX()+.5,target.getY()+.5,target.getZ()+.5);
            boolean nearby=dist<=13;
            chef.setJobAppearance(nearby&&preparing,carrying);
            if(nearby)ready=true;
            else if(level.getGameTime()%30==0||chef.getNavigation().isDone())
                ChefFloorNavigation.work(level,chef,target);
        }
        return ready;
    }
}
