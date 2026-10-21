package com.caszgamermd.golemsplus.beekeeper;

import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.MenuProvider;

/** Server-owned real tools. Hoppers may insert shears and empty bottles only. */
public final class BeeToolboxBlockEntity extends BlockEntity implements Container,MenuProvider {
    private final NonNullList<ItemStack> items=NonNullList.withSize(27,ItemStack.EMPTY);
    public BeeToolboxBlockEntity(BlockPos pos,BlockState state){
        super(WorksiteContent.BEE_TOOLBOX_ENTITY,pos,state);
    }
    @Override public Component getDisplayName(){return Component.literal("Beekeeper Toolbox");}
    @Override public AbstractContainerMenu createMenu(int id,Inventory player,Player owner){
        return ChestMenu.threeRows(id,player,this);
    }
    @Override public int getContainerSize(){return items.size();}
    @Override public boolean isEmpty(){return items.stream().allMatch(ItemStack::isEmpty);}
    @Override public ItemStack getItem(int slot){
        return slot>=0&&slot<items.size()?items.get(slot):ItemStack.EMPTY;
    }
    @Override public ItemStack removeItem(int slot,int count){
        if(slot<0||slot>=items.size())return ItemStack.EMPTY;
        ItemStack moved=ContainerHelper.removeItem(items,slot,count);
        if(!moved.isEmpty())setChanged();
        return moved;
    }
    @Override public ItemStack removeItemNoUpdate(int slot){
        if(slot<0||slot>=items.size())return ItemStack.EMPTY;
        ItemStack moved=ContainerHelper.takeItem(items,slot);
        setChanged();return moved;
    }
    @Override public void setItem(int slot,ItemStack stack){
        if(slot<0||slot>=items.size())return;
        items.set(slot,stack.copy());setChanged();
    }
    @Override public boolean canPlaceItem(int slot,ItemStack stack){
        return stack.is(Items.SHEARS)||stack.is(Items.GLASS_BOTTLE);
    }
    @Override public void clearContent(){
        for(int i=0;i<items.size();i++)items.set(i,ItemStack.EMPTY);
        setChanged();
    }
    @Override public boolean stillValid(Player player){
        return Container.stillValidBlockEntity(this,player,6f);
    }
    @Override protected void saveAdditional(ValueOutput out){
        super.saveAdditional(out);ContainerHelper.saveAllItems(out,items);
    }
    @Override protected void loadAdditional(ValueInput in){
        super.loadAdditional(in);
        for(int i=0;i<items.size();i++)items.set(i,ItemStack.EMPTY);
        ContainerHelper.loadAllItems(in,items);
    }
}
