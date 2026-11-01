package com.caszgamermd.golemsplus.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** A virtual creative depot: takes unused Builder cargo back, but stores none.
 * BuilderSupplyInventory grants exactly the requested items only once a real
 * Builder is physically next to this crate. Nothing is put in the world remotely.
 */
public final class CreativeCrateBlockEntity extends BlockEntity implements Container {
    public CreativeCrateBlockEntity(BlockPos pos,BlockState state){
        super(WorksiteContent.CREATIVE_CRATE_ENTITY,pos,state);
    }
    @Override public int getContainerSize(){return 27;}
    @Override public boolean isEmpty(){return true;}
    @Override public ItemStack getItem(int slot){return ItemStack.EMPTY;}
    @Override public ItemStack removeItem(int slot,int count){return ItemStack.EMPTY;}
    @Override public ItemStack removeItemNoUpdate(int slot){return ItemStack.EMPTY;}
    @Override public void setItem(int slot,ItemStack item){}
    @Override public boolean canPlaceItem(int slot,ItemStack item){
        return slot>=0&&slot<27;
    }
    @Override public void clearContent(){}
    @Override public boolean stillValid(Player player){
        return Container.stillValidBlockEntity(this,player,6.0f);
    }
}
