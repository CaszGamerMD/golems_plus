package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Physical temporary catwalks for a Builder blocked by an overhang.
 * A route is a chain of real, owned scaffold blocks at one elevation,
 * beginning at the top of an EXISTING owned tower. Four-way BFS permits
 * turns around protruding walls; no teleport or imaginary walk nodes.
 *
 * Only one scaffold is placed from within interaction range at a time.
 * The worker walks to each confirmed, supported segment before extending
 * the next. The Builder's inventory and its Planner still own every item.
 */
public final class BuilderScaffoldBridge {
    private static final int MAX_SPAN=6; // vanilla unsupported horizontal scaffold limit
    private static final int MAX_SEARCH=11;
    private static final int[][] DIRECTIONS={{1,0},{-1,0},{0,1},{0,-1}};
    private record Route(BlockPos target,List<BlockPos> cells,int next,long created){}
    private record Walk(BlockPos destination,long expires){}
    private static final Map<BuilderGolem,Route> routes=new WeakHashMap<>();
    private static final Map<BuilderGolem,Walk> walking=new WeakHashMap<>();
    private static final Map<BuilderGolem,String> failures=new WeakHashMap<>();

    private BuilderScaffoldBridge(){}

    public static boolean moving(BuilderGolem worker){
        return walking.containsKey(worker);
    }
    public static void cancel(BuilderGolem worker){
        routes.remove(worker);
        walking.remove(worker);
        failures.remove(worker);
    }
    /** Retrace an already built bridge in reverse before descending a
     * tower or returning to the real OnSite chest. No aerial shortcuts:
     * every step is an existing, owner-tracked scaffold under the feet.
     */
    public static boolean returnToAnchor(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table){
        if(walking.containsKey(worker))return true;
        Route route=routes.get(worker);
        if(route==null)return false;
        List<BlockPos> path=route.cells();
        int nearest=-1;
        double best=1.75;
        for(int i=0;i<path.size();i++){
            BlockPos p=path.get(i);
            if(!level.hasChunkAt(p)
                    ||!level.getBlockState(p).is(Blocks.SCAFFOLDING)
                    ||!table.scaffoldPositions(worker.getUUID()).contains(p))
                continue;
            double dx=worker.getX()-p.getX()-.5;
            double dz=worker.getZ()-p.getZ()-.5;
            double dy=worker.getY()-(p.getY()+1);
            double score=dx*dx+dz*dz+dy*dy;
            if(score<best){best=score;nearest=i;}
        }
        if(nearest<0)return false;
        if(nearest==0){
            routes.remove(worker);
            return false;
        }
        BlockPos previous=path.get(nearest-1);
        if(!level.getBlockState(previous).is(Blocks.SCAFFOLDING)){
            routes.remove(worker);
            return false;
        }
        walking.put(worker,new Walk(previous,level.getGameTime()+100));
        return true;
    }

    public static String debug(BuilderGolem worker){
        Walk moving=walking.get(worker);
        Route route=routes.get(worker);
        return moving!=null?"BRIDGE WALK "+moving.destination().toShortString()
                :route==null?failures.getOrDefault(worker,"NONE")
                        :"BRIDGE "+route.next()+"/"+route.cells().size();
    }

