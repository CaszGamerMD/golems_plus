package com.caszgamermd.golemsplus.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** 27 payment slots and 9 result slots, with player inventory. */
public final class WorkerStationMenu extends AbstractContainerMenu {
    private final Container depot;
    public WorkerStationMenu(int id,Inventory inventory,BlockPos position){
        this(id,inventory,inventory.player.level().getBlockEntity(position)
                instanceof WorkerStationBlockEntity station?station:null);
    }
    public WorkerStationMenu(int id,Inventory inventory,Container source){
        super(WorksiteContent.STATION_MENU,id);
        depot=source==null?new SimpleContainer(WorkerStationBlockEntity.SLOTS):source;
        for(int i=0;i<WorkerStationBlockEntity.SLOTS;i++){
            final int slot=i;
            addSlot(new Slot(depot,i,8+(i%9)*18,29+(i/9)*18){
                @Override public boolean mayPlace(ItemStack stack){
                    return slot<WorkerStationBlockEntity.INPUT_SLOTS;
                }
            });
        }
        addStandardInventorySlots(inventory,8,121);
    }
    @Override public boolean stillValid(Player player){return depot.stillValid(player);}
    @Override public ItemStack quickMoveStack(Player player,int index){
        if(index<0||index>=slots.size())return ItemStack.EMPTY;
        Slot src=slots.get(index);
        if(!src.hasItem())return ItemStack.EMPTY;
        ItemStack stack=src.getItem(),result=stack.copy();
        boolean moved=index<WorkerStationBlockEntity.SLOTS
                ?moveItemStackTo(stack,WorkerStationBlockEntity.SLOTS,slots.size(),true)
                :moveItemStackTo(stack,0,WorkerStationBlockEntity.INPUT_SLOTS,false);
        if(!moved)return ItemStack.EMPTY;
        if(stack.isEmpty())src.setByPlayer(ItemStack.EMPTY);
        else src.setChanged();
        return result;
    }
}
