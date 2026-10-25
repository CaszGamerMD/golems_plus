package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * A Builder must not place a solid schematic cell through its own body or
 * another worker. Nearby workers step off the target first, with their
 * construction/courier task temporarily held so its normal navigation does
 * not immediately send them back into the block about to be placed.
 *
 * No teleportation and no fake blocks: the location must be navigable with
 * actual ground and free collision. If someone cannot safely vacate, the
 * original step stays claimed and its materials are not consumed.
 */
public final class BuilderPlacementSpace {
    private BuilderPlacementSpace(){}
    private record Yield(BlockPos target,BlockPos destination,long until){}
    private static final Map<BuilderGolem,Yield> yielding=new WeakHashMap<>();

    private static AABB volume(BlockPos pos){
        // Horizontal breathing room prevents a Golem's shoulder/edge
        // clipping into a new full block. Do not inflate vertically: an
        // entity whose feet are already on TOP of the completed surface
        // does not obstruct that lower block.
        return new AABB(pos).inflate(.10,0,.10);
    }

    private static boolean occupied(BlockPos target,BuilderGolem worker){
        return worker.isAlive()&&worker.getBoundingBox().intersects(volume(target));
    }

    /** Run before ANY ordinary Builder assignment/courier/navigation tick. */
    public static boolean yieldTick(ServerLevel level,BuilderGolem worker){
        Yield hold=yielding.get(worker);
        if(hold==null)return false;
        if(!worker.isAlive()||level.getGameTime()>=hold.until()){
            yielding.remove(worker);
            return false;
        }
        // Yielded Builders keep walking away even when their assigned
        // construction task would otherwise repeatedly override the path.
        if(occupied(hold.target(),worker)
                ||worker.distanceToSqr(hold.destination().getX()+.5,
                        hold.destination().getY(),hold.destination().getZ()+.5)>2.25)
            WorkerNavigation.routeTo(level,worker,hold.destination(),.9);
        else if(!worker.getNavigation().isDone())
            worker.getNavigation().stop();
        return true;
    }

    private static boolean canStand(ServerLevel level,BuilderGolem worker,
            BlockPos at,BlockPos obstructed){
        if(at.closerThan(obstructed,1.8)
                ||!level.hasChunkAt(at)||!level.hasChunkAt(at.above())
                ||!level.hasChunkAt(at.below()))return false;
        BlockState feet=level.getBlockState(at);
        BlockState above=level.getBlockState(at.above());
        BlockState below=level.getBlockState(at.below());
        if(!level.getFluidState(at).isEmpty()
                ||!feet.getCollisionShape(level,at).isEmpty()
                ||!above.getCollisionShape(level,at.above()).isEmpty()
                ||below.getCollisionShape(level,at.below()).isEmpty())
            return false;
        AABB shifted=worker.getBoundingBox().move(
                at.getX()+.5-worker.getX(),at.getY()-worker.getY(),
                at.getZ()+.5-worker.getZ());
        if(shifted.intersects(volume(obstructed))
                ||!level.noCollision(worker,shifted))return false;
        for(var other:yielding.entrySet())
            if(other.getKey()!=worker
                    &&other.getValue().destination().equals(at)
                    &&other.getValue().until()>level.getGameTime())
                return false;
        return true;
    }

    private static BlockPos escape(ServerLevel level,BuilderGolem worker,
            BlockPos target){
        BlockPos origin=worker.blockPosition();
        List<BlockPos> choices=new ArrayList<>();
        for(int radius=2;radius<=4;radius++)
            for(int dx=-radius;dx<=radius;dx++)
                for(int dz=-radius;dz<=radius;dz++){
                    if(Math.max(Math.abs(dx),Math.abs(dz))!=radius)continue;
                    for(int dy=-1;dy<=1;dy++){
                        BlockPos p=target.offset(dx,dy,dz);
                        if(canStand(level,worker,p,target))choices.add(p);
                    }
                }
        choices.sort(java.util.Comparator.comparingDouble(p->
                p.distSqr(origin)+Math.abs(p.getY()-origin.getY())*2));
        for(BlockPos p:choices){
            // Favor a physically reachable tile; don't assume an air
            // candidate is reachable around walls/stairs/racks.
            var path=worker.getNavigation().createPath(p,0);
            if(path!=null&&path.canReach())return p.immutable();
        }
        return null;
    }

    /** @return true only when the future block volume is clear of workers. */
    public static boolean ready(ServerLevel level,BuilderGolem placing,
            WorkerStationBlockEntity table,BlockPos target){
        AABB area=volume(target);
        List<BuilderGolem> blockers=level.getEntitiesOfClass(
                BuilderGolem.class,area,
                g->g.isAlive()&&table.builderWorkerIds().contains(g.getUUID()));
        if(blockers.isEmpty()){
            // A player/other mob must also never be suffocated by placement.
            List<LivingEntity> others=level.getEntitiesOfClass(
                    LivingEntity.class,area,
                    g->g.isAlive()&&!(g instanceof BuilderGolem));
            if(others.isEmpty())return true;
            table.setProblem("Waiting for entity to vacate construction space "
                    +target.toShortString());
            return false;
        }
        boolean found=true;
        for(BuilderGolem blocker:blockers){
            Yield old=yielding.get(blocker);
            if(old!=null&&old.target().equals(target)
                    &&old.until()>level.getGameTime())continue;
            BlockPos destination=escape(level,blocker,target);
            if(destination==null){
                found=false;
                continue;
            }
            yielding.put(blocker,new Yield(target,destination,
                    level.getGameTime()+100));
            WorkerNavigation.routeTo(level,blocker,destination,.9);
        }
        table.setProblem(found
                ?"Clearing placement space "+target.toShortString()
                        +" (Builders stepping aside)"
                :"Waiting for safe standing space near "+target.toShortString());
        return false;
    }
}
