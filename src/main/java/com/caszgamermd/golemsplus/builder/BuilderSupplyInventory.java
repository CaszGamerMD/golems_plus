package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;

/** Real, component-preserving materials in the depot and supplied shulkers. */
public final class BuilderSupplyInventory {
    public record Receipt(int slot,ItemStack previous,ItemStack consumed) {}
    private BuilderSupplyInventory(){}
    private static NonNullList<ItemStack> contents(ItemStack box){
        var slots=NonNullList.withSize(27,ItemStack.EMPTY);
        box.getOrDefault(DataComponents.CONTAINER,ItemContainerContents.EMPTY).copyInto(slots);
        return slots;
    }
    public static Map<String,Integer> available(WorkerStationBlockEntity table){
        Map<String,Integer> stock=new LinkedHashMap<>();
        for(int i=0;i<WorkerStationBlockEntity.INPUT_SLOTS;i++){
            ItemStack source=table.getItem(i);
            if(source.isEmpty())continue;
            if(source.is(ItemTags.SHULKER_BOXES)&&source.has(DataComponents.CONTAINER)){
                for(ItemStack item:contents(source))if(!item.isEmpty())
                    stock.merge(BuiltInRegistries.ITEM.getKey(item.getItem()).toString(),
                            item.getCount(),Integer::sum);
            }else stock.merge(BuiltInRegistries.ITEM.getKey(source.getItem()).toString(),
                    source.getCount(),Integer::sum);
        }
        return stock;
    }
    public static boolean hasRoomForBox(WorkerStationBlockEntity table){
        for(int i=0;i<WorkerStationBlockEntity.INPUT_SLOTS;i++)
            if(table.getItem(i).isEmpty())return true;
        return false;
    }
    public static boolean depositBox(WorkerStationBlockEntity table,ItemStack box){
        if(box.isEmpty()||!box.is(ItemTags.SHULKER_BOXES))return false;
        for(int i=0;i<WorkerStationBlockEntity.INPUT_SLOTS;i++)
            if(table.getItem(i).isEmpty()){
                table.setItem(i,box.copyWithCount(1));
                return true;
            }
        return false;
    }
    public static Receipt consume(WorkerStationBlockEntity table,Item item){
        for(int i=0;i<WorkerStationBlockEntity.INPUT_SLOTS;i++){
            ItemStack existing=table.getItem(i);
            if(existing.isEmpty())continue;
            if(existing.is(item)){
                ItemStack before=existing.copy();
                ItemStack taken=existing.copyWithCount(1);
                table.setItem(i,existing.copyWithCount(existing.getCount()-1));
                return new Receipt(i,before,taken);
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
                table.setItem(i,updated);
                return new Receipt(i,before,taken);
            }
        }
        return null;
    }
    public static void refund(WorkerStationBlockEntity table,Receipt receipt){
        // Single server tick: no other actor can mutate this slot between
        // consumption and a rejected world placement.
        table.setItem(receipt.slot(),receipt.previous());
    }
}
