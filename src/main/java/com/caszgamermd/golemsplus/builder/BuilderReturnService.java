package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.UnemployedCenterBlockEntity;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.minecraft.core.BlockPos;
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
    private static final class Approach {
        BlockPos target,stand,excludedStand;
        long nextSearch,lastMovementTick;
        double lastX=Double.NaN,lastY=Double.NaN,lastZ=Double.NaN;
    }
    /** A route that keeps returning MOVING without actually displacing a
     * worker is not a valid forever-route. Don't abandon active scaffold,
     * grapple, or door crossing: only a stationary ordinary depot approach
     * is eligible for a fresh safe standing tile. */
    static boolean depotApproachStalled(long ticksSinceMovement,
            boolean verticalOrGrappleActive){
        return !verticalOrGrappleActive&&ticksSinceMovement>=120;
    }
    private static final java.util.Map<BuilderGolem,Approach> approaches=
            new java.util.WeakHashMap<>();
    /** Reach a walkable tile NEXT TO the solid container, not its interior.
     * Returns true while the worker is still outside pickup distance.
     */
    public static boolean approach(ServerLevel level,BuilderGolem worker,
            BlockPos target,double speed){
        if(target==null)return true;
        if(worker.distanceToSqr(target.getX()+.5,target.getY()+.5,
                target.getZ()+.5)<=10)return false;
        Approach route=approaches.computeIfAbsent(worker,ignored->new Approach());
        long now=level.getGameTime();
        if(!target.equals(route.target)){
            route.target=target.immutable();route.stand=null;route.nextSearch=0;
            route.excludedStand=null;
            route.lastMovementTick=now;
            route.lastX=worker.getX();
            route.lastY=worker.getY();
            route.lastZ=worker.getZ();
        }
        boolean mobilityActive=BuilderScaffolding.isControllingMovement(worker)
                ||BuilderGrappleAccess.active(worker);
        // An owned bridge, shaft, or rope is already executing a verified
        // physical return. The ordinary depot selector must not replace
        // its motion with a competing ground A* request.
        if(mobilityActive)return true;
        // Observe real physical displacement instead of accepting a
        // pathfinder's MOVING result indefinitely. A busy rope/scaffold
        // controller owns its motion; never interrupt that descent.
        double dx=worker.getX()-route.lastX;
        double dy=worker.getY()-route.lastY;
        double dz=worker.getZ()-route.lastZ;
        if(dx*dx+dy*dy+dz*dz>.25){
            route.lastMovementTick=now;
            route.lastX=worker.getX();
            route.lastY=worker.getY();
            route.lastZ=worker.getZ();
        }
        if(route.stand!=null
                &&depotApproachStalled(now-route.lastMovementTick,mobilityActive)){
            route.excludedStand=route.stand;
            route.stand=null;route.nextSearch=0;
            route.lastMovementTick=now;
            // Only ordinary depot navigation is canceled. No item
            // inventory or active vertical movement controller is touched.
            com.caszgamermd.golemsplus.worker.WorkerNavigation.stop(worker);
        }
        if(route.stand==null||!com.caszgamermd.golemsplus.worker.WorkerNavigation
                .canStandOn(level,worker,route.stand)){
            if(now<route.nextSearch)return true;
            route.nextSearch=now+30;
            java.util.List<BlockPos> possible=new java.util.ArrayList<>();
            // Candidate feet positions around a solid depot, including
            // one-step stairs. No fabricated platform or teleport.
            for(int dy=-1;dy<=1;dy++)
                for(int dx=-2;dx<=2;dx++)
                    for(int dz=-2;dz<=2;dz++){
                        if(dx==0&&dz==0||Math.abs(dx)==2&&Math.abs(dz)==2)
                            continue;
                        BlockPos feet=target.offset(dx,dy,dz);
                        if(level.hasChunkAt(feet)
                                &&com.caszgamermd.golemsplus.worker.WorkerNavigation
                                        .canStandOn(level,worker,feet))
                            possible.add(feet);
                    }
            possible.sort(java.util.Comparator.comparingDouble(p->
                    worker.distanceToSqr(p.getX()+.5,p.getY(),p.getZ()+.5)));
            if(possible.size()>16)possible=possible.subList(0,16);
            // A failed stand remains valid world geometry but may be
            // blocked by crew/cargo. Try another actual reachable side
            // rather than selecting the same obstructed tile forever.
            if(route.excludedStand!=null&&possible.size()>1){
                var alternatives=new java.util.ArrayList<>(possible);
                alternatives.remove(route.excludedStand);
                if(!alternatives.isEmpty())possible=alternatives;
            }
            route.stand=com.caszgamermd.golemsplus.worker.WorkerNavigation
                    .selectExecutableStand(level,worker,possible);
            if(route.stand==null)return true;
        }
        if(!com.caszgamermd.golemsplus.worker.WorkerNavigation.routeTo(
                level,worker,route.stand,speed)
                &&now>=route.nextSearch){
            route.stand=null;route.nextSearch=now+30;
        }
        return true;
    }
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
        if(approach(level,worker,pos,.82))return true;
        // The worker's named entity identity ends on recycling. The
        // Unemployed Center also normalizes items inserted manually.
        ItemStack stored=new ItemStack(WorksiteContent.BUILDER_WORKER_ITEM);
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
