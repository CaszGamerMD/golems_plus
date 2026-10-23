package com.caszgamermd.golemsplus.cooking;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Physically puts food into a connected Cooking for Blockheads oven, waits
 * for its natural furnace/fuel processing, then removes the real output.
 *
 * Requires an idle oven for exclusive use during this batch; refusing an
 * occupied oven is safer than taking food belonging to another player.
 */
final class ChefOvenWork {
    private ChefOvenWork(){}
    static String progress(ServerLevel level,BlockPos at,WorkerStationBlockEntity station,
                           ChefRecipePlanner.WorkStep step) {
        if(at==null||!level.hasChunkAt(at))return "No connected Cooking for Blockheads oven";
        BlockEntity entity=level.getBlockEntity(at);
        if(entity==null)return "Connected oven or furnace block entity missing";
        if(entity instanceof net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnace)
            return vanillaFurnace(level,furnace,station,step);
        try{
            Container internal=container(entity,"getInternalContainer");
            Container input=container(entity,"getInputContainer");
            if(internal==null||input==null||internal.getContainerSize()<16)return "Oven interface unavailable";
            if(step.inputs().size()!=1)return "Cooking recipe requires one raw ingredient";
            var raw=step.inputs().entrySet().iterator().next();
            ItemStack initial=new ItemStack(raw.getKey());
            Object result=entity.getClass().getMethod("getSmeltingResult",ItemStack.class)
                    .invoke(entity,initial);
            if(!(result instanceof ItemStack cooked)||cooked.isEmpty()
                    ||!cooked.is(step.result())
                    ||cooked.getCount()*raw.getValue()!=step.resultCount())
                return "Selected cooking recipe is not supported by this oven";
            if(!station.chefOvenLoaded()){
                for(int i=0;i<internal.getContainerSize();i++){
                    // Only begin a batch when the oven's ordinary inputs,
                    // processing area and outputs are empty.
                    if(i==3||i>=16)continue; // fuel and utensils are allowed
                    if(!internal.getItem(i).isEmpty())
                        return "Oven is in use; waiting for empty input and output";
                }
                if(raw.getValue()>initial.getMaxStackSize())
                    return "Oven batch exceeds stack size";
                if(!station.loadChefOven(step))
                    return "Reserved cooking ingredients are missing";
                input.setItem(0,initial.copyWithCount(raw.getValue()));
                input.setChanged();
                return "Oven is heating raw ingredients";
            }
            int output=0;
            for(int i=4;i<=6;i++)if(internal.getItem(i).is(step.result()))
                output+=internal.getItem(i).getCount();
            if(output<step.resultCount())return "Waiting for oven to finish cooking (check fuel)";
            int left=step.resultCount();
            for(int i=4;i<=6&&left>0;i++){
                ItemStack food=internal.getItem(i);
                if(!food.is(step.result()))continue;
                int moved=Math.min(left,food.getCount());
                internal.removeItem(i,moved);left-=moved;
            }
            internal.setChanged();
            if(!station.collectChefOven(step))return "Could not record cooked food";
            return null; // one actual cooking step was completed
        }catch(ReflectiveOperationException|RuntimeException|LinkageError ex){
            return "Oven integration failed: "+ex.getClass().getSimpleName();
        }
    }
    /** Real vanilla furnace/smoker work. The Chef must load real items
     * from its reserved order, wait for genuine fuel-based cooking, and
     * collect only the actual resulting output. */
    private static String vanillaFurnace(ServerLevel level,
            net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity furnace,
            WorkerStationBlockEntity station,ChefRecipePlanner.WorkStep step){
        if(step.inputs().size()!=1)
            return "Furnace recipe requires one raw ingredient";
        var ingredient=step.inputs().entrySet().iterator().next();
        ItemStack input=furnace.getItem(0);
        ItemStack output=furnace.getItem(2);
        if(!station.chefOvenLoaded()){
            if(!input.isEmpty()||!output.isEmpty())
                return "Furnace is occupied; clear its input/output first";
            if(ingredient.getValue()<1
                    ||ingredient.getValue()>new ItemStack(ingredient.getKey()).getMaxStackSize())
                return "Furnace batch is too large for one input slot";
            if(!station.loadChefOven(step))
                return "Reserved raw ingredients missing from Chef order";
            furnace.setItem(0,new ItemStack(ingredient.getKey(),ingredient.getValue()));
            furnace.setChanged();
            return "Chef loaded "+ingredient.getValue()+" raw items into furnace";
        }
        if(!output.isEmpty()&&!output.is(step.result()))
            return "Furnace output occupied by another item";
        if(output.getCount()<step.resultCount()){
            if(furnace.getItem(1).isEmpty()&&!furnace.getBlockState()
                    .getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LIT))
                return "Add fuel to nearby furnace/smoker to cook this ingredient";
            return "Furnace is cooking recipe ingredients";
        }
        ItemStack cooked=furnace.removeItem(2,step.resultCount());
        if(cooked.getCount()!=step.resultCount()||!cooked.is(step.result())){
            if(!cooked.isEmpty()){
                ItemStack existing=furnace.getItem(2);
                if(existing.isEmpty())furnace.setItem(2,cooked);
                else if(ItemStack.isSameItemSameComponents(existing,cooked)){
                    existing.grow(cooked.getCount());furnace.setChanged();
                }
            }
            return "Furnace output changed during pickup";
        }
        furnace.setChanged();
        if(!station.collectChefOven(step)){
            ItemStack existing=furnace.getItem(2);
            if(existing.isEmpty())furnace.setItem(2,cooked);
            else if(ItemStack.isSameItemSameComponents(existing,cooked)){
                existing.grow(cooked.getCount());furnace.setChanged();
            }
            return "Chef cannot record cooked result; kept output in furnace";
        }
        return null;
    }

    private static Container container(BlockEntity entity,String getter)
            throws ReflectiveOperationException{
        Method m=entity.getClass().getMethod(getter);
        Object result=m.invoke(entity);
        return result instanceof Container c?c:null;
    }
}
