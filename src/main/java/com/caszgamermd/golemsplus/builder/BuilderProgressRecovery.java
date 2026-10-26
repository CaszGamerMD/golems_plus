package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Cheap periodic in-build health check. A Builder can be assigned to a
 * schematic coordinate that a teammate/player has already finished, or
 * can walk in circles between temporary A* detours. Reconcile the ACTUAL
 * world state first; only then consider abandoning a repeatedly failed
 * route. Never skip a wrong block, spend an item or teleport a Builder.
 */
public final class BuilderProgressRecovery {
    private static final Map<BuilderGolem,Watch> watches=new WeakHashMap<>();
    private static final long NO_PROGRESS_TICKS=180L;
    private static final long SAMPLE_TICKS=20L;
    private static final class Watch {
        int index=-1;
        BlockPos target;
        double best=Double.POSITIVE_INFINITY;
        long since,sampledAt;
        int recovering;
    }
    private BuilderProgressRecovery(){}

    public static String debug(BuilderGolem worker){
        Watch w=watches.get(worker);
        return w==null?"IDLE":"step="+w.index+" recovered="+w.recovering
                +" nearestSq="+String.format(java.util.Locale.ROOT,"%.1f",w.best);
    }

    /** @return true when this tick recovered a completed or deferred step. */
    public static boolean tick(ServerLevel world,BuilderGolem worker,
            WorkerStationBlockEntity table,List<BuilderConstructionPlan.Step> steps){
        if(table.builderClearCursor()<0||worker.isPassenger()
                ||BuilderClockwiseTraffic.controlling(worker)
                ||BuilderPlacementSpace.controlling(worker)
                ||BuilderTeamGroundTraffic.controlling(worker)
                ||BuilderScaffolding.isControllingMovement(worker)
                ||BuilderScaffoldBridge.moving(worker))
            return false;
        int index=table.claimedStep(worker.getUUID());
        if(index<0||index>=steps.size()){
            watches.remove(worker);
            return false;
        }
        var step=steps.get(index);
        if(step.ignorePlant())return false;
        BlockPos target=new BlockPos(step.x(),step.y(),step.z());
        if(!world.hasChunkAt(target))return false;
        Watch watch=watches.computeIfAbsent(worker,k->new Watch());
        long now=world.getGameTime();
        // Recompute only once per second. The normal work tick handles
        // freshly changed blocks between samples without added load.
        if(watch.index==index&&now-watch.sampledAt<SAMPLE_TICKS)return false;
        watch.sampledAt=now;
        var state=BuilderBlockResolver.resolve(step.encodedState(),
                BuildSite.Rotation.values()[table.builderRotation()/90],
                BuildSite.Mirror.values()[table.builderMirror()]);
        if(state!=null&&BuilderPlacementState.matches(
                world.getBlockState(target),state.state())){
            // Recheck on the actual server before changing progress; do
            // not charge tools, place blocks or pull stock for a match.
            table.recordBuilderPlacement(step.requiredItem());
            if(table.completeBuilderStep(worker.getUUID(),index)){
                WorkerNavigation.stop(worker);
                watches.remove(worker);
                return true;
            }
            return false;
        }
        double distance=worker.distanceToSqr(target.getX()+.5,
                target.getY()+.5,target.getZ()+.5);
        if(watch.index!=index||!target.equals(watch.target)){
            watch.index=index;watch.target=target;
            watch.best=distance;watch.since=now;
            return false;
        }
        if(distance<watch.best-1.0){
            watch.best=distance;watch.since=now;
            return false;
        }
        // The work-order staging courier may intentionally walk AWAY
        // from this schematic coordinate to pick up real supplies.
        // Do not diagnose that delivery route as a construction detour.
        if(!table.builderOrderPrepared(worker.getUUID()))return false;
        // Do not interrupt a legitimate arrival at placement reach:
        // the regular Builder placement code owns close-range obstacles
        // and ensures no block is placed inside a worker's collision box.
        if(distance<=16.0||now-watch.since<NO_PROGRESS_TICKS)
            return false;
        // Before reassigning a stalled worker, clear the old native route
        // so it cannot continue walking the same invalid detour.
        WorkerNavigation.stop(worker);
        BuilderPlacementSpace.invalidateApproach(worker,target);
        table.waitForBuilderTraffic(worker.getUUID(),index,now);
        watch.recovering++;
        watch.index=-1;
        watch.since=now;
        return true;
    }
}
