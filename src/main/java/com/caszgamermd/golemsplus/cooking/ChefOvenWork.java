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
        if(entity==null)return "Connected oven block entity missing";
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
    private static Container container(BlockEntity entity,String getter)
            throws ReflectiveOperationException{
        Method m=entity.getClass().getMethod(getter);
        Object result=m.invoke(entity);
        return result instanceof Container c?c:null;
    }
}
