package com.caszgamermd.golemsplus.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Specialist settings; the Chef kiosk has *no* item slots.
 * Its pantry and finished meal storage live in the linked kitchen and
 * Meal Pickup Counter, not in this menu or this workstation. */
public final class WorkerStationMenu extends AbstractContainerMenu {
    private final Container depot;
    private final BlockPos position;
    private final boolean kiosk;
    public WorkerStationMenu(int id,Inventory inventory,BlockPos position){
        this(id,inventory,inventory.player.level().getBlockEntity(position)
                instanceof WorkerStationBlockEntity station?station:null,position);
    }
    public WorkerStationMenu(int id,Inventory inventory,Container source){
        this(id,inventory,source,source instanceof WorkerStationBlockEntity station
                ?station.getBlockPos():BlockPos.ZERO);
    }
    private WorkerStationMenu(int id,Inventory inventory,Container source,BlockPos position){
        super(WorksiteContent.STATION_MENU,id);
        this.position=position;
        depot=source==null?new SimpleContainer(WorkerStationBlockEntity.SLOTS):source;
        kiosk=(source instanceof WorkerStationBlockEntity station&&station.isCooking())
                ||inventory.player.level().getBlockState(position).getBlock()==WorksiteContent.COOKING_STATION;
        if(kiosk)return; // no hidden/ghost inventory slots for the kiosk
        for(int i=0;i<WorkerStationBlockEntity.SLOTS;i++){
            final int slot=i;
            addSlot(new Slot(depot,i,8+(i%9)*18,29+(i/9)*18){
                @Override public boolean mayPlace(ItemStack stack){
                    return depot.canPlaceItem(slot,stack);
                }
            });
        }
        addStandardInventorySlots(inventory,8,121);
    }
    public BlockPos stationPos(){return position;}
    public boolean isKitchenKiosk(){return kiosk;}
    @Override public boolean stillValid(Player player){return depot.stillValid(player);}
    @Override public ItemStack quickMoveStack(Player player,int index){
        if(kiosk)return ItemStack.EMPTY;
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
