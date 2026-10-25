package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import com.caszgamermd.golemsplus.worker.WorkerPhase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * First safe construction pass: air-only placement, one real item per block,
 * physical pathfinding and persisted step index. Never force loads, break
 * existing structures, clone block entities or modify non-air blocks.
 */
public final class BuilderBuildService {
    // Site clearing is one-at-a-time to preserve protected block checks,
    // but the selected cleaner cannot monopolize the cursor forever if
    // navigation is blocked. Hand the unchanged target to another loaded
    // crew member every four seconds without discarding any real items.
    private static final java.util.Map<WorkerStationBlockEntity,ClearWatch>
            clearWatch=new java.util.WeakHashMap<>();
    private static final class ClearWatch {
        int cursor=-1;
        long lastProgress;
    }
    private record Cache(String file, int rotation,int mirror,BlockPos origin,
                         List<BuilderConstructionPlan.Step> steps,
                         List<BuilderSiteClearance.Target> clearing){}
    private static final Map<BuilderGolem,Cache> cached=new WeakHashMap<>();
    // Reuse the immutable server-side schematic work plan across queued
    // warehouse retries. Parsing a 100k-block Litematica file every four
    // seconds caused visible freezes while a PTP was temporarily busy.
    private static final Map<WorkerStationBlockEntity,Cache>
            cachedWarehousePlans=new WeakHashMap<>();
    private BuilderBuildService(){}
    /** Start the warehouse packing reservation as soon as the user
     * presses Start, before any Builder Golem has reached the first block.
     * Only the server's already-imported schematic is read; this never
     * creates materials or pulls from the player's inventory.
     */
    public static String primeWarehouse(ServerLevel level,
            WorkerStationBlockEntity table){
        if(!table.isBuilder()||table.activeOrder()==null)return "No active Builder job";
        if(table.warehouse()==null)
            return "Manual supply mode: link a Rack Computer with a Warehouse Scanner to enable warehouse deliveries";
        if(table.supplyRequest()!=null)return "Warehouse material request already active";
        try{
            var cache=jobCache(table);
            var plan=cache.steps();
            if(table.builderRequired().isEmpty()&&table.activeOrder()!=null){
                // Running legacy build: retain its current cursor and only
                // backfill the static required-material checklist.
                var required=new java.util.LinkedHashMap<String,Long>();
                for(var step:plan)if(step.requiredItem()!=null)
                    required.merge(step.requiredItem(),1L,Long::sum);
                table.backfillBuilderMaterials(required);
            }
            var stock=BuilderSupplyInventory.available(table);
            var warehouse=table.warehouse();
            var pickable=warehouse==null?null:
                    com.caszgamermd.golemsplus.warehouse.BuilderWarehousePort
                        .stockSnapshot(level,new BlockPos(
                                warehouse.x(),warehouse.y(),warehouse.z()),
                                table.builderRemaining());
            var materials=BuilderMaterialDemand.outstanding(plan,
                    table.builderCursor(),stock,pickable==null?Map.of():pickable);
            if(materials.isEmpty())
                return "All remaining construction materials are already staged OnSite or with the crew";
            if(!BuilderWarehouseCoordinator.queueMaterials(level,table,plan))
                return table.problem().isBlank()?"Warehouse not ready":table.problem();
            if(table.supplyPtp().equals(BlockPos.ZERO))
                return "Warehouse request saved; "+table.problem();
            return "Warehouse preparing "+materials.size()
                    +" material types at PTP "+table.supplyPtp().toShortString()
                    +". One Builder Golem will collect the packed shulkers";
        }catch(Exception ex){
            String reason=ex.getMessage();
            return "Cannot prepare warehouse batch: "+
                    (reason==null?ex.getClass().getSimpleName():reason);
        }
    }

