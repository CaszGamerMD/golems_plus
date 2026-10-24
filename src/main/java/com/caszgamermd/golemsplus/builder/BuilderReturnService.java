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
    public static boolean tick(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table){
        if(!(table.phase()==com.caszgamermd.golemsplus.worker.WorkerPhase.COMPLETE
                ||table.builderRecallRequested())
                ||table.activeOrder()!=null||table.supplyRequest()!=null
                ||worker.hasSupplyBox()||!worker.isEmpty()
                ||table.claimedStep(worker.getUUID())>=0)return false;
        // Avoid 31k-block bin scans every 12-tick worker update.
        if(level.getGameTime()%80!=0)return false;
        BlockPos home=table.getBlockPos();
        UnemployedCenterBlockEntity closest=null;
        double distance=Double.MAX_VALUE;
        for(int dx=-24;dx<=24;dx++)for(int dy=-6;dy<=6;dy++)
            for(int dz=-24;dz<=24;dz++){
                BlockPos pos=home.offset(dx,dy,dz);
                if(!level.hasChunkAt(pos))continue;
                double d=home.distSqr(pos);
                if(d>=distance||d>24*24)continue;
                if(level.getBlockEntity(pos) instanceof UnemployedCenterBlockEntity bin){
                    closest=bin;distance=d;
                }
            }
        if(closest==null)return false; // Stay a real entity if no local bin exists.
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
