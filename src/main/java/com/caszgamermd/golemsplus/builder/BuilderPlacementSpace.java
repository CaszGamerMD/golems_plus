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
    private record Yield(BlockPos target,BlockPos destination,
            long until,boolean placingWorker){}
    private static final Map<BuilderGolem,Yield> yielding=new WeakHashMap<>();
    // A work target must keep the same approach position across successive
    // 12-tick updates. Recomputing the nearest spot from a moving golem
    // makes it switch sides every few steps and continually resets A*.
    private record Approach(BlockPos target,BlockPos destination,long until){}
    private static final Map<BuilderGolem,Approach> workApproaches=
            new WeakHashMap<>();

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
        if(!worker.isAlive()||level.getGameTime()>=hold.until()
                ||!occupied(hold.target(),worker)
                    &&(hold.placingWorker()
                        ||!level.getBlockState(hold.target()).isAir())){
            // Once the placement exists, its physical collision prevents
            // anyone from walking back into the same block. Resume work
            // immediately instead of waiting out the full yield window.
            yielding.remove(worker);
            return false;
        }
        // Yielded Builders keep walking away even when their assigned
        // construction task would otherwise repeatedly override the path.
        if(occupied(hold.target(),worker)
                ||worker.distanceToSqr(hold.destination().getX()+.5,
                        hold.destination().getY(),hold.destination().getZ()+.5)>2.25)
            WorkerNavigation.routeToExact(level,worker,hold.destination(),.9);
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
            WorkerStationBlockEntity table,BlockPos target){
        BlockPos origin=worker.blockPosition();
        List<BlockPos> choices=new ArrayList<>();
        // Probe a compact, predictable set of cardinal/diagonal safe
        // standing locations instead of inspecting hundreds of cells on
        // every 12-tick build update (which itself caused visible freezes).
        // Include straight aisle exits. The old corner-only scan tried
        // (radius,radius) diagonals and missed open cardinal paths along a
        // wall, leaving builders stalled despite clear floor two blocks away.
        // Eight directions x three heights x three radii is bounded; only
        // the best candidates below perform expensive navigation probes.
        for(int radius=2;radius<=4;radius++){
            int[][] offsets={
                    {radius,0},{-radius,0},{0,radius},{0,-radius},
                    {radius,radius},{radius,-radius},
                    {-radius,radius},{-radius,-radius}
            };
            for(int[] direction:offsets)
                for(int dy:new int[]{0,1,-1}){
                    BlockPos p=target.offset(direction[0],dy,direction[1]);
                    if(canStand(level,worker,p,target))choices.add(p);
                }
        }
        choices.sort(java.util.Comparator.comparingDouble(p->
                p.distSqr(origin)+Math.abs(p.getY()-origin.getY())*2));
        int attempted=0;
        for(BlockPos p:choices){
            if(++attempted>16)break; // bounded path work; try more than one aisle side
            // Favor a physically reachable tile; don't assume an air
            // candidate is reachable around walls/stairs/racks.
            if(WorkerNavigation.canReachExact(level,worker,p))return p.immutable();
        }
        // A suspended scaffold catwalk is a real floor for a Builder but
        // not an ordinary GroundPathNavigation node. It may be the ONLY
        // available safe sidestep along an unfinished dome overhang.
        BlockPos best=null;
        double score=Double.POSITIVE_INFINITY;
        for(BlockPos platform:table.scaffoldPositions(worker.getUUID())){
            BlockPos candidate=platform.above();
            if(!level.hasChunkAt(candidate)
                    ||!level.getBlockState(platform).is(
                            net.minecraft.world.level.block.Blocks.SCAFFOLDING)
                    ||Math.abs(candidate.getY()-worker.getY())>1.1
                    ||Math.abs(candidate.getX()-origin.getX())>1
                    ||Math.abs(candidate.getZ()-origin.getZ())>1
                    ||candidate.distSqr(target)<2.4
                    ||!level.getBlockState(candidate).getCollisionShape(
                            level,candidate).isEmpty()
                    ||!level.getBlockState(candidate.above()).getCollisionShape(
                            level,candidate.above()).isEmpty())continue;
            AABB shifted=worker.getBoundingBox().move(
                    candidate.getX()+.5-worker.getX(),
                    candidate.getY()-worker.getY(),
                    candidate.getZ()+.5-worker.getZ());
            if(shifted.intersects(volume(target))
                    ||!level.noCollision(worker,shifted))continue;
            double d=worker.distanceToSqr(candidate.getX()+.5,
                    candidate.getY(),candidate.getZ()+.5);
            if(d<score){score=d;best=candidate;}
        }
        return best;
    }

    /** Find an EXACT reachable work position within placement reach.
     * The evacuation method intentionally requires >=1.8 blocks from a
     * target, and the general routeTo() accepts approximate arrival. Neither
     * is valid for construction: stopping two blocks short of a distant
     * "safe" escape cell leaves a Builder idle five blocks from its target.
     * This probe is bounded and cached until target or terrain changes.
     */
    public static BlockPos approach(ServerLevel level,BuilderGolem worker,
            BlockPos target){
        Approach cached=workApproaches.get(worker);
        if(cached!=null&&cached.target().equals(target)
                &&cached.until()>level.getGameTime()
                &&cached.destination().distSqr(target)<=9
                &&WorkerNavigation.canStandOn(level,worker,cached.destination()))
            return cached.destination();
        List<BlockPos> candidates=new ArrayList<>();
        // Nearest cardinal cells first, including legal one-block steps,
        // then a two-block perimeter. These are work positions, not the
        // larger stand-clear buffer used when vacating a new solid block.
        for(int radius=1;radius<=3;radius++)
            for(int dx=-radius;dx<=radius;dx++)
                for(int dz=-radius;dz<=radius;dz++){
                    if(Math.max(Math.abs(dx),Math.abs(dz))!=radius)continue;
                    for(int dy:new int[]{0,1,-1}){
                        BlockPos p=target.offset(dx,dy,dz);
                        if(p.distSqr(target)>9
                                ||!WorkerNavigation.canStandOn(level,worker,p))
                            continue;
                        AABB shifted=worker.getBoundingBox().move(
                                p.getX()+.5-worker.getX(),p.getY()-worker.getY(),
                                p.getZ()+.5-worker.getZ());
                        if(shifted.intersects(volume(target))
                                ||!level.noCollision(worker,shifted))continue;
                        candidates.add(p);
                    }
                }
        candidates.sort(java.util.Comparator
                .comparingDouble((BlockPos p)->p.distSqr(worker.blockPosition())
                        +Math.abs(p.getY()-worker.blockPosition().getY())*2)
                .thenComparingDouble(p->p.distSqr(target)));
        int attempted=0;
        for(BlockPos p:candidates){
            if(++attempted>16)break;
            if(!WorkerNavigation.canReachExact(level,worker,p))continue;
            workApproaches.put(worker,new Approach(target,p.immutable(),
                    level.getGameTime()+100));
            return p.immutable();
        }
        workApproaches.remove(worker);
        return null;
    }

    /** The physical position is not fixed forever: a player or teammate
     * can obstruct it after path calculation. Drop ONLY this worker's
     * approach and re-search without disturbing any other Builder.
     */
    public static void invalidateApproach(BuilderGolem worker,BlockPos target){
        Approach approach=workApproaches.get(worker);
        if(approach!=null&&approach.target().equals(target))
            workApproaches.remove(worker);
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
                    g->g.isAlive()&&!(g instanceof BuilderGolem builder
                            &&table.builderWorkerIds().contains(builder.getUUID())));
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
            BlockPos destination=escape(level,blocker,table,target);
            if(destination==null){
                found=false;
                continue;
            }
            yielding.put(blocker,new Yield(target,destination,
                    level.getGameTime()+60,blocker==placing));
            if(!BuilderScaffoldBridge.sidestep(level,blocker,table,destination))
                WorkerNavigation.routeToExact(level,blocker,destination,.9);
        }
        table.setProblem(found
                ?"Clearing placement space "+target.toShortString()
                        +" (Builders stepping aside)"
                :"Waiting for safe standing space near "+target.toShortString());
        return false;
    }
}