    public static void dispatch(ServerLevel level,WorkerStationBlockEntity table){
        BuilderChunkTickets.tick(level,table);
        // Keep cancellation on the JOB'S loaded block, not the player or
        // courier entity. Retry while packing is in flight; never drop a
        // live order UUID or orphan its physical shulkers.
        if(table.isBuilder()&&table.builderCancelShipment()
                &&level.getGameTime()%40==0){
            if(!BuilderWarehouseCoordinator.cancelShipment(level,table))
                table.setProblem("Warehouse cancellation pending: "
                        +"finish/collect any packed shulkers or active picker cargo");
        }
        if(table.isBuilder()&&level.getGameTime()%40==0)
            table.updateBuilderStagedStock(BuilderSupplyInventory.available(table));
        if(table.isBuilder()&&level.getGameTime()%80==0){
            var link=table.warehouse();
            if(link!=null&&level.dimension().identifier().toString().equals(link.dimension())){
                BlockPos computer=new BlockPos(link.x(),link.y(),link.z());
                var snapshot=com.caszgamermd.golemsplus.warehouse.BuilderWarehousePort
                        .stockSnapshot(level,computer,table.builderRemaining());
                table.setBuilderWarehouseStock(snapshot==null?Map.of():snapshot,snapshot!=null);
                table.updateBuilderWarehouseName(
                        com.caszgamermd.golemsplus.warehouse.BuilderWarehousePort
                                .warehouseName(level,computer));
            }else{
                table.setBuilderWarehouseStock(Map.of(),false);
                table.updateBuilderWarehouseName("");
            }
        }
        BuilderWarehouseCoordinator.pollTable(level,table);
        // Warehouse requests belong to the active JOB, not construction
        // cursor progress or whichever Builder happens to be loaded.
        // Retry the manifest from the Planner every four seconds while
        // no order is active. Actual rack stock is prioritized, and the
        // full remaining demand is never silently dropped.
        if(table.isBuilder()&&table.activeOrder()!=null
                &&table.warehouse()!=null&&table.supplyRequest()==null
                &&!table.builderCancelShipment()
                &&level.getGameTime()%80==0)
            primeWarehouse(level,table);
        // Existing crafted crew members execute the job; the station never
        // materializes extra golems automatically.
        if(level.getGameTime()%20!=0 || !table.isBuilder()
                ||table.activeOrder()==null||!table.hasPlacedSchematic()
                ||table.phase()==WorkerPhase.PAUSED)return;
        var linked=table.builderWorkerIds();
        // Never replace a registered but unloaded worker: its work, materials
        // and provisional scaffold could be persisted in another chunk.
        for(UUID id:linked)if(level.getEntity(id)==null){
            BlockPos last=table.builderLastPosition(id);
            table.setProblem(last==null
                    ?"Builder crew unavailable; last location not recorded"
                    :"Reloading Builder crew from last saved chunk "
                        +last.toShortString()+" (no replacement or cargo loss)");
            return;
        }
        // Wait for the player to craft and place builders beside the table.
    }
    public static void tick(ServerLevel level,BuilderGolem worker){
        BlockPos home=worker.home();
        if(worker.owner()==null||!level.hasChunkAt(home))return;
        if(!(level.getBlockEntity(home) instanceof WorkerStationBlockEntity table)
                ||!table.isBuilder()||!worker.owner().equals(table.owner())){
            // An asynchronously unloading or replaced home block must NEVER
            // destroy a real Builder with tools, materials or shulker cargo.
            // Preserve the named entity and its exact saved inventory so the
            // owner can restore the Planner or reclaim it safely.
            worker.getNavigation().stop();
            return;
        }
        worker.setDeliveryRole(table.activeOrder()!=null
                &&worker.getUUID().equals(table.supplyRunner()));
        if(!table.builderWorkerIds().contains(worker.getUUID())){
            // Never discard inventory-bearing entities: a misconfigured
            // workstation must not silently destroy delivered shulker cargo.
            table.setProblem("Unrecognized Builder Golem near workstation");
            return;
        }
        // A teammate may need the block space currently occupied by this
        // Builder. Honor its short physical sidestep before resuming the
        // current 10-coordinate assignment or warehouse courier route.
        if(BuilderPlacementSpace.yieldTick(level,worker))return;
        if(table.activeOrder()==null||table.phase()==WorkerPhase.COMPLETE){
            worker.setNoGravity(false);
            BuilderScaffolding.retrieve(level,worker,table,false);
            // A completed/canceled plan may still own boxed warehouse stock.
            // Recover real cargo first rather than losing it on job end.
            if(worker.hasSupplyBox()||table.supplyRequest()!=null){
                ServerPlayer recipient=level.getServer().getPlayerList()
                        .getPlayer(worker.owner());
                if(recipient!=null&&recipient.level()==level)try{
                    Cache outstanding=cache(table,worker);
                    if(BuilderWarehouseCoordinator.tick(level,worker,table,
                            recipient,outstanding.steps()))return;
                }catch(Exception ignored){}
            }
            // A furnished tool belongs back at the Planner, not buried
            // in the on-site debris/materials chest.
            if(BuilderSupplyInventory.hasTools(worker)){
                BlockPos depot=BuilderSupplyInventory.pouchReturnPos(table);
                if(worker.distanceToSqr(depot.getX()+.5,depot.getY()+.5,
                        depot.getZ()+.5)>12){
                    com.caszgamermd.golemsplus.worker.WorkerNavigation
                            .routeTo(level,worker,depot,.83);
                    return;
                }
                if(!BuilderSupplyInventory.returnTools(worker,table)){
                    table.setProblem("Make room in OnSite Storage for returned mining tools");
                    return;
                }
            }
            if(!worker.hasSupplyBox()&&!worker.isEmpty()){
                BlockPos returnTo=BuilderSupplyInventory.pouchReturnPos(table);
                if(level.hasChunkAt(returnTo)){
                    if(worker.distanceToSqr(returnTo.getX()+.5,returnTo.getY()+.5,
                            returnTo.getZ()+.5)>12)
                        com.caszgamermd.golemsplus.worker.WorkerNavigation
                                .routeTo(level,worker,returnTo,.8);
                    else BuilderSupplyInventory.returnPouch(table,worker);
                }
                return;
            }
            if(!worker.hasSupplyBox()&&table.supplyRequest()==null){
                if(BuilderReturnService.tick(level,worker,table))return;
                if(table.phase()!=WorkerPhase.COMPLETE&&!table.builderRecallRequested())
                    com.caszgamermd.golemsplus.worker.WorkerIdleRoam.tick(level,worker,home);
            }
            return;
        }
        if(table.phase()==WorkerPhase.PAUSED){
            worker.getNavigation().stop();
            worker.setNoGravity(false);return;
        }
        int membership=table.builderWorkerIds().indexOf(worker.getUUID());
        if(membership>=table.crewTarget()&&table.claimedStep(worker.getUUID())<0
                &&!worker.hasSupplyBox()){
            worker.setNoGravity(false);
            BuilderScaffolding.retrieve(level,worker,table,false);
            com.caszgamermd.golemsplus.worker.WorkerNavigation
                    .routeTo(level,worker,home,.8);
            return;
        }
        ServerPlayer owner=level.getServer().getPlayerList().getPlayer(worker.owner());
        if(owner==null||owner.level()!=level){
            table.setProblem("Owner must be online in the same dimension");
            return;
        }
        try{
            Cache plan=cache(table,worker);
            // Reserve the next actual construction materials *before*
            // walking to a placement that would otherwise leave the crew
            // stalled waiting for a block. Warehouse golems can prepare a
            // shulker while Builders continue working with existing stock.
            // Only one linked worker can claim the courier ticket; other
            // crew members may keep building.
            if(table.warehouse()!=null && table.supplyRequest()==null
                    &&table.builderCursor()<plan.steps().size())
                BuilderWarehouseCoordinator.queueMaterials(level,table,plan.steps());
            // The assigned courier keeps ownership of the whole ticket.
            // Only its actual carried box or a ready warehouse pickup
            // interrupts local Builder work; unassigned crew never waits
            // for an unrelated PTP reservation.
            if((worker.hasSupplyBox()||table.supplyRequest()!=null)
                    &&BuilderWarehouseCoordinator.tick(level,worker,table,
                            owner,plan.steps()))return;
            if(table.builderCursor()>=plan.steps().size()){
                table.setPhase(WorkerPhase.COMPLETE);return;
            }
            // PREPARE THE ENTIRE SELECTED SITE before placing the first
            // block. A clearing worker visits each exact schematic position,
            // including AIR cells; the courier may still fetch shulkers in
            // parallel. A site with an obstruction or no tool cannot start
            // placing around that obstruction or advance into upper layers.
            if(table.builderClearCursor()<plan.clearing().size()){
                // Every actual crew member can claim a DIFFERENT obstruction
                // or already-correct schematic cell. Courier stays available
                // to collect ready shulkers; when this is the only Builder,
                // it can clear while its next shulker is being packed.
                boolean courier=worker.getUUID().equals(table.supplyRunner());
                if(!courier||table.builderWorkerIds().size()==1)
                    BuilderSiteClearance.tick(level,worker,table,owner,
                            plan.clearing(),plan.steps());
                return;
            }
            // All workers finish the current bottommost layer before any
            // worker can claim a higher block, even with four-person crews.
            int layer=plan.steps().get(Math.min(table.builderCursor(),
                    plan.steps().size()-1)).y();
            int limit=table.builderCursor()+1;
            while(limit<plan.steps().size()&&plan.steps().get(limit).y()==layer)
                limit++;
            // Existing materials are REAL items in each Builder's pouch.
            // Prioritize construction cells needing what this worker already
            // carries, before assigning it work that another worker has
            // 300+ of and incorrectly reporting a warehouse shortage.
            java.util.Set<String> carriedItems=new java.util.HashSet<>();
            for(int slot=0;slot<worker.getContainerSize();slot++){
                var stack=worker.getItem(slot);
                if(!stack.isEmpty())carriedItems.add(
                        net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .getKey(stack.getItem()).toString());
            }
            // Retrieve temporary towers at a work-order boundary, never
            // after each block: workers should complete all ten cells on
            // their current scaffolding before returning for more stock.
            if(table.builderWorkOrder(worker.getUUID()).isEmpty()
                    &&!table.scaffoldPositions(worker.getUUID()).isEmpty())
                BuilderScaffolding.retrieve(level,worker,table,false);
            int index=table.claimBuilderStep(worker.getUUID(),limit,
                    plan.steps(),carriedItems);
            if(index<0)return; // wait for current layer assignments to finish
            var step=plan.steps().get(index);
            // Every real Builder goes to the chosen OnSite depot ONCE per
            // exclusive ten-coordinate batch, unloads other materials and
            // collects actual stock (including flowers within shulkers).
            // It does not visit the Planning Table for assignments.
            if(!table.builderOrderPrepared(worker.getUUID())){
                var prep=BuilderSupplyInventory.prepareWorkOrder(
                        level,worker,table,plan.steps());
                if(prep==BuilderSupplyInventory.LoadStatus.MOVING)return;
                if(prep==BuilderSupplyInventory.LoadStatus.MISSING){
                    table.setProblem("Builder work order waiting for reachable "
                            +"OnSite Storage (material staging not complete)");
                    return;
                }
                table.prepareBuilderOrder(worker.getUUID());
            }
            BlockPos pos=new BlockPos(step.x(),step.y(),step.z());
            if(Math.abs((long)pos.getX()-home.getX())>256||
                    Math.abs((long)pos.getZ()-home.getZ())>256){
                table.setProblem("Build extends beyond 256-block Builder range");
                return;
            }
            // Load the current layer's next target before stopping on an
            // unloaded schematic chunk. The same ticket is reclaimed as
            // the real Builder advances to the next target.
            BuilderChunkTickets.destination(level,worker,pos);
            if(!level.hasChunkAt(pos)){
                table.setProblem("Loading construction chunk "+pos.toShortString());
                return;
            }
            if(!level.mayInteract(owner,pos)){
                table.setProblem("Owner cannot modify this location");return;
            }
            var state=BuilderBlockResolver.resolve(step.encodedState(),
                    BuildSite.Rotation.values()[table.builderRotation()/90],
                    BuildSite.Mirror.values()[table.builderMirror()]);
            if(state==null){
                table.setProblem("Unsupported block state: "+step.encodedState());return;
            }
            BlockState existing=level.getBlockState(pos);
            if(BuilderPlacementState.matches(existing,state.state())){
                // Auto-connected walls/fences/panes and stair corner shapes
                // may differ from the saved palette after neighbouring
                // blocks change. They ARE already built; never dismantle
                // a correct block in pursuit of stale schematic geometry.
                table.recordBuilderPlacement(step.requiredItem());
                table.completeBuilderStep(worker.getUUID(),index);
                BuilderScaffolding.retrieve(level,worker,table,false);
                table.setProblem("");return;
            }
            // Check the replacement's survival BEFORE clearing an existing
            // block. Clearing first destroyed a useful piece of completed
            // cobblestone/support, then deadlocked on "Missing supporting
            // blocks" after the now-unsupported target turned to air.
            if(!state.state().canSurvive(level,pos)){
                // A door, wall torch, rail or other dependent schematic
                // cell may be queued before its supporting neighbour in
                // this same layer. Yield the cell without consuming an
                // item or advancing the layer; another Builder can fill
                // the support, and the cell will be retried after 40 ticks.
                table.waitForBuilderSupport(worker.getUUID(),index,
                        level.getGameTime());
                BlockPos underneath=pos.below();
                String support=net.minecraft.core.registries.BuiltInRegistries
                        .BLOCK.getKey(level.getBlockState(underneath).getBlock())
                        .toString();
                table.setProblem("Waiting for support: "+step.encodedState()
                        +" at "+pos+"; below="+support
                        +" (other Builders continuing this layer)");
                return;
            }
            if(!existing.isAir()){
                // A player may add a block after the initial site-clear
                // phase. Never destroy it until its planned replacement
                // has support. This protects physical build progress.
                BuilderClearance.clear(level,worker,table,owner,pos,existing);
                return;
            }
            // Every worker first visits actual staging storage, loads a
            // three-layer pouch, THEN walks to the construction site.
            // Never debit a remote chest while standing at the build.
            if(step.requiredItem()!=null
                    &&worker.carriedCount(state.item())<=0){
                var loading=BuilderSupplyInventory.loadForLayerStatus(
                        level,worker,table,plan.steps(),index);
                if(loading==BuilderSupplyInventory.LoadStatus.MOVING)
                    return; // KEEP this step while walking to real stock.
                if(loading==BuilderSupplyInventory.LoadStatus.MISSING){
                    // Only release for another worker when material is truly
                    // unavailable to this worker. Releasing while moving
                    // previously aborted navigation every 12 ticks.
                    table.waitForBuilderMaterial(worker.getUUID(),index,
                            level.getGameTime());
                    int teammates=BuilderSupplyInventory.carriedByTeammates(
                            level,worker,table,state.item());
                    if(teammates>0){
                        table.setProblem("Builder teammate is carrying "
                                +teammates+" "+step.requiredItem()
                                +"; reorganizing crew pickup");
                        return;
                    }
                    if(BuilderWarehouseCoordinator.request(level,worker,table,
                            owner,plan.steps()))return;
                    String problem=table.problem();
                    if(problem.isBlank()||(!problem.contains("Warehouse")
                            &&!problem.contains("output")&&!problem.contains("PTP")
                            &&!problem.contains("shulker")))
                        table.setProblem("Builder needs "+step.requiredItem()
                                +" in OnSite Storage or a crew pouch");
                    return;
                }
            }
            double horizontal=Math.pow(worker.getX()-(pos.getX()+.5),2)
                    +Math.pow(worker.getZ()-(pos.getZ()+.5),2);
            if(pos.getY()-worker.getY()>2.5 && horizontal>=36){
                // Reach the base of the tower on foot before laying any
                // temporary blocks; never construct remotely.
                com.caszgamermd.golemsplus.worker.WorkerNavigation.routeTo(
                        level,worker,new BlockPos(pos.getX(),
                                worker.blockPosition().getY(),pos.getZ()),.85);
                return;
            }
            if(pos.getY()-worker.getY()>2.5 && horizontal<36){
                var climbing=BuilderScaffolding.reach(level,worker,table,owner,pos,
                        plan.steps());
                if(climbing==BuilderScaffolding.Result.NEED_MATERIALS){
                    table.setProblem("Needs scaffolding for height "+pos.getY()
                            +" (minecraft:scaffolding)");
                    if(BuilderWarehouseCoordinator.requestScaffolding(level,worker,
                            table,owner,plan.steps()))return;
                    return;
                }
                if(climbing==BuilderScaffolding.Result.NO_SAFE_TOWER){
                    table.setProblem("No clear scaffold column beside "+pos);
                    return;
                }
                if(climbing==BuilderScaffolding.Result.CLIMBING)return;
            }
            if(worker.distanceToSqr(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)>14){
                if(!com.caszgamermd.golemsplus.worker.WorkerNavigation
                        .routeTo(level,worker,pos,.85))
                    table.setProblem("Builder cannot path to "+pos);
                return;
            }
            // Never place into any Builder's body. Check before spending
            // the item so waiting for a teammate to move consumes nothing.
            if(!BuilderPlacementSpace.ready(level,worker,table,pos))return;
            // Upper door halves do not consume another door item; the
            // lower half was already placed and paid for in the prior
            // layer. Keep refund logic for all ordinary materials.
            boolean freeHalf=step.requiredItem()==null;
            BuilderSupplyInventory.Receipt payment=freeHalf?null:
                    BuilderSupplyInventory.consume(worker,state.item());
            if(!freeHalf&&payment==null){
                table.waitForBuilderMaterial(worker.getUUID(),index,
                        level.getGameTime());
                if(BuilderWarehouseCoordinator.request(level,worker,table,
                        owner,plan.steps()))return;
                table.setProblem("Builder pouch missing "+step.requiredItem());
                return;
            }
            // Check the actual future collision volume before charging
            // ANY real items. The placing Builder and nearby crew must
            // physically step to safe adjacent ground; setBlock itself
            // does not prevent burying entities in new solid blocks.
            // The real consumed stack is debited from either a loose slot
            // or its packed shulker box BEFORE the world changes.
            if(!level.setBlock(pos,state.state(),3)){
                if(payment!=null)BuilderSupplyInventory.refund(table,payment);
                table.setProblem("World rejected placement at "+pos);
                return;
            }
            table.recordBuilderPlacement(step.requiredItem());
            table.completeBuilderStep(worker.getUUID(),index);
            BuilderSupplyInventory.recycleEmptyShulkers(table);
            // Keep any deployed scaffolding until this ten-block work order
            // completes; retrieving per placed block caused route churn.
            table.setProblem("");
        }catch(Exception exception){
            table.setProblem("Builder paused: "+exception.getMessage());
        }
    }
    private static Cache jobCache(WorkerStationBlockEntity table)
            throws Exception {
        Cache previous=cachedWarehousePlans.get(table);
        if(previous!=null&&previous.file().equals(table.builderFile())
                &&previous.rotation()==table.builderRotation()
                &&previous.mirror()==table.builderMirror()
                &&previous.origin().equals(table.builderOrigin()))
            return previous;
        if(!table.hasPlacedSchematic())
            throw new IllegalArgumentException("No placed schematic");
        Path folder=FabricLoader.getInstance().getConfigDir()
                .resolve("golems_plus").resolve("schematics");
        Path file=folder.resolve(table.builderFile()).normalize();
        if(!file.startsWith(folder)||!Files.isRegularFile(file))
            throw new IllegalArgumentException("Saved schematic missing");
        var schematic=LitematicFileReader.read(file);
        var origin=table.builderOrigin();
        var site=new BuildSite(origin.getX(),origin.getY(),origin.getZ(),
                BuildSite.Rotation.values()[table.builderRotation()/90],
                BuildSite.Mirror.values()[table.builderMirror()]);
        var result=BuilderConstructionPlan.create(schematic,site);
        Cache next=new Cache(table.builderFile(),table.builderRotation(),
                table.builderMirror(),origin,result.steps(),
                BuilderSiteClearance.targets(schematic,site));
        cachedWarehousePlans.put(table,next);
        return next;
    }

    private static Cache cache(WorkerStationBlockEntity table,BuilderGolem worker)
            throws Exception {
        Cache previous=cached.get(worker);
        if(previous!=null&&previous.file().equals(table.builderFile())
                &&previous.rotation()==table.builderRotation()
                &&previous.mirror()==table.builderMirror()
                &&previous.origin().equals(table.builderOrigin()))return previous;
        // Share the same immutable validated work plan among all eight
        // Builders. A different Litematica parse per worker caused pauses
        // when several workers started the job at once.
        Cache next=jobCache(table);
        cached.put(worker,next);
        return next;
    }
}