    /** Steering uses normal gravity and the real collision box, and runs
     * every entity tick. This avoids vanilla A* treating a scaffold top as
     * a nonexistent floor and repeatedly cancelling a valid sideways path.
     */
    public static boolean tickMotion(ServerLevel level,BuilderGolem worker){
        Walk walk=walking.get(worker);
        if(walk==null)return false;
        BlockPos platform=walk.destination();
        if(level.getGameTime()>walk.expires()
                ||!level.hasChunkAt(platform)
                ||!level.getBlockState(platform).is(Blocks.SCAFFOLDING)
                ||worker.getY()<platform.getY()+.35
                ||worker.getY()>platform.getY()+2){
            walking.remove(worker);
            routes.remove(worker);
            return false;
        }
        double tx=platform.getX()+.5,tz=platform.getZ()+.5;
        double dx=tx-worker.getX(),dz=tz-worker.getZ();
        if(dx*dx+dz*dz<.10
                &&Math.abs(worker.getY()-platform.getY()-1)<.55){
            walking.remove(worker);
            worker.getNavigation().stop();
            worker.setDeltaMovement(0,worker.getDeltaMovement().y,0);
            return false;
        }
        worker.getNavigation().stop();
        worker.setNoGravity(false);
        worker.setShiftKeyDown(false);
        worker.getMoveControl().setWantedPosition(tx,platform.getY()+1,tz,1);
        double vx=Math.max(-.13,Math.min(.13,dx*.24));
        double vz=Math.max(-.13,Math.min(.13,dz*.24));
        if(level.noCollision(worker,worker.getBoundingBox().move(vx,0,vz))){
            var v=worker.getDeltaMovement();
            worker.setDeltaMovement(vx,v.y,vz);
        }
        return true;
    }

    // The candidate scaffold is BELOW the Builder's feet, not a block
    // occupying the target's body. Reject any schematic cell or obstruction,
    // and require empty two-block headroom for real entity collision.
    private static boolean open(ServerLevel level,ServerPlayer owner,
            WorkerStationBlockEntity table,BuilderGolem worker,BlockPos p,
            Set<BlockPos> future,Set<BlockPos> owned){
        if(!level.hasChunkAt(p)||!level.hasChunkAt(p.above())
                ||!level.hasChunkAt(p.above().above())
                ||!level.mayInteract(owner,p)||future.contains(p))return false;
        if(level.getBlockState(p).is(Blocks.SCAFFOLDING)){
            if(!owned.contains(p))return false;
        }else if(!level.getBlockState(p).isAir())return false;
        if(!level.getBlockState(p.above()).getCollisionShape(level,p.above()).isEmpty()
                ||!level.getBlockState(p.above().above())
                        .getCollisionShape(level,p.above().above()).isEmpty())
            return false;
        return true;
    }

    private static List<BlockPos> search(ServerLevel level,ServerPlayer owner,
            WorkerStationBlockEntity table,BuilderGolem worker,
            BlockPos anchor,BlockPos target,Set<BlockPos> future,
            Set<BlockPos> owned){
        if(!owned.contains(anchor)||Math.abs(anchor.getY()+1-target.getY())>1)
            return List.of();
        ArrayDeque<BlockPos> queue=new ArrayDeque<>();
        Map<BlockPos,BlockPos> parent=new HashMap<>();
        queue.add(anchor);
        parent.put(anchor,anchor);
        BlockPos winner=null;
        while(!queue.isEmpty()){
            BlockPos current=queue.removeFirst();
            int distance=0;
            for(BlockPos walk=current;!walk.equals(anchor);walk=parent.get(walk))
                distance++;
            double dx=current.getX()-target.getX(),dz=current.getZ()-target.getZ();
            double dy=(current.getY()+1)-target.getY();
            if(dx*dx+dz*dz+dy*dy<=12.5&&!current.equals(target)
                    &&!future.contains(current)){
                winner=current;
                break;
            }
            if(distance>=MAX_SPAN)continue;
            for(int[] dir:DIRECTIONS){
                BlockPos next=current.offset(dir[0],0,dir[1]);
                if(parent.containsKey(next)
                        ||Math.abs(next.getX()-anchor.getX())>MAX_SEARCH
                        ||Math.abs(next.getZ()-anchor.getZ())>MAX_SEARCH
                        ||!open(level,owner,table,worker,next,future,owned))
                    continue;
                parent.put(next,current);
                queue.addLast(next);
            }
        }
        if(winner==null||winner.equals(anchor))return List.of();
        List<BlockPos> result=new ArrayList<>();
        for(BlockPos p=winner;!p.equals(anchor);p=parent.get(p))
            result.add(p);
        result.add(anchor);
        java.util.Collections.reverse(result);
        return List.copyOf(result);
    }

