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
 * A route is a chain of real, owned scaffold blocks across adjacent elevations,
 * beginning at the top of an EXISTING owned tower. 3D BFS permits
 * turns around protruding walls; no teleport or imaginary walk nodes.
 *
 * Only one scaffold is placed from within interaction range at a time.
 * The worker walks to each confirmed, supported segment before extending
 * the next. The Builder's inventory and its Planner still own every item.
 */
public final class BuilderScaffoldBridge {
    private static final int MAX_SPAN=48; // virtual flight path: extended scaffold corridor
    private static final int MAX_SEARCH=30;
    private static final int[][] DIRECTIONS={{1,0},{-1,0},{0,1},{0,-1}};
    private record Route(BlockPos target,List<BlockPos> cells,int next,long created){}
    private record Walk(BlockPos destination,long expires){}
    private static final Map<BuilderGolem,Route> routes=new WeakHashMap<>();
    private static final Map<BuilderGolem,Walk> walking=new WeakHashMap<>();
    private static final Map<BuilderGolem,String> failures=new WeakHashMap<>();
    // Native A* cannot walk on suspended scaffold. Track actual displacement
    // on each physically driven edge and briefly exclude an edge that is
    // blocked by a new wall, cargo entity or another worker.
    private record BlockedCell(BlockPos cell,long expires){}
    private static final Map<BuilderGolem,BlockedCell> blockedCells=
            new WeakHashMap<>();
    private static final class WalkMonitor {
        BlockPos destination;
        net.minecraft.world.phys.Vec3 last;
        long lastMoved;
        double bestRange;
        WalkMonitor(BlockPos destination,BuilderGolem worker,long tick){
            this.destination=destination;
            last=worker.position();
            lastMoved=tick;
            bestRange=worker.distanceToSqr(destination.getX()+.5,
                    destination.getY()+1.0,destination.getZ()+.5);
        }
    }
    private static final Map<BuilderGolem,WalkMonitor> walkMonitors=
            new WeakHashMap<>();

