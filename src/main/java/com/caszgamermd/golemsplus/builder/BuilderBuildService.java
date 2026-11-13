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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
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
    // A stationary approach should not pause a crew for nine seconds;
    // moving detours get more time before abandoning a claimed cell.
    // Active scaffolding/grappling remains excluded from both watchdogs.
    static final int STATIONARY_STALL_TICKS=120;
    static final int TARGET_PROGRESS_STALL_TICKS=240;
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
                         boolean ignorePlants,int rulesRevision,
                         BuilderZones.Layout zones){}
    private static final Map<BuilderGolem,Cache> cached=new WeakHashMap<>();
    /** One immutable COMPLETE job-zone plan per Planner/job/crew. Assignment
     * work is never repeated on each Builder tick or after a placement.
     */
    private record ScheduledZones(UUID job,Cache cache,List<UUID> roster,
            BuilderZoneWorkPlan tasks,Map<Integer,List<Integer>> layerSteps,
            Map<Integer,List<UUID>> layerStaff){}
    private static final Map<WorkerStationBlockEntity,ScheduledZones>
            schedules=new WeakHashMap<>();
    /** The immutable job plan only needs its virtual skips reconciled once.
     * Real placements update Planner totals directly on completion. */
    private record ReconciledJob(UUID job,Cache plan){}
    private static final Map<WorkerStationBlockEntity,ReconciledJob>
            reconciledJobs=new WeakHashMap<>();
    private enum Action { STAGE_MATERIALS, MOVE_TO_WORK, PLACE, COMPLETE }
    static final class WorkerAction {
        int index=-1;
        Action phase=Action.STAGE_MATERIALS;
        long blockedSince=-1;
        long frontierWaitSince=-1;
        int frontierWaitLayer=Integer.MIN_VALUE;
        long workTravelSince=-1;
        // Moving in circles is not progress toward the selected block.
        // A bounded target-progress window allows real detours
        // but eventually hands off a claim that never gets closer.
        double bestTargetDistance=Double.POSITIVE_INFINITY;
        long lastTargetProgressTick=-1;
        // Track actual displacement, not repeated navigation requests.
        double lastWorkX=Double.NaN,lastWorkY=Double.NaN,lastWorkZ=Double.NaN;
        long lastMovementTick=-1;
        long supplyProgressTick=-1;
        double bestSupplyDistance=Double.POSITIVE_INFINITY;
        long lastPerchEscapeTick=-1000;
        long retreatUntil=-1;
        int lastFailedStep=-1;
        // A dry rail lost to a vanilla neighbor update may be repaired for
        // the water pass, using another real rail from OnSite. Never keep
        // spending replacements on a physically impossible rail layout.
        // After returning to OnSite from a failed approach, take a real
        // walking route to a different side of the build BEFORE selecting
        // a new work stance. Never teleport or hold a claim while scouting.
        boolean alternateSideRequested;
        BlockPos alternateSideTarget;
        long alternateSideStarted=-1;
        int alternateSideAttempts;
        BlockPos failedApproachFrom;
        boolean pauseHandled;
        // Next-step prediction is read-only until the current block
        // is actually placed. Never claim two cells or move the worker
        // away from its current physical assignment.
        BuilderConstructionPlanner.Choice lookahead;
        List<Integer> lookaheadZone=List.of();
        // Snapshot just 32 nearby candidates ONCE per active work target,
        // instead of rescanning and allocating the entire 1k-cell zone
        // on every five-tick travel callback. Claims/safety are still
        // revalidated by the Planner before any next assignment.
        List<Integer> lookaheadEligible=List.of();
        int lookaheadEligibleLayer=Integer.MIN_VALUE;
        int lookaheadFor=-1;
        int lookaheadScanned;
        boolean lookaheadFinished;
        boolean fastFollowup;
        // A completed or idle-layer Builder must PHYSICALLY reach OnSite
        // before another rooftop rescue assignment may recruit it.
        // Otherwise the six-second assist watchdog can keep a "finished"
        // teammate camping inside the next Builder's placement volume.
        boolean returningToSite;
        /** Recoveries are bounded per obstruction, not for the entire job.
         * One ACTUAL block placed means the golem has made fresh progress,
         * so later roof cells deserve their own three alternate approaches.
         * A mere successful detour does not reset this limit: it could
         * otherwise send a blocked worker in circles indefinitely. */
        void notePhysicalPlacement(){
            alternateSideAttempts=0;
            alternateSideRequested=false;
            alternateSideTarget=null;
            alternateSideStarted=-1;
            failedApproachFrom=null;
            lastFailedStep=-1;
            retreatUntil=-1;
        }
        void setStep(int next){
            if(index!=next){
                index=next;phase=Action.STAGE_MATERIALS;
                blockedSince=-1;workTravelSince=-1;
                    bestTargetDistance=Double.POSITIVE_INFINITY;
                lastTargetProgressTick=-1;
                lastMovementTick=-1;lastPerchEscapeTick=-1000;
                supplyProgressTick=-1;bestSupplyDistance=Double.POSITIVE_INFINITY;
                lastWorkX=Double.NaN;
                lookahead=null;lookaheadZone=List.of();
                lookaheadEligible=List.of();
                lookaheadEligibleLayer=Integer.MIN_VALUE;
                lookaheadFor=-1;lookaheadScanned=0;
                lookaheadFinished=false;
                fastFollowup=false;
            }
        }
    }
    private record SiteFootprint(UUID job,Cache plan,int minX,int maxX,
                                 int minZ,int maxZ){}
    private static final Map<WorkerStationBlockEntity,SiteFootprint>
            siteFootprints=new WeakHashMap<>();
    private static SiteFootprint siteFootprint(WorkerStationBlockEntity table,
            Cache plan){
        SiteFootprint old=siteFootprints.get(table);
        if(old!=null&&old.job().equals(table.activeOrder())&&old.plan()==plan)
            return old;
        int minX=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE;
        int minZ=Integer.MAX_VALUE,maxZ=Integer.MIN_VALUE;
        for(var step:plan.steps()){
            minX=Math.min(minX,step.x());maxX=Math.max(maxX,step.x());
            minZ=Math.min(minZ,step.z());maxZ=Math.max(maxZ,step.z());
        }
        if(minX==Integer.MAX_VALUE){
            minX=maxX=table.builderOrigin().getX();
            minZ=maxZ=table.builderOrigin().getZ();
        }
        SiteFootprint result=new SiteFootprint(table.activeOrder(),plan,
                minX,maxX,minZ,maxZ);
        siteFootprints.put(table,result);
        return result;
    }
    /** East, south, west, north. Other-side order is shared by all crew,
     * but a different worker starts with a different perimeter approach.
     * In particular, never send all four workers back to the failed side.
     */
    static int alternateSide(int failedSide,int crewIndex,int attempt){
        int[] offsets={2,1,3};
        return Math.floorMod(failedSide+offsets[Math.floorMod(
                crewIndex+attempt,offsets.length)],4);
    }
    private static int sideOf(SiteFootprint site,BlockPos from){
        double centerX=((double)site.minX()+site.maxX())/2;
        double centerZ=((double)site.minZ()+site.maxZ())/2;
        double dx=from.getX()-centerX,dz=from.getZ()-centerZ;
        if(Math.abs(dx)>=Math.abs(dz))return dx>=0?0:2;
        return dz>=0?1:3;
    }
    /** Prefer staying on the Builder's current elevation when escaping an
     * obstructed roof; a physical route to the depot level is a fallback.
     * These are feet elevations, so add one to a depot block's Y. */
    static int[] alternateStagingHeights(int currentFeetY,int depotFeetY){
        return currentFeetY==depotFeetY
                ?new int[]{currentFeetY}:new int[]{currentFeetY,depotFeetY};
    }
    /** A reachable waypoint OUTSIDE the schematic. First try a same-height
     * perimeter escape; never assume a trapped rooftop Builder can walk down
     * to OnSite. Every candidate still requires verified standability and
     * a physical route before we commit to it. */
    private static BlockPos alternateStaging(ServerLevel level,
            BuilderGolem worker,WorkerStationBlockEntity table,Cache plan,
            WorkerAction action){
        SiteFootprint site=siteFootprint(table,plan);
        BlockPos failed=action.failedApproachFrom==null
                ?worker.blockPosition():action.failedApproachFrom;
        int badSide=sideOf(site,failed);
        int crew=Math.max(0,table.builderWorkerIds().indexOf(worker.getUUID()));
        int spanX=Math.max(1,site.maxX()-site.minX());
        int spanZ=Math.max(1,site.maxZ()-site.minZ());
        int middleX=site.minX()+spanX/2,middleZ=site.minZ()+spanZ/2;
        BlockPos depot=table.builderUnloadingStorage();
        if(depot==null)depot=table.getBlockPos();
        int[] heights=alternateStagingHeights(worker.blockPosition().getY(),
                depot.getY()+1);
        int[] yOffsets={0,1,-1};
        for(int baseY:heights){
            for(int sideTry=0;sideTry<3;sideTry++){
                int side=alternateSide(badSide,crew,
                        action.alternateSideAttempts+sideTry);
                int span=(side%2==0?spanZ:spanX);
                int quarter=Math.max(2,span/4);
                int[] lateral={0,quarter,-quarter,2*quarter,-2*quarter};
                for(int offset:lateral){
                    int x=switch(side){
                        case 0 -> site.maxX()+3;
                        case 2 -> site.minX()-3;
                        default -> Math.max(site.minX()-2,
                                Math.min(site.maxX()+2,middleX+offset));
                    };
                    int z=switch(side){
                        case 1 -> site.maxZ()+3;
                        case 3 -> site.minZ()-3;
                        default -> Math.max(site.minZ()-2,
                                Math.min(site.maxZ()+2,middleZ+offset));
                    };
                    for(int dy:yOffsets){
                        int y=baseY+dy;
                        BlockPos waypoint=new BlockPos(x,y,z);
                        if(!level.hasChunkAt(waypoint)
                                ||worker.distanceToSqr(x+.5,y,z+.5)<9
                                ||!com.caszgamermd.golemsplus.worker.WorkerNavigation
                                        .canStandOn(level,worker,waypoint))continue;
                        if(com.caszgamermd.golemsplus.worker.WorkerNavigation
                                .routeToExact(level,worker,waypoint,1.0))
                            return waypoint;
                    }
                }
            }
        }
        return null;
    }
    /** Shared per-station handoff board: a failed cell is offered to a
     * different crew member before its previous owner can try again. */
    private record Handoff(UUID failedWorker,long until,String reason){}
    private static final Map<WorkerStationBlockEntity,Map<Integer,Handoff>>
            handoffBoard=new WeakHashMap<>();
    private static void publishHandoff(WorkerStationBlockEntity table,
            UUID worker,int index,long now,String reason){
        handoffBoard.computeIfAbsent(table,k->new java.util.HashMap<>())
                .put(index,new Handoff(worker,now+1200,reason));
    }
    /** A stationary worker on an unreachable supply route must release
     * its placement so an available teammate can collect the material. */
    private static void watchSupplyTrip(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,WorkerAction activity,int index){
        BlockPos depot=table.builderUnloadingStorage();
        if(depot==null||table.builderWorkerIds().size()<2)return;
        double distance=worker.distanceToSqr(depot.getX()+.5,
                depot.getY()+.5,depot.getZ()+.5);
        long now=level.getGameTime();
        if(activity.supplyProgressTick<0||distance<activity.bestSupplyDistance-1){
            activity.supplyProgressTick=now;
            activity.bestSupplyDistance=distance;
        }
        if(BuilderReturnService.withinStorageReach(worker,depot)||now-activity.supplyProgressTick<=TARGET_PROGRESS_STALL_TICKS
                ||BuilderScaffolding.isControllingMovement(worker)
                ||BuilderGrappleAccess.active(worker))return;
        String reason="no physical progress toward OnSite Storage";
        table.waitForBuilderAccess(worker.getUUID(),index,now,reason);
        publishHandoff(table,worker.getUUID(),index,now,reason);
        activity.returningToSite=true;
        activity.supplyProgressTick=-1;
        table.setProblem("Builder handed off placement "+index+" after blocked supply return");
    }
    private static boolean reservedForTeammate(WorkerStationBlockEntity table,
            UUID worker,int index,long now){
        var board=handoffBoard.get(table);
        if(board==null)return false;
        Handoff record=board.get(index);
        if(record==null)return false;
        if(now>=record.until()){
            board.remove(index);return false;
        }
        return record.failedWorker().equals(worker)
                &&table.builderWorkerIds().size()>1;
    }
    private static final Map<BuilderGolem,WorkerAction> workerActions=
            new WeakHashMap<>();
    /** Normally every X/Z zone is exclusive. A zone with no work on this
     * shared Y may help its stuck neighbor after construction has made
     * no physical progress for six seconds. This is read-only monitoring:
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
        return level.getGameTime()-progress.lastChange>=120;
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
            if(table.builderStepInActivePhase(steps,idx)&&table.builderStepReadyForClaim(idx)
                    &&!table.builderStepClaimedByOther(worker,idx))
                extra.add(idx);
        }
        return extra;
    }
    /** Spend a small, bounded planning slice WHILE the worker walks.
     * No claim is made and the current route is not stopped. A blocked
     * or too-distant next target simply falls back to normal selection.
     */
    private static void prefetchNext(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,
            List<BuilderConstructionPlan.Step> steps,List<Integer> zone,
            int current,BlockPos target,WorkerAction activity,long now){
        if(activity.lookaheadFinished||activity.workTravelSince<0
                ||now-activity.workTravelSince<5
                ||worker.getNavigation().isDone()
                    &&!com.caszgamermd.golemsplus.worker.WorkerNavigation
                            .retryScheduled(worker)
                ||BuilderScaffoldPlanA.hasPlan(worker)
                ||worker.distanceToSqr(target.getX()+.5,
                        target.getY()+.5,target.getZ()+.5)>100)return;
        int layer=table.builderActiveLayer(steps);
        if(activity.lookaheadEligibleLayer!=layer){
            activity.lookaheadEligible=nearbyLookaheadCandidates(
                    table,worker,steps,zone,current,target,now);
            activity.lookaheadEligibleLayer=layer;
            activity.lookaheadScanned=0;
        }
        List<Integer> eligible=activity.lookaheadEligible;
        int remaining=eligible.size()-activity.lookaheadScanned;
        if(remaining<=0){
            activity.lookaheadFinished=true;return;
        }
        int budget=Math.min(2,remaining);
        var next=BuilderConstructionPlanner.chooseLookahead(
                level,worker,table,steps,eligible,
                activity.lookaheadScanned,budget,target);
        activity.lookaheadScanned+=budget;
        if(next!=null){
            activity.lookahead=next;
            activity.lookaheadFor=current;
            activity.lookaheadZone=List.copyOf(zone);
            activity.lookaheadFinished=true;
        }else if(activity.lookaheadScanned>=eligible.size())
            activity.lookaheadFinished=true;
    }
    /** Bounded nearest-first speculative work list. One pass over the
     * assigned zone at task start is cheaper than repeating a full list
     * allocation/sort and access checks on every walking callback.
     * It is only a hint: current claims, materials, world geometry and Y
     * restrictions remain server-validated before the next placement.
     */
    private static List<Integer> nearbyLookaheadCandidates(
            WorkerStationBlockEntity table,BuilderGolem worker,
            List<BuilderConstructionPlan.Step> steps,List<Integer> zone,
            int current,BlockPos target,long now){
        java.util.PriorityQueue<Integer> nearest=new java.util.PriorityQueue<>(
                (a,b)->{
                    double da=stepDistanceSquared(steps.get(a),target);
                    double db=stepDistanceSquared(steps.get(b),target);
                    int order=Double.compare(db,da);
                    return order!=0?order:Integer.compare(b,a);
                });
        int layer=table.builderActiveLayer(steps);
        for(int idx:zone){
            if(idx==current||idx<0||idx>=steps.size()
                    ||!table.builderStepInActivePhase(steps,idx)
                    ||!table.builderStepReadyForClaim(idx)
                    ||table.builderStepClaimedByOther(worker.getUUID(),idx)
                    ||reservedForTeammate(table,worker.getUUID(),idx,now))
                continue;
            if(nearest.size()<32)nearest.add(idx);
            else if(stepDistanceSquared(steps.get(idx),target)
                    <stepDistanceSquared(steps.get(nearest.peek()),target)){
                nearest.poll();
                nearest.add(idx);
            }
        }
        var result=new java.util.ArrayList<>(nearest);
        result.sort(java.util.Comparator
                .comparingDouble((Integer i)->stepDistanceSquared(steps.get(i),target))
                .thenComparingInt(Integer::intValue));
        return List.copyOf(result);
    }
    private static double stepDistanceSquared(
            BuilderConstructionPlan.Step step,BlockPos target){
        double dx=step.x()-target.getX(),dy=step.y()-target.getY(),
                dz=step.z()-target.getZ();
        return dx*dx+dy*dy+dz*dz;
    }
    /** Called by the real entity tick exactly once following a successful
     * same-layer prevalidated handoff. Not a second planning pass.
     */
    static boolean takeFastFollowup(BuilderGolem worker){
        WorkerAction action=workerActions.get(worker);
        if(action==null||!action.fastFollowup)return false;
        action.fastFollowup=false;
        return true;
    }
    /** Cheap handoff when a Builder already occupies a verified pose for
     * another block. This covers short roof runs with no travel time, where
     * motion-only lookahead never fires. No A* probe or scaffold plan is
     * started; the next ordinary work tick still checks world collision,
     * placement support, material stock, and any crew moving into the cell.
     */
    private static BuilderConstructionPlanner.Choice nearbyDirectFollowup(
            ServerLevel level,BuilderGolem worker,WorkerStationBlockEntity table,
            List<BuilderConstructionPlan.Step> steps,List<Integer> zone,
            int completed){
        if(!worker.onGround()||worker.isNoGravity()
                ||BuilderScaffoldPlanA.hasPlan(worker)
                ||BuilderGrappleAccess.active(worker)
                ||completed<0||completed>=steps.size())return null;
        var previous=steps.get(completed);
        int layer=table.builderActiveLayer(steps);
        BlockPos pose=worker.blockPosition();
        int best=-1;
        double bestDistance=Double.POSITIVE_INFINITY;
        for(int idx:zone){
            if(idx==completed||idx<0||idx>=steps.size()
                    ||!table.builderStepInActivePhase(steps,idx)
                    ||!table.builderStepReadyForClaim(idx)
                    ||table.builderStepClaimedByOther(worker.getUUID(),idx)
                    ||reservedForTeammate(table,worker.getUUID(),idx,
                            level.getGameTime()))continue;
            var step=steps.get(idx);
            double dx=step.x()-previous.x(),dz=step.z()-previous.z();
            double locality=dx*dx+dz*dz;
            if(locality>9||locality>=bestDistance)continue;
            BlockPos target=new BlockPos(step.x(),step.y(),step.z());
            if(!level.hasChunkAt(target)
                    ||!BuilderPlacementSpace.placementHeight(target,worker)
                    ||!BuilderPlacementSpace.horizontalClear(target,worker)
                    ||worker.distanceToSqr(target.getX()+.5,
                            target.getY()+.5,target.getZ()+.5)>BuilderConstructionPlanner.REACH_SQUARED)continue;
            var resolved=BuilderBlockResolver.resolve(step.encodedState(),
                    BuildSite.Rotation.values()[table.builderRotation()/90],
                    BuildSite.Mirror.values()[table.builderMirror()]);
            if(resolved==null
                    ||!resolved.state().canSurvive(level,target)
                    ||!level.getBlockState(target).isAir()
                    ||step.requiredItem()!=null
                        &&worker.carriedCount(resolved.item())==0
                    ||!(BuilderPlacementSpace.safeFractionalCapPose(
                                level,worker,target)
                        ||BuilderConstructionPlanner.safeStand(
                                level,worker,target,pose)))continue;
            best=idx;
            bestDistance=locality;
        }
        return best<0?null:
                new BuilderConstructionPlanner.Choice(best,pose,false,
                        level.canSeeSky(pose.above()),1);
    }
    /** After a REAL placement, consume the speculative choice only if
     * the same Y gate, claim ownership, support and safe pose STILL hold.
     * Start the next physical route immediately instead of spending five
     * ticks motionless before the next Builder work callback.
     */
    private static void handoffNext(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,
            List<BuilderConstructionPlan.Step> steps,List<Integer> zone,
            WorkerAction activity,int completed){
        var choice=activity.lookaheadFor==completed?activity.lookahead:null;
        // A nearby connected run may be placeable without any walking;
        // in that case movement-based lookahead did not run at all.
        if(choice==null)choice=nearbyDirectFollowup(
                level,worker,table,steps,zone,completed);
        if(choice==null)return;
        int next=choice.index();
        if(next<0||next>=steps.size()
                ||!table.builderStepInActivePhase(steps,next)
                ||!table.builderStepReadyForClaim(next)
                ||table.builderStepClaimedByOther(worker.getUUID(),next)
                ||reservedForTeammate(table,worker.getUUID(),next,
                        level.getGameTime()))return;
        var step=steps.get(next);
        BlockPos target=new BlockPos(step.x(),step.y(),step.z());
        if(!level.hasChunkAt(target))return;
        var state=BuilderBlockResolver.resolve(step.encodedState(),
                BuildSite.Rotation.values()[table.builderRotation()/90],
                BuildSite.Mirror.values()[table.builderMirror()]);
        if(state==null)return;
        if(!BuilderPlacementState.matches(level.getBlockState(target),
                    state.state())&&!state.state().canSurvive(level,target))return;
        BlockPos stance=choice.stance();
        if(stance!=null&&!BuilderConstructionPlanner.safeStand(
                level,worker,target,stance))return;
        if(table.claimPreferredBuilderZoneStep(worker.getUUID(),
                zone,steps,next)!=next)return;
        activity.setStep(next);
        // Move on the very next game tick, not at the next four-per-second
        // Builder work cadence. This removes the visible idle beat between
        // placements WITHOUT repeating expensive frontier selection.
        activity.fastFollowup=true;
        if(stance!=null&&step.requiredItem()!=null
                &&worker.carriedCount(state.item())>0
                &&!BuilderConstructionPlanner.safeStand(
                        level,worker,target,worker.blockPosition()))
            com.caszgamermd.golemsplus.worker.WorkerNavigation.routeToExact(
                    level,worker,stance,1.0);
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
    /** A return is complete only when the REAL worker stands beside
     * OnSite on non-scaffold ground. A nearby roof at the same X/Z must
     * never count as arriving at the depot.
     */
    private static boolean atOnSite(ServerLevel level,BuilderGolem worker,
            BlockPos depot){
        return worker.onGround()&&!worker.isNoGravity()
                &&!BuilderScaffoldPlanA.hasPlan(worker)
                &&!BuilderScaffolding.isControllingMovement(worker)
                &&!BuilderGrappleAccess.active(worker)
                &&!level.getBlockState(worker.blockPosition().below())
                        .is(net.minecraft.world.level.block.Blocks.SCAFFOLDING)
                &&BuilderReturnService.withinStorageReach(worker,depot);
    }
    /** Return while never issuing an ordinary A* command over an active
     * owned scaffold bridge. Descend that real tower, then optionally
     * rappel on a safe anchor and walk to OnSite. No teleporting or
     * unphysical drop, and no new work claims until arrival.
     *
     * @return true only after the worker physically reaches OnSite.
     */
    static boolean parkAtOnSite(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table){
        BlockPos depot=table.builderUnloadingStorage();
        if(depot==null)depot=table.getBlockPos();
        if(atOnSite(level,worker,depot)){
            com.caszgamermd.golemsplus.worker.WorkerNavigation.stop(worker);
            return true;
        }
        // Plan-A bridges and tracked shafts are mandatory return routes:
        // finish their bridge crossing and controlled dismount first.
        if(BuilderScaffolding.descendForTravel(level,worker,table))
            return false;
        // A high roof with no available scaffold route should seek an
        // actual descent, not endlessly routeTo() a depot far below.
        if(worker.getY()>depot.getY()+1.5
                &&BuilderGrappleAccess.rappelHome(level,worker,table,depot))
            return false;
        if(BuilderGrappleAccess.rappelToward(level,worker,table,depot))
            return false;
        if(BuilderGrappleAccess.escapeRailPocket(level,worker,depot))
            return false;
        BuilderReturnService.approach(level,worker,depot,.8);
        return false;
    }
    /** A return request is a travel state, not a cooldown timer.
     * Reassignment is forbidden until the worker physically arrives.
     */
    static boolean shouldContinueEvacuation(boolean returning,boolean arrived){
        return returning&&!arrived;
    }
    /** Workers with nothing left on current Y must vacate the active
     * structure before joining another Builder's roof-rescue work.
     */
    static boolean shouldEvacuateIdleLayer(boolean ownLayerClear,
            int claimedStep,boolean atDepot){
        return ownLayerClear&&claimedStep<0&&!atDepot;
    }
    /** An active cross-zone rescue claim must finish before parking; a
     * worker with no live claim and no remaining OWN work returns OnSite.
     */
    static boolean shouldParkFinishedZone(boolean ownZoneDone,int claimedStep,
            boolean roofNeedsRescue){
        // A COMPLETED zone is terminal for this Builder's build role.
        // The old global six-second roof watchdog re-recruited finished
        // workers to repeatedly enter inaccessible water/rail pockets.
        // Active workers with later own-zone steps may still volunteer
        // on stalled neighboring layers; completed workers stay OnSite.
        return ownZoneDone&&claimedStep<0;
    }
    /** Retain the ordinary no-rescue policy for existing callers/tests. */
    static boolean shouldParkFinishedZone(boolean ownZoneDone,int claimedStep){
        return shouldParkFinishedZone(ownZoneDone,claimedStep,false);
    }
    /** Re-check a high roof from its existing safe footing briefly, but
     * never let an unavailable frontier strand a Builder forever. */
    static boolean shouldHoldRoofFrontier(long waitingTicks,boolean highRoof){
        return waitingTicks<= (highRoof?160:80);
    }
    /** A worker that has cleared its portion of the current shared Y
     * returns OnSite while a teammate finishes, unless assigned a live
     * cross-zone rescue. Future-layer tasks remain queued, not canceled. */
    static boolean shouldParkWaitingForLayer(boolean ownLayerClear,
            boolean assisting,int claimedStep){
        return ownLayerClear&&!assisting&&claimedStep<0;
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
                        target.getY()+.5,target.getZ()+.5)<=BuilderConstructionPlanner.REACH_SQUARED
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
        if(BuilderSupplyInventory.creativeSupply(table))
            return "Creative Crate linked: unlimited Builder materials and tools. No warehouse scan required.";
        if(table.warehouse()==null)
            return "Manual supply mode: link a Rack Computer with a Warehouse Scanner to enable warehouse deliveries";
        try{
            var cache=jobCache(table);
            var plan=cache.steps();
            // A job may have been previewed BEFORE Ignore Crops was
            // toggled. Reconcile its persisted material ledger BEFORE
            // inspecting or creating a warehouse ticket, preserving the
            // count of actual placements already completed.
            table.reconcileBuilderMaterialsFromPlan(plan);
            if(table.supplyRequest()!=null)
                return "Warehouse material request already active"
                        +(table.builderCancelShipment()
                            ?"; cancelling the previous unfiltered manifest":"");
            // queueMaterials owns the single OnSite + rack snapshot.
            // Duplicating both scans here blocked the server at Start.
            if(!BuilderWarehouseCoordinator.queueMaterials(level,table,plan)){
                var window=BuilderMaterialDemand.nextWindow(
                        plan,table.builderCursor(),
                        table::builderStepCompletedForMaterials,
                        BuilderMaterialRules.WINDOW_LAYERS);
                if(BuilderMaterialDemand.outstandingRemaining(window,
                        BuilderSupplyInventory.available(table),Map.of()).isEmpty())
                    return "Next eight Builder layers are staged or require no new stock";
                return table.problem().isBlank()?"Warehouse not ready":table.problem();
            }
            if(table.supplyPtp().equals(BlockPos.ZERO))
                return "Warehouse request saved; "+table.problem();
            return "Warehouse preparing "+table.supplyManifest().size()
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
        // Also recover completed jobs saved by older versions with a live
        // warehouse ticket. A stale request otherwise blocks retirement
        // even after every schematic block has been placed.
        if(table.isBuilder()&&table.phase()==WorkerPhase.COMPLETE
                &&table.activeOrder()==null&&table.supplyRequest()!=null
                &&!table.builderCancelShipment())
            table.requestBuilderShipmentCancellation();
        // A cancellation takes precedence over the remaining warehouse
        // fulfillment workload. It is retried from the Planner even if
        // every Builder is unloaded, paused or awaiting a stuck shipment.
        // The warehouse stops picking, then safely drains REAL picker cargo.
        if(table.isBuilder()&&table.builderCancelShipment()
                &&level.getGameTime()%20==0){
            var cancellation=BuilderWarehouseCoordinator.cancelShipmentDetailed(level,table);
            if(!cancellation.completed())
                table.setProblem("Warehouse cancellation pending: "
                        +BuilderWarehouseCoordinator.cancellationExplanation(
                                cancellation.reason()));
        }
        if(table.isBuilder()&&level.getGameTime()%40==0)
            table.updateBuilderStagedStock(BuilderSupplyInventory.available(table));
        if(table.isBuilder()&&!BuilderSupplyInventory.creativeSupply(table)
                &&level.getGameTime()%80==0){
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
                &&table.phase()!=WorkerPhase.PAUSED
                &&table.warehouse()!=null
                &&!BuilderSupplyInventory.creativeSupply(table)
                &&table.supplyRequest()==null
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
        // PAUSED is a recall order, not a freeze wherever the Builder
        // happens to be. Let an in-flight rope/bridge land safely, then
        // descend, walk to OnSite and empty every real inventory slot.
        // This must precede dropped-item pickup, courier and work logic.
        if(table.phase()==WorkerPhase.PAUSED){
            WorkerAction paused=workerActions.computeIfAbsent(worker,
                    unused->new WorkerAction());
            if(!paused.pauseHandled){
                paused.setStep(-1);
                paused.retreatUntil=-1;
                paused.returningToSite=false;
                paused.alternateSideRequested=false;
                paused.alternateSideTarget=null;
                paused.alternateSideAttempts=0;
                BuilderPlacementSpace.cancelYield(worker);
                BuilderDroppedBlockRecovery.suspend(worker,table);
                paused.pauseHandled=true;
            }
            if(BuilderGrappleAccess.active(worker)
                    ||BuilderScaffolding.isControllingMovement(worker))return;
            if(!parkAtOnSite(level,worker,table))return;
            BuilderScaffolding.retrieve(level,worker,table,false);
            if(worker.hasSupplyBox()){
                if(!BuilderSupplyInventory.depositBox(table,worker.supplyBox())){
                    table.setProblem("Paused: make room in OnSite for returned shulker cargo");
                    return;
                }
                worker.clearSupplyBox();
            }
            if(!worker.isEmpty()&&!BuilderSupplyInventory.returnPouch(table,worker)){
                table.setProblem("Paused: make room in OnSite to empty Builder pockets");
                return;
            }
            if(!BuilderDroppedBlockRecovery.unloadRecoveredAtSite(worker,table)){
                table.setProblem("Paused: make room in OnSite for recovered blocks");
                return;
            }
            com.caszgamermd.golemsplus.worker.WorkerNavigation.stop(worker);
            worker.setNoGravity(false);
            return;
        }
        WorkerAction previousAction=workerActions.get(worker);
        if(previousAction!=null)previousAction.pauseHandled=false;
        // A teammate may need the block space currently occupied by this
        // Builder. Honor its short physical sidestep before resuming the
        // current 10-coordinate assignment or warehouse courier route.
        if(BuilderGrappleAccess.active(worker))return;
        if(BuilderScaffolding.isControllingMovement(worker))return;
        if(BuilderPlacementSpace.yieldTick(level,worker))return;
        // A recovered stack owns a real, persisted delivery trip. A
        // dropped-item collection route likewise must not be interrupted
        // by an idle-layer park request on the next five-tick callback.
        if(BuilderDroppedBlockRecovery.busy(worker)
                &&BuilderDroppedBlockRecovery.tick(level,worker,table,null))return;
        // A returning Builder does NOT need an online owner, task scan,
        // warehouse manifest or rescue work to finish getting off the roof.
        // Run this BEFORE all such code and even before a new task is
        // considered. The normal completed-job branch below still owns
        // final inventory unload and recycling.
        if(table.activeOrder()!=null&&table.phase()!=WorkerPhase.COMPLETE){
            WorkerAction returning=workerActions.get(worker);
            if(returning!=null&&returning.returningToSite){
                boolean arrived=parkAtOnSite(level,worker,table);
                if(shouldContinueEvacuation(true,arrived))return;
                returning.returningToSite=false;
            }
        }
        if(table.activeOrder()==null||table.phase()==WorkerPhase.COMPLETE){
            // On job completion a Builder may still be on its LAST
            // scaffold tower. It must return to ground and dismount
            // BEFORE reclaiming blocks, delivering tools or going home.
            BlockPos returnGoal=table.builderUnloadingStorage();
            if(returnGoal==null)returnGoal=table.getBlockPos();
            if(!BuilderScaffoldPlanA.hasPlan(worker)
                    &&BuilderGrappleAccess.rappelHome(
                            level,worker,table,returnGoal))return;
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
                if(BuilderReturnService.approach(level,worker,depot,.83))
                    return;
                if(!BuilderSupplyInventory.returnTools(worker,table)){
                    table.setProblem("Make room in OnSite Storage for returned mining tools");
                    return;
                }
            }
            if(!worker.hasSupplyBox()&&!worker.isEmpty()){
                BlockPos returnTo=BuilderSupplyInventory.pouchReturnPos(table);
                if(level.hasChunkAt(returnTo)){
                    if(!BuilderReturnService.approach(level,worker,returnTo,.8))
                        BuilderSupplyInventory.returnPouch(table,worker);
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
        // A failed worker relinquishes the cell and physically returns to
        // OnSite before trying another assignment. Other crew members can
        // claim the released cell without waiting for this worker.
        WorkerAction retreat=workerActions.get(worker);
        // A pending alternate-side rescue must be allowed to search from the
        // current roof before the normal OnSite retreat forces a descent.
        if(retreat!=null&&retreat.retreatUntil>0
                &&!retreat.alternateSideRequested){
            BlockPos depot=table.builderUnloadingStorage();
            if(depot==null)depot=home;
            if(worker.distanceToSqr(depot.getX()+.5,depot.getY()+.5,
                    depot.getZ()+.5)>12){
                if(!BuilderScaffoldPlanA.hasPlan(worker)
                        &&BuilderGrappleAccess.rappelHome(level,worker,table,depot))return;
                if(BuilderScaffolding.descendForTravel(level,worker,table)
                        ||BuilderGrappleAccess.rappelForTravel(level,worker,table))return;
                com.caszgamermd.golemsplus.worker.WorkerNavigation
                        .routeTo(level,worker,depot,.8);
                return;
            }
            if(level.getGameTime()<retreat.retreatUntil)return;
            retreat.retreatUntil=-1;
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
            WorkerAction alternate=workerActions.get(worker);
            if(alternate!=null&&alternate.alternateSideRequested){
                long now=level.getGameTime();
                BlockPos waypoint=alternate.alternateSideTarget;
                if(waypoint==null){
                    waypoint=alternateStaging(level,worker,table,plan,alternate);
                    alternate.alternateSideTarget=waypoint;
                    alternate.alternateSideStarted=now;
                    alternate.alternateSideAttempts++;
                    if(waypoint!=null)
                        System.out.println("[BUILDER ALT SIDE] "
                                +worker.getName().getString()+" going to "
                                +waypoint+" from "+alternate.failedApproachFrom
                                +" to replan remaining work");
                    else alternate.alternateSideRequested=false;
                }
                if(waypoint!=null){
                    if(worker.onGround()&&worker.distanceToSqr(
                            waypoint.getX()+.5,waypoint.getY(),
                            waypoint.getZ()+.5)<4){
                        alternate.alternateSideTarget=null;
                        alternate.alternateSideRequested=false;
                        // Successful perimeter recovery replaces the forced
                        // OnSite detour; rebuild work plans from this side.
                        alternate.retreatUntil=-1;
                        alternate.workTravelSince=-1;
                        alternate.lastMovementTick=-1;
                        alternate.lastTargetProgressTick=-1;
                        alternate.bestTargetDistance=Double.POSITIVE_INFINITY;
                        alternate.setStep(-1);
                        com.caszgamermd.golemsplus.worker.WorkerNavigation.stop(worker);
                        if(alternate.lastFailedStep>=0
                                &&alternate.lastFailedStep<plan.steps().size()){
                            var failed=plan.steps().get(alternate.lastFailedStep);
                            BuilderPlacementSpace.invalidateApproach(worker,
                                    new BlockPos(failed.x(),failed.y(),failed.z()));
                        }
                        System.out.println("[BUILDER ALT SIDE] "
                                +worker.getName().getString()
                                +" arrived; rebuilding approach search");
                        return;
                    }
                    if(now-alternate.alternateSideStarted<240
                            &&com.caszgamermd.golemsplus.worker.WorkerNavigation
                                    .routeToExact(level,worker,waypoint,1.0))return;
                    alternate.alternateSideTarget=null;
                    alternate.alternateSideRequested=false;
                    com.caszgamermd.golemsplus.worker.WorkerNavigation.stop(worker);
                }
            }
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
            // The full schematic sweep is expensive on large builds. Run
            // it once when this immutable job/rule plan becomes active;
            // completeBuilderStep maintains actual progress thereafter.
            var lastReconciled=reconciledJobs.get(table);
            if(lastReconciled==null||lastReconciled.plan()!=plan
                    ||!java.util.Objects.equals(lastReconciled.job(),table.activeOrder())){
                table.reconcileBuilderWork(plan.steps());
                reconciledJobs.put(table,new ReconciledJob(table.activeOrder(),plan));
            }
            if(table.phase()==WorkerPhase.COMPLETE)return;
            // All positions for each X/Z zone were pre-assigned as one
            // immutable ordered execution plan. The Planner does NOT
            // arbitrate between Builders on each move or block placement.
            var assigned=zoneSchedule(table,plan);
            var zone=assigned.tasks().steps(worker.getUUID());
            // Completion is per Builder's ORIGINAL X/Z zone, across all
            // heights. A worker that was already sent home must FINISH
            // the physical trip before the rescue watchdog can recruit
            // it again, even if a different layer became active en route.
            int currentClaim=table.claimedStep(worker.getUUID());
            WorkerAction returnAction=workerActions.computeIfAbsent(
                    worker,k->new WorkerAction());
            int layer=table.builderActiveLayer(plan.steps());
            List<UUID> layerStaff=assigned.layerStaff().getOrDefault(
                    layer,assigned.roster());
            // Small Y slices need only one Builder per ten real blocks.
            // The others stay at OnSite; any in-flight claim is finished
            // safely rather than discarding its paid materials mid-step.
            if(!layerStaff.contains(worker.getUUID())&&currentClaim<0
                    &&!worker.hasSupplyBox()){
                boolean parked=parkAtOnSite(level,worker,table);
                returnAction.returningToSite=!parked;
                if(parked)BuilderDroppedBlockRecovery.tick(
                        level,worker,table,plan.zones());
                return;
            }
            if(layerStaff.size()<assigned.roster().size()
                    &&layerStaff.contains(worker.getUUID())){
                // A compact layer cannot use the original multi-zone
                // partition: the chosen small crew must see ALL its cells,
                // including those originally assigned to parked workers.
                zone=assigned.layerSteps().getOrDefault(layer,zone);
            }
            boolean stalledRoof=roofNeedsHelp(level,table);
            if(shouldParkFinishedZone(table.builderZoneDone(zone),currentClaim,
                    stalledRoof)){
                activityForFinished(worker);
                boolean parked=parkAtOnSite(level,worker,table);
                returnAction.returningToSite=!parked;
                if(parked)BuilderDroppedBlockRecovery.tick(
                        level,worker,table,plan.zones());
                return;
            }
            // Unreachable frontier cells may now be served by owned,
            // physically climbable scaffold towers or two-cell bridges.
            // Construction intelligence runs BEFORE step claiming:
            // assign only a frontier cell with an actually reachable,
            // collision-safe work stance and a route out after placing.
            // A currently claimed task is kept during physical travel;
            // failed tasks are explicitly deferred and then reselected.
            int index=table.claimedStep(worker.getUUID());
            boolean ownLayerClear=true;
            for(int idx:zone)
                if(idx>=0&&idx<plan.steps().size()
                        &&table.builderStepInActivePhase(plan.steps(),idx)
                        &&!table.builderZoneDone(List.of(idx))){
                    ownLayerClear=false;break;
                }
            // A worker that has cleared its own current layer must
            // vacate the roof BEFORE volunteering as a cross-zone helper.
            // This keeps finished Builders out of the placement envelopes
            // seen in the narrow dome cap test. It can rejoin once it
            // has actually arrived at OnSite; no claimed rescue is lost.
            if(ownLayerClear&&index<0){
                BlockPos depot=table.builderUnloadingStorage();
                if(depot==null)depot=table.getBlockPos();
                if(shouldEvacuateIdleLayer(true,index,
                        atOnSite(level,worker,depot))){
                    returnAction.returningToSite=true;
                    parkAtOnSite(level,worker,table);
                    return;
                }
            }
            // A published failure is an immediate call for help, even
            // before the normal ten-second roof-rescue watchdog expires.
            boolean teammateRequested=false;
            var requests=handoffBoard.get(table);
            if(requests!=null)
                for(var request:requests.entrySet()){
                    int requested=request.getKey();
                    if(request.getValue().until()>level.getGameTime()
                            &&!request.getValue().failedWorker().equals(worker.getUUID())
                            &&requested>=0&&requested<plan.steps().size()
                            &&table.builderStepInActivePhase(plan.steps(),requested)
                            &&table.builderStepReadyForClaim(requested)){
                        teammateRequested=true;break;
                    }
                }
            boolean emergencyRoofAssist=ownLayerClear
                    &&(teammateRequested||index>=0&&!zone.contains(index)
                        ||stalledRoof);
            // Monitoring already ran before the early finished-zone
            // return, so every crew member sees the SAME rescue window.
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
            // Keep the previously failing worker from immediately
            // reclaiming the same cell while another Builder is available.
            long selectionTick=level.getGameTime();
            if(index>=0&&reservedForTeammate(table,worker.getUUID(),index,selectionTick))
                index=-1;
            var handoffCandidates=new java.util.ArrayList<Integer>();
            for(int candidate:zone)
                if(!reservedForTeammate(table,worker.getUUID(),candidate,selectionTick))
                    handoffCandidates.add(candidate);
            zone=handoffCandidates;
            // No unfinished cell in this worker's portion of the active Y:
            // go OnSite immediately, without burning a full search turn.
            // A real cross-zone rescue is the only reason to stay active.
            if(shouldParkWaitingForLayer(
                    ownLayerClear,emergencyRoofAssist,index)){
                boolean parked=parkAtOnSite(level,worker,table);
                returnAction.returningToSite=!parked;
                if(parked)BuilderDroppedBlockRecovery.tick(
                        level,worker,table,plan.zones());
                return;
            }
            if(index<0||index>=plan.steps().size()
                    ||!zone.contains(index)
                    ||plan.steps().get(index).y()!=layer
                    ||!table.builderStepReadyForClaim(index)){
                // Expensive frontier probes are spread across the crew's
                // five-tick work cadence. Their walking/path execution
                // continues every tick, and one blocked search cannot cause
                // all Builders to launch A* probes in the same tick.
                if(!BuilderCrewSchedule.frontierTurn(level.getGameTime(),
                        layerStaff.indexOf(worker.getUUID()),
                        layerStaff.size()))return;
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
                // All workers whose OWN current layer is finished wait
                // at OnSite. The shared Y gate still holds, but idle crew
                // no longer crowds the structure or freezes beside a stuck
                // teammate. Roof assistance can reassign them from depot.
                if(ownLayerClear){
                    boolean parked=parkAtOnSite(level,worker,table);
                    returnAction.returningToSite=!parked;
                    if(parked)BuilderDroppedBlockRecovery.tick(
                            level,worker,table,plan.zones());
                    return;
                }
                // This worker still has unfinished current-layer work, but
                // no safe pose was found. Keep a genuine elevated foothold
                // briefly while its own retry search explores new routes.
                if(canWaitNearFrontier(level,worker,table,
                        plan.steps(),zone,layer)){
                    var waiting=workerActions.computeIfAbsent(worker,k->new WorkerAction());
                    if(waiting.frontierWaitSince<0
                            ||waiting.frontierWaitLayer!=layer){
                        waiting.frontierWaitSince=level.getGameTime();
                        waiting.frontierWaitLayer=layer;
                    }
                    // A Builder with unfinished own work can retry from a
                    // safe high-roof ledge. Those whose layer is finished
                    // have already been sent back to OnSite above.
                    boolean expensiveAscent=worker.getY()
                            -table.getBlockPos().getY()>=12;
                    long waited=level.getGameTime()-waiting.frontierWaitSince;
                    if(shouldHoldRoofFrontier(waited,expensiveAscent)){
                        com.caszgamermd.golemsplus.worker.WorkerNavigation.stop(worker);
                        return;
                    }
                    // An elevated cap is not an eternal parking space.
                    // After the bounded retry window, physically retreat
                    // and restart approach planning from a different ledge.
                    // Keep the exact unfinished cells and current Y gate.
                    waiting.retreatUntil=level.getGameTime()+160;
                    waiting.frontierWaitSince=-1;
                    if(waiting.alternateSideAttempts<3){
                        waiting.failedApproachFrom=worker.blockPosition();
                        waiting.alternateSideRequested=true;
                    }
                    if(expensiveAscent)
                        System.out.println("[ROOF REPOSITION] "
                                +worker.getName().getString()
                                +" has no executable stance after "+waited
                                +" ticks at "+worker.blockPosition()
                                +"; descending to retry from OnSite");
                }
                if(table.builderZoneDone(zone))activityForFinished(worker);
                returnAction.returningToSite=!parkAtOnSite(level,worker,table);
                return;
            }
            var activity=action(worker,index);
            activity.frontierWaitSince=-1;
            var claimedStep=plan.steps().get(index);
            int activeLayer=table.builderActiveLayer(plan.steps());
            if(claimedStep.y()!=activeLayer
                    ||!table.builderStepInActivePhase(plan.steps(),index)){
                // Defensive execution guard: even a stale claim from a
                // previous build version must not break the Y-layer rule.
                table.setProblem("Builder waiting for completion of Y="+activeLayer
                        +" before Y="+claimedStep.y());
                return;
            }
            if(claimedStep.ignorePlant()){
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
            BlockPos column=new BlockPos(claimedStep.x(),claimedStep.y(),claimedStep.z());
            if(Math.abs((long)column.getX()-home.getX())>256||
                    Math.abs((long)column.getZ()-home.getZ())>256){
                table.setProblem("Build extends beyond 256-block Builder range");
                return;
            }
            // Load the current layer's next target before stopping on an
            // unloaded schematic chunk. The same ticket is reclaimed as
            // the real Builder advances to the next target.
            BuilderChunkTickets.destination(level,worker,column);
            if(!level.hasChunkAt(column)){
                table.setProblem("Loading construction chunk "+column.toShortString());
                return;
            }
            if(!level.mayInteract(owner,column)){
                table.setProblem("Owner cannot modify this location");return;
            }
            var effectiveStep=claimedStep;
            if(BuilderWaterPass.isBubbleColumn(claimedStep.encodedState())){
                var effective=BuilderWaterPass.bubbleAction(level,column,claimedStep,
                        BuilderConstructionPlanner.schematicStates(plan.steps(),
                            BuildSite.Rotation.values()[table.builderRotation()/90],
                            BuildSite.Mirror.values()[table.builderMirror()]));
                if(effective==null){
                    table.waitForBuilderSupport(worker.getUUID(),index,level.getGameTime());
                    table.setProblem("Bubble column needs compatible support at "+column.below()
                            +" found="+level.getBlockState(column.below()));
                    return;
                }
                if(effective!=claimedStep){
                    if(!level.mayInteract(owner,column.below())){
                        table.setProblem("Bubble column base is protected at "+column.below());
                        return;
                    }
                    effectiveStep=effective;
                }
            }
            final var step=effectiveStep;
            final boolean repairingBubbleBase=step!=claimedStep;
            final BlockPos pos=repairingBubbleBase?column.below():column;
            var state=BuilderBlockResolver.resolve(step.encodedState(),
                    BuildSite.Rotation.values()[table.builderRotation()/90],
                    BuildSite.Mirror.values()[table.builderMirror()]);
            if(state==null){
                table.setProblem("Unsupported block state: "+step.encodedState());return;
            }
            BlockState existing=level.getBlockState(pos);
            // A door, bed or double plant is a single saved work index.
            // The partner's exact transformed palette is verified within
            // this claim; it must never spawn another work order/item bill.
            BuilderConstructionPlan.Companion partner=step.companion();
            BlockPos partnerPos=partner==null?null:BuilderMultiblock.position(partner);
            BuilderBlockResolver.Resolved partnerResolved=partner==null?null:
                    BuilderBlockResolver.resolve(partner.encodedState(),
                        BuildSite.Rotation.values()[table.builderRotation()/90],
                        BuildSite.Mirror.values()[table.builderMirror()]);
            if(partner!=null&&partnerResolved==null){
                table.setProblem("Unsupported multiblock partner at "+partnerPos);
                return;
            }
            BlockState partnerExisting=partnerPos==null?null:
                    level.getBlockState(partnerPos);
            boolean primaryComplete=BuilderPlacementState.matches(
                    existing,state.state());
            boolean partnerComplete=partner==null||BuilderPlacementState.matches(
                    partnerExisting,partnerResolved.state());
            if(primaryComplete&&partnerComplete){
                // Auto-connected walls/fences/panes and stair corner shapes
                // may differ from the saved palette after neighbouring
                // blocks change. They ARE already built; never dismantle
                // a correct block in pursuit of stale schematic geometry.
                table.recordBuilderPlacement(step.requiredItem());
                table.completeBuilderStep(worker.getUUID(),index);
                // A pre-existing block is not this golem's physical work:
                // do not relocate its last real placement anchor here.
                activity.phase=Action.COMPLETE;
                // Already-correct positions are NO-OPs, never mine or
                // dismantle the nearby scaffold to rebuild them.
                return;
            }
            // A wrong lever/repeater/comparator/gate click-setting is not
            // a missing block. Retain the existing paid block and use
            // its native Minecraft interaction when within safe reach.
            boolean interactionCorrection=BuilderBlockInteractions
                    .needsInteraction(existing,state.state());
            // Repairing a generated upper door/plant or bed head is not
            // a second item purchase. The original lower/foot block is
            // still present and can be used as the physical anchor.
            boolean repairPartner=primaryComplete&&!partnerComplete;
            boolean waterlog=BuilderWaterPass.isWaterlogPass(step.encodedState());
            boolean bubble=BuilderWaterPass.isBubbleColumn(step.encodedState());
            BuilderBlockResolver.Resolved dryWaterlog=null;
            boolean rebuildMissingWetRail=false;
            if(waterlog){
                dryWaterlog=BuilderBlockResolver.resolve(
                        BuilderWaterPass.dryState(
                                BuilderWaterPass.originalState(step.encodedState())),
                        BuildSite.Rotation.values()[table.builderRotation()/90],
                        BuildSite.Mirror.values()[table.builderMirror()]);
                // If a paid rail was popped off by vanilla updates after
                // its dry placement, the water step may rebuild it using
                // a SECOND physically drawn rail. Do not repair other
                // blocks or force an unsupported/un-survivable rail.
                // A popped waterloggable rail can leave SOURCE WATER
                // behind as well as air. Replacing a water source is
                // allowed ONLY after this exact dry rail was already paid
                // for; the replacement itself also consumes a real rail.
                rebuildMissingWetRail=dryWaterlog!=null
                        &&(existing.isAir()
                            ||existing.is(net.minecraft.world.level.block.Blocks.WATER)
                                &&existing.getFluidState().isSource())
                        &&BuilderRailSlope.raisedEnd(pos,dryWaterlog.state())!=null
                        &&dryWaterlog.state().canSurvive(level,pos);
                if(dryWaterlog!=null
                        &&BuilderRailSlope.raisedEnd(pos,dryWaterlog.state())!=null
                        &&!existing.isAir()
                        &&!BuilderPlacementState.matches(existing,dryWaterlog.state())){
                    if(BuilderRailSlope.restorePaidSlope(
                            level,pos,dryWaterlog.state()))
                        existing=level.getBlockState(pos);
                }
                if(!rebuildMissingWetRail&&(dryWaterlog==null
                        ||!BuilderPlacementState.matches(existing,dryWaterlog.state()))){
                    table.waitForBuilderSupport(worker.getUUID(),index,
                            level.getGameTime());
                    table.setProblem("Water pass waiting for dry block at "+pos
                            +" actual="+existing+" expected="
                            +(dryWaterlog==null?"unsupported":dryWaterlog.state()));
                    return;
                }
            }
            if(bubble&&existing.is(net.minecraft.world.level.block.Blocks.WATER)
                    &&existing.getFluidState().isSource()){
                level.scheduleTick(pos.below(),level.getBlockState(pos.below()).getBlock(),1);
                // The Builder already supplied this source. Don't mine it
                // or repeatedly place the same water block while vanilla
                // propagates the upward/downward bubble from below.
                table.waitForBuilderSupport(worker.getUUID(),index,
                        level.getGameTime());
                table.setProblem("Waiting for soul-sand/magma bubble propagation at "+pos);
                return;
            }
            boolean reShapeRail=!waterlog&&BuilderRailSlope.canReshapePaidSlope(
                    level,pos,state.state());
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
                    &&worker.carriedCount(state.item())<=0
                    &&!interactionCorrection&&!repairPartner&&!reShapeRail){
                activity.phase=Action.STAGE_MATERIALS;
                var prep=BuilderSupplyInventory.prepareWorkOrder(
                        level,worker,table,plan.steps(),zone);
                if(prep==BuilderSupplyInventory.LoadStatus.MOVING){
                    watchSupplyTrip(level,worker,table,activity,index);
                    return;
                }
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
            BlockPos raisedRail=BuilderRailSlope.raisedEnd(pos,state.state());
            boolean needsRailPlacement=raisedRail!=null&&(!waterlog||rebuildMissingWetRail);
            boolean temporaryRailFoundation=needsRailPlacement
                    &&!BuilderRailSlope.hasRaisedEndSupport(level,pos,state.state())
                    &&BuilderRailSlope.canStageFoundation(level,pos,state.state(),
                        BuilderConstructionPlanner.schematicStates(plan.steps(),
                            BuildSite.Rotation.values()[table.builderRotation()/90],
                            BuildSite.Mirror.values()[table.builderMirror()]));
            if(needsRailPlacement&&!reShapeRail&&!temporaryRailFoundation
                    &&!BuilderRailSlope.hasRaisedEndSupport(level,pos,state.state())){
                BlockPos support=raisedRail.below();
                table.waitForBuilderSupport(worker.getUUID(),index,level.getGameTime());
                table.setProblem("Ascending rail needs a place for temporary footing at "
                        +support+" found="+level.getBlockState(support));
                return;
            }
            var railItem=state.state().getBlock().asItem();
            boolean railJig=needsRailPlacement&&level.hasChunkAt(raisedRail)
                    &&level.getBlockState(raisedRail).isAir()
                    &&(temporaryRailFoundation
                        ||BuilderRailSlope.hasRaisedEndSupport(level,pos,state.state()));
            if(!existing.isAir()&&!waterlog&&!reShapeRail
                    &&!interactionCorrection&&!repairPartner&&!repairingBubbleBase
                    &&!(bubble&&(existing.is(net.minecraft.world.level.block.Blocks.WATER)
                            ||existing.is(net.minecraft.world.level.block.Blocks.BUBBLE_COLUMN)))){
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
                    &&worker.carriedCount(state.item())<=0
                    &&!interactionCorrection&&!repairPartner&&!reShapeRail){
                var loading=BuilderSupplyInventory.loadForLayerStatus(
                        level,worker,table,step,index);
                if(loading==BuilderSupplyInventory.LoadStatus.MOVING){
                    activity.phase=Action.STAGE_MATERIALS;
                    watchSupplyTrip(level,worker,table,activity,index);
                    return;
                }
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
            // Stage the complete rail kit before committing to a climb.
            // Discovering a missing spare at the work pose sends the
            // worker straight back down the route it just built.
            if(railJig||rebuildMissingWetRail){
                int needed=railJig&&!reShapeRail?2:1;
                var extra=BuilderSupplyInventory.loadRailJig(
                        level,worker,table,railItem,needed);
                if(extra!=BuilderSupplyInventory.LoadStatus.READY){
                    activity.phase=Action.STAGE_MATERIALS;
                    if(extra==BuilderSupplyInventory.LoadStatus.MOVING)
                        watchSupplyTrip(level,worker,table,activity,index);
                    if(extra==BuilderSupplyInventory.LoadStatus.MISSING)
                        table.setProblem("Ascending rail needs a spare rail in OnSite Storage");
                    return;
                }
                if(temporaryRailFoundation){
                    var footing=BuilderSupplyInventory.loadRailJig(
                            level,worker,table,net.minecraft.world.item.Items.COBBLESTONE,1);
                    if(footing!=BuilderSupplyInventory.LoadStatus.READY){
                        activity.phase=Action.STAGE_MATERIALS;
                        if(footing==BuilderSupplyInventory.LoadStatus.MOVING)
                            watchSupplyTrip(level,worker,table,activity,index);
                        if(footing==BuilderSupplyInventory.LoadStatus.MISSING)
                            table.setProblem("Ascending rail jig needs one cobblestone in OnSite Storage");
                        return;
                    }
                }
            }
            if(repairingBubbleBase&&!existing.isAir()){
                BuilderClearance.clear(level,worker,table,owner,pos,existing);
                return;
            }
            activity.supplyProgressTick=-1;
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
            // Every meaningful physical movement refreshes the stall timer.
            // A long detour, grapple, or scaffold climb must not be
            // interrupted merely because it takes longer than 180 ticks.
            double targetDistance=worker.distanceToSqr(
                    pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5);
            if(activity.lastTargetProgressTick<0
                    ||targetDistance<activity.bestTargetDistance-1.0){
                activity.bestTargetDistance=targetDistance;
                activity.lastTargetProgressTick=now;
            }
            if(activity.lastMovementTick<0){
                activity.lastMovementTick=now;
                activity.lastWorkX=worker.getX();
                activity.lastWorkY=worker.getY();
                activity.lastWorkZ=worker.getZ();
            }else{
                double dx=worker.getX()-activity.lastWorkX;
                double dy=worker.getY()-activity.lastWorkY;
                double dz=worker.getZ()-activity.lastWorkZ;
                if(dx*dx+dy*dy+dz*dz>=0.36){
                    activity.lastMovementTick=now;
                    activity.lastWorkX=worker.getX();
                    activity.lastWorkY=worker.getY();
                    activity.lastWorkZ=worker.getZ();
                }
            }
            // A genuinely stationary Builder on fence/wall/stair geometry
            // may have no legal ground node even though a clear rope face
            // and safe lower landing exist. Check that physical escape at
            // most once every four seconds; no hook is used during normal
            // movement, or while a scaffold controller is in charge.
            if(now-activity.lastMovementTick>80
                    &&now-activity.lastPerchEscapeTick>=80
                    &&!BuilderGrappleAccess.active(worker)
                    &&!BuilderScaffoldPlanA.hasPlan(worker)
                    &&!BuilderScaffolding.isControllingMovement(worker)){
                activity.lastPerchEscapeTick=now;
                if(BuilderGrappleAccess.escapePerch(
                        level,worker,table,pos)
                        ||BuilderGrappleAccess.escapeRailPocket(
                                level,worker,pos)){
                    activity.lastMovementTick=now;
                    activity.workTravelSince=now;
                    return;
                }
            }
            // Prepare a bounded next-step choice during ordinary walking.
            // This never issues movement to the next target prematurely.
            prefetchNext(level,worker,table,plan.steps(),zone,index,pos,
                    activity,now);
            // Once an owned shaft/bridge starts, its controller owns all
            // physical movement. Do not replace it with a normal route
            // just because another nominal work pose becomes visible.
            if(BuilderScaffoldPlanA.hasPlan(worker)
                    &&scaffoldWork(level,worker,table,owner,pos,plan.steps()))
                return;
            if(now-activity.lastTargetProgressTick>TARGET_PROGRESS_STALL_TICKS
                    &&!BuilderGrappleAccess.active(worker)
                    &&!BuilderScaffoldPlanA.hasPlan(worker)
                    &&!BuilderScaffolding.isControllingMovement(worker)){
                // A golem may move continuously while circling the same
                // roof gap. Actual distance to its goal must improve
                // eventually. Release the exact step for another crew
                // member rather than treating motion as progress forever.
                if(scaffoldBlockedWork(level,worker,table,owner,pos,
                        plan.steps())){
                    activity.lastTargetProgressTick=now;
                    return;
                }
                BuilderPlacementSpace.cancelYield(worker);
                BuilderPlacementSpace.invalidateApproach(worker,pos);
                com.caszgamermd.golemsplus.worker.WorkerNavigation.stop(worker);
                table.waitForBuilderAccess(worker.getUUID(),index,now,
                        TARGET_PROGRESS_STALL_TICKS+" ticks without target-distance progress");
                activity.lastFailedStep=index;
                activity.retreatUntil=now+100;
                if(activity.alternateSideAttempts<3){
                    activity.failedApproachFrom=worker.blockPosition();
                    activity.alternateSideRequested=true;
                }
                publishHandoff(table,worker.getUUID(),index,now,
                        "circling without approaching work cell");
                System.out.println("[BUILDER HANDOFF] worker="
                        +worker.getName().getString()+" released="+index
                        +" noCloserFor="+(now-activity.lastTargetProgressTick)
                        +" ticks; teammate may try");
                activity.lastTargetProgressTick=now;
                return;
            }
            if(now-activity.lastMovementTick>STATIONARY_STALL_TICKS
                    &&!BuilderGrappleAccess.active(worker)
                    &&!BuilderScaffoldPlanA.hasPlan(worker)
                    &&!BuilderScaffolding.isControllingMovement(worker)){
                // Reposition before abandoning the assignment: try an
                // alternate physical grapple or paid scaffold access route.
                // Neither is mandatory; a failed attempt falls through to
                // deferred frontier selection rather than spinning forever.
                if(scaffoldBlockedWork(level,worker,table,owner,pos,
                        plan.steps())){
                    activity.workTravelSince=now;
                    return;
                }
                if(pos.getY()>worker.blockPosition().getY()+1){
                    var reposition=BuilderGrappleAccess.reachWork(
                            level,worker,table,pos);
                    if(reposition==BuilderGrappleAccess.Result.MOVING){
                        activity.workTravelSince=now;
                        return;
                    }
                }
                // Repeatedly executable movement is not proof that this
                // placement is making progress. Preserve the cell/materials
                // and let another feasible frontier on THIS Y run first.
                BuilderPlacementSpace.cancelYield(worker);
                BuilderPlacementSpace.invalidateApproach(worker,pos);
                com.caszgamermd.golemsplus.worker.WorkerNavigation.stop(worker);
                table.waitForBuilderAccess(worker.getUUID(),index,now,
                        STATIONARY_STALL_TICKS+"-tick stationary work approach; alternative access exhausted");
                activity.lastFailedStep=index;
                activity.retreatUntil=now+100;
                if(activity.alternateSideAttempts<3){
                    activity.failedApproachFrom=worker.blockPosition();
                    activity.alternateSideRequested=true;
                }
                publishHandoff(table,worker.getUUID(),index,now,
                        "stationary after alternate scaffold and grapple");
                System.out.println("[BUILDER HANDOFF] worker="+worker.getName().getString()
                        +" released="+index+" returnOnSite=true");
                activity.workTravelSince=-1;
                activity.lastMovementTick=now;
                table.setProblem("Deferred stationary work approach "+pos);
                System.out.println("[FRONTIER RETRY] deferred unchanged step="+index
                        +" after "+STATIONARY_STALL_TICKS+" stationary ticks at "+worker.position());
                return;
            }
            int yDiff=pos.getY()-worker.blockPosition().getY();
            boolean temporaryWorkPose=BuilderScaffoldPlanA.safeTemporaryWorkPose(
                    level,worker,table,pos);
            double workDistance=worker.distanceToSqr(
                    pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5);
            // Thin rails/wires occupy no future body collision. A
            // grounded Builder with a safe hand pose should not walk
            // several blocks farther just to satisfy a SOLID cube's
            // 0.8m shoulder clearance for a walk-through circuit.
            boolean bodyBlockingWork=BuilderPlacementSpace.stepNeedsVacating(
                    level,pos,state.state(),waterlog);
            boolean armClear=!bodyBlockingWork
                    ||BuilderPlacementSpace.horizontalClear(pos,worker);
            boolean directOrdinaryWork=BuilderPlacementSpace.placementHeight(
                        pos,worker)
                    &&workDistance<=BuilderConstructionPlanner.REACH_SQUARED
                    &&armClear
                    &&(temporaryWorkPose
                        ||BuilderPlacementSpace.safeFractionalCapPose(
                                level,worker,pos)
                        ||BuilderConstructionPlanner.safeStandForStep(
                                level,worker,pos,worker.blockPosition(),
                                step.encodedState()));
            // Low dome walls and fence caps often sit two Y above a
            // grounded Builder. Its five-block reach may
            // already cover the target from a SIDE stance. Find that
            // safe, walkable position before spending time on a rope or
            // scaffold tower. A trained route can walk backward first.
            if(yDiff>1&&!directOrdinaryWork
                    &&!BuilderGrappleAccess.active(worker)
                    &&!BuilderScaffolding.isControllingMovement(worker)){
                BlockPos walk=BuilderPlacementSpace.approachForStep(
                        level,worker,pos,bodyBlockingWork,false,
                        at->BuilderConstructionPlanner.safeStandForStep(
                                level,worker,pos,at,step.encodedState()));
                if(walk!=null){
                    activity.phase=Action.MOVE_TO_WORK;
                    if(com.caszgamermd.golemsplus.worker.WorkerNavigation
                            .routeToExact(level,worker,walk,1.0))return;
                    BuilderPlacementSpace.invalidateApproach(worker,pos);
                }
            }
            // Elevated cells without a supporting block below need a
            // construction platform, not repeated grapple ascents to a
            // non-existent work stance. An inventory-paid scaffold bridge
            // gets first refusal when ordinary walking cannot reach it.
            // A failed scaffold preflight still permits grappling below.
            if(yDiff>0
                    &&BuilderConstructionPlanner.preferScaffoldForUnsupportedWork(
                            directOrdinaryWork,
                            level.getBlockState(pos.below()).isAir())
                    &&!BuilderGrappleAccess.active(worker)
                    &&scaffoldBlockedWork(level,worker,table,owner,pos,
                            plan.steps()))
                return;
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
                    &&now-activity.lastMovementTick>80
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
                    ||workDistance>BuilderConstructionPlanner.REACH_SQUARED
                    ||!armClear
                    ||!(temporaryWorkPose
                        ||BuilderPlacementSpace.safeFractionalCapPose(
                                level,worker,pos)
                        ||BuilderConstructionPlanner.safeStandForStep(
                                level,worker,pos,worker.blockPosition(),
                                step.encodedState()))){
                // Even when within arm's reach, a Builder must first
                // occupy a PREVALIDATED work stance. This prevents the
                // repeated old deadlock where it arrived next to an AIR
                // cell, then had to escape its own placement volume.
                activity.phase=Action.MOVE_TO_WORK;
                BlockPos approach=BuilderPlacementSpace.approachForStep(
                        level,worker,pos,bodyBlockingWork,false,
                        at->BuilderConstructionPlanner.safeStandForStep(
                                level,worker,pos,at,step.encodedState()));
                if(approach!=null){
                    boolean moving=com.caszgamermd.golemsplus.worker
                            .WorkerNavigation.routeToExact(
                                    level,worker,approach,1.0);
                    if(!moving){
                        // Approximate routeTo() may stop short of the EXACT
                        // 0.8m-clearance pose on narrow roofs. It reported
                        // success while the worker repeatedly missed its
                        // actual placement stance. Never use it as a
                        // fallback for an exact construction destination.
                        BuilderPlacementSpace.invalidateApproach(worker,pos);
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
                            +pos+" (exit / clearance)");
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
            if(!(BuilderScaffoldPlanA.safeTemporaryWorkPose(
                        level,worker,table,pos)
                    ||BuilderPlacementSpace.safeFractionalCapPose(
                            level,worker,pos)
                    ||BuilderConstructionPlanner.safeStandForStep(
                        level,worker,pos,worker.blockPosition(),
                        step.encodedState()))){
                BuilderPlacementSpace.invalidateApproach(worker,pos);
                table.waitForBuilderAccess(worker.getUUID(),index,
                        level.getGameTime(),"lost safe pose or exit during approach");
                table.setProblem("FRONTIER: lost safe pose or exit at "+pos);
                return;
            }
            // Never place into any Builder's body. Check before spending
            // the item so waiting for a teammate to move consumes nothing.
            // Filling a waterloggable block changes no solid collision
            // geometry: do not make teammates vacate an imaginary cube.
            // Rail repairs or fresh source water still require full
            // body-clearance and the usual 0.8m placement margin.
            // Rail and wire geometry has no solid collision: even if a
            // worker occupies that tile, the placed block cannot bury it.
            // Bypass only the imaginary full-cube evacuation; ordinary
            // work reach, inventory, support, and permission checks remain.
            if(bodyBlockingWork
                    &&!BuilderPlacementSpace.ready(level,worker,table,pos)){
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
                    BuilderPlacementSpace.invalidateApproach(worker,pos);
                    com.caszgamermd.golemsplus.worker.WorkerNavigation.stop(worker);
                    activity.lastFailedStep=index;
                    activity.retreatUntil=level.getGameTime()+100;
                    if(activity.alternateSideAttempts<3){
                        activity.failedApproachFrom=worker.blockPosition();
                        activity.alternateSideRequested=true;
                    }
                    table.setProblem("Deferred block "+pos
                            +" until a safe 0.8m placement stance is reachable");
                    activity.blockedSince=-1;
                }
                return;
            }
            activity.blockedSince=-1;
            if(bodyBlockingWork
                    &&!BuilderPlacementSpace.horizontalClear(pos,worker)){
                // Defense-in-depth against a self-collision regression.
                // Vertical separation NEVER waives the X/Z 0.8 margin.
                table.setProblem("Builder still within 0.8 X/Z of "+pos);
                return;
            }
            // Upper door halves do not consume another door item; the
            // lower half was already placed and paid for in the prior
            // layer. Keep refund logic for all ordinary materials.
            // An already-paid rail that lost its slope is reshaped in
            // place, without destroying it and charging for a second rail.
            // A native right-click changes the state of a block that
            // was already paid for; never debit or destroy it again.
            if(interactionCorrection){
                if(!BuilderBlockInteractions.interact(level,pos,state.state(),owner)){
                    table.waitForBuilderSupport(worker.getUUID(),index,
                            level.getGameTime());
                    table.setProblem("Interactive state waiting at "+pos
                            +" current="+level.getBlockState(pos)
                            +" target="+state.state());
                    return;
                }
                table.recordBuilderPlacement(step.requiredItem());
                table.completeBuilderStep(worker.getUUID(),index);
                BuilderConstructionPlanner.noteCompleted(worker,pos);
                activity.notePhysicalPlacement();
                activity.phase=Action.COMPLETE;
                return;
            }
            if(partnerPos!=null&&!partnerComplete){
                if(!level.hasChunkAt(partnerPos)){
                    BuilderChunkTickets.destination(level,worker,partnerPos);
                    table.setProblem("Loading multiblock partner at "+partnerPos);
                    return;
                }
                if(!level.mayInteract(owner,partnerPos)
                        ||!partnerExisting.isAir()
                        ||table.isTrackedScaffold(partnerPos)
                        ||level.getBlockEntity(partnerPos)!=null){
                    // A companion is never permission to overwrite a
                    // player's protected or occupied schematic cell.
                    table.waitForBuilderSupport(worker.getUUID(),index,
                            level.getGameTime());
                    table.setProblem("Multiblock partner needs clear protected "
                            +"space at "+partnerPos+" found="+partnerExisting);
                    return;
                }
                if(!BuilderPlacementSpace.readyForState(level,worker,table,
                        partnerPos,partnerResolved.state()))
                    return; // The entire future two-cell collision is safe.
            }
            boolean freeHalf=step.requiredItem()==null||reShapeRail||repairPartner;
            boolean reusableFluid=BuilderBlockResolver.reusableSupply(step.requiredItem());
            if(railJig){
                if(!level.mayInteract(owner,raisedRail)
                        ||temporaryRailFoundation&&!level.mayInteract(owner,raisedRail.below())){
                    table.waitForBuilderSupport(worker.getUUID(),index,level.getGameTime());
                    table.setProblem("Temporary rail jig space is protected at "+raisedRail);
                    return;
                }
                if(temporaryRailFoundation){
                    if(!BuilderPlacementSpace.readyForState(level,worker,table,
                            raisedRail.below(),net.minecraft.world.level.block.Blocks.COBBLESTONE
                                    .defaultBlockState()))return;
                }
            }
            BuilderSupplyInventory.Receipt payment=freeHalf||reusableFluid?null:
                    BuilderSupplyInventory.consume(worker,state.item());
            if(!freeHalf&&!reusableFluid&&payment==null){
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
            // A bubble column is a PHYSICS RESULT, never an inventory
            // placement. Put down source water and let its real supporting
            // soul sand/magma (or column below) convert it naturally.
            boolean railPlaced=false;
            if(railJig||rebuildMissingWetRail){
                var replacement=rebuildMissingWetRail
                        ?BuilderSupplyInventory.consume(worker,railItem):null;
                var spare=railJig?BuilderSupplyInventory.consume(worker,railItem):null;
                var footing=temporaryRailFoundation
                        ?BuilderSupplyInventory.consume(worker,net.minecraft.world.item.Items.COBBLESTONE)
                        :null;
                boolean paid=(!rebuildMissingWetRail||replacement!=null)
                        &&(!railJig||spare!=null)
                        &&(!temporaryRailFoundation||footing!=null);
                if(paid){
                    railPlaced=railJig
                            ?BuilderRailSlope.placeWithJig(level,pos,state.state(),temporaryRailFoundation)
                            :level.setBlock(pos,BuilderBlockInteractions.placementState(state.state()),3)
                                &&BuilderRailSlope.restorePaidSlope(level,pos,state.state());
                    if(!railJig&&!railPlaced)
                        level.setBlock(pos,existing,2);
                }
                // Refund in reverse order because the target and connector
                // can come from the same inventory slot or shulker.
                if(footing!=null)BuilderSupplyInventory.refund(table,footing);
                if(spare!=null)BuilderSupplyInventory.refund(table,spare);
                if(!railPlaced){
                    if(replacement!=null)BuilderSupplyInventory.refund(table,replacement);
                    if(payment!=null)BuilderSupplyInventory.refund(table,payment);
                    table.waitForBuilderSupport(worker.getUUID(),index,level.getGameTime());
                    table.setProblem("Temporary rail did not produce a stable slope at "+pos);
                    return;
                }
            }
            BlockState toPlace=bubble
                    ?net.minecraft.world.level.block.Blocks.WATER.defaultBlockState()
                    :BuilderBlockInteractions.placementState(state.state());
            // A real multi-cell item is one transaction and one payment.
            // The world may keep a placed lower/foot cell even if the
            // companion temporarily fails; the receipt is NOT refunded
            // in that case, so it cannot yield free duplicate materials.
            var multiblockPlacement=railPlaced
                    ?new BuilderMultiblock.Placement(true,true)
                    :BuilderMultiblock.place(
                    level,pos,toPlace,partnerPos,
                    partnerResolved==null?null:
                            BuilderBlockInteractions.placementState(
                                    partnerResolved.state()),
                    repairPartner,partnerComplete);
            boolean placed=multiblockPlacement.complete();
            if(!placed){
                if(payment!=null&&!multiblockPlacement.primaryPlaced())
                    BuilderSupplyInventory.refund(table,payment);
                table.setProblem("World rejected complete multiblock placement at "+pos
                        +" companion="+partnerPos
                        +" primaryPlaced="+multiblockPlacement.primaryPlaced());
                return;
            }
            if(partnerPos!=null
                    &&(!BuilderPlacementState.matches(
                            level.getBlockState(pos),state.state())
                        ||!BuilderPlacementState.matches(
                            level.getBlockState(partnerPos),
                            partnerResolved.state()))){
                // No second payment: on retry a valid primary half is
                // retained, while the single grouped task stays pending.
                table.waitForBuilderSupport(worker.getUUID(),index,
                        level.getGameTime());
                table.setProblem("Multiblock interaction not yet stable: "
                        +pos+" -> "+level.getBlockState(pos)
                        +" and "+partnerPos+" -> "+level.getBlockState(partnerPos));
                return;
            }
            // Only after spending the physical block item and placing
            // its neutral state may the Builder click its real controls.
            // The resulting neighbor updates are genuine vanilla behavior.
            if(BuilderBlockInteractions.hasManualControl(state.state())
                    &&!BuilderPlacementState.matches(
                            level.getBlockState(pos),state.state())
                    &&!BuilderBlockInteractions.interact(level,pos,
                            state.state(),owner)){
                // The item has been paid for, but the actual click setting
                // could not be reached. The next attempt uses the SAME
                // physical block and does not charge its item again.
                table.waitForBuilderSupport(worker.getUUID(),index,
                        level.getGameTime());
                table.setProblem("Placed block, awaiting real interaction at "
                        +pos+" actual="+level.getBlockState(pos)
                        +" wanted="+state.state());
                return;
            }
            if(BuilderRailSlope.raisedEnd(pos,state.state())!=null
                    &&!BuilderRailSlope.restorePaidSlope(level,pos,state.state())){
                // A vanilla update may flatten the rail after a jig is
                // recovered. A support-safe correction is attempted on
                // the already paid, physical rail; if it still will not
                // hold its state, defer instead of falsely finishing it.
                table.waitForBuilderSupport(worker.getUUID(),index,
                        level.getGameTime());
                table.setProblem("Ascending rail awaiting stable slope at "+pos);
                return;
            }
            if(repairingBubbleBase){
                BuilderConstructionPlanner.noteCompleted(worker,pos);
                activity.notePhysicalPlacement();
                return;
            }
            if(bubble)level.scheduleTick(pos.below(),level.getBlockState(pos.below()).getBlock(),1);
            if(bubble&&!BuilderPlacementState.matches(
                    level.getBlockState(pos),state.state())){
                // Do not advance the global Y or replace an actual water
                // block with a fake bubble column. Wait for the vanilla
                // fluid/neighbor update; the bucket remains in the pouch.
                table.waitForBuilderSupport(worker.getUUID(),index,
                        level.getGameTime());
                table.setProblem("Waiting for natural bubble column at "+pos
                        +" (needs source water over soul sand/magma)");
                return;
            }
            table.recordBuilderPlacement(step.requiredItem());
            table.completeBuilderStep(worker.getUUID(),index);
            BuilderConstructionPlanner.noteCompleted(worker,pos);
            activity.notePhysicalPlacement();
            activity.phase=Action.COMPLETE;
            // Use the already-validated next task, if still safe and
            // claimable. No second full frontier search at this stop.
            handoffNext(level,worker,table,plan.steps(),zone,activity,index);
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
            Identifier itemId=current.requiredItem()==null?null:
                    Identifier.tryParse(current.requiredItem());
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
                    +" | staged="+table.builderOrderPrepared(worker.getUUID())
                    +" | stock="+(itemId==null?"none":itemId+" x"+
                        worker.carriedCount(BuiltInRegistries.ITEM.getValue(itemId)))
                    +" | footing="+level.getBlockState(worker.blockPosition().below())
                    +" | feetBlock="+level.getBlockState(worker.blockPosition())
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
            int ignored=0;
            for(var step:cache.steps())if(step.ignorePlant())ignored++;
            // Reconcile virtual skips FIRST: an un-ignored cell may reopen
            // from the saved completed prefix without recreating materials
            // for physically placed work.
            if((table.activeOrder()!=null
                    ||table.phase()==WorkerPhase.COMPLETE)
                    &&table.builderClearCursor()>=cache.clearing().size())
                table.reconcileBuilderWork(cache.steps());
            table.reconcileBuilderMaterialsFromPlan(cache.steps());
            table.previewBuilderWork(cache.steps());
            return "Builder plan updated: "+ignored+" skipped cells; "
                    +"remaining inventory and next warehouse window recalculated";
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
                &&previous.ignorePlants()==table.builderIgnorePlants()
                &&previous.rulesRevision()==table.builderRuleRevision())
            return previous;
        if(!table.hasPlacedSchematic())
            throw new IllegalArgumentException("No placed schematic");
        Path folder=FabricLoader.getInstance().getConfigDir()
                .resolve("golems_plus").resolve("schematics")
                .toAbsolutePath().normalize();
        Path file=folder.resolve(table.builderFile()).normalize();
        if(!file.startsWith(folder)||!Files.isRegularFile(file))
            throw new IllegalArgumentException("Saved schematic missing");
        boolean frozen=BuilderPlacementSnapshot.isSnapshot(file);
        var schematic=frozen?BuilderPlacementSnapshot.read(file)
                :LitematicFileReader.readLegacyPlacement(file);
        var origin=table.builderOrigin();
        // Snapshot cells are already ABSOLUTE world positions from the
        // selected Litematica ghost. A second transform is forbidden.
        // Keep legacy .litematic jobs readable until they are reimported.
        var site=frozen?BuilderPlacementSnapshot.worldSpaceSite()
                :new BuildSite(origin.getX(),origin.getY(),origin.getZ(),
                        BuildSite.Rotation.values()[table.builderRotation()/90],
                        BuildSite.Mirror.values()[table.builderMirror()]);
        var original=BuilderConstructionPlan.create(schematic,site,
                table.builderIgnorePlants());
        var steps=BuilderMaterialRules.apply(original.steps(),
                table.builderMaterialRules(),table.builderStartY(),
                table.builderHeightMode(),site.rotation(),site.mirror());
        // The default job does no extra 100k-position map work.
        List<BuilderSiteClearance.Target> clearing=
                BuilderSiteClearance.targets(schematic,site);
        if(!table.builderMaterialRules().isEmpty()
                ||table.builderHeightMode()!=BuilderMaterialRules.ALL){
            // Preserve clearance index stability across mid-job rule edits.
            // A skipped target is a no-op, never a permission to remove it.
            var byPosition=new java.util.HashMap<BlockPos,
                    BuilderConstructionPlan.Step>();
            var originallyIgnored=new java.util.HashSet<BlockPos>();
            for(var step:original.steps()){
                if(step.ignorePlant())
                    originallyIgnored.add(new BlockPos(step.x(),step.y(),step.z()));
            }
            for(var step:steps)
                byPosition.put(new BlockPos(step.x(),step.y(),step.z()),step);
            var adjusted=new java.util.ArrayList<BuilderSiteClearance.Target>(
                    clearing.size());
            for(var target:clearing){
                var effective=byPosition.get(target.pos());
                boolean skip=!table.builderIncludesY(target.pos().getY())
                        ||effective!=null&&effective.ignorePlant()
                            &&!originallyIgnored.contains(target.pos());
                adjusted.add(skip
                        ?new BuilderSiteClearance.Target(target.pos(),
                                "golems_plus:skip")
                        :effective==null?target:
                                new BuilderSiteClearance.Target(
                                        target.pos(),effective.encodedState()));
            }
            clearing=List.copyOf(adjusted);
        }
        Cache next=new Cache(table.builderFile(),table.builderRotation(),
                table.builderMirror(),origin,steps,List.copyOf(clearing),
                table.builderIgnorePlants(),table.builderRuleRevision(),
                BuilderZones.footprint(steps));
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
        var tasks=BuilderZoneWorkPlan.compile(cache.steps(),cache.zones(),
                roster,table.builderHeightMode()==BuilderMaterialRules.FROM_Y_DOWN);
        var layers=new java.util.HashMap<Integer,List<Integer>>();
        var mutableLayers=new java.util.HashMap<Integer,
                java.util.ArrayList<Integer>>();
        for(int i=0;i<cache.steps().size();i++){
            var step=cache.steps().get(i);
            mutableLayers.computeIfAbsent(step.y(),unused->new java.util.ArrayList<>())
                    .add(i);
        }
        mutableLayers.forEach((y,indices)->layers.put(y,List.copyOf(indices)));
        // Count each zone cell once. Recounting the full job separately
        // for every Y would make tall 100k-block schematics expensive.
        var ownership=new java.util.HashMap<Integer,int[]>();
        int crewSize=roster.size();
        for(int w=0;w<roster.size();w++)
            for(int i:tasks.steps(roster.get(w))){
                var step=cache.steps().get(i);
                if(!step.ignorePlant())ownership.computeIfAbsent(step.y(),
                        unused->new int[crewSize])[w]++;
            }
        var staff=new java.util.HashMap<Integer,List<UUID>>();
        for(var entry:layers.entrySet()){
            int y=entry.getKey();
            int minX=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE;
            int minZ=Integer.MAX_VALUE,maxZ=Integer.MIN_VALUE;
            int physical=0;
            for(int index:entry.getValue()){
                var step=cache.steps().get(index);
                // A waterlogged cell is placed dry and filled afterward:
                // two job steps but only one physical workspace cell.
                if(step.ignorePlant()
                        ||BuilderWaterPass.isWaterlogPass(step.encodedState()))
                    continue;
                physical++;
                minX=Math.min(minX,step.x());maxX=Math.max(maxX,step.x());
                minZ=Math.min(minZ,step.z());maxZ=Math.max(maxZ,step.z());
            }
            int width=physical==0?1:maxX-minX+1;
            int depth=physical==0?1:maxZ-minZ+1;
            int[] own=ownership.getOrDefault(y,new int[roster.size()]);
            // Narrow floors do not recruit eight workers to a seven-by-five
            // platform. The remaining crew waits safely at OnSite while
            // ranked zone owners share all current-layer placements.
            staff.put(y,BuilderCrewSchedule.staffForFootprint(roster,
                    physical,own,width,depth));
        }
        table.beginBuilderZonePlan();
        ScheduledZones result=new ScheduledZones(table.activeOrder(),cache,
                List.copyOf(roster),tasks,Map.copyOf(layers),Map.copyOf(staff));
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
                &&previous.ignorePlants()==table.builderIgnorePlants()
                &&previous.rulesRevision()==table.builderRuleRevision())
            return previous;
        // Share the same immutable validated work plan among all eight
        // Builders. A different Litematica parse per worker caused pauses
        // when several workers started the job at once.
        Cache next=jobCache(table);
        cached.put(worker,next);
        return next;
    }
}
