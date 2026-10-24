package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
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
        // Materials loaded into the assigned workers' saved nine-slot
        // pouches are not "missing" from this construction job. This
        // prevents concurrent warehouse tickets from double-ordering.
        if(table.getLevel() instanceof ServerLevel world){
            for(var workerId:table.builderWorkerIds()){
                if(!(world.getEntity(workerId) instanceof BuilderGolem golem))continue;
                for(int i=0;i<golem.getContainerSize();i++){
                    ItemStack item=golem.getItem(i);
                    if(!item.isEmpty())
                        stock.merge(BuiltInRegistries.ITEM.getKey(item.getItem()).toString(),
                                item.getCount(),Integer::sum);
                }
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
    private static int tryPocket(BuilderGolem worker,ItemStack sample,int amount){
        int remaining=amount;
        for(int i=0;i<worker.getContainerSize()&&remaining>0;i++){
            ItemStack current=worker.getItem(i);
            if(current.isEmpty()||!ItemStack.isSameItemSameComponents(current,sample))
                continue;
            int space=Math.max(0,current.getMaxStackSize()-current.getCount());
            int moved=Math.min(space,remaining);
            if(moved>0){
                worker.setItem(i,current.copyWithCount(current.getCount()+moved));
                remaining-=moved;
            }
        }
        for(int i=0;i<worker.getContainerSize()&&remaining>0;i++){
            if(!worker.getItem(i).isEmpty())continue;
            int moved=Math.min(sample.getMaxStackSize(),remaining);
            worker.setItem(i,sample.copyWithCount(moved));
            remaining-=moved;
        }
        return amount-remaining;
    }
    private static int room(BuilderGolem worker,ItemStack item){
        int space=0;
        for(int i=0;i<worker.getContainerSize();i++){
            ItemStack have=worker.getItem(i);
            if(have.isEmpty())space+=item.getMaxStackSize();
            else if(ItemStack.isSameItemSameComponents(have,item))
                space+=Math.max(0,have.getMaxStackSize()-have.getCount());
        }
        return space;
    }
    private static boolean includesItem(Container container,int limit,Item item){
        for(int i=0;i<limit;i++){
            ItemStack stack=container.getItem(i);
            if(stack.is(item))return true;
            if(stack.is(ItemTags.SHULKER_BOXES)&&stack.has(DataComponents.CONTAINER))
                for(ItemStack nested:contents(stack))
                    if(nested.is(item))return true;
        }
        return false;
    }
    /** Move real items into the Builder's persistent nine-slot pouch.
     * No teleported consumption: the golem first has to reach the physical
     * chest/Planner. We load up to one full stack of each type needed over
     * the next three layers, limited by its pouch capacity.
     */
    public static boolean loadForLayer(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,List<BuilderConstructionPlan.Step> steps,
            int index){
        if(index<0||index>=steps.size())return false;
        var current=steps.get(index);
        var id=net.minecraft.resources.Identifier.tryParse(current.requiredItem()==null?
                "":current.requiredItem());
        if(id==null||!BuiltInRegistries.ITEM.containsKey(id))return false;
        Item target=BuiltInRegistries.ITEM.getValue(id);
        if(worker.carriedCount(target)>0)return true;
        Map<String,Integer> wanted=BuilderMaterialDemand.batch(steps,index,Map.of());
        var selections=new ArrayList<>(wanted.entrySet());
        selections.sort((left,right)->{
            boolean a=left.getKey().equals(current.requiredItem());
            boolean c=right.getKey().equals(current.requiredItem());
            return a==c?left.getKey().compareTo(right.getKey()):(a?-1:1);
        });
        for(var row:selections){
            var key=net.minecraft.resources.Identifier.tryParse(row.getKey());
            if(key==null||!BuiltInRegistries.ITEM.containsKey(key))continue;
            Item requested=BuiltInRegistries.ITEM.getValue(key);
            int needed=Math.max(0,row.getValue()-worker.carriedCount(requested));
            if(needed==0)continue;
            Container source=null;
            BlockPos location=null;
            Container site=selectedStorage(table);
            if(site!=null&&includesItem(site,site.getContainerSize(),requested)){
                source=site;location=table.builderMaterialStorage();
            }else if(includesItem(table,WorkerStationBlockEntity.INPUT_SLOTS,requested)){
                source=table;location=table.getBlockPos();
            }
            if(source==null)continue;
            if(worker.distanceToSqr(location.getX()+.5,location.getY()+.5,
                    location.getZ()+.5)>12){
                com.caszgamermd.golemsplus.worker.WorkerNavigation.routeTo(
                        level,worker,location,.83);
                return false;
            }
            int limit=source==table?WorkerStationBlockEntity.INPUT_SLOTS:
                    source.getContainerSize();
            for(int i=0;i<limit&&needed>0;i++){
                ItemStack original=source.getItem(i);
                if(original.isEmpty())continue;
                if(original.is(requested)){
                    int n=Math.min(needed,Math.min(original.getCount(),
                            room(worker,original)));
                    if(n<=0)continue;
                    int accepted=tryPocket(worker,original,n);
                    if(accepted>0){
                        source.setItem(i,original.copyWithCount(
                                original.getCount()-accepted));
                        source.setChanged();needed-=accepted;
                    }
                }else if(original.is(ItemTags.SHULKER_BOXES)
                        &&original.has(DataComponents.CONTAINER)){
                    var nested=contents(original);
                    boolean changed=false;
                    for(int j=0;j<nested.size()&&needed>0;j++){
                        ItemStack stack=nested.get(j);
                        if(!stack.is(requested))continue;
                        int n=Math.min(needed,Math.min(stack.getCount(),
                                room(worker,stack)));
                        if(n<=0)continue;
                        int accepted=tryPocket(worker,stack,n);
                        if(accepted>0){
                            nested.set(j,stack.copyWithCount(stack.getCount()-accepted));
                            needed-=accepted;changed=true;
                        }
                    }
                    if(changed){
                        ItemStack packed=original.copy();
                        packed.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(nested));
                        source.setItem(i,packed);source.setChanged();
                    }
                }
            }
        }
        return worker.carriedCount(target)>0;
    }
    public static Receipt consume(BuilderGolem worker,Item item){
        for(int i=0;i<worker.getContainerSize();i++){
            ItemStack have=worker.getItem(i);
            if(have.isEmpty()||!have.is(item))continue;
            ItemStack previous=have.copy();
            worker.setItem(i,have.copyWithCount(have.getCount()-1));
            return new Receipt(worker,i,previous,previous.copyWithCount(1));
        }
        return null;
    }
    /** Return unused real pouch items at the next accessible site container.
     * If storage fills up, leave remaining stock persisted on the golem.
     */
    public static boolean returnPouch(WorkerStationBlockEntity table,BuilderGolem worker){
        Container target=table.builderMaterialStorage()==null?table:selectedStorage(table);
        if(target==null)return false;
        int count=target==table?WorkerStationBlockEntity.INPUT_SLOTS:target.getContainerSize();
        for(int i=0;i<worker.getContainerSize();i++){
            ItemStack stack=worker.getItem(i);
            if(stack.isEmpty())continue;
            int left=stack.getCount();
            for(int k=0;k<count&&left>0;k++){
                ItemStack existing=target.getItem(k);
                if(!existing.isEmpty()&&ItemStack.isSameItemSameComponents(existing,stack)
                        &&target.canPlaceItem(k,stack)){
                    int accepted=Math.min(left,existing.getMaxStackSize()-existing.getCount());
                    if(accepted>0){
                        target.setItem(k,existing.copyWithCount(existing.getCount()+accepted));
                        left-=accepted;
                    }
                }
            }
            for(int k=0;k<count&&left>0;k++){
                if(!target.getItem(k).isEmpty()||!target.canPlaceItem(k,stack))continue;
                int accepted=Math.min(left,stack.getMaxStackSize());
                target.setItem(k,stack.copyWithCount(accepted));
                left-=accepted;
            }
            worker.setItem(i,stack.copyWithCount(left));
            target.setChanged();
        }
        return worker.isEmpty();
    }
    public static BlockPos pouchReturnPos(WorkerStationBlockEntity table){
        return table.builderMaterialStorage()==null?table.getBlockPos():
                table.builderMaterialStorage();
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
