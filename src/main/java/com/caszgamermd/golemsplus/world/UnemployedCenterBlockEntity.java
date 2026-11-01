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
    private static ItemStack genericWorker(ItemStack worker){
        // Never transfer custom names, components or entity identity into
        // the reusable worker pool. Cargo is unloaded before return.
        return new ItemStack(worker.getItem(),worker.getCount());
    }
    public boolean acceptWorker(ItemStack golem){
        if(!canPlaceItem(0,golem))return false;
        ItemStack generic=genericWorker(golem).copyWithCount(1);
        // Prefer an existing stack BEFORE an empty slot. This also makes
        // partially filled bins behave like ordinary Minecraft containers.
        for(int slot=0;slot<items.size();slot++){
            ItemStack previous=items.get(slot);
            if(!previous.isEmpty()
                    &&ItemStack.isSameItemSameComponents(previous,generic)
                    &&previous.getCount()<previous.getMaxStackSize()){
                items.set(slot,previous.copyWithCount(previous.getCount()+1));
                setChanged();return true;
            }
        }
        for(int slot=0;slot<items.size();slot++)
            if(items.get(slot).isEmpty()){
                items.set(slot,generic);
                setChanged();return true;
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
        items.set(slot,stack.isEmpty()?ItemStack.EMPTY:genericWorker(stack));
        setChanged();
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
        // Older releases persisted individual names in the bin. Migrate
        // them to anonymous, stackable profession items on world load.
        // Consolidation never discards a worker, even in a full bin.
        for(int slot=0;slot<items.size();slot++){
            ItemStack old=items.get(slot);
            if(!old.isEmpty()&&canPlaceItem(slot,old))
                items.set(slot,genericWorker(old));
        }
        for(int slot=1;slot<items.size();slot++){
            ItemStack source=items.get(slot);
            if(source.isEmpty())continue;
            for(int prior=0;prior<slot&&!source.isEmpty();prior++){
                ItemStack target=items.get(prior);
                if(target.isEmpty()
                        ||!ItemStack.isSameItemSameComponents(target,source))
                    continue;
                int moved=Math.min(source.getCount(),
                        target.getMaxStackSize()-target.getCount());
                if(moved<=0)continue;
                items.set(prior,target.copyWithCount(target.getCount()+moved));
                source=source.copyWithCount(source.getCount()-moved);
                items.set(slot,source.isEmpty()?ItemStack.EMPTY:source);
            }
        }
    }
}
