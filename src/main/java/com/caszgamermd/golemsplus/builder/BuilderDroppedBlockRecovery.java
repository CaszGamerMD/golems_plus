package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.phys.AABB;

/** Idle builders physically retrieve dropped block-item stacks around their
 * placed build. Drops are never duplicated: the real entity is discarded
 * only once its exact contents are in a persisted Builder cargo slot, and
 * the cargo is cleared only after OnSite accepts the complete stack. */
public final class BuilderDroppedBlockRecovery {
    private BuilderDroppedBlockRecovery(){}
    private static final class Task {
        ItemEntity target;
        BlockPos stand;
        long since;
        long nextScan;
    }
    private static final Map<BuilderGolem,Task> tasks=new WeakHashMap<>();
    private static final Map<WorkerStationBlockEntity,Map<UUID,UUID>> claims=
            new WeakHashMap<>();
    private static final Map<WorkerStationBlockEntity,Map<UUID,Long>> ignored=
            new WeakHashMap<>();

    public static boolean busy(BuilderGolem worker){
        Task task=tasks.get(worker);
        return worker.hasRecoveredDrops()||task!=null&&task.target!=null;
    }
    private static void release(BuilderGolem worker,
            WorkerStationBlockEntity table,Task task){
        if(task.target!=null){
            Map<UUID,UUID> byItem=claims.get(table);
            if(byItem!=null)byItem.remove(task.target.getUUID(),worker.getUUID());
        }
        task.target=null;task.stand=null;
    }
    /** @return true while moving to a dropped block or carrying it home.
     * A null footprint permits only delivery of already recovered cargo. */
    public static boolean tick(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,BuilderZones.Layout footprint){
        Task task=tasks.computeIfAbsent(worker,k->new Task());
        long now=level.getGameTime();
        if(worker.hasRecoveredDrops()){
            release(worker,table,task);
            BlockPos depot=table.builderUnloadingStorage();
            if(depot==null)depot=table.getBlockPos();
            if(BuilderReturnService.approach(level,worker,depot,.87))return true;
            var items=List.of(worker.recoveredDrops());
            if(!BuilderSupplyInventory.storeDebris(table,items,false)){
                table.setProblem("OnSite Storage full: Builder carrying recovered blocks");
                return true;
            }
            if(BuilderSupplyInventory.storeDebris(table,items,true)){
                worker.clearRecoveredDrops();
                task.nextScan=now+35;
            }
            return true;
        }
        if(task.target!=null){
            ItemEntity item=task.target;
            if(!item.isAlive()||item.getItem().isEmpty()
                    ||now-task.since>180){
                if(item.isAlive())ignored.computeIfAbsent(table,k->new HashMap<>())
                        .put(item.getUUID(),now+400);
                release(worker,table,task);
                return false;
            }
            if(worker.distanceToSqr(item)<2.3 &&worker.hasLineOfSight(item)){
                var stack=item.getItem().copy();
                if(!BuilderSupplyInventory.storeDebris(table,List.of(stack),false)){
                    release(worker,table,task);return false;
                }
                worker.holdRecoveredDrops(stack);
                item.discard();
                release(worker,table,task);
                return true;
            }
            if(task.stand!=null)
                WorkerNavigation.routeTo(level,worker,task.stand,.88);
            return true;
        }
        if(footprint==null||now<task.nextScan)return false;
        task.nextScan=now+60;
        // Entity queries do not generate new chunks. Stay within the
        // actual X/Z schematic outline plus a small dropped-item margin.
        AABB site=new AABB(footprint.minX()-2,level.getMinY(),
                footprint.minZ()-2,footprint.maxX()+3,
                level.getMaxY()+1,footprint.maxZ()+3);
        Map<UUID,UUID> reserved=claims.computeIfAbsent(table,k->new HashMap<>());
        Map<UUID,Long> suppressed=ignored.computeIfAbsent(table,k->new HashMap<>());
        List<ItemEntity> candidates=level.getEntitiesOfClass(ItemEntity.class,
                site,entity->entity.isAlive()
                        &&entity.getItem().getItem() instanceof BlockItem
                        &&!reserved.containsKey(entity.getUUID())
                        &&suppressed.getOrDefault(entity.getUUID(),0L)<=now);
        candidates.sort(Comparator.comparingDouble(worker::distanceToSqr));
        for(int i=0;i<Math.min(12,candidates.size());i++){
            ItemEntity item=candidates.get(i);
            if(!BuilderSupplyInventory.storeDebris(table,
                    List.of(item.getItem()),false))continue;
            BlockPos stand=item.blockPosition();
            if(!WorkerNavigation.canStandOn(level,worker,stand))continue;
            if(!WorkerNavigation.routeTo(level,worker,stand,.88))continue;
            task.target=item;task.stand=stand;task.since=now;
            reserved.put(item.getUUID(),worker.getUUID());
            return true;
        }
        return false;
    }
}