    /** Determine scaffold stability from REAL world supports before spending
     * a worker's item. Vanilla scaffolds fall at unsupported distance seven.
     * Cardinal detours and new pier columns must obey the same rule.
     */
    private static int supportedDistance(ServerLevel level,BlockPos at){
        BlockPos beneath=at.below();
        if(!level.hasChunkAt(at)||!level.hasChunkAt(beneath))
            return 7; // never load another chunk merely to plan a bridge
        var below=level.getBlockState(beneath);
        if(below.is(Blocks.SCAFFOLDING)
                ||below.isFaceSturdy(level,beneath,Direction.UP))return 0;
        int distance=7;
        for(Direction side:Direction.Plane.HORIZONTAL){
            BlockPos adjacent=at.relative(side);
            if(!level.hasChunkAt(adjacent))continue;
            var state=level.getBlockState(adjacent);
            if(state.is(Blocks.SCAFFOLDING)){
                BlockPos underAdjacent=adjacent.below();
                var support=level.getBlockState(underAdjacent);
                // Freshly placed vertical towers may still have their
                // default distance=7 before the next block update. Their
                // actual physical footing makes them distance zero.
                int neighboringDistance=support.is(Blocks.SCAFFOLDING)
                        ||support.isFaceSturdy(level,underAdjacent,Direction.UP)
                        ?0:state.getValue(
                            net.minecraft.world.level.block.ScaffoldingBlock.DISTANCE);
                distance=Math.min(distance,1+neighboringDistance);
            }
        }
        return distance;
    }
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
                &&table.sharedScaffoldPositions().contains(support);
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
        blockedCells.remove(worker);
        walkMonitors.remove(worker);
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
                    ||!table.sharedScaffoldPositions().contains(p))
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
        if(moving!=null){
            BlockPos platform=moving.destination();
            String base="BRIDGE WALK "+platform.toShortString();
            if(!(worker.level() instanceof ServerLevel level))return base;
            double dx=platform.getX()+.5-worker.getX();
            double dz=platform.getZ()+.5-worker.getZ();
            double sx=Math.max(-.13,Math.min(.13,dx*.24));
            double sz=Math.max(-.13,Math.min(.13,dz*.24));
            var nearby=worker.getBoundingBox().move(sx,0,sz);
            var monitor=walkMonitors.get(worker);
            return base+" toward="+String.format(java.util.Locale.ROOT,
                        "(%.2f,%.2f)",dx,dz)
                    +" feet="+String.format(java.util.Locale.ROOT,
                        "(%.2f,%.2f,%.2f)",worker.getX(),worker.getY(),worker.getZ())
                    +" velocity="+worker.getDeltaMovement()
                    +" grounded="+worker.onGround()
                    +" collides="+!level.noCollision(worker,nearby)
                    +" bodyBlocks="+level.getBlockCollisions(worker,nearby).iterator().hasNext()
                    +" support="+level.getBlockState(platform)
                    +" under="+level.getBlockState(platform.below())
                    +" head="+level.getBlockState(platform.above())
                    +" idle="+(monitor==null?-1:level.getGameTime()-monitor.lastMoved);
        }
        return route==null?failures.getOrDefault(worker,"NONE")
                        :"BRIDGE "+route.next()+"/"+route.cells().size();
    }

    /** Steering uses normal gravity and the real collision box, and runs
     * every entity tick. This avoids vanilla A* treating a scaffold top as
     * a nonexistent floor and repeatedly cancelling a valid sideways path.
     */
    public static boolean tickMotion(ServerLevel level,BuilderGolem worker){
        Walk walk=walking.get(worker);
        if(walk==null){
            walkMonitors.remove(worker);
            return false;
        }
        BlockPos platform=walk.destination();
        if(level.getGameTime()>walk.expires()
                ||!level.hasChunkAt(platform)
                ||level.getBlockState(platform).is(Blocks.SCAFFOLDING)
                    &&supportedDistance(level,platform)>=7
                ||!level.getBlockState(platform).is(Blocks.SCAFFOLDING)
                    &&!com.caszgamermd.golemsplus.worker.WorkerNavigation
                            .canStandOn(level,worker,platform.above())
                ||worker.getY()<platform.getY()-.25
                ||worker.getY()>platform.getY()+2){
            walking.remove(worker);
            routes.remove(worker);
            walkMonitors.remove(worker);
            return false;
        }
        double tx=platform.getX()+.5,tz=platform.getZ()+.5;
        double dx=tx-worker.getX(),dz=tz-worker.getZ();
        if(dx*dx+dz*dz<.10
                &&Math.abs(worker.getY()-platform.getY()-1)<.22){
            walking.remove(worker);
            walkMonitors.remove(worker);
            worker.getNavigation().stop();
            worker.setDeltaMovement(0,worker.getDeltaMovement().y,0);
            return false;
        }
        WalkMonitor watch=walkMonitors.get(worker);
        if(watch==null||!watch.destination.equals(platform)){
            watch=new WalkMonitor(platform,worker,level.getGameTime());
            walkMonitors.put(worker,watch);
        }
        // Merely shifting left/right isn't progress on a ghost-flight
        // route. Count only real motion that SHORTENS the remaining 3D
        // distance to the next paid scaffold tile.
        double remaining=worker.distanceToSqr(platform.getX()+.5,
                platform.getY()+1.0,platform.getZ()+.5);
        if(remaining<watch.bestRange-.04){
            watch.last=worker.position();
            watch.bestRange=remaining;
            watch.lastMoved=level.getGameTime();
        }else if(level.getGameTime()-watch.lastMoved>=65){
            // A live bridge route must NEVER claim permanent progress while
            // its entity is standing still. Stop this failed physical edge,
            // then let BFS try a different, actually walkable side for the
            // next 160 ticks. No scaffold is deleted and no items are spent.
            blockedCells.put(worker,new BlockedCell(platform.immutable(),
                    level.getGameTime()+160));
            failures.put(worker,"BRIDGE_BLOCKED at "+platform.toShortString()
                    +" from="+worker.blockPosition().toShortString());
            walking.remove(worker);
            routes.remove(worker);
            walkMonitors.remove(worker);
            worker.getNavigation().stop();
            return false;
        }
        worker.getNavigation().stop();
        worker.setShiftKeyDown(false);
        // Creative-flight EMULATION: stabilize only while the next block
        // is ALREADY a physically supported scaffold (or solid landing).
        // No teleporting, collision disabling or remote unsupported hover.
        // Restoring gravity happens automatically as soon as this walking
        // reservation is released, timed out or the platform disappears.
        boolean supported=level.getBlockState(platform).is(Blocks.SCAFFOLDING)
                &&supportedDistance(level,platform)<7;
        boolean close=dx*dx+dz*dz<=4.0
                &&Math.abs(worker.getY()-(platform.getY()+1))<=1.5;
        worker.setNoGravity(supported&&close);
        double destinationY=platform.getY()+1.0;
        worker.getMoveControl().setWantedPosition(tx,destinationY,tz,1);
        double vx=Math.max(-.13,Math.min(.13,dx*.24));
        double vz=Math.max(-.13,Math.min(.13,dz*.24));
        double deltaY=destinationY-worker.getY();
        double vy=worker.getDeltaMovement().y;
        if(supported&&close){
            if(deltaY>.16){
                // Rise above the previous scaffold before crossing onto
                // the next one-block-higher footprint.
                vx=0;vz=0;
                vy=Math.min(.16,Math.max(.06,deltaY*.30));
            }else if(deltaY<-.16){
                // Correct small altitude errors WHILE crossing: a worker
                // half a block above the next scaffold otherwise hovers
                // sideways forever. For a full one-block step DOWN,
                // traverse to the supported lower tile before lowering.
                if(deltaY<-.80&&dx*dx+dz*dz>.16)vy=0;
                else vy=Math.max(-.15,Math.min(-.05,deltaY*.30));
            }else vy=deltaY*.20;
        }
        // Vanilla move() still resolves all FULL block/entity collisions.
        // The virtual flight route instructs where to lay scaffolds; it
        // cannot make an impossible physical edge magically passable.
        worker.setDeltaMovement(vx,vy,vz);
        return true;
    }

    // The candidate scaffold is BELOW the Builder's feet, not a block
    // occupying the target's body. Reject any schematic cell or obstruction,
    // and require empty two-block headroom for real entity collision.
    private static boolean open(ServerLevel level,ServerPlayer owner,
            WorkerStationBlockEntity table,BuilderGolem worker,BlockPos p,
            Set<BlockPos> future,Set<BlockPos> owned){
        if(!level.hasChunkAt(p)||!level.hasChunkAt(p.below())
                ||!level.hasChunkAt(p.above())
                ||!level.hasChunkAt(p.above().above())
                ||!level.mayInteract(owner,p)
                // Future schematic solid cannot occupy the temporary
                // platform OR the Builder's body/head above it.
                ||future.contains(p)||future.contains(p.above())
                ||future.contains(p.above().above()))return false;
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
        Set<BlockPos> owned=new HashSet<>(table.sharedScaffoldPositions());
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
        // GHOST-FLIGHT PLAN: survey up to two levels either side of the
        // existing real tower. This is a VIRTUAL route through clear air,
        // not entity flight and not permission to cross future blocks.
        // When executed, every footprint becomes one paid, physically
        // supported scaffold BEFORE its Builder advances onto it.
        Set<BlockPos> workFloors=new HashSet<>();
        boolean wholeBuild=shell.solids().size()>=24;
        for(int y=anchor.getY()-2;y<=anchor.getY()+2;y++){
            if(wholeBuild)for(BlockPos floor:shell.atHeight(y)){
                int dx=floor.getX()-target.getX(),dz=floor.getZ()-target.getZ();
                int dy=floor.getY()+1-target.getY();
                if(dx*dx+dz*dz+dy*dy<=12.5)workFloors.add(floor);
            }
            // A local cross-section may expose a reachable ledge even when
            // the whole-build outer shell has no nearby usable platform.
            for(var station:BuilderScaffoldSections.stations(future,target,y))
                workFloors.add(station.scaffold());
        }
        if(workFloors.isEmpty()){
            failures.put(worker,"No unobstructed X/Z section work platforms");
            return List.of();
        }
        ArrayDeque<BlockPos> queue=new ArrayDeque<>();
        Map<BlockPos,BlockPos> parent=new HashMap<>();
        Map<BlockPos,Integer> depths=new HashMap<>();
        queue.add(anchor);
        parent.put(anchor,anchor);
        depths.put(anchor,0);
        BlockPos winner=null;
        while(!queue.isEmpty()){
            BlockPos current=queue.removeFirst();
            int distance=depths.get(current);
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
            List<BlockPos> neighbors=new ArrayList<>(12);
            // Simulate a Builder flying around the silhouette: north,
            // south, east and west, including one-level changes. The
            // real-world execution below constructs actual scaffold
            // stairs/piers and follows the resulting collision-safe path.
            for(int heightStep:new int[]{0,1,-1})
                for(int[] dir:DIRECTIONS)
                    neighbors.add(current.offset(dir[0],heightStep,dir[1]));
            neighbors.sort(Comparator
                    .comparingInt((BlockPos node)->wholeBuild&&shell.isShell(node)?0:1)
                    .thenComparingDouble(node->node.distSqr(target)));
            for(BlockPos next:neighbors){
                BlockedCell blocked=blockedCells.get(worker);
                if(blocked!=null&&blocked.expires()>level.getGameTime()
                        &&blocked.cell().equals(next))continue;
                if(parent.containsKey(next)
                        ||Math.abs(next.getX()-anchor.getX())>MAX_SEARCH
                        ||Math.abs(next.getZ()-anchor.getZ())>MAX_SEARCH
                        ||Math.abs(next.getY()-anchor.getY())>2
                        ||!open(level,owner,table,worker,next,future,owned)
                        // Vertical scaffold steps and every fifth lateral
                        // span need a feasible load-bearing pier below.
                        ||((distance+1)%5==0
                            ||next.getY()!=current.getY())
                            &&!pierFeasible(level,owner,next,future,owned))
                    continue;
                parent.put(next,current);
                depths.put(next,distance+1);
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
        Set<BlockPos> owned=new HashSet<>(table.sharedScaffoldPositions());
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
            var cardinalBases=new HashSet<Long>();
            for(var tower:shell.towers())cardinalBases.add(
                    BlockPos.asLong(tower.base().getX(),0,tower.base().getZ()));
            for(BlockPos top:tops.values()){
                if(Math.abs(top.getY()-bridgeY)>2)continue;
                matchingTops++;
                List<BlockPos> candidate=search(level,owner,table,worker,
                        top,target,future,owned,shell);
                if(candidate.isEmpty())continue;
                // Prefer a short, physical route that stays on the
                // planned exterior shell; penalize needless detours across
                // unrelated open ground.
                int outside=0;
                for(BlockPos node:candidate)if(!shell.isShell(node))outside++;
                // Prefer a permanent extreme tower even when a nearby
                // ad-hoc pillar would shorten the route slightly. Shared
                // towers reduce scaffold rebuilding across four workers.
                boolean cardinal=cardinalBases.contains(
                        BlockPos.asLong(top.getX(),0,top.getZ()));
                double cost=candidate.size()*10+outside*3
                        +(cardinal?-14:0)
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
            // Never steer a Builder through open air toward a REMOTE
            // scaffold segment. This happens after a supply run or when a
            // different tower was chosen while the Builder stood on an
            // isolated completed ledge. Only an adjacent real platform is
            // eligible for the physical one-block bridge controller.
            if(worker.distanceToSqr(from.getX()+.5,from.getY()+1,
                    from.getZ()+.5)>6.25){
                routes.remove(worker);
                failures.put(worker,"BRIDGE_REANCHOR needed at "
                        +from.toShortString()+" worker="
                        +worker.blockPosition().toShortString());
                return BuilderScaffolding.Result.NO_SAFE_TOWER;
            }
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
            if(next%5==0||to.getY()!=from.getY()){
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
            int stability=supportedDistance(level,to);
            if(stability>=7){
                // Do not consume an item to place a falling scaffold.
                failures.put(worker,"Unsupported bridge at "+to.toShortString());
                routes.remove(worker);
                return BuilderScaffolding.Result.NO_SAFE_TOWER;
            }
            var scaffold=Blocks.SCAFFOLDING.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.ScaffoldingBlock.DISTANCE,
                            stability)
                    .setValue(net.minecraft.world.level.block.ScaffoldingBlock.BOTTOM,
                            level.getBlockState(to.below()).isAir());
            var receipt=BuilderSupplyInventory.consume(worker,Items.SCAFFOLDING);
            if(receipt==null)return BuilderScaffolding.Result.NEED_MATERIALS;
            if(!level.setBlock(to,scaffold,3)){
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