    public static BuilderScaffolding.Result reach(ServerLevel level,
            BuilderGolem worker,WorkerStationBlockEntity table,
            ServerPlayer owner,BlockPos target,
            List<BuilderConstructionPlan.Step> plan){
        if(walking.containsKey(worker))return BuilderScaffolding.Result.CLIMBING;
        Route route=routes.get(worker);
        Set<BlockPos> owned=new HashSet<>(table.scaffoldPositions(worker.getUUID()));
        if(route!=null&&(!route.target().equals(target)
                ||level.getGameTime()-route.created()>1200
                ||route.cells().stream().anyMatch(p->
                    !level.hasChunkAt(p)
                    ||!level.getBlockState(p).is(Blocks.SCAFFOLDING)
                        &&!level.getBlockState(p).isAir()))){
            route=null;
            routes.remove(worker);
        }
        if(route==null){
            Set<BlockPos> future=new HashSet<>();
            int bridgeY=target.getY()-1;
            for(var step:plan)if(!step.ignorePlant()
                    &&step.y()==bridgeY)
                future.add(new BlockPos(step.x(),step.y(),step.z()));
            List<BlockPos> best=List.of();
            int matchingTops=0;
            double score=Double.POSITIVE_INFINITY;
            // Only genuine owner-tracked, existing tower tops can anchor a
            // bridge. Select the shortest turn-capable route around the wall.
            Map<Long,BlockPos> tops=new HashMap<>();
            for(BlockPos p:owned){
                if(!level.hasChunkAt(p)
                        ||!level.getBlockState(p).is(Blocks.SCAFFOLDING))continue;
                long key=BlockPos.asLong(p.getX(),0,p.getZ());
                BlockPos previous=tops.get(key);
                if(previous==null||previous.getY()<p.getY())tops.put(key,p);
            }
            for(BlockPos top:tops.values()){
                if(top.getY()!=bridgeY&&top.getY()!=bridgeY-1)continue;
                matchingTops++;
                List<BlockPos> candidate=search(level,owner,table,worker,
                        top,target,future,owned);
                if(candidate.isEmpty())continue;
                double cost=candidate.size()*10
                        +Math.sqrt(worker.distanceToSqr(top.getX()+.5,
                                top.getY()+1,top.getZ()+.5));
                if(cost<score){score=cost;best=candidate;}
            }
            if(best.isEmpty()){
                failures.put(worker,"NO_BRIDGE owned="+owned.size()
                        +" tops="+tops.size()+" compatible="+matchingTops
                        +" targetY="+target.getY());
                return BuilderScaffolding.Result.NO_SAFE_TOWER;
            }
            failures.remove(worker);
            route=new Route(target,best,1,level.getGameTime());
            routes.put(worker,route);
        }
        List<BlockPos> path=route.cells();
        // If returning from materials, physically climb the owned tower
        // before using the upper bridge. No remote item delivery.
        BlockPos anchor=path.get(0);
        if(worker.getY()<anchor.getY()+.82){
            // Re-enter the owned column even for the final ONE-block
            // ascent. BuilderScaffolding.reach() intentionally considers
            // 1.35 blocks "close enough" for placing, but a scaffold
            // bridge requires feet on the exact top before walking.
            double dx=worker.getX()-anchor.getX()-.5;
            double dz=worker.getZ()-anchor.getZ()-.5;
            if(dx*dx+dz*dz>1.2){
                BlockPos approach=null;
                double bestDistance=Double.POSITIVE_INFINITY;
                for(Direction side:Direction.Plane.HORIZONTAL){
                    BlockPos landing=new BlockPos(anchor.getX(),
                            worker.blockPosition().getY(),anchor.getZ())
                            .relative(side);
                    if(!com.caszgamermd.golemsplus.worker.WorkerNavigation
                            .canReachExact(level,worker,landing))continue;
                    double d=worker.distanceToSqr(landing.getX()+.5,
                            landing.getY(),landing.getZ()+.5);
                    if(d<bestDistance){bestDistance=d;approach=landing;}
                }
                if(approach!=null)
                    com.caszgamermd.golemsplus.worker.WorkerNavigation
                            .routeToExact(level,worker,approach,1.0);
                else com.caszgamermd.golemsplus.worker.WorkerNavigation
                            .routeTo(level,worker,anchor.below(),1.0);
                return BuilderScaffolding.Result.CLIMBING;
            }
            BlockPos base=anchor;
            for(BlockPos candidate:owned)
                if(candidate.getX()==anchor.getX()
                        &&candidate.getZ()==anchor.getZ()
                        &&candidate.getY()<base.getY())base=candidate;
            BuilderScaffolding.begin(level,worker,base,
                    anchor.getY()+1.0,false);
            return BuilderScaffolding.Result.CLIMBING;
        }
        int next=route.next();
        // A resume after server/path changes must never skip a real edge.
        // The next leg begins only if the worker has reached the preceding
        // physical scaffold, not just because the future platform exists.
        if(next>=path.size()){
            routes.remove(worker);
            return BuilderScaffolding.Result.NOT_NEEDED;
        }
        BlockPos from=path.get(next-1),to=path.get(next);
        if(worker.distanceToSqr(from.getX()+.5,from.getY()+1,
                from.getZ()+.5)>1.6){
            // On an already constructed branch, walk back toward the
            // previous known scaffold instead of spawning another block.
            if(owned.contains(from)&&level.getBlockState(from).is(Blocks.SCAFFOLDING)){
                walking.put(worker,new Walk(from,level.getGameTime()+100));
                return BuilderScaffolding.Result.CLIMBING;
            }
            routes.remove(worker);
            return BuilderScaffolding.Result.NO_SAFE_TOWER;
        }
        if(!level.getBlockState(to).is(Blocks.SCAFFOLDING)){
            if(!level.getBlockState(to).isAir()||owned.contains(to)){
                routes.remove(worker);
                return BuilderScaffolding.Result.NO_SAFE_TOWER;
            }
            var supply=BuilderSupplyInventory.loadScaffold(level,worker,table,1);
            if(supply==BuilderSupplyInventory.ScaffoldLoad.MOVING)
                return BuilderScaffolding.Result.CLIMBING;
            if(supply==BuilderSupplyInventory.ScaffoldLoad.MISSING)
                return BuilderScaffolding.Result.NEED_MATERIALS;
            // Physically adjacent to the worker's current scaffold, never
            // place the entire remote bridge in one operation.
            if(worker.distanceToSqr(to.getX()+.5,to.getY()+.5,
                    to.getZ()+.5)>14)return BuilderScaffolding.Result.NO_SAFE_TOWER;
            var receipt=BuilderSupplyInventory.consume(worker,Items.SCAFFOLDING);
            if(receipt==null)return BuilderScaffolding.Result.NEED_MATERIALS;
            if(!level.setBlock(to,Blocks.SCAFFOLDING.defaultBlockState(),3)){
                BuilderSupplyInventory.refund(table,receipt);
                return BuilderScaffolding.Result.NO_SAFE_TOWER;
            }
            if(!table.trackScaffold(worker.getUUID(),to)){
                level.setBlock(to,Blocks.AIR.defaultBlockState(),3);
                BuilderSupplyInventory.refund(table,receipt);
                routes.remove(worker);
                return BuilderScaffolding.Result.NO_SAFE_TOWER;
            }
        }else if(!owned.contains(to)){
            routes.remove(worker);
            return BuilderScaffolding.Result.NO_SAFE_TOWER;
        }
        routes.put(worker,new Route(target,path,next+1,route.created()));
        walking.put(worker,new Walk(to,level.getGameTime()+100));
        return BuilderScaffolding.Result.CLIMBING;
    }
}
