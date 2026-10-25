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
    private static final int MAX_SPAN=20; // real supported piers every five blocks
    private static final int MAX_SEARCH=20;
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
    /** Move one legal step along an already paid-for scaffold platform.
     * Placement clearance uses this instead of vanilla ground navigation,
     * which cannot recognize suspended scaffold tiles as standing floor.
     */
    public static boolean sidestep(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,BlockPos feet){
        BlockPos support=feet.below();
        boolean scaffold=level.getBlockState(support).is(Blocks.SCAFFOLDING)
                &&table.scaffoldPositions(worker.getUUID()).contains(support);
        boolean floor=com.caszgamermd.golemsplus.worker.WorkerNavigation
                .canStandOn(level,worker,feet);
        if(!level.hasChunkAt(support)||!(scaffold||floor)
                ||worker.distanceToSqr(feet.getX()+.5,
                        feet.getY(),feet.getZ()+.5)>4.2)
            return false;
        walking.put(worker,new Walk(support,level.getGameTime()+100));
        return true;
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
                    &&!com.caszgamermd.golemsplus.worker.WorkerNavigation
                            .canStandOn(level,worker,platform.above())
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

    /** Can this particular bridge node receive a real vertical support
     * within eight blocks, without taking space reserved by the schematic?
     * A horizontal span longer than vanilla's limit needs a new foundation.
     */
    private static boolean pierFeasible(ServerLevel level,ServerPlayer owner,
            BlockPos platform,Set<BlockPos> future,Set<BlockPos> owned){
        for(int depth=1;depth<=8;depth++){
            BlockPos below=platform.below(depth);
            if(!level.hasChunkAt(below)||future.contains(below)
                    ||!level.mayInteract(owner,below))return false;
            var state=level.getBlockState(below);
            if(state.isFaceSturdy(level,below,Direction.UP))return true;
            if(!state.isAir()&&!state.is(Blocks.SCAFFOLDING))return false;
            if(state.is(Blocks.SCAFFOLDING)&&!owned.contains(below))
                return false;
        }
        return false;
    }

    /** Construct a real footing below every fifth suspended walkway node.
     * One real material is transferred and placed per call; never create
     * the pillar all at once at a distance from the Builder.
     */
    private static BuilderScaffolding.Result addPier(ServerLevel level,
            BuilderGolem worker,WorkerStationBlockEntity table,
            ServerPlayer owner,BlockPos at,
            List<BuilderConstructionPlan.Step> plan){
        Set<BlockPos> forbidden=new HashSet<>();
        for(var step:plan)if(!step.ignorePlant())forbidden.add(
                new BlockPos(step.x(),step.y(),step.z()));
        Set<BlockPos> owned=new HashSet<>(table.scaffoldPositions(worker.getUUID()));
        if(!pierFeasible(level,owner,at,forbidden,owned))
            return BuilderScaffolding.Result.NO_SAFE_TOWER;
        int footingY=at.getY()-9;
        for(int depth=1;depth<=8;depth++){
            BlockPos under=at.below(depth);
            if(level.getBlockState(under).isFaceSturdy(level,under,
                    Direction.UP)){
                footingY=under.getY();break;
            }
        }
        for(int y=footingY+1;y<at.getY();y++){
            BlockPos segment=new BlockPos(at.getX(),y,at.getZ());
            if(level.getBlockState(segment).is(Blocks.SCAFFOLDING)
                    &&owned.contains(segment))continue;
            if(!level.getBlockState(segment).isAir()
                    ||forbidden.contains(segment)
                    ||worker.distanceToSqr(segment.getX()+.5,
                            segment.getY()+.5,segment.getZ()+.5)>14)
                return BuilderScaffolding.Result.NO_SAFE_TOWER;
            var stock=BuilderSupplyInventory.loadScaffold(level,worker,table,1);
            if(stock==BuilderSupplyInventory.ScaffoldLoad.MOVING)
                return BuilderScaffolding.Result.CLIMBING;
            if(stock==BuilderSupplyInventory.ScaffoldLoad.MISSING)
                return BuilderScaffolding.Result.NEED_MATERIALS;
            var receipt=BuilderSupplyInventory.consume(worker,Items.SCAFFOLDING);
            if(receipt==null)return BuilderScaffolding.Result.NEED_MATERIALS;
            if(!level.setBlock(segment,Blocks.SCAFFOLDING.defaultBlockState(),3)){
                BuilderSupplyInventory.refund(table,receipt);
                return BuilderScaffolding.Result.NO_SAFE_TOWER;
            }
            if(!table.trackScaffold(worker.getUUID(),segment)){
                level.setBlock(segment,Blocks.AIR.defaultBlockState(),3);
                BuilderSupplyInventory.refund(table,receipt);
                return BuilderScaffolding.Result.NO_SAFE_TOWER;
            }
            return BuilderScaffolding.Result.CLIMBING;
        }
        return BuilderScaffolding.Result.NOT_NEEDED;
    }

    private static List<BlockPos> search(ServerLevel level,ServerPlayer owner,
            WorkerStationBlockEntity table,BuilderGolem worker,
            BlockPos anchor,BlockPos target,Set<BlockPos> future,
            Set<BlockPos> owned,BuilderScaffoldShell.Blueprint shell){
        if(!owned.contains(anchor)||Math.abs(anchor.getY()+1-target.getY())>1)
            return List.of();
        // Profile the model in both vertical axes first. The search goal
        // is a safe WORK PLATFORM outside the finished silhouette, not the
        // placement cell itself (which would collide with the Builder).
        var sectionStations=BuilderScaffoldSections.stations(
                future,target,anchor.getY());
        Set<BlockPos> workFloors=new HashSet<>();
        // The complete blueprint already contains a one-wide OUTER
        // walkway around every level, including overhangs and corners.
        // Favor that route instead of searching directly toward a wall.
        for(BlockPos floor:shell.atHeight(anchor.getY())){
            int dx=floor.getX()-target.getX(),dz=floor.getZ()-target.getZ();
            int dy=floor.getY()+1-target.getY();
            if(dx*dx+dz*dz+dy*dy<=12.5)workFloors.add(floor);
        }
        if(workFloors.isEmpty())
            for(var station:sectionStations)workFloors.add(station.scaffold());
        if(workFloors.isEmpty()){
            failures.put(worker,"No unobstructed X/Z section work platforms");
            return List.of();
        }
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
            if(workFloors.contains(current)
                    &&dx*dx+dz*dz+dy*dy<=12.5){
                winner=current;
                break;
            }
            if(distance>=MAX_SPAN)continue;
            // Keep true breadth-first order; addFirst(shell) caused a
            // depth-first bias, marking nodes reached by long winding
            // routes before their short approaches. Sort ONLY siblings.
            List<BlockPos> neighbors=new ArrayList<>(4);
            for(int[] dir:DIRECTIONS)
                neighbors.add(current.offset(dir[0],0,dir[1]));
            neighbors.sort(Comparator
                    .comparingInt((BlockPos node)->shell.isShell(node)?0:1)
                    .thenComparingDouble(node->node.distSqr(target)));
            for(BlockPos next:neighbors){
                if(parent.containsKey(next)
                        ||Math.abs(next.getX()-anchor.getX())>MAX_SEARCH
                        ||Math.abs(next.getZ()-anchor.getZ())>MAX_SEARCH
                        ||!open(level,owner,table,worker,next,future,owned)
                        ||(distance+1)%5==0
                            &&!pierFeasible(level,owner,next,future,owned))
                    continue;
                parent.put(next,current);
                queue.addLast(next);
            }
        }
        if(winner==null||winner.equals(anchor)){
            double nearest=parent.keySet().stream().mapToDouble(p->
                    Math.sqrt(p.distSqr(target))).min().orElse(-1);
            failures.put(worker,"BFS nodes="+parent.size()
                    +" nearest="+String.format(java.util.Locale.ROOT,"%.1f",nearest)
                    +" anchor="+anchor.toShortString());
            return List.of();
        }
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
            // Every planned solid cell, not just the bridge layer: a
            // stabilizing support post must never overwrite later walls.
            for(var step:plan)if(!step.ignorePlant())
                future.add(new BlockPos(step.x(),step.y(),step.z()));
            // Build the virtual entire-height shell ONCE per Planner
            // plan. No materials are spent just to compute the graph.
            var shell=BuilderScaffoldShell.forWorksite(table,plan);
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
                        top,target,future,owned,shell);
                if(candidate.isEmpty())continue;
                // Prefer a short, physical route that stays on the
                // planned exterior shell; penalize needless detours across
                // unrelated open ground.
                int outside=0;
                for(BlockPos node:candidate)if(!shell.isShell(node))outside++;
                double cost=candidate.size()*10+outside*3
                        +Math.sqrt(worker.distanceToSqr(top.getX()+.5,
                                top.getY()+1,top.getZ()+.5));
                if(cost<score){score=cost;best=candidate;}
            }
            if(best.isEmpty()){
                failures.put(worker,"NO_BRIDGE owned="+owned.size()
                        +" tops="+tops.size()+" compatible="+matchingTops
                        +" targetY="+target.getY()+" "
                        +failures.getOrDefault(worker,"no search"));
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
            if(next%5==0){
                var support=addPier(level,worker,table,owner,to,plan);
                if(support!=BuilderScaffolding.Result.NOT_NEEDED)return support;
            }
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
