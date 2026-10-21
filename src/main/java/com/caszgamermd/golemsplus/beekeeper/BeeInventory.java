package com.caszgamermd.golemsplus.beekeeper;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

/** Exact-stack capacity checks and transfers, without overfilling or loss. */
public final class BeeInventory {
    private BeeInventory(){}
    public static boolean canFit(Container to,ItemStack item){
        if(item.isEmpty())return false;
        int left=item.getCount();
        for(int i=0;i<to.getContainerSize();i++){
            ItemStack there=to.getItem(i);
            if(!to.canPlaceItem(i,item))continue;
            if(there.isEmpty())left-=item.getMaxStackSize();
            else if(ItemStack.isSameItemSameComponents(there,item))
                left-=Math.max(0,there.getMaxStackSize()-there.getCount());
            if(left<=0)return true;
        }
        return false;
    }
    public static boolean insert(Container to,ItemStack item){
        if(!canFit(to,item))return false;
        int left=item.getCount();
        for(int i=0;i<to.getContainerSize()&&left>0;i++){
            ItemStack slot=to.getItem(i);
            if(!to.canPlaceItem(i,item)||slot.isEmpty()
                    ||!ItemStack.isSameItemSameComponents(slot,item))continue;
            int n=Math.min(left,slot.getMaxStackSize()-slot.getCount());
            if(n>0){to.setItem(i,slot.copyWithCount(slot.getCount()+n));left-=n;}
        }
        for(int i=0;i<to.getContainerSize()&&left>0;i++){
            if(!to.canPlaceItem(i,item)||!to.getItem(i).isEmpty())continue;
            int n=Math.min(left,item.getMaxStackSize());
            to.setItem(i,item.copyWithCount(n));left-=n;
        }
        to.setChanged();
        return true;
    }
    public static ItemStack takeOne(Container from,net.minecraft.world.item.Item item){
        for(int i=0;i<from.getContainerSize();i++){
            if(from.getItem(i).is(item)){
                ItemStack stack=from.removeItem(i,1);
                from.setChanged();return stack;
            }
        }
        return ItemStack.EMPTY;
    }
}
