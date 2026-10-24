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
            // When a nine-slot pouch contains leftovers from prior layers,
            // unload them at the *physical* staging chest before trying the
            // next recipe material. Never silently deadlock at pouch capacity.
            if(room(worker,new ItemStack(target))<=0){
                returnPouch(table,worker);
                if(room(worker,new ItemStack(target))<=0)return false;
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
    /** Store actual block drops in the designated on-site container. The
     * entire set is simulated before touching any slot; if storage lacks
     * space, the obstruction is NOT removed.
     */
    public static boolean storeDebris(WorkerStationBlockEntity table,
            java.util.List<ItemStack> drops,boolean commit){
        Container destination=table.builderMaterialStorage()==null
                ?table:selectedStorage(table);
        if(destination==null)return false;
        int count=destination==table?WorkerStationBlockEntity.INPUT_SLOTS:
                destination.getContainerSize();
        ItemStack[] planned=new ItemStack[count];
        for(int i=0;i<count;i++)planned[i]=destination.getItem(i).copy();
        for(ItemStack drop:drops){
            if(drop.isEmpty())continue;
            int remaining=drop.getCount();
            for(int pass=0;pass<2;pass++)
                for(int slot=0;slot<count&&remaining>0;slot++){
                    ItemStack existing=planned[slot];
                    if(pass==0&&existing.isEmpty())continue;
                    if(pass==1&&!existing.isEmpty())continue;
                    if(!existing.isEmpty()&&!ItemStack.isSameItemSameComponents(
                            existing,drop))continue;
                    if(!destination.canPlaceItem(slot,drop))continue;
                    int capacity=Math.min(drop.getMaxStackSize(),
                            destination.getMaxStackSize());
                    int room=capacity-(existing.isEmpty()?0:existing.getCount());
                    int moved=Math.min(remaining,Math.max(0,room));
                    if(moved==0)continue;
                    planned[slot]=drop.copyWithCount(moved+
                            (existing.isEmpty()?0:existing.getCount()));
                    remaining-=moved;
                }
            if(remaining>0)return false;
        }
        if(commit){
            for(int i=0;i<count;i++)
                if(!ItemStack.matches(destination.getItem(i),planned[i]))
                    destination.setItem(i,planned[i]);
            destination.setChanged();
        }
        return true;
    }
    public static boolean isMiningTool(ItemStack item){
        return item.is(net.minecraft.tags.ItemTags.PICKAXES)
                ||item.is(net.minecraft.tags.ItemTags.AXES)
                ||item.is(net.minecraft.tags.ItemTags.SHOVELS)
                ||item.is(net.minecraft.tags.ItemTags.HOES);
    }
    public static boolean correctTool(ItemStack item,
            net.minecraft.world.level.block.state.BlockState block){
        if(!isMiningTool(item))return false;
        if(block.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE))
            return item.is(net.minecraft.tags.ItemTags.PICKAXES);
        if(block.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE))
            return item.is(net.minecraft.tags.ItemTags.AXES);
        if(block.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_SHOVEL))
            return item.is(net.minecraft.tags.ItemTags.SHOVELS);
        if(block.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_HOE))
            return item.is(net.minecraft.tags.ItemTags.HOES);
        return !block.requiresCorrectToolForDrops();
    }
    public enum ToolStatus { READY, MOVING, MISSING }
    public static int toolSlot(BuilderGolem worker,
            net.minecraft.world.level.block.state.BlockState target){
        for(int i=0;i<worker.getContainerSize();i++)
            if(correctTool(worker.getItem(i),target))return i;
        return -1;
    }
    public static ToolStatus equipForClearing(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,
            net.minecraft.world.level.block.state.BlockState target){
        if(toolSlot(worker,target)>=0)return ToolStatus.READY;
        int selected=-1;
        for(int i=0;i<WorkerStationBlockEntity.INPUT_SLOTS;i++)
            if(correctTool(table.getItem(i),target)){selected=i;break;}
        if(selected<0)return ToolStatus.MISSING;
        int pocket=-1;
        for(int i=0;i<worker.getContainerSize();i++)
            if(worker.getItem(i).isEmpty()){pocket=i;break;}
        if(pocket<0){
            // A full pouch is NOT a missing pickaxe. Make a physical
            // unload trip to the selected site chest, then go back to
            // the Planner to borrow the real tool. Never delete stock
            // to create an inventory slot.
            BlockPos depot=pouchReturnPos(table);
            if(!level.hasChunkAt(depot))return ToolStatus.MISSING;
            if(worker.distanceToSqr(depot.getX()+.5,
                    depot.getY()+.5,depot.getZ()+.5)>12){
                com.caszgamermd.golemsplus.worker.WorkerNavigation.routeTo(
                        level,worker,depot,.84);
                return ToolStatus.MOVING;
            }
            returnPouch(table,worker);
            for(int i=0;i<worker.getContainerSize();i++)
                if(worker.getItem(i).isEmpty()){pocket=i;break;}
            if(pocket<0)return ToolStatus.MISSING;
        }
        BlockPos station=table.getBlockPos();
        if(worker.distanceToSqr(station.getX()+.5,
                station.getY()+.5,station.getZ()+.5)>12){
            com.caszgamermd.golemsplus.worker.WorkerNavigation.routeTo(
                    level,worker,station,.85);
            return ToolStatus.MOVING;
        }
        ItemStack stored=table.getItem(selected);
        worker.setItem(pocket,stored.copyWithCount(1));
        table.setItem(selected,stored.copyWithCount(stored.getCount()-1));
        return ToolStatus.READY;
    }
    /** Prioritize returning tool stacks to their original Builder Planner
     * rather than mixing them into the construction debris chest.
     */
    public static boolean hasTools(BuilderGolem worker){
        for(int i=0;i<worker.getContainerSize();i++)
            if(isMiningTool(worker.getItem(i)))return true;
        return false;
    }
    public static boolean returnTools(BuilderGolem worker,
            WorkerStationBlockEntity table){
        boolean all=true;
        for(int i=0;i<worker.getContainerSize();i++){
            ItemStack held=worker.getItem(i);
            if(!isMiningTool(held))continue;
            int left=held.getCount();
            for(int slot=0;slot<WorkerStationBlockEntity.INPUT_SLOTS&&left>0;slot++){
                ItemStack existing=table.getItem(slot);
                if(!existing.isEmpty()&&ItemStack.isSameItemSameComponents(existing,held)
                        &&table.canPlaceItem(slot,held)){
                    int moved=Math.min(left,existing.getMaxStackSize()-existing.getCount());
                    if(moved>0){
                        table.setItem(slot,existing.copyWithCount(existing.getCount()+moved));
                        left-=moved;
                    }
                }
            }
            for(int slot=0;slot<WorkerStationBlockEntity.INPUT_SLOTS&&left>0;slot++){
                if(!table.getItem(slot).isEmpty()||!table.canPlaceItem(slot,held))continue;
                int moved=Math.min(left,held.getMaxStackSize());
                table.setItem(slot,held.copyWithCount(moved));
                left-=moved;
            }
            worker.setItem(i,held.copyWithCount(left));
            if(left>0)all=false;
        }
        return all;
    }
    public enum ScaffoldLoad { READY, MOVING, MISSING }
    /** Fetch scaffolding with the same real chest/pouch contract as build
     * materials. No tower block can be purchased from a distant container.
     */
    public static ScaffoldLoad loadScaffold(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,int required){
        Item item=net.minecraft.world.item.Items.SCAFFOLDING;
        if(worker.carriedCount(item)>=required)return ScaffoldLoad.READY;
        Container site=selectedStorage(table);
        Container source=site!=null&&includesItem(site,site.getContainerSize(),item)
                ?site:includesItem(table,WorkerStationBlockEntity.INPUT_SLOTS,item)
                    ?table:null;
        if(source==null)return ScaffoldLoad.MISSING;
        BlockPos pos=source==table?table.getBlockPos():table.builderMaterialStorage();
        if(worker.distanceToSqr(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)>12){
            com.caszgamermd.golemsplus.worker.WorkerNavigation.routeTo(
                    level,worker,pos,.82);
            return ScaffoldLoad.MOVING;
        }
        int stillNeeded=required-worker.carriedCount(item);
        int limit=source==table?WorkerStationBlockEntity.INPUT_SLOTS:source.getContainerSize();
        for(int slot=0;slot<limit&&stillNeeded>0;slot++){
            ItemStack container=source.getItem(slot);
            if(container.is(item)){
                int requested=Math.min(stillNeeded,Math.min(container.getCount(),
                        room(worker,container)));
                int moved=tryPocket(worker,container,Math.max(0,requested));
                if(moved>0){
                    source.setItem(slot,container.copyWithCount(container.getCount()-moved));
                    source.setChanged();stillNeeded-=moved;
                }
            }else if(container.is(ItemTags.SHULKER_BOXES)
                    &&container.has(DataComponents.CONTAINER)){
                var nested=contents(container);
                boolean changed=false;
                for(int inner=0;inner<nested.size()&&stillNeeded>0;inner++){
                    ItemStack stack=nested.get(inner);
                    if(!stack.is(item))continue;
                    int wanted=Math.min(stillNeeded,Math.min(stack.getCount(),room(worker,stack)));
                    int moved=tryPocket(worker,stack,Math.max(0,wanted));
                    if(moved>0){
                        nested.set(inner,stack.copyWithCount(stack.getCount()-moved));
                        stillNeeded-=moved;changed=true;
                    }
                }
                if(changed){
                    ItemStack replaced=container.copy();
                    replaced.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(nested));
                    source.setItem(slot,replaced);source.setChanged();
                }
            }
        }
        return worker.carriedCount(item)>=required?
                ScaffoldLoad.READY:ScaffoldLoad.MISSING;
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
    private static int tuckIntoShulker(Container target,int slot,ItemStack item,
            int remaining,boolean onlyMatching){
        ItemStack box=target.getItem(slot);
        if(!box.is(ItemTags.SHULKER_BOXES)||!box.has(DataComponents.CONTAINER))
            return remaining;
        var slots=contents(box);
        boolean modified=false;
        for(int j=0;j<slots.size()&&remaining>0;j++){
            ItemStack old=slots.get(j);
            if(onlyMatching && old.isEmpty())continue;
            if(!old.isEmpty()&&!ItemStack.isSameItemSameComponents(old,item))
                continue;
            int limit=item.getMaxStackSize();
            int room=old.isEmpty()?limit:Math.max(0,limit-old.getCount());
            int accepted=Math.min(room,remaining);
            if(accepted>0){
                slots.set(j,item.copyWithCount((old.isEmpty()?0:old.getCount())+accepted));
                remaining-=accepted;modified=true;
            }
        }
        if(modified){
            ItemStack packed=box.copy();
            packed.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(slots));
            target.setItem(slot,packed);
            target.setChanged();
        }
        return remaining;
    }
    /** Return unused real pouch items to the staging container, including
     * empty space INSIDE physically stored shulker boxes. If every real
     * slot is full, remaining materials stay saved on the Golem.
     */
    public static boolean returnPouch(WorkerStationBlockEntity table,BuilderGolem worker){
        Container target=table.builderMaterialStorage()==null?table:selectedStorage(table);
        if(target==null)return false;
        int count=target==table?WorkerStationBlockEntity.INPUT_SLOTS:target.getContainerSize();
        for(int i=0;i<worker.getContainerSize();i++){
            ItemStack item=worker.getItem(i);
            if(item.isEmpty())continue;
            int left=item.getCount();
            // Existing matching loose stacks first.
            for(int k=0;k<count&&left>0;k++){
                ItemStack old=target.getItem(k);
                if(old.isEmpty()||!ItemStack.isSameItemSameComponents(old,item)
                        ||!target.canPlaceItem(k,item))continue;
                int accepted=Math.min(left,old.getMaxStackSize()-old.getCount());
                if(accepted>0){
                    target.setItem(k,old.copyWithCount(old.getCount()+accepted));
                    left-=accepted;
                }
            }
            // Matching item stacks already packed in delivered shulkers.
            for(int k=0;k<count&&left>0;k++)
                left=tuckIntoShulker(target,k,item,left,true);
            for(int k=0;k<count&&left>0;k++){
                if(!target.getItem(k).isEmpty()||!target.canPlaceItem(k,item))continue;
                int moved=Math.min(left,item.getMaxStackSize());
                target.setItem(k,item.copyWithCount(moved));left-=moved;
            }
            // Last resort: spare empty slots inside shulkers.
            for(int k=0;k<count&&left>0;k++)
                left=tuckIntoShulker(target,k,item,left,false);
            worker.setItem(i,item.copyWithCount(left));
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
