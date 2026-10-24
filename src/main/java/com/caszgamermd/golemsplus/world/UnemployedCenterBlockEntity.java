package com.caszgamermd.golemsplus.world;

import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Real stored worker items; never virtualize a loaded entity or its cargo. */
public final class UnemployedCenterBlockEntity extends BlockEntity
        implements Container,MenuProvider {
    private final NonNullList<ItemStack> items=NonNullList.withSize(27,ItemStack.EMPTY);
    public UnemployedCenterBlockEntity(BlockPos pos,BlockState state){
        super(WorksiteContent.UNEMPLOYED_CENTER_ENTITY,pos,state);
    }
    public boolean acceptWorker(ItemStack golem){
        if(!golem.is(WorksiteContent.BUILDER_WORKER_ITEM)
                &&!golem.is(WorksiteContent.CHEF_WORKER_ITEM)
                &&!golem.is(WorksiteContent.TRADER_WORKER_ITEM)
                &&!golem.is(WorksiteContent.BEEKEEPER_WORKER_ITEM)
                &&!golem.is(WorksiteContent.FISHER_WORKER_ITEM))return false;
        for(int slot=0;slot<items.size();slot++){
            ItemStack previous=items.get(slot);
            if(previous.isEmpty()){
                items.set(slot,golem.copyWithCount(1));
                setChanged();return true;
            }
            if(ItemStack.isSameItemSameComponents(previous,golem)
                    &&previous.getCount()<previous.getMaxStackSize()){
                items.set(slot,previous.copyWithCount(previous.getCount()+1));
                setChanged();return true;
            }
        }
        return false;
    }
    @Override public Component getDisplayName(){
        return Component.literal("Unemployed Center");
    }
    @Override public AbstractContainerMenu createMenu(int id,Inventory player,Player viewer){
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
        if(!moved.isEmpty())setChanged();
        return moved;
    }
    @Override public void setItem(int slot,ItemStack stack){
        if(slot<0||slot>=items.size()||!stack.isEmpty()
                &&!canPlaceItem(slot,stack))return;
        items.set(slot,stack.copy());setChanged();
    }
    @Override public boolean canPlaceItem(int slot,ItemStack stack){
        return stack.getItem() instanceof com.caszgamermd.golemsplus.worker.WorkerGolemItem;
    }
    @Override public void clearContent(){
        items.replaceAll(stack->ItemStack.EMPTY);setChanged();
    }
    @Override public boolean stillValid(Player player){
        return Container.stillValidBlockEntity(this,player,6.0f);
    }
    @Override protected void saveAdditional(ValueOutput out){
        super.saveAdditional(out);ContainerHelper.saveAllItems(out,items);
    }
    @Override protected void loadAdditional(ValueInput in){
        super.loadAdditional(in);
        items.replaceAll(stack->ItemStack.EMPTY);
        ContainerHelper.loadAllItems(in,items);
    }
}
