package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.CreativeCrateBlockEntity;
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
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.ShulkerBoxBlock;

/** Real, component-preserving materials in the depot and supplied shulkers. */
public final class BuilderSupplyInventory {
    public record Receipt(Container source,int slot,ItemStack previous,
                          ItemStack consumed) {}
    private BuilderSupplyInventory(){}
    // Best-effort kit staging: short retry window for simultaneous crew
    // visits to the same real OnSite chest. Never spawn tools or wait forever
    // if the user has not actually supplied them.
    private static final long STARTER_KIT_RETRY_TICKS=100;
    private static final Map<BuilderGolem,Long> kitStageStarted=
            new java.util.WeakHashMap<>();
    private static NonNullList<ItemStack> contents(ItemStack box){
        var slots=NonNullList.withSize(27,ItemStack.EMPTY);
        box.getOrDefault(DataComponents.CONTAINER,ItemContainerContents.EMPTY).copyInto(slots);
        return slots;
    }
    public static Container selectedStorage(WorkerStationBlockEntity table){
        BlockPos pos=table.builderUnloadingStorage();
        if(pos==null||!(table.getLevel() instanceof ServerLevel world)
                ||!world.hasChunkAt(pos))return null;
        if(pos.equals(table.getBlockPos()))return null;
        if(!table.builderRecallRequested()
                &&table.builderOrigin().distSqr(pos)>128L*128L)return null;
        return world.getBlockEntity(pos) instanceof Container storage?storage:null;
    }
    public static boolean creativeSupply(WorkerStationBlockEntity table){
        return selectedStorage(table) instanceof CreativeCrateBlockEntity;
    }
    /** Supplies physically accessible at the selected OnSite container.
     * Warehouse shulkers may be stored as items INSIDE this container or
     * placed as real shulker blocks immediately next to it.
     */
    private static java.util.List<Container> sources(WorkerStationBlockEntity table){
        Container selected=selectedStorage(table);
        if(selected==null)return java.util.List.of();
        java.util.List<Container> result=new java.util.ArrayList<>();
        result.add(selected);
        if(table.getLevel() instanceof ServerLevel world){
            BlockPos depot=table.builderUnloadingStorage();
            for(Direction side:Direction.values()){
                BlockPos at=depot.relative(side);
                if(world.hasChunkAt(at)
                        &&world.getBlockState(at).getBlock() instanceof ShulkerBoxBlock
                        &&world.getBlockEntity(at) instanceof Container box)
                    result.add(box);
            }
        }
        return result;
    }
    public static boolean ready(WorkerStationBlockEntity table){
        return selectedStorage(table)!=null;
    }
    public static Map<String,Integer> available(WorkerStationBlockEntity table){
        Map<String,Integer> stock=new LinkedHashMap<>();
        if(creativeSupply(table)){
            // No rack scan or 100k-item synthetic inventory. Advertise only
            // the actual job palette and Builder kit to the normal planner.
            for(String item:table.builderRemaining().keySet())
                stock.put(item,Integer.MAX_VALUE/2);
            stock.put("minecraft:scaffolding",Integer.MAX_VALUE/2);
            stock.put("minecraft:bucket",Integer.MAX_VALUE/2);
            stock.put("golems_plus:grapple_hook",Integer.MAX_VALUE/2);
            return stock;
        }
        for(Container store:sources(table)){
            int max=store.getContainerSize();
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
    /** Keep empty delivered shulkers at the physical site, not in Planner slots. */
    public static int recycleEmptyShulkers(WorkerStationBlockEntity table){return 0;}
    public static boolean canStoreScaffolding(WorkerStationBlockEntity table){
        ItemStack sample=new ItemStack(net.minecraft.world.item.Items.SCAFFOLDING);
        for(Container site:sources(table)){
            for(int i=0;i<site.getContainerSize();i++){
                ItemStack stack=site.getItem(i);
                if((stack.isEmpty()||ItemStack.isSameItemSameComponents(stack,sample)
                        &&stack.getCount()<stack.getMaxStackSize())
                        &&site.canPlaceItem(i,sample))return true;
            }
        }
        return false;
    }
    /** Server-side exact single-block return into the OnSite chest. */
    public static boolean storeScaffolding(WorkerStationBlockEntity table,ItemStack sample){
        if(sample.isEmpty())return false;
        for(Container site:sources(table))
            for(int pass=0;pass<2;pass++)
                for(int i=0;i<site.getContainerSize();i++){
                    ItemStack old=site.getItem(i);
                    if((pass==0&&old.isEmpty())
                            ||(pass==1&&!old.isEmpty())
                            ||!site.canPlaceItem(i,sample))continue;
                    if(!old.isEmpty()&&!ItemStack.isSameItemSameComponents(old,sample))
                        continue;
                    if(!old.isEmpty()&&old.getCount()>=old.getMaxStackSize())continue;
                    site.setItem(i,old.isEmpty()?sample.copyWithCount(1):
                            old.copyWithCount(old.getCount()+1));
                    site.setChanged();return true;
                }
        return false;
    }
    public static boolean hasRoomForBox(WorkerStationBlockEntity table){
        // A configured site container is the destination, even if full.
        // Do not silently divert supplies to another storage location.
        Container destination=selectedStorage(table);
        if(destination==null)return false;
        int limit=destination.getContainerSize();
        ItemStack box=new ItemStack(net.minecraft.world.item.Items.SHULKER_BOX);
        for(int i=0;i<limit;i++)
            if(destination.getItem(i).isEmpty()&&destination.canPlaceItem(i,box))
                return true;
        return false;
    }
    public static boolean depositBox(WorkerStationBlockEntity table,ItemStack box){
        if(box.isEmpty()||!box.is(ItemTags.SHULKER_BOXES))return false;
        Container destination=selectedStorage(table);
        if(destination==null||destination instanceof CreativeCrateBlockEntity)
            return false; // Existing physical warehouse shulkers must not be voided.
        int limit=destination.getContainerSize();
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
            int space=Math.max(0,worker.slotCapacity(i,sample)-current.getCount());
            int moved=Math.min(space,remaining);
            if(moved>0){
                worker.setItem(i,current.copyWithCount(current.getCount()+moved));
                remaining-=moved;
            }
        }
        // The three belt slots are genuinely dedicated: install a new
        // scaffold/hook/bucket there BEFORE filling loose material pockets.
        // Ordinary schematic blocks must never spill into the belt.
        for(int i=BuilderGolem.SCAFFOLD_BELT_SLOT;
                i<worker.getContainerSize()&&remaining>0;i++){
            if(!worker.getItem(i).isEmpty()||!worker.canPlaceItem(i,sample))
                continue;
            int moved=Math.min(worker.slotCapacity(i,sample),remaining);
            worker.setItem(i,sample.copyWithCount(moved));
            remaining-=moved;
        }
        for(int i=0;i<BuilderGolem.SCAFFOLD_BELT_SLOT&&remaining>0;i++){
            if(!worker.getItem(i).isEmpty())continue;
            int moved=Math.min(worker.slotCapacity(i,sample),remaining);
            worker.setItem(i,sample.copyWithCount(moved));
            remaining-=moved;
        }
        return amount-remaining;
    }
    private static int room(BuilderGolem worker,ItemStack item){
        int space=0;
        for(int i=0;i<worker.getContainerSize();i++){
            ItemStack have=worker.getItem(i);
            if(!worker.canPlaceItem(i,item))continue;
            if(have.isEmpty())space+=worker.slotCapacity(i,item);
            else if(ItemStack.isSameItemSameComponents(have,item))
                space+=Math.max(0,worker.slotCapacity(i,item)-have.getCount());
        }
        return space;
    }
    /** Read physical loose stacks, placed shulker inventories and
     * container components inside item-form shulkers. Each transfer
     * updates both inventories on the server thread; never duplicate.
     */
    private static int drawFromStorage(Container storage,BuilderGolem worker,
                                       Item material,int amount){
        if(storage instanceof CreativeCrateBlockEntity){
            ItemStack sample=new ItemStack(material);
            return tryPocket(worker,sample,Math.min(amount,room(worker,sample)));
        }
        int remaining=amount;
        for(int slot=0;slot<storage.getContainerSize()&&remaining>0;slot++){
            ItemStack held=storage.getItem(slot);
            if(held.is(material)){
                int pull=Math.min(remaining,Math.min(held.getCount(),room(worker,held)));
                int moved=tryPocket(worker,held,pull);
                if(moved>0){
                    storage.setItem(slot,held.copyWithCount(held.getCount()-moved));
                    storage.setChanged();remaining-=moved;
                }
            }else if(held.is(ItemTags.SHULKER_BOXES)
                    &&held.has(DataComponents.CONTAINER)){
                var nested=contents(held);
                boolean changed=false;
                for(int inner=0;inner<nested.size()&&remaining>0;inner++){
                    ItemStack item=nested.get(inner);
                    if(!item.is(material))continue;
                    int pull=Math.min(remaining,Math.min(item.getCount(),
                            room(worker,item)));
                    int moved=tryPocket(worker,item,pull);
                    if(moved<=0)continue;
                    nested.set(inner,item.copyWithCount(item.getCount()-moved));
                    changed=true;remaining-=moved;
                }
                if(changed){
                    ItemStack box=held.copy();
                    box.set(DataComponents.CONTAINER,
                            ItemContainerContents.fromItems(nested));
                    storage.setItem(slot,box);storage.setChanged();
                }
            }
        }
        return amount-remaining;
    }
    private static int drawFromSources(WorkerStationBlockEntity table,
                                       BuilderGolem worker,Item item,int amount){
        if(!BuilderReturnService.withinStorageReach(worker,
                table.builderUnloadingStorage()))return 0;
        int remaining=amount;
        for(Container source:sources(table)){
            if(remaining<=0)break;
            remaining-=drawFromStorage(source,worker,item,remaining);
        }
        return amount-remaining;
    }
    private static boolean containsInSources(WorkerStationBlockEntity table,Item item){
        if(creativeSupply(table))return true;
        for(Container source:sources(table))
            if(includesItem(source,source.getContainerSize(),item))return true;
        return false;
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
    /** Bring a real reusable water source to an elevated worksite BEFORE
     * the dry floor is finished. Waterlogging and source-water actions on
     * the current or very next Y are unavoidable near-term demands; a
     * builder must not climb four blocks and then walk back down solely
     * to fetch the finishing tool. This is a material selection policy,
     * not a source of free buckets or remotely accessed stock.
     */
    static boolean hasNearTermWaterWork(
            List<BuilderConstructionPlan.Step> steps,List<Integer> zone,int y){
        if(steps==null||zone==null)return false;
        for(int index:zone){
            if(index<0||index>=steps.size())continue;
            var cell=steps.get(index);
            if(cell.ignorePlant()||cell.requiredItem()==null
                    ||!cell.requiredItem().equals(BuilderWaterPass.WATER_BUCKET))
                continue;
            if(cell.y()>=y-1&&cell.y()<=y+1)return true;
        }
        return false;
    }

    /** Stage the exclusive ten-coordinate work order at its physical depot.
     * Near the roof, also carry a SMALL reserve of the worker's own final
     * cap materials and paid scaffolding. A long real ascent must not be
     * undone just because a new batch changes from glass to a rare fence.
     * This never changes the ten-cell claim or draws from remote stock:
     * every reserved item leaves the actual OnSite inventory now.
     */
    public static LoadStatus prepareWorkOrder(ServerLevel level,
            BuilderGolem worker,WorkerStationBlockEntity table,
            List<BuilderConstructionPlan.Step> steps,List<Integer> zone){
        var indices=table.builderWorkOrder(worker.getUUID());
        if(indices.isEmpty())return LoadStatus.MISSING;
        BlockPos depot=table.builderUnloadingStorage();
        Container storage=selectedStorage(table);
        if(depot==null||storage==null)return LoadStatus.MISSING;
        if(!BuilderReturnService.withinStorageReach(worker,depot)
                &&!BuilderFlight.enabled(worker)&&(BuilderScaffolding.descendForTravel(level,worker,table)
                    ||BuilderGrappleAccess.rappelForTravel(level,worker,table)))
            return LoadStatus.MOVING;
        if(BuilderReturnService.approach(level,worker,depot,.83))
            return LoadStatus.MOVING;
        worker.organizeToolBelt();
        Map<String,Integer> materials=new LinkedHashMap<>();
        // Prioritize the reserved tool belt before schematic material stacks.
        materials.put("golems_plus:grapple_hook",1);
        materials.put("minecraft:scaffolding",64);
        materials.put("minecraft:bucket",1);
        // Stage the actual claim before speculative neighbors. The
        // active work order can contain a large mixed-material palette.
        int claim=table.claimedStep(worker.getUUID());
        String claimedRail=null;
        if(claim>=0&&claim<steps.size()){
            var first=steps.get(claim);
            if(first.requiredItem()!=null)materials.putIfAbsent(first.requiredItem(),0);
            // Reserve the auxiliary kit BEFORE speculative construction
            // varieties. The connector and support are reused, not
            // charged as permanent schematic cells.
            if(BuilderRailSlope.needsExtraStock(first.encodedState())){
                var resolved=BuilderBlockResolver.resolve(first.encodedState(),
                        BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
                if(resolved!=null){
                    claimedRail=BuiltInRegistries.ITEM.getKey(
                            resolved.state().getBlock().asItem()).toString();
                    materials.putIfAbsent(claimedRail,0);
                    materials.putIfAbsent("minecraft:cobblestone",1);
                }
            }
        }
        int highest=worker.blockPosition().getY();
        for(int index:indices){
            if(index<0||index>=steps.size())continue;
            var next=steps.get(index);
            highest=Math.max(highest,next.y());
            if(next.requiredItem()!=null)
                materials.merge(next.requiredItem(),1,Integer::sum);
        }
        if(claim>=0&&claim<steps.size()&&steps.get(claim).requiredItem()!=null)
            materials.computeIfPresent(steps.get(claim).requiredItem(),(id,n)->Math.max(1,n));
        if(claimedRail!=null)
            materials.put(claimedRail,Math.max(2,materials.getOrDefault(claimedRail,0)+1));
        // The active ten-cell dry order may not yet contain its water
        // phase, even though the very same elevated zone needs it next.
        // Bring ONE inventory-backed reusable bucket at this depot visit
        // instead of abandoning a hard-won scaffold tower afterward.
        if(hasNearTermWaterWork(steps,zone,highest))
            materials.put(BuilderWaterPass.WATER_BUCKET,1);
        // Keep a full stack of high-volume CURRENT-layer materials when
        // a depot visit is already necessary. Previously staging a new
        // ten-cell order unloaded the other 54 stone, causing a full
        // roof descent / ascent every handful of blocks. Do not add
        // extra material types or stock for a future construction layer.
        Map<String,Integer> activeDemand=new LinkedHashMap<>();
        boolean layerHasSlopes=false;
        if(zone!=null){
            int layer=table.builderActiveLayer(steps);
            for(int idx:zone){
                if(idx<0||idx>=steps.size())continue;
                var cell=steps.get(idx);
                String item=cell.requiredItem();
                if(cell.y()!=layer||item==null
                        ||cell.ignorePlant()||table.builderStepCompletedForMaterials(idx)
                        ||!table.builderStepInActivePhase(steps,idx))continue;
                activeDemand.merge(item,1,Integer::sum);
                if(BuilderRailSlope.needsExtraStock(cell.encodedState()))layerHasSlopes=true;
            }
        }
        // Keep the reusable footing through other work on this layer.
        if(layerHasSlopes&&worker.carriedCount(net.minecraft.world.item.Items.COBBLESTONE)>0)
            materials.merge("minecraft:cobblestone",1,Math::max);
        // Preserve useful already-carried stock on this Y before unloading.
        // Fill at most eight of nine material slots with optional varieties;
        // the ninth remains available for a rare next claim. Required batch
        // items always retain priority over these optional reservations.
        int varieties=(int)materials.keySet().stream().filter(id->!beltItem(id)).count();
        for(int slot=0;slot<BuilderGolem.SCAFFOLD_BELT_SLOT&&varieties<8;slot++){
            ItemStack held=worker.getItem(slot);
            if(held.isEmpty())continue;
            String id=BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
            if(activeDemand.containsKey(id)&&!materials.containsKey(id)){
                materials.put(id,Math.min(held.getCount(),activeDemand.get(id)));
                varieties++;
            }
        }
        for(var row:activeDemand.entrySet()){
            var key=net.minecraft.resources.Identifier.tryParse(row.getKey());
            if(key==null||!BuiltInRegistries.ITEM.containsKey(key)
                    ||BuilderBlockResolver.reusableSupply(row.getKey()))continue;
            int max=new ItemStack(BuiltInRegistries.ITEM.getValue(key)).getMaxStackSize();
            if(max>1)materials.computeIfPresent(row.getKey(),(id,amount)->
                    Math.max(amount,Math.min(max,row.getValue())));
        }
        // A reusable fluid source is one tool for the whole work order,
        // independent of how many fluid cells are assigned.
        for(String item:java.util.List.copyOf(materials.keySet()))
            if(BuilderBlockResolver.reusableSupply(item))materials.put(item,1);
        // Every Builder carries a dedicated persistent TOOL BELT. Fill it
        // only from real OnSite stock: one reusable hook, up to a full
        // 64-block stack of scaffolding and one empty bucket. This does
        // not take construction-material slots or fabricate absent tools.
        int highestSchematic=steps.stream().mapToInt(
                BuilderConstructionPlan.Step::y).max().orElse(highest);
        // Distant final-cap blocks still wait until the final eight
        // schematic layers, because they would displace current materials.
        if(highest>=highestSchematic-8&&highestSchematic-highest>=0
                &&highest-table.builderOrigin().getY()>=12){
            if(zone!=null)
                for(int idx:zone){
                    if(idx<0||idx>=steps.size())continue;
                    var future=steps.get(idx);
                    if(future.y()<highestSchematic-4
                            ||future.y()<=highest
                            ||future.requiredItem()==null)continue;
                    // Reserve at most six per cap material. The ordinary
                    // per-step inventory check still pays for every block.
                    String item=future.requiredItem();
                    int amount=materials.getOrDefault(item,0);
                    if(amount<6)materials.put(item,amount+1);
                }
        }
        // Preserve enough actual items for the requested cells, unload the
        // rest. A full OnSite Storage never deletes carried block inventory.
        for(int slot=0;slot<worker.getContainerSize();slot++){
            ItemStack held=worker.getItem(slot);
            if(held.isEmpty())continue;
            String id=BuiltInRegistries.ITEM.getKey(held.getItem()).toString();
            int needed=Math.max(0,materials.getOrDefault(id,0));
            // The same item may appear in several pockets; preserve up to
            // this batch's amount across all slots, not per slot.
            for(int earlier=0;earlier<slot;earlier++){
                ItemStack before=worker.getItem(earlier);
                if(before.is(held.getItem()))needed-=before.getCount();
            }
            int keep=Math.min(held.getCount(),Math.max(0,needed));
            int giveBack=held.getCount()-keep;
            if(giveBack<=0)continue;
            // Deposit ONLY this slot's surplus. Calling returnPouch()
            // here would empty previously staged assignment materials in
            // OTHER slots and could repeat work forever.
            int remainder=storeSurplus(storage,held,giveBack);
            worker.setItem(slot,held.copyWithCount(keep+remainder));
        }
        for(var row:materials.entrySet()){
            var key=net.minecraft.resources.Identifier.tryParse(row.getKey());
            if(key==null||!BuiltInRegistries.ITEM.containsKey(key))continue;
            Item item=BuiltInRegistries.ITEM.getValue(key);
            int needed=row.getValue()-worker.carriedCount(item);
            if(needed<=0)continue;
            drawFromSources(table,worker,item,needed);
        }
        int scaffolds=worker.getItem(BuilderGolem.SCAFFOLD_BELT_SLOT).getCount();
        boolean hook=worker.getItem(BuilderGolem.GRAPPLE_BELT_SLOT).is(
                com.caszgamermd.golemsplus.world.WorksiteContent.GRAPPLE_HOOK);
        boolean bucket=worker.getItem(BuilderGolem.BUCKET_BELT_SLOT).is(
                net.minecraft.world.item.Items.BUCKET);
        if(scaffolds<20||!hook||!bucket){
            long start=kitStageStarted.computeIfAbsent(worker,
                    ignored->level.getGameTime());
            if(level.getGameTime()-start<STARTER_KIT_RETRY_TICKS)
                return LoadStatus.MISSING; // Retry physical chest next tick.
            System.out.println("[BUILDER KIT] partial after actual stock retries"
                    +" worker="+worker.getName().getString()
                    +" scaffolds="+scaffolds+" hook="+hook+" bucket="+bucket);
        }
        kitStageStarted.remove(worker);
        return LoadStatus.READY;
    }

    private static boolean beltItem(String id){
        return id.equals("minecraft:scaffolding")||id.equals("golems_plus:grapple_hook")
                ||id.equals("minecraft:bucket");
    }

    /** Return only surplus from one inventory slot; never touch another
     * worker pocket or duplicate an item if the actual chest is full.
     */
    private static int storeSurplus(Container target,ItemStack sample,int left){
        for(int pass=0;pass<2;pass++){
            for(int i=0;i<target.getContainerSize()&&left>0;i++){
                ItemStack previous=target.getItem(i);
                if(pass==0&&(previous.isEmpty()
                        ||!ItemStack.isSameItemSameComponents(previous,sample)))
                    continue;
                if(pass==1&&!previous.isEmpty())continue;
                if(!target.canPlaceItem(i,sample))continue;
                int room=previous.isEmpty()?sample.getMaxStackSize():
                        previous.getMaxStackSize()-previous.getCount();
                int moved=Math.min(room,left);
                if(moved<=0)continue;
                target.setItem(i,sample.copyWithCount(moved+
                        (previous.isEmpty()?0:previous.getCount())));
                left-=moved;target.setChanged();
            }
        }
        for(int pass=0;pass<2&&left>0;pass++)
            for(int i=0;i<target.getContainerSize()&&left>0;i++)
                left=tuckIntoShulker(target,i,sample,left,pass==0);
        return left;
    }

    /** Loads one physically carried pouch from the OnSite chest or another
     * registered Builder. MOVING preserves the claimed construction step;
     * MISSING lets another crew member try a stocked cell in this layer.
     * Item ownership only changes when both Builder entities are nearby or
     * the requesting Builder reaches the actual OnSite storage block.
     */
    public enum LoadStatus { READY, MOVING, MISSING }
    public static boolean loadForLayer(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,List<BuilderConstructionPlan.Step> steps,
            int index){
        return loadForLayerStatus(level,worker,table,steps,index)==LoadStatus.READY;
    }
    public static LoadStatus loadForLayerStatus(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,List<BuilderConstructionPlan.Step> steps,
            int index){
        if(index<0||index>=steps.size())return LoadStatus.MISSING;
        return loadForLayerStatus(level,worker,table,steps.get(index),index);
    }

    static LoadStatus loadForLayerStatus(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,BuilderConstructionPlan.Step current,int index){
        return loadForLayerStatus(level,worker,table,current,index,null,null);
    }
    static LoadStatus loadForLayerStatus(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,BuilderConstructionPlan.Step current,int index,
            List<BuilderConstructionPlan.Step> steps,List<Integer> zone){
        var id=net.minecraft.resources.Identifier.tryParse(current.requiredItem()==null?
                "":current.requiredItem());
        if(id==null||!BuiltInRegistries.ITEM.containsKey(id))return LoadStatus.MISSING;
        Item target=BuiltInRegistries.ITEM.getValue(id);
        if(worker.carriedCount(target)>0)return LoadStatus.READY;
        // First use a physically nearby surplus, if there is one. No
        // chase or remote transfer, and leave the donor's own claimed item
        // in its pouch. Otherwise the ordinary depot route remains valid.
        if(borrowNearby(level,worker,table,target,steps,1))return LoadStatus.READY;
        // The flower can already be in a different Builder's pouch. When
        // the carrier is eligible to build, exchange an unstarted job cell
        // rather than making the requester chase a constantly moving peer.
        // The same block index remains owned by exactly one Builder.
         for(var teammateId:table.builderWorkerIds()){
            if(teammateId.equals(worker.getUUID()))continue;
            if(table.builderWorkerIds().size()>1
                    &&teammateId.equals(table.supplyRunner()))continue;
            if(level.getEntity(teammateId) instanceof BuilderGolem carrier
                    &&carrier.isAlive()&&carrier.carriedCount(target)>0
                    // A stranded airborne carrier is not an available
                    // material source. Keep the request with a worker
                    // who can actually reach storage or a grounded donor.
                    &&carrier.onGround()&&!BuilderFlight.active(carrier)
                    &&!BuilderScaffolding.isControllingMovement(carrier)
                    &&table.reassignBuilderStep(worker.getUUID(),teammateId,index))
                return LoadStatus.MOVING;
        }

        // A full pouch of stone or other material cannot receive cobble.
        // Walk to the depot and unload; NEVER teleport items from a remote
        // Builder to the chest. This also frees stock for other crew.
        if(room(worker,new ItemStack(target))<=0){
            BlockPos depot=table.builderUnloadingStorage();
            Container site=selectedStorage(table);
            if(site==null||depot==null)return LoadStatus.MISSING;
            if(!BuilderReturnService.withinStorageReach(worker,depot)
                    &&!BuilderFlight.enabled(worker)&&(BuilderScaffolding.descendForTravel(level,worker,table)
                    ||BuilderGrappleAccess.rappelForTravel(level,worker,table)))
                return LoadStatus.MOVING;
            if(BuilderReturnService.approach(level,worker,depot,.83))
                return LoadStatus.MOVING;
            returnPouch(table,worker);
            if(room(worker,new ItemStack(target))<=0)return LoadStatus.MISSING;
        }

        // Runtime replenishment is for THIS Builder's claimed step,
        // never a three-layer slice of the global schematic index (which
        // can belong to several *other* X/Z zones). Prepared stage supplies
        // already cover this worker's full 32-cell local window.
        int demand=0;
        if(steps!=null&&zone!=null){
            for(int n:zone){
                if(n<0||n>=steps.size()||!table.builderStepInActivePhase(steps,n)
                        ||table.builderStepCompletedForMaterials(n))continue;
                if(current.requiredItem().equals(steps.get(n).requiredItem()))demand++;
            }
        }else demand=new ItemStack(target).getMaxStackSize();
        int max=new ItemStack(target).getMaxStackSize();
        int desired=BuilderBlockResolver.reusableSupply(current.requiredItem())?1:
                Math.max(1,Math.min(max,demand));
        Map<String,Integer> wanted=Map.of(current.requiredItem(),desired);
        var selections=new ArrayList<>(wanted.entrySet());
        selections.sort((left,right)->{
            boolean first=left.getKey().equals(current.requiredItem());
            boolean second=right.getKey().equals(current.requiredItem());
            return first==second?left.getKey().compareTo(right.getKey()):
                    (first?-1:1);
        });
        Container site=selectedStorage(table);
        BlockPos depot=table.builderUnloadingStorage();
        if(site!=null&&depot!=null){
            for(var row:selections){
                var key=net.minecraft.resources.Identifier.tryParse(row.getKey());
                if(key==null||!BuiltInRegistries.ITEM.containsKey(key))continue;
                Item requested=BuiltInRegistries.ITEM.getValue(key);
                // Do not fill all nine slots with blocks for later layers
                // before obtaining the material for this assigned step.
                if(worker.carriedCount(target)==0&&requested!=target)continue;
                int capacity=new ItemStack(requested).getMaxStackSize();
                int quota=capacity*(requested==target?2:1);
                int needed=Math.min(quota,Math.max(0,
                        row.getValue()-worker.carriedCount(requested)));
                if(needed==0||!containsInSources(table,requested))
                    continue;
                if(!BuilderReturnService.withinStorageReach(worker,depot)
                        &&!BuilderFlight.enabled(worker)&&(BuilderScaffolding.descendForTravel(level,worker,table)
                    ||BuilderGrappleAccess.rappelForTravel(level,worker,table)))
                    return LoadStatus.MOVING;
                if(BuilderReturnService.approach(level,worker,depot,.83))
                    return LoadStatus.MOVING;
                drawFromSources(table,worker,requested,needed);
            }
        }
        if(worker.carriedCount(target)>0)return LoadStatus.READY;
        // The Planner counts all crew pouches as job stock. Without an
        // actual transfer that accounting made resources 'available' but
        // impossible for a different Builder to place. Borrow one physical
        // stack only when the crew are close enough to hand it over.
        return borrowFromCrew(level,worker,table,target,steps);
    }
    private static int donorReserved(WorkerStationBlockEntity table,BuilderGolem donor,
            Item target,List<BuilderConstructionPlan.Step> steps){
        if(steps==null)return 1;
        String material=BuiltInRegistries.ITEM.getKey(target).toString();
        int needed=0;
        for(int idx:table.builderWorkOrder(donor.getUUID())){
            if(idx>=0&&idx<steps.size()&&!table.builderStepCompletedForMaterials(idx)
                    &&material.equals(steps.get(idx).requiredItem()))needed++;
        }
        int claim=table.claimedStep(donor.getUUID());
        if(claim>=0&&claim<steps.size()){
            var step=steps.get(claim);
            if(material.equals(step.requiredItem()))needed=Math.max(1,needed);
            if(BuilderRailSlope.needsExtraStock(step.encodedState())){
                if(material.equals("minecraft:cobblestone"))needed=Math.max(1,needed);
                var resolved=BuilderBlockResolver.resolve(step.encodedState(),
                        BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
                if(resolved!=null&&resolved.state().getBlock().asItem()==target)
                    needed=Math.max(2,needed+1);
            }
        }
        return BuilderBlockResolver.reusableSupply(material)?Math.min(1,needed):needed;
    }
    // Preserve a donor's current work order and only transfer material across
    // a short clear physical gap, never through a wall or different floor.
    static boolean borrowNearby(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,Item target,List<BuilderConstructionPlan.Step> steps,int requested){
        if(beltItem(BuiltInRegistries.ITEM.getKey(target).toString()))return false;
        for(var id:table.builderWorkerIds()){
            if(id.equals(worker.getUUID())||!(level.getEntity(id) instanceof BuilderGolem donor)
                    ||!donor.isAlive()||!worker.onGround()||!donor.onGround()
                    ||worker.distanceToSqr(donor)>4||Math.abs(worker.getY()-donor.getY())>.6
                    ||!worker.hasLineOfSight(donor))continue;
            int surplus=donor.carriedCount(target)-donorReserved(table,donor,target,steps);
            // Never borrow an entire working stack and make the donor
            // abandon its own task. The receiver can do this one step.
            int quantity=Math.min(requested,Math.max(0,surplus));
            if(quantity<=0)continue;
            for(int slot=0;slot<BuilderGolem.SCAFFOLD_BELT_SLOT;slot++){
                ItemStack stack=donor.getItem(slot);
                if(!stack.is(target))continue;
                int moved=tryPocket(worker,stack,Math.min(quantity,stack.getCount()));
                if(moved>0){
                    donor.setItem(slot,stack.copyWithCount(stack.getCount()-moved));
                    return true;
                }
            }
        }
        return false;
    }
    private record BorrowMeet(Item material,java.util.UUID donor,BlockPos stand,long deadline){}
    private static final Map<BuilderGolem,BorrowMeet> borrowMeets=new java.util.WeakHashMap<>();
    private static final Map<BuilderGolem,Long> borrowRetry=new java.util.WeakHashMap<>();
    private static LoadStatus borrowFromCrew(ServerLevel level,
            BuilderGolem worker,WorkerStationBlockEntity table,Item target,
            List<BuilderConstructionPlan.Step> steps){
        if(borrowNearby(level,worker,table,target,steps,1)){
            borrowMeets.remove(worker);return LoadStatus.READY;
        }
        if(beltItem(BuiltInRegistries.ITEM.getKey(target).toString()))return LoadStatus.MISSING;
        long now=level.getGameTime();
        BorrowMeet meet=borrowMeets.get(worker);
        // If stock is truly finite, allow a single fixed rendezvous with
        // a stationary donor. Never continuously retarget a moving peer.
        if(meet!=null){
            if(meet.material()==target&&now<meet.deadline()
                    &&level.getEntity(meet.donor()) instanceof BuilderGolem donor
                    &&donor.isAlive()&&donor.onGround()
                    &&donor.distanceToSqr(meet.stand().getX()+.5,
                            meet.stand().getY(),meet.stand().getZ()+.5)<=4
                    &&donor.carriedCount(target)>donorReserved(table,donor,target,steps)
                    &&com.caszgamermd.golemsplus.worker.WorkerNavigation
                        .routeToExact(level,worker,meet.stand(),.83))
                return LoadStatus.MOVING;
            borrowMeets.remove(worker);borrowRetry.put(worker,now+120);
        }
        if(now<borrowRetry.getOrDefault(worker,0L)||!worker.onGround()
                ||BuilderFlight.active(worker))return LoadStatus.MISSING;
        BuilderGolem donor=null;
        double nearest=Double.MAX_VALUE;
        for(var id:table.builderWorkerIds()){
            if(id.equals(worker.getUUID()))continue;
            if(!(level.getEntity(id) instanceof BuilderGolem peer)
                    ||!peer.isAlive()||!peer.onGround()||BuilderFlight.active(peer)
                    ||!peer.getNavigation().isDone()
                    ||peer.carriedCount(target)<=donorReserved(table,peer,target,steps))continue;
            double distance=worker.distanceToSqr(peer);
            if(distance<nearest&&distance<=256){nearest=distance;donor=peer;}
        }
        if(donor==null){borrowRetry.put(worker,now+40);return LoadStatus.MISSING;}
        var candidates=new ArrayList<BlockPos>();
        BlockPos at=donor.blockPosition();
        for(Direction side:Direction.Plane.HORIZONTAL){
            BlockPos p=at.relative(side);
            if(com.caszgamermd.golemsplus.worker.WorkerNavigation.canStandOn(level,worker,p))
                candidates.add(p);
        }
        BlockPos stand=com.caszgamermd.golemsplus.worker.WorkerNavigation
                .selectExecutableStand(level,worker,candidates);
        if(stand==null){borrowRetry.put(worker,now+120);return LoadStatus.MISSING;}
        borrowMeets.put(worker,new BorrowMeet(target,donor.getUUID(),stand,now+100));
        return com.caszgamermd.golemsplus.worker.WorkerNavigation.routeToExact(level,worker,stand,.83)
                ?LoadStatus.MOVING:LoadStatus.MISSING;
    }
    public static int carriedByTeammates(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,Item item){
        int total=0;
        for(var id:table.builderWorkerIds())
            if(!id.equals(worker.getUUID())
                    &&level.getEntity(id) instanceof BuilderGolem teammate)
                total+=teammate.carriedCount(item);
        return total;
    }
    /** Store actual block drops in the designated on-site container. The
     * entire set is simulated before touching any slot; if storage lacks
     * space, the obstruction is NOT removed.
     */
    public static boolean storeDebris(WorkerStationBlockEntity table,
            java.util.List<ItemStack> drops,boolean commit){
        Container destination=selectedStorage(table);
        if(destination==null)return false;
        if(destination instanceof CreativeCrateBlockEntity)
            return true; // Creative clearing deliberately discards debris.
        int count=destination.getContainerSize();
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
        Container site=selectedStorage(table);
        if(site==null)return ToolStatus.MISSING;
        if(site instanceof CreativeCrateBlockEntity){
            Item tool=target.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE)
                    ?net.minecraft.world.item.Items.NETHERITE_AXE:
                    target.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_SHOVEL)
                    ?net.minecraft.world.item.Items.NETHERITE_SHOVEL:
                    target.is(net.minecraft.tags.BlockTags.MINEABLE_WITH_HOE)
                    ?net.minecraft.world.item.Items.NETHERITE_HOE:
                    net.minecraft.world.item.Items.NETHERITE_PICKAXE;
            if(BuilderReturnService.approach(level,worker,
                    table.builderUnloadingStorage(),.83))return ToolStatus.MOVING;
            if(room(worker,new ItemStack(tool))<=0)returnPouch(table,worker);
            drawFromSources(table,worker,tool,1);
            return toolSlot(worker,target)>=0?ToolStatus.READY:ToolStatus.MISSING;
        }
        int selected=-1;
        for(int i=0;i<site.getContainerSize();i++)
            if(correctTool(site.getItem(i),target)){selected=i;break;}
        if(selected<0)return ToolStatus.MISSING;
        BlockPos depot=table.builderUnloadingStorage();
        if(BuilderReturnService.approach(level,worker,depot,.83))
            return ToolStatus.MOVING;
        int pocket=-1;
        for(int i=0;i<worker.getContainerSize();i++)
            if(worker.getItem(i).isEmpty()
                    &&worker.canPlaceItem(i,site.getItem(selected))){pocket=i;break;}
        if(pocket<0){
            returnPouch(table,worker);
            for(int i=0;i<worker.getContainerSize();i++)
                if(worker.getItem(i).isEmpty()
                        &&worker.canPlaceItem(i,site.getItem(selected))){pocket=i;break;}
        }
        if(pocket<0)return ToolStatus.MISSING;
        ItemStack stored=site.getItem(selected);
        worker.setItem(pocket,stored.copyWithCount(1));
        site.setItem(selected,stored.copyWithCount(stored.getCount()-1));
        site.setChanged();
        return ToolStatus.READY;
    }
    /** Any borrowed tools must go back to the actual OnSite Storage, not
     * the Planner's now-deprecated 27-slot inventory.
     */
    public static boolean hasTools(BuilderGolem worker){
        for(int i=0;i<worker.getContainerSize();i++)
            if(isMiningTool(worker.getItem(i)))return true;
        return false;
    }
    public static boolean returnTools(BuilderGolem worker,
            WorkerStationBlockEntity table){
        if(!BuilderReturnService.withinStorageReach(worker,
                table.builderUnloadingStorage()))return false;
        Container site=selectedStorage(table);
        if(site==null)return false;
        boolean all=true;
        for(int i=0;i<worker.getContainerSize();i++){
            ItemStack held=worker.getItem(i);
            if(!isMiningTool(held))continue;
            int left=held.getCount();
            for(int pass=0;pass<2;pass++)
                for(int slot=0;slot<site.getContainerSize()&&left>0;slot++){
                    ItemStack previous=site.getItem(slot);
                    if((pass==0&&previous.isEmpty())
                            ||(pass==1&&!previous.isEmpty())
                            ||!site.canPlaceItem(slot,held)
                            ||!previous.isEmpty()&&!ItemStack.isSameItemSameComponents(previous,held))
                        continue;
                    int capacity=Math.min(site.getMaxStackSize(),held.getMaxStackSize());
                    int space=previous.isEmpty()?capacity:
                            Math.max(0,Math.min(capacity,previous.getMaxStackSize())
                                    -previous.getCount());
                    int moved=Math.min(left,space);
                    if(moved<=0)continue;
                    site.setItem(slot,held.copyWithCount(moved+
                            (previous.isEmpty()?0:previous.getCount())));
                    left-=moved;
                }
            worker.setItem(i,held.copyWithCount(left));
            site.setChanged();
            if(left>0)all=false;
        }
        return all;
    }
    /** Physically fetch one reusable Grapple Hook from OnSite Storage. */
    public static LoadStatus loadGrapple(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table){
        Item tool=com.caszgamermd.golemsplus.world.WorksiteContent.GRAPPLE_HOOK;
        if(worker.carriedCount(tool)>0)return LoadStatus.READY;
        BlockPos depot=table.builderUnloadingStorage();
        if(depot==null||selectedStorage(table)==null
                ||!containsInSources(table,tool))return LoadStatus.MISSING;
        // Missing hook cannot summon a grapple. Walk to a real adjacent
        // depot stance rather than planning into its solid chest block.
        if(BuilderReturnService.approach(level,worker,depot,.83))
            return LoadStatus.MOVING;
        if(room(worker,new ItemStack(tool))<=0)return LoadStatus.MISSING;
        drawFromSources(table,worker,tool,1);
        return worker.carriedCount(tool)>0?LoadStatus.READY:LoadStatus.MISSING;
    }

    public enum ScaffoldLoad { READY, MOVING, MISSING }
    /** An ascending rail temporarily needs a SECOND real rail at its
     * raised endpoint. The helper rail is recovered after shaping, so one
     * extra item can serve many slopes; never fabricate it at the target.
     */
    public static LoadStatus loadRailJig(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,Item rail,int required){
        return loadRailKit(level,worker,table,rail,required,false,null);
    }

    /** Stage the whole real kit atomically at the depot. Never evict
     * the required rail to make room for its support, or vice versa. */
    static LoadStatus loadRailKit(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,Item rail,int required,
            boolean foundation,List<BuilderConstructionPlan.Step> steps){
        return loadRailKit(level,worker,table,rail,required,foundation,false,steps);
    }
    static LoadStatus loadRailKit(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,Item rail,int required,
            boolean foundation,boolean wetRepair,List<BuilderConstructionPlan.Step> steps){
        Item cobble=net.minecraft.world.item.Items.COBBLESTONE;
        var kit=new LinkedHashMap<Item,Integer>();
        kit.put(rail,required);
        if(foundation)kit.merge(cobble,1,Math::max);
        if(wetRepair)kit.put(com.caszgamermd.golemsplus.world.WorksiteContent.INFINITE_WATER_BUCKET,1);
        // A wet-rail repair must not displace the reusable water source.
        java.util.Set<Item> protectedItems=new java.util.HashSet<>(kit.keySet());
        protectedItems.add(com.caszgamermd.golemsplus.world.WorksiteContent.INFINITE_WATER_BUCKET);
        boolean ready=kit.entrySet().stream().allMatch(e->worker.carriedCount(e.getKey())>=e.getValue());
        if(ready)return LoadStatus.READY;
        for(var row:kit.entrySet()){
            int missing=row.getValue()-worker.carriedCount(row.getKey());
            if(missing>0)borrowNearby(level,worker,table,row.getKey(),steps,missing);
        }
        if(kit.entrySet().stream().allMatch(e->worker.carriedCount(e.getKey())>=e.getValue()))
            return LoadStatus.READY;
        BlockPos depot=table.builderUnloadingStorage();
        if(depot==null||selectedStorage(table)==null)return LoadStatus.MISSING;
        if(!BuilderReturnService.withinStorageReach(worker,depot)
                &&!BuilderFlight.enabled(worker)&&(BuilderScaffolding.descendForTravel(level,worker,table)
                    ||BuilderGrappleAccess.rappelForTravel(level,worker,table)))
            return LoadStatus.MOVING;
        if(BuilderReturnService.approach(level,worker,depot,.82))return LoadStatus.MOVING;
        for(var row:kit.entrySet()){
            Item item=row.getKey();
            int missing=row.getValue()-worker.carriedCount(item);
            if(missing<=0)continue;
            drawFromSources(table,worker,item,missing);
            if(worker.carriedCount(item)>=row.getValue())continue;
            // All nine pockets may already hold full stacks. Reclaim a
            // lower-priority pocket; even a completely full depot can
            // exchange a whole real source stack with that pocket.
            if(room(worker,new ItemStack(item))<=0){
                if(!makeRailKitRoom(table,worker,item,protectedItems))continue;
                int stillMissing=row.getValue()-worker.carriedCount(item);
                if(stillMissing>0)drawFromSources(table,worker,item,stillMissing);
            }
        }
        return kit.entrySet().stream().allMatch(e->worker.carriedCount(e.getKey())>=e.getValue())
                ?LoadStatus.READY:LoadStatus.MISSING;
    }

    private static boolean makeRailKitRoom(WorkerStationBlockEntity table,
            BuilderGolem worker,Item needed,java.util.Set<Item> protectedItems){
        if(!BuilderReturnService.withinStorageReach(worker,table.builderUnloadingStorage()))
            return false;
        if(!containsInSources(table,needed))return false;
        for(int slot=BuilderGolem.SCAFFOLD_BELT_SLOT-1;slot>=0;slot--){
            ItemStack old=worker.getItem(slot);
            if(old.isEmpty()||protectedItems.contains(old.getItem()))continue;
            for(Container source:sources(table)){
                // Creative supply is infinite but must not void actual
                // inventory. Only use normal finite exchange below.
                if(source instanceof CreativeCrateBlockEntity)continue;
                int left=storeSurplus(source,old,old.getCount());
                worker.setItem(slot,old.copyWithCount(left));
                if(left==0)return true;
                old=worker.getItem(slot);
            }
            for(Container source:sources(table)){
                if(source instanceof CreativeCrateBlockEntity)continue;
                for(int i=0;i<source.getContainerSize();i++){
                    ItemStack stock=source.getItem(i);
                    if(stock.is(needed)&&source.canPlaceItem(i,old)
                            &&stock.getCount()<=worker.slotCapacity(slot,stock)){
                        source.setItem(i,old.copy());worker.setItem(slot,stock);
                        source.setChanged();return true;
                    }
                    // A delivered shulker can be just as full as the
                    // chest. Swap its actual nested stack, preserving
                    // both components and all counts.
                    if(!stock.is(ItemTags.SHULKER_BOXES)||!stock.has(DataComponents.CONTAINER))
                        continue;
                    var nested=contents(stock);
                    for(int n=0;n<nested.size();n++){
                        ItemStack inside=nested.get(n);
                        if(!inside.is(needed)||inside.getCount()>worker.slotCapacity(slot,inside)
                                ||old.is(ItemTags.SHULKER_BOXES))continue;
                        nested.set(n,old.copy());
                        ItemStack box=stock.copy();
                        box.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(nested));
                        source.setItem(i,box);worker.setItem(slot,inside);
                        source.setChanged();return true;
                    }
                }
            }
        }
        return false;
    }
    /** Fetch scaffolding with the same real chest/pouch contract as build
     * materials. No tower block can be purchased from a distant container.
     */
    public static ScaffoldLoad loadScaffold(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,int required){
        return loadScaffold(level,worker,table,required,false);
    }
    static ScaffoldLoad loadScaffold(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,int required,boolean groundPickup){
        Item item=net.minecraft.world.item.Items.SCAFFOLDING;
        if(worker.carriedCount(item)>=required)return ScaffoldLoad.READY;
        Container site=selectedStorage(table);
        Container source=site!=null&&containsInSources(table,item)?site:null;
        if(source==null)return ScaffoldLoad.MISSING;
        BlockPos pos=table.builderUnloadingStorage();
        if(!groundPickup&&!BuilderReturnService.withinStorageReach(worker,pos)
                &&!BuilderFlight.enabled(worker)&&(BuilderScaffolding.descendForTravel(level,worker,table)
                    ||BuilderGrappleAccess.rappelForTravel(level,worker,table)))
            return ScaffoldLoad.MOVING;
        if(BuilderReturnService.approach(level,worker,pos,.82))
            return ScaffoldLoad.MOVING;
        // Refill the reusable tool-belt stack during an actual depot visit.
        // Limited stock still satisfies a smaller route when possible.
        drawFromSources(table,worker,item,Math.max(required,64)-worker.carriedCount(item));
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
        if(!BuilderReturnService.withinStorageReach(worker,
                table.builderUnloadingStorage()))return false;
        Container target=selectedStorage(table);
        if(target==null)return false;
        int count=target.getContainerSize();
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
        return table.builderUnloadingStorage()==null?table.getBlockPos():
                table.builderUnloadingStorage();
    }
    public static Receipt consume(WorkerStationBlockEntity table,Item item){
        for(Container store:sources(table)){
            int limit=store.getContainerSize();
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
