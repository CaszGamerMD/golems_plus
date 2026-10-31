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
import net.minecraft.world.phys.Vec3;

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
                         List<BuilderSiteClearance.Target> clearing,
                         boolean ignorePlants,BuilderZones.Layout zones){}
    private static final Map<BuilderGolem,Cache> cached=new WeakHashMap<>();
    /** One immutable COMPLETE job-zone plan per Planner/job/crew. Assignment
     * work is never repeated on each Builder tick or after a placement.
     */
    private record ScheduledZones(UUID job,Cache cache,List<UUID> roster,
            BuilderZoneWorkPlan tasks){}
    private static final Map<WorkerStationBlockEntity,ScheduledZones>
            schedules=new WeakHashMap<>();
    private enum Action { STAGE_MATERIALS, MOVE_TO_WORK, PLACE, COMPLETE }
    private static final class WorkerAction {
        int index=-1;
        Action phase=Action.STAGE_MATERIALS;
        long blockedSince=-1;
        long frontierWaitSince=-1;
        int frontierWaitLayer=Integer.MIN_VALUE;
        long workTravelSince=-1;
        long lastMoved=-1,lastCloser=-1,lastAlternativeTry=-1;
        double bestDistanceSquared=Double.POSITIVE_INFINITY;
        Vec3 lastObserved;
        void setStep(int next){
            if(index!=next){
                index=next;phase=Action.STAGE_MATERIALS;
                blockedSince=-1;workTravelSince=-1;
                lastMoved=-1;lastCloser=-1;lastAlternativeTry=-1;
                bestDistanceSquared=Double.POSITIVE_INFINITY;
                lastObserved=null;
            }
        }
        void observeTravel(BuilderGolem worker,BlockPos target,long now){
            double distance=worker.distanceToSqr(target.getX()+.5,
                    target.getY()+.5,target.getZ()+.5);
            if(lastCloser<0||distance+.5<bestDistanceSquared){
                bestDistanceSquared=distance;
                lastCloser=now;
            }
            if(lastObserved==null
                    ||worker.position().distanceToSqr(lastObserved)>.35*.35){
                lastObserved=worker.position();
                lastMoved=now;
            }
        }
        /** A worker may walk AWAY from a goal on a correct detour. Give
         * that physical navigation time to work; seek another tool only
         * after 80 ticks without motion or 200 without getting closer.
         * Failed scaffold previews are throttled to every 60 ticks.
         */
        boolean alternateAccessDue(long now){
            return workTravelSince>=0&&now-workTravelSince>=80
                    &&(now-lastMoved>=80||now-lastCloser>=200)
                    &&(lastAlternativeTry<0||now-lastAlternativeTry>=60);
        }
    }
    private static final Map<BuilderGolem,WorkerAction> workerActions=
            new WeakHashMap<>();
    /** Normally every X/Z zone is exclusive. A zone with no work on this
     * shared Y may help its stuck neighbor after construction has made
     * no physical progress for ten seconds. This is read-only monitoring:
     * actual ownership stays with the Planner's single-step claim map.
     */
    private static final class RoofProgress {
        int completed=-1;
        long lastChange;
    }
    private static final Map<WorkerStationBlockEntity,RoofProgress>
            roofProgress=new WeakHashMap<>();
    private static boolean roofNeedsHelp(ServerLevel level,
            WorkerStationBlockEntity table){
        RoofProgress progress=roofProgress.computeIfAbsent(table,
                unused->new RoofProgress());
        int completed=table.builderWorkDone();
        if(progress.completed!=completed||level.getGameTime()<progress.lastChange){
            progress.completed=completed;
            progress.lastChange=level.getGameTime();
        }
        return level.getGameTime()-progress.lastChange>=600;
    }
    /** Only workers with NO unfinished task in their zone on the active Y assist: they
     * never abandon an assigned cell or break the global height gate.
     * A worker whose own next assignments are on Y+1 can safely help a
     * blocked neighboring zone complete Y before those become runnable.
     * Extra candidates must be unclaimed and physically reachable.
     */
    private static List<Integer> roofRescueZone(WorkerStationBlockEntity table,
            UUID worker,List<BuilderConstructionPlan.Step> steps,int layer){
        var extra=new java.util.ArrayList<Integer>();
        for(int idx=0;idx<steps.size();idx++){
            var step=steps.get(idx);
            if(step.y()==layer&&table.builderStepReadyForClaim(idx)
                    &&!table.builderStepClaimedByOther(worker,idx))
                extra.add(idx);
        }
        return extra;
    }
    private static WorkerAction action(BuilderGolem worker,int index){
        var activity=workerActions.computeIfAbsent(worker,k->new WorkerAction());
        activity.setStep(index);
        return activity;
    }
    /** A retry cooldown is not a request to descend to storage. Keep a
     * grounded worker on its current work level, or up to two blocks
     * BELOW its next work layer, when its zone still has work now OR
     * ahead. This preserves hard-won high-roof access while another
     * zone completes the previous layer. The worker must remain clear
     * of every unfinished current-layer placement envelope.
     */
    static boolean canWaitNearFrontier(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,List<BuilderConstructionPlan.Step> steps,
            List<Integer> zone,int layer){
        boolean highRoof=worker.getY()-table.getBlockPos().getY()>=12;
        if(!worker.onGround()||worker.isNoGravity()
                ||worker.blockPosition().getY()<layer-(highRoof?5:2)
                ||worker.blockPosition().getY()>layer+1
                ||!level.noCollision(worker,worker.getBoundingBox()))return false;
        boolean pending=false;
        for(int idx:zone)
            if(idx>=0&&idx<steps.size()&&steps.get(idx).y()>=layer
                    &&!table.builderZoneDone(List.of(idx))){pending=true;break;}
        if(!pending)return false;
        // Check the whole crew's current layer, not only this worker's
        // zone, so a waiting worker cannot occupy a teammate's target.
        for(int idx=0;idx<steps.size();idx++){
            var step=steps.get(idx);
            if(step.y()!=layer||step.ignorePlant()
                    ||table.builderZoneDone(List.of(idx)))continue;
            BlockPos target=new BlockPos(step.x(),step.y(),step.z());
            // Waiting beside a future cap is NOT a placement attempt:
            // the .8m arm-clearance margin applies to the Builder doing
            // the placing. For idle staging, protect the actual body
            // plus .1m collision allowance; requiring the arm margin
            // needlessly drove rooftop helpers to the ground depot.
            if(worker.getBoundingBox().intersects(
                    new net.minecraft.world.phys.AABB(target).inflate(.1,0,.1)))
                return false;
        }
        return true;
    }

    // Reuse the immutable server-side schematic work plan across queued
    // warehouse retries. Parsing a 100k-block Litematica file every four
    // seconds caused visible freezes while a PTP was temporarily busy.
    private static final Map<WorkerStationBlockEntity,Cache>
            cachedWarehousePlans=new WeakHashMap<>();
    /** Completed Builders must never camp on the structure or occupy
     * another zone's future blocks. The OnSite buffer is outside the
     * schematic; return via a genuine hook descent, then ordinary A*.
     * Do not retire or despawn a worker while the other zones run.
     */
    private static void parkAtOnSite(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table){
        BlockPos depot=table.builderUnloadingStorage();
        if(depot==null)depot=table.getBlockPos();
        if(BuilderScaffolding.descendForTravel(level,worker,table)
                ||BuilderGrappleAccess.rappelToward(level,worker,table,depot))
            return;
        if(worker.distanceToSqr(depot.getX()+.5,depot.getY(),
                depot.getZ()+.5)>4.0){
            com.caszgamermd.golemsplus.worker.WorkerNavigation
                    .routeTo(level,worker,depot,.8);
        }else{
            com.caszgamermd.golemsplus.worker.WorkerNavigation.stop(worker);
        }
    }
    private static void activityForFinished(BuilderGolem worker){
        var a=workerActions.get(worker);
        if(a!=null)a.phase=Action.COMPLETE;
    }
    /** Existing inventory-paid access systems, tried only after normal
     * grounded work routing cannot find a reachable stance. Every shaft
     * and bridge is tracked by the Planner and has a checked exit.
     * A missing scaffolding stack never fabricates building materials.
     */
    private static boolean scaffoldWork(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,ServerPlayer owner,BlockPos target,
            List<BuilderConstructionPlan.Step> steps){
        if(BuilderScaffoldPlanA.hasPlan(worker)
                &&table.scaffoldPositions(worker.getUUID()).isEmpty()
                &&!BuilderScaffolding.isControllingMovement(worker)
                &&worker.onGround()&&!worker.isNoGravity()
                &&BuilderPlacementSpace.placementHeight(target,worker)
                &&BuilderPlacementSpace.horizontalClear(target,worker)
                &&worker.distanceToSqr(target.getX()+.5,
                        target.getY()+.5,target.getZ()+.5)<14
                &&BuilderConstructionPlanner.safeStand(
                        level,worker,target,worker.blockPosition())){
            // A worker may reach a legal roof stance while heading for
            // an optional paid tower. If the tower has not been erected,
            // release that obsolete approach and place from REAL footing;
            // never interrupt an active climb or abandon owned scaffold.
            BuilderScaffoldPlanA.cancel(worker);
            return false;
        }
        if(BuilderScaffoldPlanA.hasPlan(worker)
                ||BuilderScaffoldPlanA.canPlanWorkPose(
                        level,worker,table,target,steps)){
            var bridge=BuilderScaffoldPlanA.reachWorkPose(
                    level,worker,table,owner,target,steps);
            if(bridge==BuilderScaffoldPlanA.Result.MOVING
                    ||bridge==BuilderScaffoldPlanA.Result.NEED_MATERIALS)
            {
                if(bridge==BuilderScaffoldPlanA.Result.NEED_MATERIALS)
                    table.setProblem("Builder needs Scaffolding in OnSite Storage for "
                            +target.toShortString());
                return true;
            }
            if(bridge==BuilderScaffoldPlanA.Result.READY)return false;
        }
        var shaft=BuilderScaffolding.reachBlockedWork(
                level,worker,table,owner,target,steps);
        if(shaft==BuilderScaffolding.Result.CLIMBING
                ||shaft==BuilderScaffolding.Result.NEED_MATERIALS)return true;
        // When the shaft opens onto a real roof surface, leave the top
        // physically before starting an ordinary placement approach.
        return BuilderScaffolding.stepOffTowardWork(level,worker,table,target);
    }
    /** Work positions can fail on a completely built roof at the SAME Y.
     * Give any Builder a paid, reversible scaffold approach without
     * requiring a floating target or a positive vertical Y difference.
     */
    private static boolean scaffoldBlockedWork(ServerLevel level,
            BuilderGolem worker,WorkerStationBlockEntity table,
            ServerPlayer owner,BlockPos target,
            List<BuilderConstructionPlan.Step> steps){
        return (BuilderScaffoldPlanA.hasPlan(worker)
                    ||BuilderScaffoldPlanA.canPlanWorkPose(
                            level,worker,table,target,steps)
                    ||BuilderScaffolding.canPlanAccess(
                            level,worker,table,target,steps))
                &&scaffoldWork(level,worker,table,owner,target,steps);
    }
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
            var materials=BuilderMaterialDemand.outstandingRemaining(
                    table.builderRemaining(),stock,
                    pickable==null?Map.of():pickable);
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
        // Keep already-queued warehouse work in sync with the real on-site
        // ledger. Cancel only via the warehouse's safe physical cargo
        // contract, never erase existing packing or carried shulkers.
        if(table.isBuilder()&&table.activeOrder()!=null
                &&table.supplyRequest()!=null&&!table.builderCancelShipment()
                &&level.getGameTime()%40==0)
            BuilderWarehouseCoordinator.reconcileTicket(level,table);
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
        if(BuilderGrappleAccess.active(worker))return;
        if(BuilderScaffolding.isControllingMovement(worker))return;
        if(BuilderPlacementSpace.yieldTick(level,worker))return;
        if(table.activeOrder()==null||table.phase()==WorkerPhase.COMPLETE){
            // On job completion a Builder may still be on its LAST
            // scaffold tower. It must return to ground and dismount
            // BEFORE reclaiming blocks, delivering tools or going home.
            if(BuilderScaffolding.descendForTravel(level,worker,table)
                    ||BuilderGrappleAccess.rappelForTravel(level,worker,table))
                return;
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
            BuilderGrappleAccess.cancel(worker);
            worker.getNavigation().stop();
            worker.setNoGravity(false);return;
        }
        // Plan B owns the rope maneuver exclusively until landing.
        if(BuilderGrappleAccess.active(worker))return;
        int membership=table.builderWorkerIds().indexOf(worker.getUUID());
        if(membership>=table.crewTarget()&&table.claimedStep(worker.getUUID())<0
                &&!worker.hasSupplyBox()){
            worker.setNoGravity(false);
            if(BuilderScaffolding.descendForTravel(level,worker,table))return;
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
            // Display required physical placements without waiting for
            // site clearance. This is accounting only, never a completion.
            table.previewBuilderWork(plan.steps());
            // Warehouse reconciliation and proactive orders are Planner-
            // level jobs running from dispatch() every two/four seconds.
            // Doing both here from EVERY Builder every five ticks generated
            // dozens of redundant order/stock scans and starved navigation.
            // Only the courier handles a physical shipment in its own tick.
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
            // Ignore-plant entries are virtual no-op completions, not
            // physical work or a route a Builder must walk. This also
            // completes a job with only ignored vegetation remaining.
            table.reconcileBuilderWork(plan.steps());
            if(table.phase()==WorkerPhase.COMPLETE)return;
            // All positions for each X/Z zone were pre-assigned as one
            // immutable ordered execution plan. The Planner does NOT
            // arbitrate between Builders on each move or block placement.
            var assigned=zoneSchedule(table,plan);
            var zone=assigned.tasks().steps(worker.getUUID());
            // Unreachable frontier cells may now be served by owned,
            // physically climbable scaffold towers or two-cell bridges.
            // Construction intelligence runs BEFORE step claiming:
            // assign only a frontier cell with an actually reachable,
            // collision-safe work stance and a route out after placing.
            // A currently claimed task is kept during physical travel;
            // failed tasks are explicitly deferred and then reselected.
            int index=table.claimedStep(worker.getUUID());
            int layer=table.builderActiveLayer(plan.steps());
            boolean ownLayerClear=true;
            for(int idx:zone)
                if(idx>=0&&idx<plan.steps().size()
                        &&plan.steps().get(idx).y()==layer
                        &&!table.builderZoneDone(List.of(idx))){
                    ownLayerClear=false;break;
                }
            boolean emergencyRoofAssist=ownLayerClear
                    &&(index>=0&&!zone.contains(index)
                        ||roofNeedsHelp(level,table));
            // Monitoring must run for every working crew, including the
            // worker still in its own zone: otherwise a fully active crew
            // could never start the rescue clock.
            if(!emergencyRoofAssist)roofNeedsHelp(level,table);
            if(emergencyRoofAssist){
                var rescue=roofRescueZone(table,worker.getUUID(),
                        plan.steps(),layer);
                if(!rescue.isEmpty()){
                    zone=rescue;
                    if(level.getGameTime()%200==0)
                        System.out.println("[ROOF ASSIST] "
                                +worker.getName().getString()+" can serve "
                                +rescue.size()+" unfinished Y="
                                +(layer-table.builderOrigin().getY())
                                +" cells outside its cleared layer zone");
                }
            }
            if(index<0||index>=plan.steps().size()
                    ||!zone.contains(index)
                    ||plan.steps().get(index).y()!=layer
                    ||!table.builderStepReadyForClaim(index)){
                var next=BuilderConstructionPlanner.choose(
                        level,worker,table,plan.steps(),zone);
                index=next==null?-1:table.claimPreferredBuilderZoneStep(
                        worker.getUUID(),zone,plan.steps(),next.index());
            }
            if(index<0){
                // No safely reachable or runnable cell on this same Y
                // in this worker's zone. Do NOT accept a random index or
                // climb to a higher construction layer. Get out of the
                // active construction volume so another Builder can work.
                if(level.getGameTime()%80==0){
                    String report=BuilderConstructionPlanner.blockedSummary(
                            level,table,plan.steps(),zone);
                    table.setProblem("FRONTIER: "+report);
                    System.out.println("[FRONTIER BLOCKED] "
                            +worker.getName().getString()+" "+report);
                }
                // When a finished zone still has a safe upper ledge,
                // preserve it for emergency help on a stuck neighboring
                // zone. Sending all three idle roof workers down to the
                // OnSite chest leaves nobody who can step back and build
                // a paid scaffold to the last few cap cells.
                List<Integer> waitingZone=zone;
                if(ownLayerClear
                        &&worker.getY()-table.getBlockPos().getY()>=12){
                    var rescue=roofRescueZone(table,worker.getUUID(),
                            plan.steps(),layer);
                    if(!rescue.isEmpty())waitingZone=rescue;
                }
                if(canWaitNearFrontier(level,worker,table,
                        plan.steps(),waitingZone,layer)){
                    var waiting=workerActions.computeIfAbsent(worker,k->new WorkerAction());
                    if(waiting.frontierWaitSince<0
                            ||waiting.frontierWaitLayer!=layer){
                        waiting.frontierWaitSince=level.getGameTime();
                        waiting.frontierWaitLayer=layer;
                    }
                    // Short low walls are cheap to approach again: keep
                    // the old 80-tick courtesy wait so idle Builders do
                    // not clog early dome corridors. At a high roof,
                    // abandoning an otherwise safe ledge means losing
                    // 12+ real blocks of hard-won vertical access. Hold
                    // that ledge while this zone has unfinished work on
                    // the current or a future layer. Completed zones
                    // still leave immediately.
                    boolean expensiveAscent=worker.getY()
                            -table.getBlockPos().getY()>=12;
                    if(expensiveAscent||level.getGameTime()
                            -waiting.frontierWaitSince<=80){
                        com.caszgamermd.golemsplus.worker.WorkerNavigation.stop(worker);
                        return;
                    }
                }
                if(table.builderZoneDone(zone))activityForFinished(worker);
                parkAtOnSite(level,worker,table);
                return;
            }
            var activity=action(worker,index);
            activity.frontierWaitSince=-1;
            var step=plan.steps().get(index);
            int activeLayer=table.builderActiveLayer(plan.steps());
            if(step.y()!=activeLayer){
                // Defensive execution guard: even a stale claim from a
                // previous build version must not break the Y-layer rule.
                table.setProblem("Builder waiting for completion of Y="+activeLayer
                        +" before Y="+step.y());
                return;
            }
            if(step.ignorePlant()){
                // The position stays in the saved indexed work order, but
                // the requested crop/flower is never placed or debited.
                // Site clearance already ran normally for this coordinate.
                table.completeBuilderStep(worker.getUUID(),index);
                activity.phase=Action.COMPLETE;
                return;
            }
            // Read the actual world first. Already-correct positions are
            // NO-OP assignments, not an excuse to walk all the way back to
            // OnSite for materials that will never be consumed.
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
                activity.phase=Action.COMPLETE;
                // Already-correct positions are NO-OPs, never mine or
                // dismantle the nearby scaffold to rebuild them.
                return;
            }
            // The hook controller retains exclusive movement until
            // physically landed; do not issue an overlapping work route.
            // Every real Builder goes to the chosen OnSite depot ONCE per
            // exclusive ten-coordinate batch, unloads other materials and
            // collects actual stock (including flowers within shulkers).
            // It does not visit the Planning Table for assignments.
            // A frontier choice can change the ten/32-cell stage.
            // That invalidates the prior *batch* marker, not the physical
            // blocks ALREADY in this golem's persisted pouch. If it has
            // the exact item needed for this step, proceed with that real
            // inventory rather than repeatedly rappelling back to OnSite.
            // The normal per-step load below will still require a genuine
            // depot visit when a later item is actually missing.
            if(!table.builderOrderPrepared(worker.getUUID())
                    &&step.requiredItem()!=null
                    &&worker.carriedCount(state.item())<=0){
                activity.phase=Action.STAGE_MATERIALS;
                // An A* route from the TOP of a temporary scaffold to
                // OnSite Storage can never discover the vertical descent.
                if(BuilderScaffolding.descendForTravel(level,worker,table)
                        ||BuilderGrappleAccess.rappelForTravel(level,worker,table))
                    return;
                var prep=BuilderSupplyInventory.prepareWorkOrder(
                        level,worker,table,plan.steps(),zone);
                if(prep==BuilderSupplyInventory.LoadStatus.MOVING)return;
                if(prep==BuilderSupplyInventory.LoadStatus.MISSING){
                    table.setProblem("Builder work order waiting for reachable "
                            +"OnSite Storage (material staging not complete)");
                    return;
                }
                table.prepareBuilderOrder(worker.getUUID());
            }
            activity.phase=Action.MOVE_TO_WORK;
            // Work descent is resolved against the actual work pose below.
            // Do not send a stocked worker back to the depot merely
            // because its legal work route temporarily crosses a high ledge.
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
            // PLAN B: physical Grapple Hook up to 20 blocks, never a
            // scaffold/flying/teleport fallback. Ordinary slopes use A*.
            // A released rope leaves feet slightly above the ledge until
            // gravity settles them. Do not reject a route or abandon the
            // claim from that transient airborne start.
            // A genuine scaffold climb is different: its top can momentarily
            // leave the worker inside scaffold with onGround=false. Advance
            // the active scaffold session while it owns that physical motion
            // instead of freezing at CLIMB and repeatedly falling down.
            if(!worker.onGround()){
                if(BuilderScaffoldPlanA.hasPlan(worker))
                    BuilderScaffoldPlanA.reach(
                            level,worker,table,owner,pos,plan.steps());
                return;
            }
            // Narrow final wall/fence caps support feet at Y+.5. If
            // the Builder is already perched there but its shoulder is
            // 20-40cm too close to the next block, execute a short,
            // support-proven step BACK before asking it to build a new
            // scaffold or seek a nonexistent integer-centered A* node.
            if(BuilderPlacementSpace.stepBackFromFractionalCap(
                    level,worker,table,pos)){
                activity.phase=Action.MOVE_TO_WORK;
                return;
            }
            long now=level.getGameTime();
            if(activity.workTravelSince<0)activity.workTravelSince=now;
            activity.observeTravel(worker,pos,now);
            // Once an owned shaft/bridge starts, its controller owns all
            // physical movement. Do not replace it with a normal route
            // just because another nominal work pose becomes visible.
            if(BuilderScaffoldPlanA.hasPlan(worker)
                    &&scaffoldWork(level,worker,table,owner,pos,plan.steps()))
                return;
            if(now-activity.workTravelSince>900&&!BuilderGrappleAccess.active(worker)){
                // Repeatedly executable movement is not proof that this
                // placement is making progress. Preserve the cell/materials
                // and let another feasible frontier on THIS Y run first.
                BuilderPlacementSpace.cancelYield(worker);
                BuilderPlacementSpace.invalidateApproach(worker,pos);
                com.caszgamermd.golemsplus.worker.WorkerNavigation.stop(worker);
                table.waitForBuilderAccess(worker.getUUID(),index,now,
                        "900-tick work approach without placement");
                activity.workTravelSince=-1;
                table.setProblem("Deferred cycling work approach "+pos);
                System.out.println("[FRONTIER RETRY] deferred unchanged step="+index
                        +" after 900 ticks without placement at "+worker.position());
                return;
            }
            int yDiff=pos.getY()-worker.blockPosition().getY();
            boolean temporaryWorkPose=BuilderScaffoldPlanA.safeTemporaryWorkPose(
                    level,worker,table,pos);
            double workDistance=worker.distanceToSqr(
                    pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5);
            boolean directOrdinaryWork=BuilderPlacementSpace.placementHeight(
                        pos,worker)
                    &&workDistance<14
                    &&BuilderPlacementSpace.horizontalClear(pos,worker)
                    &&BuilderConstructionPlanner.fenceWallClearance(
                            level,worker.getBoundingBox())
                    &&(temporaryWorkPose
                        ||BuilderPlacementSpace.safeFractionalCapPose(
                                level,worker,pos)
                        ||BuilderConstructionPlanner.safeStand(
                                level,worker,pos,worker.blockPosition()));
            // If the trained route is idle, stuck behind a railing, or
            // making no actual progress toward its work cell, offer the
            // inventory-paid scaffold/step-back tool NOW. This applies
            // at the same Y as well as on climbs; a reachable walking
            // detour remains preferred while it is making progress.
            if(!directOrdinaryWork&&activity.alternateAccessDue(now)
                    &&!BuilderGrappleAccess.active(worker)
                    &&!BuilderScaffolding.isControllingMovement(worker)){
                activity.lastAlternativeTry=now;
                if(scaffoldBlockedWork(level,worker,table,owner,pos,
                        plan.steps())){
                    System.out.println("[BUILDER ACCESS ALTERNATE] step="+index
                            +" localY="+(pos.getY()-table.builderOrigin().getY())
                            +" moveIdle="+(now-activity.lastMoved)
                            +" closerIdle="+(now-activity.lastCloser)
                            +" via=paid_scaffold from="+worker.position());
                    return;
                }
            }
            // AIR below a roof block is not a reason to defer it for
            // "missing support": full cubes can be placed in mid-air.
            // It IS a strong sign that a golem needs temporary footing.
            // Give the inventory-paid scaffold option an EARLY turn after
            // an ordinary route has had time to make progress, including
            // side-connected blocks that are not completely isolated.
            // A fence beneath a block is real support and stays on the
            // ordinary collision-checked approach path if reachable.
            if(!directOrdinaryWork
                    &&BuilderScaffoldPlanA.needsTemporaryWorkPlatform(level,pos)
                    &&now-activity.workTravelSince>120
                    &&!BuilderGrappleAccess.active(worker)
                    &&scaffoldBlockedWork(level,worker,table,owner,pos,
                            plan.steps()))return;
            // Low dome walls and fence caps often sit two Y above a
            // grounded Builder. Its normal three-block hand reach may
            // already cover the target from a SIDE stance. Find that
            // safe, walkable position before spending time on a rope or
            // scaffold tower. A trained route can walk backward first.
            if(yDiff>1&&!directOrdinaryWork
                    &&!BuilderGrappleAccess.active(worker)
                    &&!BuilderScaffolding.isControllingMovement(worker)){
                BlockPos walk=BuilderPlacementSpace.approachWithRules(
                        level,worker,pos,
                        at->BuilderConstructionPlanner.safeStand(
                                level,worker,pos,at));
                if(walk!=null){
                    activity.phase=Action.MOVE_TO_WORK;
                    if(com.caszgamermd.golemsplus.worker.WorkerNavigation
                            .routeToExact(level,worker,walk,1.0))return;
                    BuilderPlacementSpace.invalidateApproach(worker,pos);
                }
            }
            // A grapple can repeatedly select an unreachable high ledge.
            // After a genuine physical approach timeout, try a supported,
            // inventory-paid scaffold bridge rooted on this same build.
            // Never interrupt a moving rope or a current bridge session.
            // Leave executable ordinary walking routes alone. A fixed
            // same-height timeout was taking Builders off their valid
            // low-layer work paths and onto needless scaffold searches.
            // Same-Y scaffolding is instead offered below only AFTER
            // all safe walking stances or their real routes are rejected.
            if(yDiff>1&&!directOrdinaryWork
                    &&now-activity.workTravelSince>260
                    &&!BuilderGrappleAccess.active(worker)
                    &&scaffoldBlockedWork(level,worker,table,owner,pos,
                            plan.steps()))
                return;
            if(yDiff>1&&!directOrdinaryWork){
                var access=BuilderGrappleAccess.reachWork(level,worker,table,pos);
                if(access==BuilderGrappleAccess.Result.NEED_HOOK){
                    // A hook shortage is not a movement prohibition.
                    // Walking to a higher work stance and a paid scaffold
                    // are still candidates for this same block.
                    if(scaffoldWork(level,worker,table,owner,pos,plan.steps()))
                        return;
                }
                if(access==BuilderGrappleAccess.Result.NO_SAFE_ANCHOR){
                    if(scaffoldWork(level,worker,table,owner,pos,plan.steps()))
                        return;
                    // Neither an anchor nor a tower is mandatory. An
                    // ordinary detour may first descend or move away from
                    // this target before arriving at a valid elevated pose.
                    // Let the normal work-stance search prove that route.
                }
                if(access==BuilderGrappleAccess.Result.MOVING)return;
            }
            if(!BuilderPlacementSpace.placementHeight(pos,worker)
                    ||workDistance>14
                    ||!BuilderPlacementSpace.horizontalClear(pos,worker)
                    ||!BuilderConstructionPlanner.fenceWallClearance(
                            level,worker.getBoundingBox())
                    ||!(temporaryWorkPose
                        ||BuilderPlacementSpace.safeFractionalCapPose(
                                level,worker,pos)
                        ||BuilderConstructionPlanner.safeStand(
                                level,worker,pos,worker.blockPosition()))){
                // Even when within arm's reach, a Builder must first
                // occupy a PREVALIDATED work stance. This prevents the
                // repeated old deadlock where it arrived next to an AIR
                // cell, then had to escape its own placement volume.
                activity.phase=Action.MOVE_TO_WORK;
                BlockPos approach=BuilderPlacementSpace.approachWithRules(
                        level,worker,pos,
                        at->BuilderConstructionPlanner.safeStand(
                                level,worker,pos,at));
                if(approach!=null){
                    boolean moving=com.caszgamermd.golemsplus.worker
                            .WorkerNavigation.routeToExact(
                                    level,worker,approach,1.0);
                    if(!moving){
                        BuilderPlacementSpace.invalidateApproach(worker,pos);
                        moving=com.caszgamermd.golemsplus.worker
                                .WorkerNavigation.routeTo(
                                        level,worker,approach,.85);
                    }
                    if(!moving&&scaffoldBlockedWork(
                            level,worker,table,owner,pos,plan.steps()))return;
                    if(!moving)
                        table.waitForBuilderAccess(worker.getUUID(),index,
                                level.getGameTime(),"trained routes rejected work pose and scaffold");
                    return;
                }
                // An enclosed or connected roof cell can need temporary
                // footing just as much as a floating one. Every scaffold
                // is inventory-paid, on air outside the future schematic,
                // and has a checked path back to its ground/roof exit.
                if(scaffoldBlockedWork(level,worker,table,owner,pos,plan.steps()))
                    return;
                if(yDiff>0){
                    var access=BuilderGrappleAccess.reachWork(level,worker,table,pos);
                    if(access==BuilderGrappleAccess.Result.NEED_HOOK)
                        table.setProblem("Builder needs a Grapple Hook");
                    else if(access==BuilderGrappleAccess.Result.NO_SAFE_ANCHOR){
                        table.waitForBuilderAccess(worker.getUUID(),index,
                                level.getGameTime(),"one-block rise no grapple anchor");
                        table.setProblem("Deferred blocked one-step rise "+pos);
                    }else if(access==BuilderGrappleAccess.Result.READY)
                        table.waitForBuilderAccess(worker.getUUID(),index,
                                level.getGameTime(),"rise route ready but no safe pose");
                    return;
                }
                if(yDiff< -1 && (BuilderScaffolding.descendForTravel(
                        level,worker,table)||BuilderGrappleAccess.rappelToward(
                        level,worker,table,pos)))return;
                if(yDiff==-1){
                    // One layer above the block is legal ONLY when a
                    // physical standing location, side clearance and
                    // post-placement exit all exist. Don't grapple down
                    // unnecessarily or invent an unreachable stance.
                    table.waitForBuilderAccess(worker.getUUID(),index,
                            level.getGameTime(),"no Y+1 validated reachable work approach");
                    table.setProblem("FRONTIER: no safe Y+1 work stance at "
                            +pos+" (line of sight / exit / clearance)");
                    return;
                }
                // Larger downward changes still require collision-safe
                // navigation, not a free fall.
                // This is only a MOVEMENT beacon when no safe work pose
                // has been discovered yet, never permission to PLACE into
                // the target. Actual placement still requires the 0.8m
                // edge margin, grounded work pose and clear exit above.
                // Retain the fractional branch's last-resort local motion
                // so low-level connected cells are not deferred forever.
                if(!com.caszgamermd.golemsplus.worker.WorkerNavigation
                        .routeTo(level,worker,pos,.85))
                    table.waitForBuilderAccess(worker.getUUID(),index,
                            level.getGameTime(),"cannot walk to lower target");
                return;
            }
            // A Builder may place from its assigned layer or from one
            // grounded block ABOVE that layer. This changes only its
            // physical feet height, never the crew-wide Y work gate.
            activity.phase=Action.PLACE;
            // Travel and placement are mutually exclusive for THIS worker:
            // stop the previous A* path and horizontal momentum before a
            // freshly placed block can appear in its upcoming route.
            // Other Builders' independent paths are never interrupted.
            com.caszgamermd.golemsplus.worker.WorkerNavigation.stop(worker);
            var velocity=worker.getDeltaMovement();
            worker.setDeltaMovement(0,velocity.y,0);
            // Re-evaluate the real work pose immediately before spending
            // any item: a teammate or a new schematic block may have
            // invalidated the cached approach while this worker walked.
            if(!BuilderConstructionPlanner.fenceWallClearance(
                        level,worker.getBoundingBox())
                    ||!(BuilderScaffoldPlanA.safeTemporaryWorkPose(
                        level,worker,table,pos)
                    ||BuilderPlacementSpace.safeFractionalCapPose(
                            level,worker,pos)
                    ||BuilderConstructionPlanner.safeStand(
                        level,worker,pos,worker.blockPosition()))){
                BuilderPlacementSpace.invalidateApproach(worker,pos);
                table.waitForBuilderAccess(worker.getUUID(),index,
                        level.getGameTime(),"lost safe pose or exit during approach");
                table.setProblem("FRONTIER: lost safe pose or exit at "+pos);
                return;
            }
            // Never place into any Builder's body. Check before spending
            // the item so waiting for a teammate to move consumes nothing.
            if(!BuilderPlacementSpace.ready(level,worker,table,pos)){
                // Waiting at an impossible collision envelope must not
                // pin one target or one Y-level forever. Give genuine
                // sidesteps 100 ticks, then preserve the unfinished index
                // and retry another SAFE cell on the same current Y.
                if(activity.blockedSince<0)
                    activity.blockedSince=level.getGameTime();
                if(level.getGameTime()-activity.blockedSince>=100){
                    BuilderPlacementSpace.cancelYield(worker);
                    table.waitForBuilderAccess(worker.getUUID(),index,
                            level.getGameTime(),"100-tick placement body-occupancy timeout");
                    table.setProblem("Deferred block "+pos
                            +" until a safe 0.8m placement stance is reachable");
                    activity.blockedSince=-1;
                }
                return;
            }
            activity.blockedSince=-1;
            if(!BuilderPlacementSpace.horizontalClear(pos,worker)){
                // Defense-in-depth against a self-collision regression.
                // Vertical separation NEVER waives the X/Z 0.8 margin.
                table.setProblem("Builder still within 0.8 X/Z of "+pos);
                return;
            }
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
            activity.phase=Action.COMPLETE;
            BuilderSupplyInventory.recycleEmptyShulkers(table);
            // Keep any deployed scaffolding until this ten-block work order
            // completes; retrieving per placed block caused route churn.
        }catch(Exception exception){
            table.setProblem("Builder paused: "
                    +exception.getClass().getSimpleName()+": "
                    +exception.getMessage());
            System.err.println("[BUILDER WORK EXCEPTION] "+worker.position()
                    +" claimed="+table.claimedStep(worker.getUUID()));
            exception.printStackTrace(System.err);
        }
    }
    /** Reconcile the selected job's visible materials immediately after
     * changing its vegetation filter, including while construction runs.
     * Never reset the already-completed real block quantities.
     */
    /** Shift-right-click a Builder to see what it is actually waiting on.
     * Diagnostic state is derived directly from its individual task and
     * the live station. No per-tick global Planner message spam.
     */
    public static String debugStatus(ServerLevel level,BuilderGolem worker){
        BlockPos home=worker.home();
        if(!level.hasChunkAt(home))return "Home Planner chunk unavailable";
        if(!(level.getBlockEntity(home) instanceof WorkerStationBlockEntity table))
            return "Planner missing at "+home.toShortString();
        if(table.activeOrder()==null)return "No active construction job";
        if(table.phase()==WorkerPhase.PAUSED)return "Build paused";
        try{
            Cache plan=cache(table,worker);
            if(table.builderClearCursor()<plan.clearing().size()){
                int claim=table.builderClearClaim(worker.getUUID());
                String target=claim>=0&&claim<plan.clearing().size()
                        ?plan.clearing().get(claim).pos().toShortString()
                        :"unassigned";
                return "CLEARING "+table.builderClearCursor()+"/"
                        +plan.clearing().size()
                        +" | worker claim="+claim+" target="+target
                        +" | nav="+(worker.getNavigation().isDone()?"idle":"walking")
                        +" | "+table.problem();
            }
            ScheduledZones schedule=zoneSchedule(table,plan);
            var zone=schedule.tasks().steps(worker.getUUID());
            int index=table.claimedStep(worker.getUUID());
            var action=workerActions.get(worker);
            String stage=action==null?"AWAITING TASK":action.phase.name();
            if(index<0)return stage+" | zone tasks="+zone.size()
                    +" | stage="+table.builderWorkOrder(worker.getUUID()).size()
                    +" | "+BuilderConstructionPlanner.blockedSummary(
                            level,table,plan.steps(),zone)
                    +BuilderConstructionPlanner.diagnoseBlockedZone(
                            level,worker,table,plan.steps(),zone)
                    +" | scan="+BuilderConstructionPlanner.scanStatus(worker)
                    +" | claimTrace="+table.builderClaimDebug(worker.getUUID())
                    +" | "+table.problem();
            var current=plan.steps().get(index);
            BlockPos target=new BlockPos(current.x(),current.y(),current.z());
            BlockPos local=target.subtract(table.builderOrigin());
            return stage+" step "+index+" at "+target.toShortString()
                    +" | schematicXYZ="+local.toShortString()
                    +" | placeBlock="+current.encodedState()
                    +" | placeItem="+(current.requiredItem()==null
                            ?"none":current.requiredItem())
                    +" | targetNow="+level.getBlockState(target)
                    +" | below="+level.getBlockState(target.below())
                    +" | range="+(int)Math.sqrt(worker.distanceToSqr(
                            target.getX()+.5,target.getY()+.5,target.getZ()+.5))
                    +" | nav="+(worker.getNavigation().isDone()?"idle":"walking")
                    +" "+com.caszgamermd.golemsplus.worker.WorkerNavigation
                            .debugStatus(worker)
                    +" | accessProgress="+(action==null||action.lastMoved<0
                            ?"not_observed"
                            :"motionIdle="+(level.getGameTime()-action.lastMoved)
                                    +"t closerIdle="+(level.getGameTime()-action.lastCloser)
                                    +"t toolTryAgo="+(action.lastAlternativeTry<0
                                            ?"none":level.getGameTime()
                                                    -action.lastAlternativeTry+"t"))
                    +" | staged="+table.builderOrderPrepared(worker.getUUID())
                    +" | stock="+(current.requiredItem()==null?"none":current.requiredItem())
                    +" | tower="+table.scaffoldPositions(worker.getUUID()).size()
                    +" | gravity="+(!worker.isNoGravity())
                    +" | sneaking="+worker.isShiftKeyDown()
                    +" | climb="+BuilderScaffolding.debugClimb(worker)
                    +" | depot="+(table.builderUnloadingStorage()==null
                            ?"unlinked":table.builderUnloadingStorage().toShortString());
        }catch(Exception error){
            return "Builder inspection failed: "+error.getClass().getSimpleName()
                    +" "+error.getMessage();
        }
    }
    public static String refreshPlantFilter(ServerLevel level,
            WorkerStationBlockEntity table){
        try{
            var cache=jobCache(table);
            Map<String,Long> materials=new java.util.LinkedHashMap<>();
            int ignored=0;
            for(var step:cache.steps()){
                if(step.ignorePlant())ignored++;
                else if(step.requiredItem()!=null)
                    materials.merge(step.requiredItem(),1L,Long::sum);
            }
            table.reconcileBuilderMaterials(materials);
            table.previewBuilderWork(cache.steps());
            // A running planner may have already cleared the site.
            // Otherwise preserve the clearing gate until all real
            // obstructions have actually been handled.
            if((table.activeOrder()!=null
                    ||table.phase()==WorkerPhase.COMPLETE)
                    &&table.builderClearCursor()>=cache.clearing().size())
                table.reconcileBuilderWork(cache.steps());
            return table.builderIgnorePlants()
                    ?"Ignoring "+ignored+" schematic vegetation placements; "
                            +"site clearing remains enabled"
                    :"Crops and flowers restored to this job's material plan";
        }catch(Exception e){
            return "Plant filter saved; unable to recalculate material list: "
                    +e.getClass().getSimpleName();
        }
    }

    private static Cache jobCache(WorkerStationBlockEntity table)
            throws Exception {
        Cache previous=cachedWarehousePlans.get(table);
        if(previous!=null&&previous.file().equals(table.builderFile())
                &&previous.rotation()==table.builderRotation()
                &&previous.mirror()==table.builderMirror()
                &&previous.origin().equals(table.builderOrigin())
                &&previous.ignorePlants()==table.builderIgnorePlants())
            return previous;
        if(!table.hasPlacedSchematic())
            throw new IllegalArgumentException("No placed schematic");
        Path folder=FabricLoader.getInstance().getConfigDir()
                .resolve("golems_plus").resolve("schematics")
                .toAbsolutePath().normalize();
        Path file=folder.resolve(table.builderFile()).normalize();
        if(!file.startsWith(folder)||!Files.isRegularFile(file))
            throw new IllegalArgumentException("Saved schematic missing");
        var schematic=LitematicFileReader.read(file);
        var origin=table.builderOrigin();
        var site=new BuildSite(origin.getX(),origin.getY(),origin.getZ(),
                BuildSite.Rotation.values()[table.builderRotation()/90],
                BuildSite.Mirror.values()[table.builderMirror()]);
        var result=BuilderConstructionPlan.create(schematic,site,
                table.builderIgnorePlants());
        Cache next=new Cache(table.builderFile(),table.builderRotation(),
                table.builderMirror(),origin,result.steps(),
                BuilderSiteClearance.targets(schematic,site),
                table.builderIgnorePlants(),BuilderZones.footprint(result.steps()));
        cachedWarehousePlans.put(table,next);
        return next;
    }

    private static ScheduledZones zoneSchedule(WorkerStationBlockEntity table,
            Cache cache){
        List<UUID> roster=table.builderWorkerIds();
        if(roster.size()>table.crewTarget())
            roster=roster.subList(0,table.crewTarget());
        ScheduledZones found=schedules.get(table);
        if(found!=null&&found.job().equals(table.activeOrder())
                &&found.cache()==cache&&found.roster().equals(roster))
            return found;
        // Preserves completed cells and carried real items when adding or
        // retiring a Builder. Only unfinished staging reservations change.
        var tasks=BuilderZoneWorkPlan.compile(cache.steps(),cache.zones(),roster);
        table.beginBuilderZonePlan();
        ScheduledZones result=new ScheduledZones(table.activeOrder(),cache,
                List.copyOf(roster),tasks);
        schedules.put(table,result);
        return result;
    }

    private static Cache cache(WorkerStationBlockEntity table,BuilderGolem worker)
            throws Exception {
        Cache previous=cached.get(worker);
        if(previous!=null&&previous.file().equals(table.builderFile())
                &&previous.rotation()==table.builderRotation()
                &&previous.mirror()==table.builderMirror()
                &&previous.origin().equals(table.builderOrigin())
                &&previous.ignorePlants()==table.builderIgnorePlants())
            return previous;
        // Share the same immutable validated work plan among all eight
        // Builders. A different Litematica parse per worker caused pauses
        // when several workers started the job at once.
        Cache next=jobCache(table);
        cached.put(worker,next);
        return next;
    }
}
