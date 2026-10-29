package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Controlled site preparation: only a mismatched block occupying one
 * positively identified schematic step may be removed. Never clear an
 * adjacent landscape region, other workers' scaffolds, inventories,
 * protected areas, fluids or indestructible blocks.
 */
public final class BuilderClearance{
    public enum Result{CLEARED,MOVING,BLOCKED}
    private BuilderClearance(){}
    public static Result clear(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,ServerPlayer owner,BlockPos pos,
            BlockState current){
        if(current.isAir())return Result.CLEARED;
        if(!level.hasChunkAt(pos)||!level.mayInteract(owner,pos)){
            table.setProblem("Cannot clear protected or unloaded construction block "+pos);
            return Result.BLOCKED;
        }
        if(table.isTrackedScaffold(pos)||level.getBlockEntity(pos)!=null
                ||!current.getFluidState().isEmpty()
                ||current.is(Blocks.BEDROCK)
                ||current.getDestroySpeed(level,pos)<0){
            table.setProblem("Site obstruction requires player review: "+pos);
            return Result.BLOCKED;
        }
        // Thin snow (and snow blocks) are harmless construction debris.
        // They are breakable by hand; requiring a shovel AND a scaffold
        // tower before removing a one-pixel snow layer deadlocks clearing.
        // Hard blocks still require a real borrowed OnSite Storage tool.
        boolean snow=current.is(Blocks.SNOW)||current.is(Blocks.SNOW_BLOCK);
        int slot=-1;
        if(!snow){
            var result=BuilderSupplyInventory.equipForClearing(level,worker,table,current);
            if(result==BuilderSupplyInventory.ToolStatus.MOVING)return Result.MOVING;
            if(result==BuilderSupplyInventory.ToolStatus.MISSING){
                table.setProblem("Supply a matching pickaxe, axe, shovel or hoe "
                        +"in OnSite Storage to clear "+pos);
                return Result.BLOCKED;
            }
            slot=BuilderSupplyInventory.toolSlot(worker,current);
            if(slot<0)return Result.BLOCKED;
        }
        if(worker.distanceToSqr(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)>14){
            com.caszgamermd.golemsplus.worker.WorkerNavigation.routeTo(
                    level,worker,pos,.84);
            return Result.MOVING;
        }
        // Clearing a support beneath the worker (or sealing the only
        // neighboring exit) is prohibited even if the target is in reach.
        if(BuilderEscapeRoute.preview(level,worker,pos,
                BuilderEscapeRoute.Action.BREAK)==null){
            table.setProblem("Builder needs independent escape before "
                    +"clearing "+pos.toShortString());
            return Result.BLOCKED;
        }
        ItemStack tool=snow?ItemStack.EMPTY:worker.getItem(slot);
        List<ItemStack> drops=Block.getDrops(current,level,pos,null,worker,tool);
        // Require room for ALL item drops before breaking the world block.
        // Drops may be zero legitimately, e.g. glass without Silk Touch.
        if(!BuilderSupplyInventory.storeDebris(table,drops,false)){
            table.setProblem("On-site debris container full; cannot clear "+pos);
            return Result.BLOCKED;
        }
        if(!level.destroyBlock(pos,false,worker)){
            table.setProblem("World refused Builder block removal at "+pos);
            return Result.BLOCKED;
        }
        if(!BuilderSupplyInventory.storeDebris(table,drops,true)){
            // This should not happen in the same server tick after the
            // simulation. Never void the drops if a mod mutates the chest
            // synchronously during block removal.
            for(ItemStack drop:drops)
                if(!drop.isEmpty())net.minecraft.world.Containers.dropItemStack(
                        level,pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5,drop);
            table.setProblem("Debris storage changed; dropped recovered items at site");
        }else table.setProblem("");
        // Explicit durability debit: no magic infinitely durable tools.
        if(slot>=0&&tool.isDamageableItem()){
            ItemStack used=tool.copy();
            used.setDamageValue(used.getDamageValue()+1);
            worker.setItem(slot,used.getDamageValue()>=used.getMaxDamage()?
                    ItemStack.EMPTY:used);
        }
        return Result.CLEARED;
    }
}
