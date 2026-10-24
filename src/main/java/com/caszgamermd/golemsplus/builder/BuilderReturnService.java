package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.UnemployedCenterBlockEntity;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/** Lose no Builder tools, cargo, identity or workers on completion.
 * The recycling bin only accepts the actual worker item AFTER cargo is
 * physically unloaded. There is never a virtual/unbounded worker pool.
 */
public final class BuilderReturnService {
    private BuilderReturnService(){}
    // One nearest-bin search per job; eight crew do not each scan thirty
    // thousand BlockPos every four seconds. Weak keys avoid world leaks.
    private static final java.util.Map<WorkerStationBlockEntity,BlockPos> bins=
            new java.util.WeakHashMap<>();
    private static final java.util.Map<WorkerStationBlockEntity,Long> searchAfter=
            new java.util.WeakHashMap<>();
    public static boolean tick(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table){
        if(!(table.phase()==com.caszgamermd.golemsplus.worker.WorkerPhase.COMPLETE
                ||table.builderRecallRequested())
                ||table.activeOrder()!=null||table.supplyRequest()!=null
                ||worker.hasSupplyBox()||!worker.isEmpty()
                ||table.claimedStep(worker.getUUID())>=0)return false;
        BlockPos home=table.getBlockPos();
        UnemployedCenterBlockEntity closest=null;
        BlockPos cached=bins.get(table);
        if(cached!=null&&level.hasChunkAt(cached)
                &&level.getBlockEntity(cached) instanceof UnemployedCenterBlockEntity bin)
            closest=bin;
        if(closest==null){
            long now=level.getGameTime();
            if(now<searchAfter.getOrDefault(table,0L))return false;
            searchAfter.put(table,now+120);
            bins.remove(table);
            double distance=Double.MAX_VALUE;
            for(int dx=-24;dx<=24;dx++)for(int dy=-6;dy<=6;dy++)
                for(int dz=-24;dz<=24;dz++){
                    BlockPos pos=home.offset(dx,dy,dz);
                    double d=home.distSqr(pos);
                    if(d>=distance||d>24*24||!level.hasChunkAt(pos))continue;
                    if(level.getBlockEntity(pos) instanceof UnemployedCenterBlockEntity bin){
                        closest=bin;distance=d;
                    }
                }
            if(closest!=null)bins.put(table,closest.getBlockPos());
        }
        if(closest==null){
            table.setProblem("Recycling crew: place a blue Unemployed Center "
                    +"within 24 blocks of the Builder Planner");
            return false; // No bin: never delete the real golem.
        }
        BlockPos pos=closest.getBlockPos();
        if(worker.distanceToSqr(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)>10){
            com.caszgamermd.golemsplus.worker.WorkerNavigation.routeTo(
                    level,worker,pos,.82);
            return true;
        }
        ItemStack stored=new ItemStack(WorksiteContent.BUILDER_WORKER_ITEM);
        if(worker.hasCustomName())stored.set(DataComponents.CUSTOM_NAME,
                worker.getCustomName());
        // Commit item into the actual 27-slot bin first, then unlink and
        // discard the now empty entity. No disappearance when the bin is full.
        if(!closest.acceptWorker(stored)){
            table.setProblem("Unemployed Center full: free space for the Builder crew");
            return true;
        }
        table.retireBuilderWorker(worker.getUUID());
        worker.discard();
        return true;
    }
}
