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
    private record Cache(String file, int rotation,int mirror,BlockPos origin,
                         List<BuilderConstructionPlan.Step> steps,
                         List<BuilderSiteClearance.Target> clearing){}
    private static final Map<BuilderGolem,Cache> cached=new WeakHashMap<>();
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
            Path folder=FabricLoader.getInstance().getConfigDir()
                    .resolve("golems_plus").resolve("schematics");
            Path file=folder.resolve(table.builderFile()).normalize();
            if(!file.startsWith(folder)||!Files.isRegularFile(file))
                return "The uploaded build schematic is missing on the server";
            var schematic=LitematicFileReader.read(file);
            var origin=table.builderOrigin();
            var site=new BuildSite(origin.getX(),origin.getY(),origin.getZ(),
                    BuildSite.Rotation.values()[table.builderRotation()/90],
                    BuildSite.Mirror.values()[table.builderMirror()]);
            var plan=BuilderConstructionPlan.create(schematic,site);
            if(table.builderRequired().isEmpty()&&table.activeOrder()!=null){
                // Running legacy build: retain its current cursor and only
                // backfill the static required-material checklist.
                table.backfillBuilderMaterials(plan.required());
            }
            var materials=BuilderMaterialDemand.batch(plan.steps(),
                    table.builderCursor(),BuilderSupplyInventory.available(table));
            if(materials.isEmpty())return "Next construction batch already stocked in Planner";
            if(!BuilderWarehouseCoordinator.queueMaterials(level,table,plan.steps()))
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
        // Existing crafted crew members execute the job; the station never
        // materializes extra golems automatically.
        if(level.getGameTime()%20!=0 || !table.isBuilder()
                ||table.activeOrder()==null||!table.hasPlacedSchematic()
                ||table.phase()==WorkerPhase.PAUSED)return;
        var linked=table.builderWorkerIds();
        // Never replace a registered but unloaded worker: its work, materials
        // and provisional scaffold could be persisted in another chunk.
        for(UUID id:linked)if(level.getEntity(id)==null){
            table.setProblem("Builder crew member unloaded; load its chunk");
            return;
        }
        // Wait for the player to craft and place builders beside the table.
    }
    public static void tick(ServerLevel level,BuilderGolem worker){
        BlockPos home=worker.home();
        if(worker.owner()==null||!level.hasChunkAt(home))return;
        if(!(level.getBlockEntity(home) instanceof WorkerStationBlockEntity table)
                ||!table.isBuilder()||!worker.owner().equals(table.owner())){
            worker.dropCargo(level);
            worker.discard();return;
        }
        if(!table.builderWorkerIds().contains(worker.getUUID())){
            // Never discard inventory-bearing entities: a misconfigured
            // workstation must not silently destroy delivered shulker cargo.
            table.setProblem("Unrecognized Builder Golem near workstation");
            return;
        }
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
                if(worker.distanceToSqr(home.getX()+.5,home.getY()+.5,
                        home.getZ()+.5)>12){
                    com.caszgamermd.golemsplus.worker.WorkerNavigation
                            .routeTo(level,worker,home,.83);
                    return;
                }
                if(!BuilderSupplyInventory.returnTools(worker,table)){
                    table.setProblem("Make room in Builder Planner for returned mining tools");
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
            if(!worker.hasSupplyBox()&&table.supplyRequest()==null)
                com.caszgamermd.golemsplus.worker.WorkerIdleRoam.tick(level,worker,home);
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
                UUID cleaner=null;
                for(UUID id:table.builderWorkerIds()){
                    if(!id.equals(table.supplyRunner())){cleaner=id;break;}
                }
                if(worker.getUUID().equals(cleaner))
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
            int index=table.claimBuilderStep(worker.getUUID(),limit);
            if(index<0)return; // wait for current layer assignments to finish
            var step=plan.steps().get(index);
            BlockPos pos=new BlockPos(step.x(),step.y(),step.z());
            if(Math.abs((long)pos.getX()-home.getX())>256||
                    Math.abs((long)pos.getZ()-home.getZ())>256){
                table.setProblem("Build extends beyond 256-block Builder range");
                return;
            }
            if(!level.hasChunkAt(pos)){
                table.setProblem("Build chunk is not loaded");return;
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
            if(existing.equals(state.state())){
                table.recordBuilderPlacement(step.requiredItem());
                table.completeBuilderStep(worker.getUUID(),index);
                BuilderScaffolding.retrieve(level,worker,table,false);
                table.setProblem("");return;
            }
            if(!existing.isAir()){
                // A player may add a block after the initial site-clear
                // phase. Safely clear only this exact schematic cell,
                // retaining bottom-up ordering and normal protections.
                BuilderClearance.clear(level,worker,table,owner,pos,existing);
                return;
            }
            if(!state.state().canSurvive(level,pos)){
                table.setProblem("Missing supporting blocks at "+pos);return;
            }
            // Every worker first visits actual staging storage, loads a
            // three-layer pouch, THEN walks to the construction site.
            // Never debit a remote chest while standing at the build.
            if(worker.carriedCount(state.item())<=0 &&
                    !BuilderSupplyInventory.loadForLayer(
                            level,worker,table,plan.steps(),index)){
                // Release this assignment temporarily: another real block
                // in the SAME layer may already be available. A missing
                // material does not consume a step or skip to the next Y.
                table.waitForBuilderMaterial(worker.getUUID(),index,
                        level.getGameTime());
                if(BuilderWarehouseCoordinator.request(level,worker,table,
                        owner,plan.steps()))return;
                // Do not hide a more useful warehouse diagnostic behind
                // the generic "site storage is empty" warning. A courier
                // request can be retried automatically when a PTP is freed.
                String problem=table.problem();
                if(problem.isBlank()||(!problem.contains("Warehouse")
                        &&!problem.contains("output")&&!problem.contains("PTP")
                        &&!problem.contains("shulker")))
                    table.setProblem("Builder needs "+step.requiredItem()+
                            " in on-site storage; waiting for warehouse delivery");
                return;
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
            BuilderSupplyInventory.Receipt payment=
                    BuilderSupplyInventory.consume(worker,state.item());
            if(payment==null){
                table.waitForBuilderMaterial(worker.getUUID(),index,
                        level.getGameTime());
                if(BuilderWarehouseCoordinator.request(level,worker,table,
                        owner,plan.steps()))return;
                table.setProblem("Builder pouch missing "+step.requiredItem());
                return;
            }
            // The real consumed stack is debited from either a loose slot
            // or its packed shulker box BEFORE the world changes.
            if(!level.setBlock(pos,state.state(),3)){
                BuilderSupplyInventory.refund(table,payment);
                table.setProblem("World rejected placement at "+pos);
                return;
            }
            table.recordBuilderPlacement(step.requiredItem());
            table.completeBuilderStep(worker.getUUID(),index);
            BuilderSupplyInventory.recycleEmptyShulkers(table);
            BuilderScaffolding.retrieve(level,worker,table,false);
            table.setProblem("");
        }catch(Exception exception){
            table.setProblem("Builder paused: "+exception.getMessage());
        }
    }
    private static Cache cache(WorkerStationBlockEntity table,BuilderGolem worker)
            throws Exception {
        Cache previous=cached.get(worker);
        if(previous!=null&&previous.file().equals(table.builderFile())
                &&previous.rotation()==table.builderRotation()
                &&previous.mirror()==table.builderMirror()
                &&previous.origin().equals(table.builderOrigin()))return previous;
        if(!table.hasPlacedSchematic())throw new IllegalArgumentException("No placed schematic");
        Path file=FabricLoader.getInstance().getConfigDir()
                .resolve("golems_plus").resolve("schematics").resolve(table.builderFile());
        if(!Files.isRegularFile(file))throw new IllegalArgumentException("Saved schematic missing");
        var schematic=LitematicFileReader.read(file);
        var site=new BuildSite(table.builderOrigin().getX(),table.builderOrigin().getY(),
                table.builderOrigin().getZ(),
                BuildSite.Rotation.values()[table.builderRotation()/90],
                BuildSite.Mirror.values()[table.builderMirror()]);
        var result=BuilderConstructionPlan.create(schematic,site);
        // A job may start with unsupported blocks. It stops safely at any
        // unresolvable step and reports the exact state instead of deleting
        // blocks or fabricating missing materials.
        Cache next=new Cache(table.builderFile(),table.builderRotation(),
                table.builderMirror(),table.builderOrigin(),result.steps(),
                BuilderSiteClearance.targets(schematic,site));
        cached.put(worker,next);
        return next;
    }
}
