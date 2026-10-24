package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;

/** Real, component-preserving materials in the depot and supplied shulkers. */
public final class BuilderSupplyInventory {
    public record Receipt(Container source,int slot,ItemStack previous,
                          ItemStack consumed) {}
    private BuilderSupplyInventory(){}
    private static NonNullList<ItemStack> contents(ItemStack box){
        var slots=NonNullList.withSize(27,ItemStack.EMPTY);
        box.getOrDefault(DataComponents.CONTAINER,ItemContainerContents.EMPTY).copyInto(slots);
        return slots;
    }
    private static Container selectedStorage(WorkerStationBlockEntity table){
        BlockPos pos=table.builderMaterialStorage();
        if(pos==null||!(table.getLevel() instanceof ServerLevel world)
                ||!world.hasChunkAt(pos))return null;
        if(pos.equals(table.getBlockPos()))return null;
        if(table.builderOrigin().distSqr(pos)>128L*128L)return null;
        return world.getBlockEntity(pos) instanceof Container storage?storage:null;
    }
    private static java.util.List<Container> sources(WorkerStationBlockEntity table){
        Container selected=selectedStorage(table);
        return selected==null?java.util.List.of(table):java.util.List.of(selected,table);
    }
    public static Map<String,Integer> available(WorkerStationBlockEntity table){
        Map<String,Integer> stock=new LinkedHashMap<>();
        for(Container store:sources(table)){
            int max=store==table?WorkerStationBlockEntity.INPUT_SLOTS:store.getContainerSize();
            for(int i=0;i<max;i++){
                ItemStack source=store.getItem(i);
                if(source.isEmpty())continue;
                if(source.is(ItemTags.SHULKER_BOXES)&&source.has(DataComponents.CONTAINER)){
                    for(ItemStack item:contents(source))if(!item.isEmpty())
                        stock.merge(BuiltInRegistries.ITEM.getKey(item.getItem()).toString(),
                                item.getCount(),Integer::sum);
                }else stock.merge(BuiltInRegistries.ITEM.getKey(source.getItem()).toString(),
                        source.getCount(),Integer::sum);
            }
        }
        return stock;
    }
    /**
     * Used packing shulkers are not garbage. Return genuinely empty
     * container boxes to the Planner's 9 output slots, preserving dye and
     * all item components. If output is full, keep the box in inputs.
     */
    public static int recycleEmptyShulkers(WorkerStationBlockEntity table){
        int returned=0;
        for(int i=0;i<WorkerStationBlockEntity.INPUT_SLOTS;i++){
            ItemStack stack=table.getItem(i);
            if(stack.isEmpty()||!stack.is(ItemTags.SHULKER_BOXES)
                    ||!stack.has(DataComponents.CONTAINER)
                    ||stack.get(DataComponents.CONTAINER).nonEmptyItemCopyStream()
                        .findAny().isPresent())continue;
            if(!table.canReceive(stack))continue;
            if(table.deposit(stack.copy())){
                table.setItem(i,ItemStack.EMPTY);
                returned++;
            }
        }
        return returned;
    }
    public static boolean canStoreScaffolding(WorkerStationBlockEntity table){
        ItemStack sample=new ItemStack(net.minecraft.world.item.Items.SCAFFOLDING);
        for(int i=0;i<WorkerStationBlockEntity.INPUT_SLOTS;i++){
            ItemStack stack=table.getItem(i);
            if(stack.isEmpty()||(ItemStack.isSameItemSameComponents(stack,sample)
                    &&stack.getCount()<stack.getMaxStackSize()))return true;
        }
        return table.canReceive(sample);
    }
    /** Server-side exact stack merge; never deletes a real scaffold refund. */
    public static boolean storeScaffolding(WorkerStationBlockEntity table,ItemStack sample){
        for(int i=0;i<WorkerStationBlockEntity.INPUT_SLOTS;i++){
            ItemStack slot=table.getItem(i);
            if(!slot.isEmpty()&&ItemStack.isSameItemSameComponents(slot,sample)
                    &&slot.getCount()<slot.getMaxStackSize()){
                table.setItem(i,slot.copyWithCount(slot.getCount()+1));return true;
            }
        }
        for(int i=0;i<WorkerStationBlockEntity.INPUT_SLOTS;i++){
            if(table.getItem(i).isEmpty()){
                table.setItem(i,sample.copyWithCount(1));return true;
            }
        }
        return table.deposit(sample.copyWithCount(1));
    }
    public static boolean hasRoomForBox(WorkerStationBlockEntity table){
        // A configured site container is the destination, even if full.
        // Do not silently divert supplies to another storage location.
        Container destination=table.builderMaterialStorage()==null?table:selectedStorage(table);
        if(destination==null)return false;
        int limit=destination==table?WorkerStationBlockEntity.INPUT_SLOTS:destination.getContainerSize();
        ItemStack box=new ItemStack(net.minecraft.world.item.Items.SHULKER_BOX);
        for(int i=0;i<limit;i++)
            if(destination.getItem(i).isEmpty()&&destination.canPlaceItem(i,box))
                return true;
        return false;
    }
    public static boolean depositBox(WorkerStationBlockEntity table,ItemStack box){
        if(box.isEmpty()||!box.is(ItemTags.SHULKER_BOXES))return false;
        Container destination=table.builderMaterialStorage()==null?table:selectedStorage(table);
        if(destination==null)return false;
        int limit=destination==table?WorkerStationBlockEntity.INPUT_SLOTS:destination.getContainerSize();
        for(int i=0;i<limit;i++)if(destination.getItem(i).isEmpty()
                &&destination.canPlaceItem(i,box)){
            destination.setItem(i,box.copyWithCount(1));
            destination.setChanged();
            return true;
        }
        return false;
    }
    public static Receipt consume(WorkerStationBlockEntity table,Item item){
        for(Container store:sources(table)){
            int limit=store==table?WorkerStationBlockEntity.INPUT_SLOTS:store.getContainerSize();
            for(int i=0;i<limit;i++){
                ItemStack existing=store.getItem(i);
                if(existing.isEmpty())continue;
                if(existing.is(item)){
                    ItemStack before=existing.copy();
                    store.setItem(i,existing.copyWithCount(existing.getCount()-1));
                    store.setChanged();
                    return new Receipt(store,i,before,before.copyWithCount(1));
                }
                if(!existing.is(ItemTags.SHULKER_BOXES)
                        ||!existing.has(DataComponents.CONTAINER))continue;
                var nested=contents(existing);
                for(int j=0;j<nested.size();j++){
                    ItemStack stored=nested.get(j);
                    if(stored.isEmpty()||!stored.is(item))continue;
                    ItemStack before=existing.copy(),taken=stored.copyWithCount(1);
                    nested.set(j,stored.copyWithCount(stored.getCount()-1));
                    ItemStack updated=existing.copy();
                    updated.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(nested));
                    store.setItem(i,updated);
                    store.setChanged();
                    return new Receipt(store,i,before,taken);
                }
            }
        }
        return null;
    }
    public static void refund(WorkerStationBlockEntity table,Receipt receipt){
        // Single server tick: no other actor can mutate this slot between
        // consumption and a rejected world placement.
        receipt.source().setItem(receipt.slot(),receipt.previous());
        receipt.source().setChanged();
    }
}
