package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
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
 * Plan A: a Builder stands at the SAME feet Y as the work cell.
 * If normal walking cannot reach a safe work stance, use an anchored
 * tower and an inventory-paid scaffold bridge. This also works when
 * the target is connected to other roof blocks, not only in open air.
 *
 * The return route is reserved before construction, and is walked back
 * in reverse before descending. Every scaffold comes from real inventory.
 * No teleportation, synthetic path, side-attached scaffold, or old-AI fallback.
 */
public final class BuilderScaffoldPlanA {
    public enum Result { MOVING, NEED_MATERIALS, NO_SAFE_ROUTE, READY }
    private enum Phase {
        APPROACH, BUILD, CLIMB, EXTEND_FIRST, WALK_FIRST,
        EXTEND_SECOND, WALK_SECOND, READY,
        RETURN_FIRST, RETURN_TOWER, DESCEND, DISMOUNT
    }
    /** Diagnoses surviving owned blocks, not just the cached plan. */
    enum ScaffoldFault { NONE, TOWER_SHORT, BRIDGE_SHORT, EXIT_BLOCKED,
        EXIT_DROP_OBSTRUCTED, CLIMB_STALLED, CLIMB_HEADROOM_BLOCKED }
    private static final class Job {
        final BlockPos target,base,planner;
        BlockPos exit;
        final List<BlockPos> bridgePath;
        final Set<BlockPos> reserved;
        final int top,bridgeSpan;
        // An adjacent, walkable launch lets the Builder STEP BACK from a
        // roof lip before it pays for and climbs the elevated scaffold.
        BlockPos launch;
        int bridgeStep=2,returnStep=1;
        Phase phase=Phase.APPROACH;
        long lastProgress;
        int lastTick;
        int bridgeTicks,reclimbs,repairs;
        String lastFailure="NONE";
        long lastRepairLog;
        double highest=-Double.MAX_VALUE;
        double lastDistance=Double.POSITIVE_INFINITY;
        double closestApproach=Double.POSITIVE_INFINITY;
        BlockPos returnWaypoint;
        double bestReturnDistance=Double.POSITIVE_INFINITY;
        long returnProgressAt,nextReturnRecovery;
        // Repeated bridge phase changes are not physical progress.
        long mobilityProgressAt=-1,nextPoweredRecovery;
        double mobilityBest=Double.POSITIVE_INFINITY;
        BlockPos mobilityGoal;
        boolean fractionalSideEntry;
        Job(BlockPos target,BlockPos base,BlockPos exit,
                BlockPos planner,List<BlockPos> bridgePath,
                Set<BlockPos> reserved,int top,long now){
            this.target=target.immutable();
            this.base=base.immutable();
            this.exit=exit.immutable();
            this.planner=planner.immutable();
            this.bridgePath=List.copyOf(bridgePath);
            // One choose() invocation shares this private, unmodified
            // blueprint snapshot across all candidates. Copying thousands
            // of cells for every possible shaft is prohibitively expensive.
            this.reserved=reserved;
            this.top=top;
            this.bridgeSpan=bridgePath.size();
            this.lastProgress=now;
        }
        BlockPos bridge(int n){return bridgePath.get(n-1);}
        BlockPos first(){return bridge(1);}
        BlockPos second(){return bridge(bridgeStep);}
    }
    // Vanilla scaffolding stays structurally anchored up to six
    // horizontal blocks from its supporting tower. Distance seven falls.
    // The underside/bottom flag describes its shape, NOT an invalid span.
    static final int MAX_HORIZONTAL_SCAFFOLD_SPAN=6;
    // The worker stands TWO blocks from a target. Those two final tiles
    // are not scaffold bridge cells: a six-cell supported bridge can
    // therefore start eight tiles away from the actual work block.
    private static final int MAX_WORK_TOWER_DISTANCE=
            MAX_HORIZONTAL_SCAFFOLD_SPAN+2;
    private static final Map<BuilderGolem,Job> jobs=new WeakHashMap<>();
    /** A teammate's route may use our paid shaft or bridge. The receipt
     * never changes hands: prevent the owner from reclaiming a borrowed
     * support until that physical route has ended. */
    static boolean teammateUsesScaffold(WorkerStationBlockEntity table,
            BuilderGolem owner,BlockPos scaffold){
        for(var entry:jobs.entrySet()){
            BuilderGolem other=entry.getKey();
            Job route=entry.getValue();
            if(other==owner||!other.isAlive()
                    ||!route.planner.equals(table.getBlockPos()))continue;
            if(routeUsesScaffold(scaffold,route.base,route.top,
                    route.bridgePath))return true;
        }
        return false;
    }
    static boolean routeUsesScaffold(BlockPos at,BlockPos base,int top,
            List<BlockPos> bridge){
        return (at.getX()==base.getX()&&at.getZ()==base.getZ()
                &&at.getY()>=base.getY()&&at.getY()<=top)
                ||bridge.contains(at);
    }
    // A single rejected base oscillated between two adjacent roof columns
    // forever. Retain each physically failed entry for a bounded window so
    // the next plan explores a DISTINCT safe support, not the last failure.
    private static final Map<BuilderGolem,Map<BlockPos,Long>> failedEntries=
            new WeakHashMap<>();
    private static void rejectEntry(ServerLevel level,BuilderGolem worker,
            BlockPos base){
        failedEntries.computeIfAbsent(worker,ignored->new java.util.HashMap<>())
                .put(base.immutable(),level.getGameTime()+2400);
    }
    private BuilderScaffoldPlanA(){}

    public static boolean hasPlan(BuilderGolem worker){return jobs.containsKey(worker);}
    /** A free-floating full cube is valid in Minecraft. Its Builder still
     * needs a physically reachable place to stand. An isolated cell has no
     * floor or side geometry to approach using ordinary ground routes.
     */
    public static boolean needsTemporaryWorkPlatform(ServerLevel level,
            BlockPos target){
        if(!level.getBlockState(target).isAir()
                ||!level.getBlockState(target.below()).isAir())return false;
        for(Direction side:Direction.Plane.HORIZONTAL)
            if(!level.getBlockState(target.relative(side)).isAir())return false;
        return true;
    }
    /** Use a genuine owned bridge as a worker's temporary construction
     * position, even next to existing roof blocks. No schematic cell is
     * occupied and the reserved return shaft/bridge remains accessible.
     * A two-or-more-block center offset is necessary: standing only one
     * tile from the target violates the 0.8m edge-clearance rule. A
     * three-by-two diagonal can also be a real, in-reach construction pose.
     */
    public static boolean safeTemporaryWorkPose(ServerLevel level,
            BuilderGolem worker,WorkerStationBlockEntity table,BlockPos target){
        Job j=jobs.get(worker);
        if(j==null||j.phase!=Phase.READY
                ||worker.isNoGravity()||!worker.onGround()
                ||Math.abs(worker.getY()-(j.top+1))>.18
                // The scaffold supports the actual stance. Compare
                // horizontal clearance to the block being placed.
                ||Math.max(
                        Math.abs(j.bridge(j.bridgeSpan).getX()-target.getX()),
                        Math.abs(j.bridge(j.bridgeSpan).getZ()-target.getZ()))<2
                ||Math.pow(j.bridge(j.bridgeSpan).getX()-target.getX(),2)
                    +Math.pow(j.bridge(j.bridgeSpan).getZ()-target.getZ(),2)>13
                ||worker.blockPosition().getX()!=j.bridge(j.bridgeSpan).getX()
                ||worker.blockPosition().getZ()!=j.bridge(j.bridgeSpan).getZ()
                ||!present(level,table,worker,j.bridge(j.bridgeSpan)))return false;
        for(int n=1;n<=j.bridgeSpan;n++)
            if(!present(level,table,worker,j.bridge(n)))return false;
        return present(level,table,worker,j.base.atY(j.top))
                &&BuilderPlacementSpace.placementHeight(target,worker)
                &&BuilderPlacementSpace.horizontalClear(target,worker)
                &&worker.distanceToSqr(target.getX()+.5,
                        target.getY()+.5,target.getZ()+.5)<=BuilderConstructionPlanner.REACH_SQUARED
                &&onOwnedScaffoldTop(level,worker,table,
                        j.bridge(j.bridgeSpan));
    }
    /** Non-mutating frontier proof: a reachable paid bridge position
     * with reserved return route exists; actual work validates it again.
     */
    public static boolean canPlan(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,BlockPos target,
            List<BuilderConstructionPlan.Step> steps){
        Job current=jobs.get(worker);
        return current!=null&&current.target.equals(target)
                ||choose(level,worker,table,target,steps,false)!=null;
    }
    /** Placement-only scaffold choice. Unlike general access/travel
     * bridges, its final standing position is TWO tiles from the target
     * so it can satisfy the full 0.8m placement edge clearance.
     */
    public static boolean canPlanWorkPose(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,BlockPos target,
            List<BuilderConstructionPlan.Step> steps){
        Job current=jobs.get(worker);
        return current!=null&&current.target.equals(target)
                ||choose(level,worker,table,target,steps,true)!=null;
    }
    public static boolean controlsMotion(BuilderGolem worker){
        Job j=jobs.get(worker);
        return j!=null&&switch(j.phase){
            case WALK_FIRST,WALK_SECOND,RETURN_FIRST,RETURN_TOWER,DESCEND,DISMOUNT->true;
            default->false;
        };
    }
    public static String status(BuilderGolem worker){
        Job j=jobs.get(worker);
        return j==null?"NONE":"PLAN_A "+j.phase+" tower="+j.base.toShortString()
                +" bridge="+j.first().toShortString()+".."+j.bridge(j.bridgeSpan).toShortString()
                +" step="+j.bridgeStep+"/"+j.bridgeSpan
                +" targetY="+j.target.getY()+" workY="+(j.top+1)
                +" exit="+j.exit.toShortString()
                +" ticks="+j.bridgeTicks+" reclimbs="+j.reclimbs
                +" repairs="+j.repairs+" fault="+j.lastFailure
                +" highest="+String.format(java.util.Locale.ROOT,"%.2f",j.highest);
    }
    static BlockPos activeExit(BuilderGolem worker){
        Job j=jobs.get(worker);
        return j==null?null:j.exit;
    }
    public static void cancel(BuilderGolem worker){jobs.remove(worker);}
    public static boolean releaseDetachedPlan(ServerLevel level,BuilderGolem worker){
        Job j=jobs.get(worker);
        if(j==null||BuilderScaffolding.isControllingMovement(worker)
                ||!worker.onGround()||worker.isNoGravity()
                ||level.getGameTime()-j.lastProgress<80)return false;
        if(j.phase==Phase.APPROACH||j.phase==Phase.BUILD||j.phase==Phase.CLIMB)return false;
        BlockPos floor=worker.blockPosition().below();
        if(level.getBlockState(floor).is(Blocks.SCAFFOLDING)
                ||level.getBlockState(worker.blockPosition()).is(Blocks.SCAFFOLDING)
                ||!level.getBlockState(floor).isFaceSturdy(level,floor,Direction.UP)
                ||!WorkerNavigation.canStandOn(level,worker,worker.blockPosition()))return false;
        rejectEntry(level,worker,j.base);
        jobs.remove(worker);
        return true;
    }

    private static Set<BlockPos> future(ServerLevel level, List<BuilderConstructionPlan.Step> steps){
        Set<BlockPos> result=new HashSet<>();
        for(var step:steps)if(!step.ignorePlant()){
            BlockPos p=new BlockPos(step.x(),step.y(),step.z());
            if(level.getBlockState(p).isAir())result.add(p);
        }
        return result;
    }
    /** Native scaffolding is supported by a six-step chain of neighboring
     * scaffold blocks. A bend is legal if each successive paid block is
     * adjacent and the whole chain is at most six cells long.
     */
    private static boolean bridgeCellClear(ServerLevel world,BlockPos p,
            Set<BlockPos> reserved,WorkerStationBlockEntity table,
            BuilderGolem worker){
            if(!world.hasChunkAt(p)||reserved.contains(p)
                    ||reserved.contains(p.above())
                    ||reserved.contains(p.above(2)))return false;
            if(world.getBlockState(p).is(Blocks.SCAFFOLDING)){
                if(!table.isTrackedScaffold(p)
                        ||world.getBlockState(p).getValue(
                                net.minecraft.world.level.block.ScaffoldingBlock.DISTANCE)
                                >=MAX_HORIZONTAL_SCAFFOLD_SPAN+1)
                    return false;
            }else if(!world.getBlockState(p).isAir())return false;
            if(!world.getBlockState(p.above()).isAir()
                    ||!world.getBlockState(p.above(2)).isAir())return false;
        return true;
    }
    /** Horizontal owned scaffolding uses a real vertical shaft and
     * reserves the whole return route from unfinished schematic cells.
     */
    private static boolean bridgeClear(ServerLevel world,BlockPos base,
            Direction toward,int workY,int span,Set<BlockPos> reserved,
            WorkerStationBlockEntity table,BuilderGolem worker){
        if(span>MAX_HORIZONTAL_SCAFFOLD_SPAN)return false;
        for(int n=1;n<=span;n++){
            BlockPos p=base.relative(toward,n).atY(workY-1);
            if(!bridgeCellClear(world,p,reserved,table,worker))return false;
        }
        return true;
    }
    /** Find a six-or-shorter SUPPORTED orthogonal scaffold path around
     * an already built roof obstruction. The terminal stance may be three
     * tiles from the block, still within placement reach and well beyond
     * the required .8m edge clearance. No diagonal jumps, floating blocks,
     * or unsupported seven-distance scaffolding are permitted.
     */
    private static Set<BlockPos> workStanceGoals(BlockPos target,int workY){
        Set<BlockPos> result=new HashSet<>();
        for(int dx=-BuilderConstructionPlanner.WORK_STANCE_RADIUS;dx<=BuilderConstructionPlanner.WORK_STANCE_RADIUS;dx++)
            for(int dz=-BuilderConstructionPlanner.WORK_STANCE_RADIUS;dz<=BuilderConstructionPlanner.WORK_STANCE_RADIUS;dz++){
            if(Math.max(Math.abs(dx),Math.abs(dz))<2)continue;
            BlockPos stance=target.offset(dx,0,dz).atY(workY);
            if(BuilderConstructionPlanner.workReachFromFeet(stance,target)
                    <BuilderConstructionPlanner.WORK_STANCE_DISTANCE_SQUARED)
                result.add(stance.below());
        }
        return result;
    }
    private static List<BlockPos> bentBridge(ServerLevel world,
            BuilderGolem worker,WorkerStationBlockEntity table,
            BlockPos target,BlockPos base,int top,Set<BlockPos> goals,
            Set<BlockPos> reserved){
        BlockPos start=base.atY(top);
        ArrayDeque<BlockPos> queue=new ArrayDeque<>();
        Map<BlockPos,BlockPos> previous=new HashMap<>();
        Map<BlockPos,Integer> length=new HashMap<>();
        queue.add(start);
        length.put(start,0);
        while(!queue.isEmpty()){
            BlockPos from=queue.removeFirst();
            int depth=length.get(from);
            if(goals.contains(from)){
                List<BlockPos> result=new ArrayList<>();
                for(BlockPos p=from;!p.equals(start);p=previous.get(p))
                    result.add(p);
                java.util.Collections.reverse(result);
                return result.isEmpty()?null:result;
            }
            if(depth>=MAX_HORIZONTAL_SCAFFOLD_SPAN)continue;
            for(Direction direction:Direction.Plane.HORIZONTAL){
                BlockPos next=from.relative(direction);
                if(length.containsKey(next)
                        ||Math.abs(next.getX()-target.getX())
                            >MAX_WORK_TOWER_DISTANCE
                        ||Math.abs(next.getZ()-target.getZ())
                            >MAX_WORK_TOWER_DISTANCE
                        ||!bridgeCellClear(world,next,reserved,table,worker))
                    continue;
                previous.put(next,from);
                length.put(next,depth+1);
                queue.addLast(next);
            }
        }
        return null;
    }
    private static boolean shaftClear(ServerLevel world,BlockPos base,int top,
            Set<BlockPos> reserved,WorkerStationBlockEntity table,
            BuilderGolem worker){
        if(base.getY()>top||!world.hasChunkAt(base.below())
                ||!world.getBlockState(base.below()).isFaceSturdy(
                        world,base.below(),Direction.UP))return false;
        for(int y=base.getY();y<=top;y++){
            BlockPos p=base.atY(y);
            if(!world.hasChunkAt(p)||reserved.contains(p))return false;
            if(world.getBlockState(p).is(Blocks.SCAFFOLDING)){
                if(!table.isTrackedScaffold(p))
                    return false;
            }else if(!world.getBlockState(p).isAir())return false;
        }
        return true;
    }
    /** Prefer a genuinely reachable ground shaft with a verified exit.
     * A short, paid scaffold can bend around a finished roof corner;
     * a tower need not share the target's X or Z to support that bend.
     */
    private static Job choose(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,BlockPos target,
            List<BuilderConstructionPlan.Step> steps,boolean workPose){
        Set<BlockPos> reserved=future(level,steps);
        Job job=chooseAtHeight(level,worker,table,target,workPose,
                target.getY(),reserved);
        // A completed wall or trench can seal every same-Y route.
        // From one block higher the same target can be worked from
        // above using the normal reach, clearance and return checks.
        return job!=null||!workPose?job:chooseAtHeight(
                level,worker,table,target,true,target.getY()+1,reserved);
    }
    private static Job chooseAtHeight(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,BlockPos target,boolean workPose,
            int workY,Set<BlockPos> reserved){
        Map<BlockPos,Long> rejected=failedEntries.get(worker);
        if(rejected!=null)
            rejected.entrySet().removeIf(entry->
                    entry.getValue()<=level.getGameTime());
        boolean isolated=needsTemporaryWorkPlatform(level,target);
        List<Job> options=new ArrayList<>();
        Set<BlockPos> workGoals=workPose?
                workStanceGoals(target,workY):Set.of();
        List<BlockPos> towerSites=new ArrayList<>();
        for(Direction outward:Direction.Plane.HORIZONTAL)
            for(int distance=workPose?3:2;
                    distance<=(workPose?MAX_WORK_TOWER_DISTANCE:
                            MAX_HORIZONTAL_SCAFFOLD_SPAN);distance++)
                towerSites.add(target.relative(outward,distance));
        if(workPose){
            // A Builder already on the outer dome rim may have real,
            // head-clear footing diagonally offset from a roof gap. Do
            // not discard that supported launch solely because a long
            // straight cardinal bridge would intersect the roof fence.
            BlockPos feet=worker.blockPosition();
            for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++){
                BlockPos near=new BlockPos(feet.getX()+dx,
                        target.getY(),feet.getZ()+dz);
                if(Math.abs(near.getX()-target.getX())
                            >MAX_WORK_TOWER_DISTANCE
                        ||Math.abs(near.getZ()-target.getZ())
                            >MAX_WORK_TOWER_DISTANCE)continue;
                if(!towerSites.contains(near))towerSites.add(near);
            }
        }
        for(BlockPos towerXZ:towerSites){
            int dx=towerXZ.getX()-target.getX();
            int dz=towerXZ.getZ()-target.getZ();
            boolean straight=dx==0||dz==0;
            Direction outward=dx<0?Direction.WEST:dx>0?Direction.EAST:
                    dz<0?Direction.NORTH:Direction.SOUTH;
            Direction inward=outward.getOpposite();
            int distance=Math.abs(dx)+Math.abs(dz);
            if((isolated||workPose)&&distance<3)continue;
            int span=workPose||isolated?distance-2:distance-1;
            // Search real standing elevations ABOVE the worker as well
            // as below. Existing pillars, stairs, slabs and complete roof
            // blocks can put a Builder onto a reachable elevated ledge.
            // The exact ground-path preflight below must prove the route.
            for(int dy=-4;dy<=5;dy++){
                BlockPos base=towerXZ.atY(worker.blockPosition().getY()-dy);
                int top=workY-1;
                if(rejected!=null&&level.getGameTime()<
                        rejected.getOrDefault(base,Long.MIN_VALUE))continue;
                if(!shaftClear(level,base,top,reserved,table,worker))continue;
                List<BlockPos> bridge=new ArrayList<>();
                if(straight
                        &&bridgeClear(level,base,inward,workY,
                        span,reserved,table,worker)){
                    for(int n=1;n<=span;n++)bridge.add(
                            base.relative(inward,n).atY(top));
                }else if(workPose){
                    bridge=bentBridge(level,worker,table,target,base,top,
                            workGoals,reserved);
                    if(bridge==null)continue;
                }else continue;
                BlockPos exit=BuilderScaffolding.safeGroundExit(
                        level,worker,base,reserved);
                if(exit==null)continue;
                int existing=0;
                for(int y=base.getY();y<=top;y++){
                    if(level.getBlockState(base.atY(y)).is(Blocks.SCAFFOLDING))
                        existing++;
                }
                for(BlockPos cell:bridge)
                    if(level.getBlockState(cell).is(Blocks.SCAFFOLDING))
                        existing++;
                double score=BuilderScaffoldSiteScore.score(base,
                        worker.blockPosition(),target,
                        Math.max(0,top-base.getY()+1+bridge.size()-existing),0,0,
                        existing>0);
                // Extra credit for not changing X/Z aisle from worker.
                score+=Math.abs(worker.getX()-base.getX()-.5)
                        +Math.abs(worker.getZ()-base.getZ()-.5);
                Job candidate=new Job(target,base,exit,
                        table.getBlockPos(),bridge,reserved,top,level.getGameTime());
                candidate.lastDistance=score;
                options.add(candidate);
            }
        }
        options.sort(Comparator.comparingDouble(j->j.lastDistance));
        List<BlockPos> entryCandidates=new ArrayList<>();
        List<BlockPos> levelEntries=new ArrayList<>();
        List<BlockPos> stagedEntries=new ArrayList<>();
        Map<BlockPos,Job> stagedJobs=new HashMap<>();
        for(Job candidate:options){
            // At most 4 directions x 6 distances x 10 elevations = 240
            // physical candidates. The old top-96 material-cost cut
            // could contain ONLY high bases while excluding a valid
            // same-level roof entry. Consider the complete bounded set
            // before giving the worker a risky uphill scaffold approach.
            // A scaffold already occupying its own base is not a native
            // walking goal; its owner can begin climbing in place.
            boolean onBase=worker.blockPosition().getX()==candidate.base.getX()
                    &&worker.blockPosition().getZ()==candidate.base.getZ()
                    &&Math.abs(worker.getY()-candidate.base.getY())<.30
                    &&worker.onGround();
            if(onBase)return candidate;
            // A slab-edge Builder can already be BESIDE a supported
            // scaffold base even when trained A* rejects its non-centered
            // starting tile. If the worker is grounded, within real item
            // placement range, and has vertical headroom, let it build
            // that non-solid climb-through scaffold from its existing
            // feet instead of demanding a long-distance route proof.
            // The active scaffold controller still has to physically
            // center and climb; this authorizes no movement shortcut.
            boolean nearGroundedBase=worker.onGround()
                    &&!worker.isNoGravity()
                    &&Math.abs(worker.getY()-candidate.base.getY())<.30
                    &&worker.distanceToSqr(candidate.base.getX()+.5,
                            worker.getY(),candidate.base.getZ()+.5)<1.20
                    &&level.noCollision(worker,
                            worker.getBoundingBox().move(0,.35,0));
            if(nearGroundedBase
                    ||canEnterFromBelow(level,worker,candidate.base)){
                System.out.println("[PLAN A LOCAL ENTRY] from grounded feet "
                        +worker.position()+" at supported base "
                        +candidate.base.toShortString()
                        +" bridge="+candidate.bridgeSpan);
                return candidate;
            }
            if(fractionalSideMove(level,worker,candidate.base)!=null){
                candidate.fractionalSideEntry=true;
                System.out.println("[PLAN A FRACTIONAL ENTRY] clear a real "
                        +"roof-edge overlap before paid shaft at "
                        +candidate.base.toShortString());
                return candidate;
            }
            // Prefer an immediately reachable grounded entry to a far
            // away roof tile that the abstract multi-goal graph claims is
            // reachable but cannot execute its FIRST collision-safe step.
            if(candidate.base.getY()==worker.blockPosition().getY()
                    &&worker.distanceToSqr(candidate.base.getX()+.5,
                            candidate.base.getY(),candidate.base.getZ()+.5)<16
                    &&WorkerNavigation.canReachExact(
                            level,worker,candidate.base))return candidate;
            if(!workPose&&WorkerNavigation.canReachExact(
                    level,worker,candidate.base))return candidate;
            // The base is AIR above a supporting roof block. It is not
            // itself a valid walking destination from the lower ledge.
            // Search for a real adjacent standing tile: the trained
            // route may initially go AWAY from the target, then walk
            // back to a side from which an ordinary one-block climb
            // can begin. Do not install scaffold until that walk ends.
            if(workPose)
                for(BlockPos launch:sideLaunchCandidates(
                        level,worker,candidate.base))
                    if(!stagedJobs.containsKey(launch)){
                        stagedEntries.add(launch);
                        stagedJobs.put(launch,candidate);
                    }
            if(!entryCandidates.contains(candidate.base))
                entryCandidates.add(candidate.base);
            if(candidate.base.getY()<=worker.blockPosition().getY()
                    &&!levelEntries.contains(candidate.base))
                levelEntries.add(candidate.base);
        }
        if(!workPose)return null;
        BlockPos staged=stagedEntries.isEmpty()?null:
                WorkerNavigation.selectExecutableStand(
                        level,worker,stagedEntries);
        if(staged!=null){
            Job candidate=stagedJobs.get(staged);
            candidate.launch=staged;
            System.out.println("[PLAN A STEP BACK] walk to safe roof launch "
                    +staged.toShortString()+" before scaffold base "
                    +candidate.base.toShortString());
            return candidate;
        }
        // Use the same trained/fractional movement preflight as work
        // approaches, not native canReachExact, which rejects some real
        // off-center detours on high roofs. One bounded multi-goal search
        // gives both Builders a fair shot at a reachable scaffold base.
        // On a finished high roof, the nearest elevated base can be a
        // theoretical one-step jump behind a slab or glass edge. Try a
        // center-reachable entry on the worker's existing/lower surface
        // first; only then consider an ascent to a higher shaft base.
        BlockPos entry=levelEntries.isEmpty()?null:
                WorkerNavigation.selectExecutableStand(
                        level,worker,levelEntries);
        if(entry==null)entry=WorkerNavigation.selectExecutableStand(
                level,worker,entryCandidates);
        if(entry!=null)
            for(Job candidate:options)
                if(candidate.base.equals(entry))return candidate;
        return null;
    }
    /** No state mutation. Explain which exact physical condition
     * rejects roof-supported access rather than guessing at navigation.
     */
    public static String diagnoseAccess(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,BlockPos target,
            List<BuilderConstructionPlan.Step> steps){
        Set<BlockPos> reserved=future(level,steps);
        int shafts=0,bridges=0,exits=0,reached=0,lowerExits=0;
        int localShafts=0,localBridges=0,localExits=0;
        BlockPos nearestLocal=null;
        List<String> localExamples=new ArrayList<>();
        Set<BlockPos> workGoals=workStanceGoals(target,target.getY());
        BlockPos feet=worker.blockPosition();
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++){
            BlockPos xz=feet.offset(dx,0,dz);
            for(int dy=-4;dy<=5;dy++){
                BlockPos base=xz.atY(feet.getY()-dy);
                if(!shaftClear(level,base,target.getY()-1,
                        reserved,table,worker))continue;
                localShafts++;
                if(bentBridge(level,worker,table,target,base,target.getY()-1,
                        workGoals,reserved)==null)continue;
                localBridges++;
                if(BuilderScaffolding.safeGroundExit(level,worker,
                        base,reserved)==null)continue;
                localExits++;
                if(nearestLocal==null)nearestLocal=base;
                if(localExamples.size()<8)localExamples.add(base.toShortString());
            }
        }
        List<BlockPos> lowerBases=new ArrayList<>();
        String example="";
        for(Direction outward:Direction.Plane.HORIZONTAL)
            for(int distance=3;distance<=MAX_WORK_TOWER_DISTANCE;distance++)
                for(int dy=-4;dy<=5;dy++){
                    BlockPos base=target.relative(outward,distance)
                            .atY(worker.blockPosition().getY()-dy);
                    int top=target.getY()-1;
                    int span=distance-2;
                    if(!shaftClear(level,base,top,reserved,table,worker)){
                        if(outward==Direction.WEST&&distance==6&&dy==0)
                            example="west6 shaft support="+level.getBlockState(base.below())
                                +" foot="+level.getBlockState(base)
                                +" top="+level.getBlockState(base.atY(top));
                        continue;
                    }
                    shafts++;
                    if(!bridgeClear(level,base,outward.getOpposite(),
                            target.getY(),span,reserved,table,worker))continue;
                    bridges++;
                    BlockPos exit=BuilderScaffolding.safeGroundExit(
                            level,worker,base,reserved);
                    if(exit==null)continue;
                    exits++;
                    if(base.getY()<=worker.blockPosition().getY()){
                        lowerExits++;
                        lowerBases.add(base);
                    }
                    boolean onBase=worker.blockPosition().getX()==base.getX()
                            &&worker.blockPosition().getZ()==base.getZ()
                            &&Math.abs(worker.getY()-base.getY())<.30
                            &&worker.onGround();
                    if(onBase||WorkerNavigation.canReachExact(level,worker,base))
                        reached++;
                }
        BlockPos west=target.west(6).atY(worker.blockPosition().getY());
        BlockPos westExit=BuilderScaffolding.safeGroundExit(
                level,worker,west,reserved);
        return "target="+target+" feet="+worker.blockPosition()
                +" grounded="+worker.onGround()
                +" shafts="+shafts+" clearBridges="+bridges
                +" safeExits="+exits+" reachable="+reached
                +" localShafts="+localShafts
                +" localBridges="+localBridges
                +" localExits="+localExits
                +" nearestLocal="+nearestLocal
                +" goalPoses="+workGoals.size()
                +" localOptions="+localExamples
                +" goalPoses="+workGoals.size()
                +" lowerExits="+lowerExits
                +" lowerTrained="+WorkerNavigation.selectExecutableStand(
                        level,worker,lowerBases)
                +" west6="+west+" onsite="+worker.blockPosition().equals(west)
                +" shaft="+shaftClear(level,west,target.getY()-1,reserved,table,worker)
                +" bridge="+bridgeClear(level,west,Direction.EAST,
                        target.getY(),5,reserved,table,worker)
                +" exit="+westExit
                +" stand="+WorkerNavigation.canStandOn(level,worker,west)
                +" exact="+WorkerNavigation.canReachExact(level,worker,west)
                +" example="+example;
    }
    private static boolean structurallySupported(ServerLevel level,BlockPos at){
        var state=level.getBlockState(at);
        return state.is(Blocks.SCAFFOLDING)
                &&state.getValue(net.minecraft.world.level.block.ScaffoldingBlock.DISTANCE)
                        <=MAX_HORIZONTAL_SCAFFOLD_SPAN;
    }
    private static boolean present(ServerLevel level,WorkerStationBlockEntity table,
            BuilderGolem worker,BlockPos at){
        // A Planner's four Builders share one physical worksite. A scaffold
        // installed and paid for by a teammate is valid footing, but its
        // ownership receipt remains with the original Builder for refunds.
        return level.getBlockState(at).is(Blocks.SCAFFOLDING)
                &&table.isTrackedScaffold(at);
    }
    /** Inspect actual owned blocks. Stale receipts do not make a missing
     * scaffold physically safe, even if a route was previously approved.
     */
    static ScaffoldFault diagnoseScaffold(ServerLevel level,
            BuilderGolem worker,WorkerStationBlockEntity table,
            BlockPos base,int top,List<BlockPos> bridge,BlockPos exit){
        for(int y=base.getY();y<=top;y++)
            if(!present(level,table,worker,base.atY(y)))
                return ScaffoldFault.TOWER_SHORT;
        BlockPos parent=base.atY(top);
        for(BlockPos cell:bridge){
            if(!present(level,table,worker,cell)
                    ||!structurallySupported(level,cell)
                    ||Math.abs(cell.getX()-parent.getX())
                        +Math.abs(cell.getZ()-parent.getZ())!=1)
                return ScaffoldFault.BRIDGE_SHORT;
            parent=cell;
        }
        if(exit==null||!WorkerNavigation.canStandOn(level,worker,exit))
            return ScaffoldFault.EXIT_BLOCKED;
        return ScaffoldFault.NONE;
    }
    private static int physicalTowerTop(ServerLevel level,
            BuilderGolem worker,WorkerStationBlockEntity table,Job j){
        int y=j.base.getY()-1;
        while(y<j.top&&present(level,table,worker,j.base.atY(y+1)))y++;
        return y;
    }
    private static int physicalBridgeSpan(ServerLevel level,
            BuilderGolem worker,WorkerStationBlockEntity table,Job j){
        int n=0;
        for(BlockPos cell:j.bridgePath){
            if(!present(level,table,worker,cell)
                    ||!structurallySupported(level,cell))break;
            n++;
        }
        return n;
    }
    private static void repairLog(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,Job j,ScaffoldFault fault,
            String action){
        j.lastFailure=fault.name();
        j.lastRepairLog=level.getGameTime();
        System.out.println("[PLAN A REPAIR] worker="+worker.getUUID()
                +" fault="+fault+" action="+action
                +" feet="+worker.position()+" grounded="+worker.onGround()
                +" tower="+j.base.toShortString()
                +" actualTop="+physicalTowerTop(level,worker,table,j)
                +" requiredTop="+j.top
                +" actualBridge="+physicalBridgeSpan(level,worker,table,j)
                +" requiredBridge="+j.bridgeSpan
                +" plannedExit="+j.exit);
    }
    /** The dome may change after the tower is planned. Prefer its reserved
     * exit, then find another real ground tile; exclude unfinished blueprint
     * cells but permit newly finished solid landings.
     */
    private static boolean refreshExit(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,Job j){
        if(BuilderScaffolding.walkableExit(level,worker,j.exit))return true;
        Set<BlockPos> unfinished=new HashSet<>();
        for(BlockPos p:j.reserved)
            if(level.getBlockState(p).isAir())unfinished.add(p);
        BlockPos safe=BuilderScaffolding.safeGroundExit(
                level,worker,j.base,unfinished);
        // During an active return, a future placement is still real air.
        // Use it for the brief evacuation if the reserved exits closed.
        // The work loop still checks physical occupancy before placement.
        if(safe==null)
            safe=BuilderScaffolding.safeGroundExit(
                    level,worker,j.base,Set.of(j.target));
        if(safe!=null){
            BlockPos previous=j.exit;
            j.exit=safe;
            repairLog(level,worker,table,j,ScaffoldFault.EXIT_BLOCKED,
                    "reroute real dismount "+previous+" -> "+safe);
            return true;
        }
        if(level.getGameTime()-j.lastRepairLog>=100)
            repairLog(level,worker,table,j,ScaffoldFault.EXIT_BLOCKED,
                    "hold supported scaffold; no solid dismount");
        return false;
    }
    private static boolean escapeBlockedExit(ServerLevel level,
            BuilderGolem worker,WorkerStationBlockEntity table,Job j){
        if(BuilderScaffolding.hasPhysicalMotion(worker))return true;
        // If the old base is enclosed, use a nearby ALREADY solid upper
        // ledge, but only through the native collision-safe side-step.
        BlockPos feet=worker.blockPosition();
        for(Direction dir:Direction.Plane.HORIZONTAL)
            for(int dy:new int[]{0,-1,1}){
                BlockPos landing=feet.relative(dir).offset(0,dy,0);
                if(level.getBlockState(landing.below()).is(Blocks.SCAFFOLDING)
                        ||!level.getBlockState(landing.below()).isFaceSturdy(
                                level,landing.below(),Direction.UP)
                        ||!WorkerNavigation.canStandOn(level,worker,landing))
                    continue;
                if(BuilderScaffolding.stepToPlatform(level,worker,landing)){
                    j.phase=Phase.DISMOUNT;
                    repairLog(level,worker,table,j,ScaffoldFault.EXIT_BLOCKED,
                            "step onto existing solid ledge "
                            +landing.toShortString());
                    return true;
                }
            }
        return false;
    }
    /** Replace only a missing NEXT cell from the Builder's settled,
     * inventory-owned parent. reach() then pays for the replacement.
     */
    private static boolean repairBridge(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,Job j){
        int step=j.phase==Phase.WALK_FIRST?1:
                j.phase==Phase.WALK_SECOND?j.bridgeStep:0;
        if(step==0)return false;
        BlockPos parent=step==1?j.base.atY(j.top):j.bridge(step-1);
        if(!present(level,table,worker,parent)
                ||!structurallySupported(level,parent))return false;
        double dx=worker.getX()-parent.getX()-.5;
        double dz=worker.getZ()-parent.getZ()-.5;
        if(dx*dx+dz*dz>.30||worker.getY()<parent.getY()-.20
                ||worker.getY()>parent.getY()+1.20)return false;
        if(!onOwnedScaffoldTop(level,worker,table,parent)){
            if(!BuilderScaffolding.climbingNow(level,worker))
                BuilderScaffolding.begin(level,worker,parent,
                        parent.getY()+1.0,false);
            return true;
        }
        if(j.repairs>=3){
            rejectEntry(level,worker,j.base);
            repairLog(level,worker,table,j,ScaffoldFault.BRIDGE_SHORT,
                    "repair budget exhausted; return via supported route");
            returnToGround(level,worker,table);
            return true;
        }
        j.repairs++;
        j.phase=step==1?Phase.EXTEND_FIRST:Phase.EXTEND_SECOND;
        j.lastProgress=level.getGameTime();
        repairLog(level,worker,table,j,ScaffoldFault.BRIDGE_SHORT,
                "replace missing bridge cell "+step+" from real parent "
                        +parent.toShortString()+" attempt="+j.repairs);
        return true;
    }
    /** A scaffold whose base is one block ABOVE nearby real footing can
     * be paid for and placed from the side. Do not demand that a Builder
     * first walk/jump into the EMPTY base cell: the finished dome's
     * upper glass rim can block that leap indefinitely. The body must
     * be wholly outside the placed block, on ground, and within reach.
     * After payment the ordinary per-entity scaffold physics climbs it.
     */
    private static boolean canEnterFromBelow(ServerLevel level,
            BuilderGolem worker,BlockPos base){
        double dy=base.getY()-worker.getY();
        if(!worker.onGround()||worker.isNoGravity()
                ||dy<.40||dy>1.10
                ||worker.distanceToSqr(base.getX()+.5,worker.getY(),
                        base.getZ()+.5)>1.20
                ||worker.getBoundingBox().intersects(
                        new net.minecraft.world.phys.AABB(base)))return false;
        return level.getBlockState(base.below()).isFaceSturdy(
                level,base.below(),Direction.UP)
                &&level.getBlockState(base).isAir()
                // A solid roof block above the launch foot can allow a
                // .3m bob but prevent the full one-block jump. Check the
                // actual entity collision volume before buying the shaft.
                &&level.noCollision(worker,
                        worker.getBoundingBox().move(0,.65,0));
    }
    /** Footprint-clear, one-tile-away roof standing positions from which
     * the base can actually be placed and climbed. The planner separately
     * proves a real walk from the current offset body to this position.
     * A centered launch may be behind the worker, or around a roof corner.
     */
    private static List<BlockPos> sideLaunchCandidates(ServerLevel level,
            BuilderGolem worker,BlockPos base){
        List<BlockPos> result=new ArrayList<>();
        if(!level.getBlockState(base).isAir()
                ||!level.getBlockState(base.below()).isFaceSturdy(
                        level,base.below(),Direction.UP))return result;
        for(Direction side:Direction.Plane.HORIZONTAL){
            BlockPos foot=base.below().relative(side);
            if(!WorkerNavigation.canStandOn(level,worker,foot)
                    ||!level.getBlockState(foot.below()).isFaceSturdy(
                            level,foot.below(),Direction.UP))continue;
            var body=worker.getBoundingBox().move(
                    foot.getX()+.5-worker.getX(),
                    foot.getY()-worker.getY(),
                    foot.getZ()+.5-worker.getZ());
            if(body.intersects(new net.minecraft.world.phys.AABB(base))
                    ||!level.noCollision(worker,body)
                    ||!level.noCollision(worker,body.move(0,.65,0)))continue;
            result.add(foot);
        }
        return result;
    }
    /** Physically sidestep a few centimeters off a proposed elevated
     * scaffold footprint before paying for the real column. Every step
     * must be collision-free and still touch genuine solid footing.
     */
    private static double[] fractionalSideMove(ServerLevel level,
            BuilderGolem worker,BlockPos base){
        double dy=base.getY()-worker.getY();
        if(!worker.onGround()||worker.isNoGravity()
                ||dy<.40||dy>1.10
                ||worker.distanceToSqr(base.getX()+.5,worker.getY(),
                        base.getZ()+.5)>1.20
                ||!level.getBlockState(base).isAir()
                ||!level.getBlockState(base.below()).isFaceSturdy(
                        level,base.below(),Direction.UP))return null;
        var box=worker.getBoundingBox();
        if(!box.intersects(new net.minecraft.world.phys.AABB(base)))return null;
        double clearX=worker.getX()<base.getX()+.5
                ?base.getX()-.025-box.maxX
                :base.getX()+1.025-box.minX;
        double clearZ=worker.getZ()<base.getZ()+.5
                ?base.getZ()-.025-box.maxZ
                :base.getZ()+1.025-box.minZ;
        double mx=Math.max(-.085,Math.min(.085,clearX));
        double mz=Math.max(-.085,Math.min(.085,clearZ));
        double[][] moves=Math.abs(clearX)<Math.abs(clearZ)
                ?new double[][]{{mx,0},{0,mz}}
                :new double[][]{{0,mz},{mx,0}};
        for(double[] step:moves){
            var shifted=box.move(step[0],0,step[1]);
            if(level.noCollision(worker,shifted)
                    &&!level.noCollision(worker,
                            shifted.move(0,-.07,0)))
                return step;
        }
        return null;
    }
    /** Find a WALKABLE, head-clear launch on another face of an elevated
     * base. The nearest side of the north dome rim has a diorite overhang;
     * its north face has real floor and enough headroom to jump onto the
     * paid column. Routing around the overhang uses ordinary ground A*.
     */
    private static BlockPos alternateSideLaunch(ServerLevel level,
            BuilderGolem worker,BlockPos base){
        if(!worker.onGround()||base.getY()-worker.getY()<.40
                ||base.getY()-worker.getY()>1.10)return null;
        List<BlockPos> launch=new ArrayList<>();
        for(Direction side:Direction.Plane.HORIZONTAL){
            BlockPos foot=base.below().relative(side);
            if(!WorkerNavigation.canStandOn(level,worker,foot))continue;
            var box=worker.getBoundingBox().move(
                    foot.getX()+.5-worker.getX(),
                    foot.getY()-worker.getY(),
                    foot.getZ()+.5-worker.getZ());
            if(level.noCollision(worker,box)
                    &&level.noCollision(worker,box.move(0,.65,0)))
                launch.add(foot);
        }
        return launch.isEmpty()?null:
                WorkerNavigation.selectExecutableStand(level,worker,launch);
    }
    private static Result place(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,ServerPlayer owner,BlockPos at,
            BlockPos previous){
        if(present(level,table,worker,at))return Result.READY;
        if(!level.hasChunkAt(at)||!level.mayInteract(owner,at)
                ||!level.getBlockState(at).isAir()
                ||!present(level,table,worker,previous)
                ||!level.getBlockState(at.above()).isAir()
                ||!level.getBlockState(at.above(2)).isAir()){
            System.out.println("[PLAN A PLACE] rejected at="+at
                    +" block="+level.getBlockState(at)
                    +" previous="+previous+" state="+level.getBlockState(previous)
                    +" prevTracked="+table.scaffoldPositions(worker.getUUID())
                            .contains(previous)
                    +" head1="+level.getBlockState(at.above())
                    +" head2="+level.getBlockState(at.above(2))
                    +" player="+owner.getGameProfile().name());
            return Result.NO_SAFE_ROUTE;
        }
        // Use the known real scaffold connection to compute the native
        // initial support distance. Placing defaultBlockState() briefly
        // installs distance=7 (unsupported); Minecraft updates neighbours
        // later. A new, valid horizontal bridge can be rejected/fall before
        // that scheduled update. A column has distance 0; horizontal
        // cantilevers inherit 1..6 from their attached neighbouring cell.
        var parent=level.getBlockState(previous);
        int distance=parent.getValue(
                net.minecraft.world.level.block.ScaffoldingBlock.DISTANCE)+1;
        if(distance>MAX_HORIZONTAL_SCAFFOLD_SPAN)
            return Result.NO_SAFE_ROUTE;
        var state=Blocks.SCAFFOLDING.defaultBlockState()
                .setValue(net.minecraft.world.level.block.ScaffoldingBlock.DISTANCE,
                        distance)
                .setValue(net.minecraft.world.level.block.ScaffoldingBlock.BOTTOM,
                        !level.getBlockState(at.below()).is(Blocks.SCAFFOLDING));
        var load=BuilderSupplyInventory.loadScaffold(level,worker,table,1);
        if(load==BuilderSupplyInventory.ScaffoldLoad.MOVING)return Result.MOVING;
        if(load==BuilderSupplyInventory.ScaffoldLoad.MISSING)
            return Result.NEED_MATERIALS;
        var receipt=BuilderSupplyInventory.consume(worker,Items.SCAFFOLDING);
        if(receipt==null)return Result.NEED_MATERIALS;
        // A fallen physical block can leave an old ownership receipt.
        // Replace it with a NEW paid block, never count stale tracking as
        // support and never record the same coordinate twice.
        if(table.scaffoldPositions(worker.getUUID()).contains(at))
            table.untrackScaffold(worker.getUUID(),at);
        if(!level.setBlock(at,state,3)
                ||!level.getBlockState(at).is(Blocks.SCAFFOLDING)){
            BuilderSupplyInventory.refund(table,receipt);
            return Result.NO_SAFE_ROUTE;
        }
        if(!table.trackScaffold(worker.getUUID(),at)){
            level.setBlock(at,Blocks.AIR.defaultBlockState(),3);
            BuilderSupplyInventory.refund(table,receipt);
            return Result.NO_SAFE_ROUTE;
        }
        return Result.READY;
    }
    private static Result shaft(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,Job j){
        int existingTop=j.base.getY()-1;
        for(int y=j.base.getY();y<=j.top;y++)
            if(present(level,table,worker,j.base.atY(y)))
                existingTop=y;
            else break;
        int top=ScaffoldVerticalRoute.stageTop(
                j.base.getY(),existingTop,j.top);
        int count=0;
        for(int y=j.base.getY();y<=top;y++){
            BlockPos p=j.base.atY(y);
            if(present(level,table,worker,p))continue;
            if(!level.getBlockState(p).isAir())return Result.NO_SAFE_ROUTE;
            count++;
        }
        // Stage the bridge as well as this shaft segment before climbing.
        // Otherwise the last tower block empties the belt and the first
        // bridge extension sends the worker straight back to the depot.
        for(BlockPos bridge:j.bridgePath)
            if(!present(level,table,worker,bridge))count++;
        // A grounded pickup must keep this build plan while walking to
        // the depot. The descent entry point clears a ground-level BUILD
        // plan, which lets the next work tick send the worker back again.
        BlockPos depot=table.builderUnloadingStorage();
        boolean groundPickup=depot!=null&&worker.onGround()
                &&worker.getY()<=j.base.getY()+.2
                &&worker.getY()<=depot.getY()+.25
                &&!BuilderScaffolding.isControllingMovement(worker);
        var load=BuilderSupplyInventory.loadScaffold(
                level,worker,table,count,groundPickup);
        if(load==BuilderSupplyInventory.ScaffoldLoad.MOVING)return Result.MOVING;
        if(load==BuilderSupplyInventory.ScaffoldLoad.MISSING)
            return Result.NEED_MATERIALS;
        // Fetching paid scaffold can take this worker all the way back
        // to OnSite storage. After a successful resupply, it must WALK
        // back to the chosen real shaft before placing any blocks. A
        // previously active tall shaft may instead be extended while
        // the worker stands on its own completed column.
        if(worker.distanceToSqr(j.base.getX()+.5,worker.getY(),
                        j.base.getZ()+.5)>1.20
                ||worker.getY()<j.base.getY()-.4
                    &&!canEnterFromBelow(level,worker,j.base)
                ||worker.getY()>Math.max(j.base.getY(),existingTop+1)+.4){
            j.phase=Phase.APPROACH;
            j.closestApproach=Double.POSITIVE_INFINITY;
            j.lastProgress=level.getGameTime();
            return Result.MOVING;
        }
        for(int y=j.base.getY();y<=top;y++){
            BlockPos at=j.base.atY(y);
            if(present(level,table,worker,at))continue;
            if(!level.getBlockState(at.below()).isFaceSturdy(
                    level,at.below(),Direction.UP)
                    &&!present(level,table,worker,at.below()))
                return Result.NO_SAFE_ROUTE;
            var item=BuilderSupplyInventory.consume(worker,Items.SCAFFOLDING);
            if(item==null)return Result.NEED_MATERIALS;
            if(table.scaffoldPositions(worker.getUUID()).contains(at))
                table.untrackScaffold(worker.getUUID(),at);
            // Native Minecraft requires a supported vertical shaft to
            // carry DISTANCE=0. The block default is DISTANCE=7, which
            // makes the first horizontal extension incorrectly conclude
            // its real tower has no support before its scheduled updates.
            var anchored=Blocks.SCAFFOLDING.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.ScaffoldingBlock.DISTANCE,0)
                    .setValue(net.minecraft.world.level.block.ScaffoldingBlock.BOTTOM,
                            !level.getBlockState(at.below()).is(Blocks.SCAFFOLDING));
            if(!level.setBlock(at,anchored,3)
                    ||!table.trackScaffold(worker.getUUID(),at)){
                if(level.getBlockState(at).is(Blocks.SCAFFOLDING))
                    level.setBlock(at,Blocks.AIR.defaultBlockState(),3);
                BuilderSupplyInventory.refund(table,item);
                return Result.NO_SAFE_ROUTE;
            }
        }
        j.phase=Phase.CLIMB;
        j.lastProgress=level.getGameTime();
        BuilderScaffolding.begin(level,worker,j.base,top+1.0,false);
        return Result.MOVING;
    }
    private static boolean close(BuilderGolem worker,BlockPos p){
        return worker.distanceToSqr(p.getX()+.5,p.getY(),
                p.getZ()+.5)<.12
                &&Math.abs(worker.getY()-p.getY())<.12;
    }
    /**
     * Physical scaffold-only footing: a Builder inside a climb-through
     * scaffold may report onGround=true while its feet are one block below
     * the scaffold top. A completed scaffold step requires the actual top
     * elevation AND settled collision support. Never apply this rule to
     * ordinary stone, slabs, stairs, or other work navigation.
     */
    static boolean onScaffoldTop(ServerLevel level,BuilderGolem worker,
            BlockPos scaffold){
        return level.getBlockState(scaffold).is(Blocks.SCAFFOLDING)
                &&worker.onGround()&&!worker.isNoGravity()
                &&Math.abs(worker.getY()-(scaffold.getY()+1.0))<.12;
    }
    private static boolean onOwnedScaffoldTop(ServerLevel level,
            BuilderGolem worker,WorkerStationBlockEntity table,
            BlockPos scaffold){
        return present(level,table,worker,scaffold)
                &&onScaffoldTop(level,worker,scaffold);
    }
    /** A work-claim loop may stop calling reach() while its claimed
     * block waits on another worker. Run the orphan watchdog from EACH
     * entity tick, not only from an occasionally evaluated work step.
     * Release a failed tower only on genuine grounded footing.
     */
    private static boolean releaseOrphanedClimb(ServerLevel level,
            BuilderGolem worker,Job j){
        if(BuilderScaffolding.climbingNow(level,worker)
                ||!worker.onGround())return false;
        double baseOffset=worker.distanceToSqr(
                j.base.getX()+.5,worker.getY(),j.base.getZ()+.5);
        boolean belowBase=worker.getY()<j.base.getY()-.35;
        boolean offColumn=baseOffset>.16;
        boolean waited=level.getGameTime()-j.lastProgress>80;
        BlockPos underFeet=worker.blockPosition().below();
        boolean supportedUpperLedge=worker.getY()>j.base.getY()+.25
                &&offColumn&&waited
                &&level.getBlockState(underFeet).isFaceSturdy(
                        level,underFeet,Direction.UP);
        if(!belowBase&&!(offColumn&&waited
                &&worker.getY()<=j.base.getY()+.25)
                &&!supportedUpperLedge)return false;
        rejectEntry(level,worker,j.base);
        System.out.println("[PLAN A ENTRY] orphaned climb, retry another "
                +"real tower from grounded feet "
                +worker.blockPosition().toShortString()
                +" base="+j.base.toShortString()
                +" offsetSq="+baseOffset
                +" solidUnderFeet="+level.getBlockState(underFeet));
        jobs.remove(worker);
        return true;
    }
    /** Physical bridge motion is reserved exclusively to the scaffold
     * controller; vanilla A* cannot treat an air-above-scaffold cell as
     * a generic walkable ground node. Keep gravity and actual collisions.
     */
    public static boolean tickMotion(ServerLevel level,BuilderGolem worker){
        Job j=jobs.get(worker);
        if(j==null)return false;
        j.bridgeTicks++;
        j.highest=Math.max(j.highest,worker.getY());
        // A worker that already left the paid scaffold onto a genuine
        // solid surface must not be pulled back by an obsolete READY or
        // WALK bridge leg. Keep ownership of the physical materials.
        if(releaseDetachedPlan(level,worker))return false;
        // A READY bridge or a walking bridge can keep oscillating long
        // after its claim is released. A propeller may take over only
        // after a verified physical flight to solid ground exists.
        if(BuilderFlight.enabled(worker)
                &&(j.phase==Phase.READY||j.phase==Phase.WALK_FIRST
                    ||j.phase==Phase.WALK_SECOND||j.phase==Phase.RETURN_FIRST
                    ||j.phase==Phase.RETURN_TOWER)){
            long now=level.getGameTime();
            BlockPos goal=switch(j.phase){
                case WALK_FIRST->j.first().above();
                case WALK_SECOND->j.second().above();
                case RETURN_FIRST->j.bridge(Math.max(1,j.returnStep)).above();
                case RETURN_TOWER->j.base.atY(j.top+1);
                default->j.bridge(j.bridgeSpan).above();
            };
            double distance=worker.distanceToSqr(goal.getX()+.5,goal.getY(),goal.getZ()+.5);
            if(j.mobilityProgressAt<0||!goal.equals(j.mobilityGoal)){
                j.mobilityGoal=goal;j.mobilityBest=distance;j.mobilityProgressAt=now;
            }else if(distance<j.mobilityBest-.04){
                j.mobilityBest=distance;j.mobilityProgressAt=now;
            }
            boolean idle=level.getBlockEntity(j.planner) instanceof WorkerStationBlockEntity table
                    &&table.claimedStep(worker.getUUID())<0;
            if(now>=j.nextPoweredRecovery
                    &&(idle&&now-j.lastProgress>100||now-j.mobilityProgressAt>160)){
                j.nextPoweredRecovery=now+40;
                if(BuilderFlight.exitStalledScaffold(level,worker)){
                    System.out.println("[PLAN A POWERED EXIT] "+worker.getName().getString()
                            +" left stale bridge at "+worker.position());
                    return true;
                }
            }
        }
        // Recover a lost vertical controller while physically suspended in
        // the paid scaffold shaft. Never treat an airborne scaffold occupant
        // as a completed work stance, and never fake a position change.
        if(j.phase==Phase.CLIMB
                &&!BuilderScaffolding.climbingNow(level,worker)
                &&level.getGameTime()-j.lastProgress>20
                &&level.getBlockEntity(j.planner)
                        instanceof WorkerStationBlockEntity table
                &&physicalTowerTop(level,worker,table,j)==j.top
                &&worker.getY()>=j.base.getY()-.15
                &&worker.getY()<j.top+.88
                &&Math.abs(worker.getX()-j.base.getX()-.5)<.35
                &&Math.abs(worker.getZ()-j.base.getZ()-.5)<.35
                &&(level.getBlockState(worker.blockPosition()).is(Blocks.SCAFFOLDING)
                    ||level.getBlockState(worker.blockPosition().below()).is(Blocks.SCAFFOLDING))
                &&level.getBlockState(j.base.atY(j.top+1)).isAir()
                &&level.getBlockState(j.base.atY(j.top+2)).isAir()){
            BuilderScaffolding.begin(level,worker,j.base,j.top+1.0,false);
            j.lastProgress=level.getGameTime();
            System.out.println("[PLAN A RECOVERY] restarted suspended scaffold climb "
                    +worker.blockPosition().toShortString()
                    +" shaft="+j.base.toShortString()+" top="+j.top);
        }
        if(j.phase==Phase.CLIMB
                &&releaseOrphanedClimb(level,worker,j))return false;
        if(j.phase==Phase.CLIMB&&j.bridgeTicks%10==0
                &&level.getGameTime()-j.lastProgress>80
                &&!BuilderScaffolding.climbingNow(level,worker)
                &&level.getBlockEntity(j.planner)
                        instanceof WorkerStationBlockEntity table){
            int actual=physicalTowerTop(level,worker,table,j);
            double dx=worker.getX()-j.base.getX()-.5;
            double dz=worker.getZ()-j.base.getZ()-.5;
            if(actual<j.top&&dx*dx+dz*dz<1.32
                    &&worker.getY()<=actual+1.2
                    &&j.repairs<3){
                j.repairs++;
                j.phase=Phase.BUILD;
                j.lastProgress=level.getGameTime();
                repairLog(level,worker,table,j,ScaffoldFault.TOWER_SHORT,
                        "extend/replace missing paid shaft from real column"
                        +" attempt="+j.repairs);
            }else if(actual==j.top&&dx*dx+dz*dz<1.32
                    &&worker.getY()>=j.base.getY()-.15
                    &&worker.getY()<j.top+.88
                    &&level.getGameTime()-j.lastRepairLog>=100){
                // The shaft exists, yet the previous climb controller is
                // gone and the Builder is still INSIDE its top scaffold.
                // Diagnose new overhead construction separately from
                // an interrupted climb: neither permits a fake Y step.
                BlockPos head=j.base.atY(j.top+1);
                boolean blocked=!level.getBlockState(head).isAir()
                        ||!level.getBlockState(head.above()).isAir();
                if(blocked||j.repairs>=3){
                    rejectEntry(level,worker,j.base);
                    repairLog(level,worker,table,j,
                            blocked?ScaffoldFault.CLIMB_HEADROOM_BLOCKED:
                                    ScaffoldFault.CLIMB_STALLED,
                            "abandon unsafe crest; descend real shaft and replan");
                    returnToGround(level,worker,table);
                }else{
                    j.repairs++;
                    j.lastProgress=level.getGameTime();
                    BuilderScaffolding.begin(level,worker,j.base,
                            j.top+1.0,false);
                    repairLog(level,worker,table,j,ScaffoldFault.CLIMB_STALLED,
                            "resume physical ascent inside complete shaft"
                            +" attempt="+j.repairs);
                }
            }
        }
        if(j.phase==Phase.READY
                &&level.getBlockEntity(j.planner)
                        instanceof WorkerStationBlockEntity table){
            BlockPos last=j.bridge(j.bridgeSpan);
            if(!onOwnedScaffoldTop(level,worker,table,last)
                    ||!close(worker,last.above())){
                j.phase=j.bridgeSpan==1
                        ?Phase.WALK_FIRST:Phase.WALK_SECOND;
                if(j.bridgeSpan>1)j.bridgeStep=j.bridgeSpan;
                System.out.println("[PLAN A FOOTING] reacquire lost "
                        +"scaffold-top stance "+last.toShortString()
                        +" from feetY="+worker.getY());
            }
        }
        // The climb may crest between five-tick work calls, but a
        // transient airborne Y value is NOT stable standing height.
        // Advance only after native gravity/collision settles the
        // Builder ON TOP of the actual scaffold shaft.
        // A bridge can connect directly to sturdy stone beside a Builder
        // whose feet have settled INSIDE the scaffold. After a real stall,
        // leave at the current height instead of restarting the same climb.
        if(level.getGameTime()-j.lastProgress>60
                &&(j.phase==Phase.WALK_FIRST||j.phase==Phase.WALK_SECOND
                    ||j.phase==Phase.READY
                        &&!BuilderConstructionPlanner.safeStand(
                                level,worker,j.target,worker.blockPosition()))
                &&level.getBlockEntity(j.planner)
                        instanceof WorkerStationBlockEntity exitTable
                &&BuilderScaffolding.stepOffAtFootHeight(
                        level,worker,exitTable,j.target)){
            j.phase=Phase.DISMOUNT;
            j.lastProgress=level.getGameTime();
            System.out.println("[PLAN A SIDE EXIT] direct supported ledge "
                    +worker.blockPosition().toShortString());
            return true;
        }
        if(j.phase==Phase.CLIMB
                &&onScaffoldTop(level,worker,j.base.atY(j.top))){
            j.phase=Phase.EXTEND_FIRST;
            j.lastProgress=level.getGameTime();
        }
        if(j.phase==Phase.APPROACH&&j.fractionalSideEntry){
            double[] step=fractionalSideMove(level,worker,j.base);
            if(step!=null){
                worker.getNavigation().stop();
                worker.setNoGravity(false);
                worker.setJumping(false);
                worker.getMoveControl().setWantedPosition(
                        worker.getX()+step[0],worker.getY(),
                        worker.getZ()+step[1],1.0);
                worker.setDeltaMovement(step[0],
                        Math.min(0,worker.getDeltaMovement().y),step[1]);
                return true;
            }
        }
        if(j.phase==Phase.DESCEND||j.phase==Phase.DISMOUNT){
            // Complete the return even if the Builder's claim expired or
            // its next task can be handled without a scaffold. The physical
            // climb/exit controller runs first in BuilderGolem.tick.
            if(!(level.getBlockEntity(j.planner)
                    instanceof WorkerStationBlockEntity table))return true;
            return worker.tickCount%5!=0||returnToGround(level,worker,table);
        }
        BlockPos destination=switch(j.phase){
            case WALK_FIRST->j.first().above();
            case RETURN_FIRST->j.bridge(j.returnStep).above();
            case WALK_SECOND->j.second().above();
            case RETURN_TOWER->j.base.atY(j.top+1);
            default->null;
        };
        if(destination==null)return false;
        if(!(level.getBlockEntity(j.planner)
                instanceof WorkerStationBlockEntity table))return true;
        if(j.phase==Phase.RETURN_FIRST||j.phase==Phase.RETURN_TOWER){
            double remaining=worker.distanceToSqr(destination.getX()+.5,
                    destination.getY(),destination.getZ()+.5);
            if(!destination.equals(j.returnWaypoint)
                    ||remaining<j.bestReturnDistance-.04){
                j.returnWaypoint=destination.immutable();
                j.bestReturnDistance=remaining;
                j.returnProgressAt=level.getGameTime();
                j.nextReturnRecovery=0;
            }
            if(level.getGameTime()-j.returnProgressAt>80
                    &&level.getGameTime()>=j.nextReturnRecovery){
                j.nextReturnRecovery=level.getGameTime()+40;
                BlockPos floor=worker.blockPosition().below();
                if(worker.onGround()&&!worker.isNoGravity()
                        &&!level.getBlockState(floor).is(Blocks.SCAFFOLDING)
                        &&level.getBlockState(floor).isFaceSturdy(
                                level,floor,Direction.UP)
                        &&WorkerNavigation.canStandOn(
                                level,worker,worker.blockPosition())){
                    // A new roof can leave us safely on solid ground
                    // while the recorded return points through that roof.
                    // Release the stale route, preserving paid scaffolds.
                    rejectEntry(level,worker,j.base);
                    jobs.remove(worker);
                    WorkerNavigation.stop(worker);
                    System.out.println("[PLAN A RETURN RESET] already on solid "
                            +"footing at "+worker.blockPosition().toShortString());
                    return false;
                }
                if(escapeBlockedExit(level,worker,table,j)){
                    j.returnProgressAt=level.getGameTime();
                    return true;
                }
                // No physical exit was proved. Stay on the paid bridge;
                // a watchdog must never turn a stalled return into a fall.
            }
        }
        if(!present(level,table,worker,destination.below())){
            // A bridge that fell is not a walkable node. Recover the exact
            // paid missing step if the Builder still stands at its parent.
            if(repairBridge(level,worker,table,j))return false;
            if(level.getGameTime()-j.lastRepairLog>=100)
                repairLog(level,worker,table,j,ScaffoldFault.BRIDGE_SHORT,
                        "cannot step into air; await a supported repair pose");
            return true;
        }
        // Before walking sideways, physically finish the current
        // scaffold climb. A Builder at the tower lip can drop INSIDE
        // the bottom=true horizontal scaffold, and pure horizontal
        // steering cannot lift it back to the work Y. Reuse the same
        // scaffold climb controller for whichever owned cell it occupies.
        if(BuilderScaffolding.climbingNow(level,worker))return false;
        if(worker.getY()<destination.getY()-.12){
            BlockPos column=worker.blockPosition().atY(destination.getY()-1);
            if(!present(level,table,worker,column)){
                // When the feet are inside scaffold, the current tile
                // itself may be the support column (not below).
                column=worker.blockPosition();
            }
            if(present(level,table,worker,column)){
                j.reclimbs++;
                BuilderScaffolding.begin(level,worker,column,
                        destination.getY(),false);
                return false; // BuilderGolem.tick invokes vertical physics.
            }
            return true; // unsupported: never step horizontally into air
        }
        worker.getNavigation().stop();
        worker.setJumping(false);
        worker.setShiftKeyDown(false);
        worker.setNoGravity(false);
        double tx=destination.getX()+.5,tz=destination.getZ()+.5;
        if(close(worker,destination)
                &&onOwnedScaffoldTop(level,worker,table,destination.below())){
            Phase completed=j.phase;
            j.phase=switch(completed){
                case WALK_FIRST->j.bridgeSpan==1
                        ?Phase.READY:Phase.EXTEND_SECOND;
                case WALK_SECOND->j.bridgeStep<j.bridgeSpan
                        ?Phase.EXTEND_SECOND:Phase.READY;
                case RETURN_FIRST->j.returnStep>1
                        ?Phase.RETURN_FIRST:Phase.RETURN_TOWER;
                case RETURN_TOWER->Phase.DESCEND;
                default->j.phase;
            };
            // WALK_FIRST schedules the SECOND bridge cell, which has not
            // yet been placed. Only advance after finishing a WALK_SECOND.
            // Skipping cell two fabricated a nonexistent scaffold parent.
            if(completed==Phase.WALK_SECOND&&j.phase==Phase.EXTEND_SECOND)
                j.bridgeStep++;
            if(completed==Phase.RETURN_FIRST&&j.returnStep>1)
                j.returnStep--;
            j.lastProgress=level.getGameTime();
            return true;
        }
        double dx=tx-worker.getX(),dz=tz-worker.getZ();
        double mx=Math.max(-.12,Math.min(.12,dx*.3));
        double mz=Math.max(-.12,Math.min(.12,dz*.3));
        // Minecraft scaffolding is climb-through material, not a solid
        // slab. When bridging sideways a real mob can settle INSIDE the
        // next scaffold and lose one block of feet elevation. Maintain
        // climbing contact while walking, with the same bounded upward
        // impulse as our ordinary scaffold ascent. Never disable gravity,
        // jump over the bridge, setPos, or bypass collision.
        double rise=destination.getY()-worker.getY();
        // A horizontal scaffold can be flagged bottom=true: the mob
        // may settle inside it, one block below the work level. Plan A
        // explicitly permits a real ONE-BLOCK jump, never larger hops.
        // This is not the tall scaffold climb: it is a short ground-level
        // hop to regain the supported bridge surface.
        boolean hop=rise>.55&&rise<=1.1;
        if(hop){
            worker.getJumpControl().jump();
            worker.setJumping(true);
        }
        double vy=hop?.42:rise>.08
                ?Math.min(.23,Math.max(.12,rise/5))
                :Math.min(0,worker.getDeltaMovement().y);
        worker.getMoveControl().setWantedPosition(tx,destination.getY(),tz,1.0);
        // Scaffolding is a climb-through block. Once the Builder has stepped
        // into its first horizontal cell, its collision box intersects the
        // scaffold's own shape. World.noCollision() against that *existing*
        // intersection is false even when the next movement is legal: the
        // old guard therefore suppressed every upward impulse and stranded
        // a real Builder one block below the planned work elevation.
        //
        // Exactly as with a vertical scaffold climb, send bounded velocity
        // into Minecraft's normal entity movement/collision solver. Actual
        // blocks still stop the movement. Both bridge supports, headroom,
        // item receipts and the reserved return path were checked first.
        // Do not teleport, disable gravity, or invoke a fallback pathfinder.
        if(!level.hasChunkAt(destination)
                ||!level.getBlockState(destination).isAir()
                ||!level.getBlockState(destination.above()).isAir()){
            worker.setDeltaMovement(0,Math.min(0,worker.getDeltaMovement().y),0);
            return true;
        }
        worker.setDeltaMovement(mx,vy,mz);
        return true;
    }
    public static Result reach(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,ServerPlayer owner,
            BlockPos target,List<BuilderConstructionPlan.Step> steps){
        return reach(level,worker,table,owner,target,steps,false);
    }
    /** Builder placement calls this variant; ordinary Plan-A mobility
     * may still use shorter one-cell bridges without claiming a safe pose.
     */
    public static Result reachWorkPose(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,ServerPlayer owner,
            BlockPos target,List<BuilderConstructionPlan.Step> steps){
        return reach(level,worker,table,owner,target,steps,true);
    }
    private static Result reach(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,ServerPlayer owner,
            BlockPos target,List<BuilderConstructionPlan.Step> steps,
            boolean workPose){
        Job j=jobs.get(worker);
        if(j!=null&&!j.target.equals(target)){
            // A paid bridge can serve another block only when the actual
            // landing is a safe work stance for that new block.
            if(workPose&&safeTemporaryWorkPose(level,worker,table,target))
                return Result.READY;
            if((j.phase==Phase.READY||j.phase==Phase.WALK_SECOND
                    ||j.phase==Phase.DISMOUNT)
                    &&BuilderScaffolding.stepOffTowardWork(
                            level,worker,table,target)){
                j.phase=Phase.DISMOUNT;
                return Result.MOVING;
            }
            if(returnToGround(level,worker,table))return Result.MOVING;
            j=null;
        }
        if(j==null){
            j=choose(level,worker,table,target,steps,workPose);
            if(j==null)return Result.NO_SAFE_ROUTE;
            jobs.put(worker,j);
        }
        switch(j.phase){
            case APPROACH -> {
                // The native ground leg normally stops within roughly
                // 0.6m of a tile center. Requiring +/-0.3 on BOTH axes
                // left all four dome Builders idle beside valid towers:
                // they had reached the base tile but could not finish
                // fractional centering around the roof's tight corners.
                // The actual scaffold controller safely recenters the
                // body before climbing, using real collision checks.
                double horizontal=worker.distanceToSqr(
                        j.base.getX()+.5,worker.getY(),j.base.getZ()+.5);
                boolean sideEntry=canEnterFromBelow(level,worker,j.base);
                if(j.launch!=null&&!sideEntry){
                    double distance=worker.distanceToSqr(
                            j.launch.getX()+.5,j.launch.getY(),
                            j.launch.getZ()+.5);
                    if(distance<j.closestApproach-.12){
                        j.closestApproach=distance;
                        j.lastProgress=level.getGameTime();
                    }
                    if(level.getGameTime()-j.lastProgress>180){
                        rejectEntry(level,worker,j.base);
                        System.out.println("[PLAN A STEP BACK] launch blocked "
                                +j.launch.toShortString()+" from "
                                +worker.blockPosition().toShortString()
                                +"; try another scaffold base");
                        jobs.remove(worker);
                        return Result.NO_SAFE_ROUTE;
                    }
                    WorkerNavigation.routeToExact(
                            level,worker,j.launch,1.0);
                    return Result.MOVING;
                }
                if(!sideEntry&&j.base.getY()>worker.getY()+.40
                        &&j.base.getY()<=worker.getY()+1.10){
                    BlockPos launch=alternateSideLaunch(level,worker,j.base);
                    if(launch!=null){
                        double approachDistance=worker.distanceToSqr(
                                launch.getX()+.5,launch.getY(),
                                launch.getZ()+.5);
                        if(approachDistance<j.closestApproach-.12){
                            j.closestApproach=approachDistance;
                            j.lastProgress=level.getGameTime();
                        }
                        if(level.getGameTime()-j.lastProgress<=180){
                            WorkerNavigation.routeToExact(
                                    level,worker,launch,1.0);
                            return Result.MOVING;
                        }
                    }
                }
                if(!sideEntry
                        &&(horizontal>1.20
                            ||Math.abs(worker.getY()-j.base.getY())>.4)){
                    double approachDistance=worker.distanceToSqr(
                            j.base.getX()+.5,j.base.getY(),j.base.getZ()+.5);
                    if(approachDistance<j.closestApproach-.12){
                        j.closestApproach=approachDistance;
                        j.lastProgress=level.getGameTime();
                    }
                    if(level.getGameTime()-j.lastProgress>180){
                        // A reachable base preview is NOT permission to
                        // hold this unfinished placement forever. The
                        // body may now be on another roof elevation or
                        // behind a changed wall. Retire only this entry,
                        // leaving other towers/frontier cells available.
                        rejectEntry(level,worker,j.base);
                        System.out.println("[PLAN A ENTRY] retry alternate base for "
                                +j.target.toShortString()+" from "
                                +worker.blockPosition().toShortString()
                                +" rejected="+j.base.toShortString()
                                +" distanceSq="+approachDistance);
                        jobs.remove(worker);
                        return Result.NO_SAFE_ROUTE;
                    }
                    WorkerNavigation.routeToExact(level,worker,j.base,1.0);
                    return Result.MOVING;
                }
                j.lastProgress=level.getGameTime();
                if(sideEntry)
                    System.out.println("[PLAN A SIDE ENTRY] paid supported shaft "
                            +j.base.toShortString()+" from real feet "
                            +worker.blockPosition().toShortString());
                j.phase=Phase.BUILD;
            }
            case CLIMB -> {
                if(releaseOrphanedClimb(level,worker,j))
                    return Result.NO_SAFE_ROUTE;
                if(worker.getY()<j.top+1.0-.15){
                    // Extend long shafts in real sixteen-block inventory
                    // stages, after physically finishing the prior climb.
                    if(!BuilderScaffolding.climbingNow(level,worker)
                            &&Math.abs(worker.getX()-j.base.getX()-.5)<.35
                            &&Math.abs(worker.getZ()-j.base.getZ()-.5)<.35){
                        j.phase=Phase.BUILD;
                        return shaft(level,worker,table,j);
                    }
                    return Result.MOVING;
                }
                if(!onOwnedScaffoldTop(level,worker,table,
                        j.base.atY(j.top)))return Result.MOVING;
                j.phase=Phase.EXTEND_FIRST;
            }
            case EXTEND_FIRST -> {
                // The climb may have crested transiently before real
                // collision support settled. Do not lay the bridge from
                // inside the shaft or while airborne.
                if(!onOwnedScaffoldTop(level,worker,table,
                        j.base.atY(j.top))){
                    j.phase=Phase.CLIMB;
                    return Result.MOVING;
                }
                Result result=place(level,worker,table,owner,j.first(),
                        j.base.atY(j.top));
                if(result!=Result.READY)return result;
                if(!structurallySupported(level,j.first()))
                    return Result.NO_SAFE_ROUTE;
                j.phase=Phase.WALK_FIRST;
                return Result.MOVING;
            }
            case EXTEND_SECOND -> {
                // Every paid bridge cell is laid from a physically settled
                // prior cell, never from a fleeting midair Y coordinate.
                BlockPos previous=j.bridge(j.bridgeStep-1);
                if(!onOwnedScaffoldTop(level,worker,table,previous)){
                    if(j.bridgeStep==2)j.phase=Phase.WALK_FIRST;
                    else{
                        j.bridgeStep--;
                        j.phase=Phase.WALK_SECOND;
                    }
                    return Result.MOVING;
                }
                Result result=place(level,worker,table,owner,j.second(),
                        j.bridge(j.bridgeStep-1));
                if(result!=Result.READY)return result;
                if(!structurallySupported(level,j.second()))
                    return Result.NO_SAFE_ROUTE;
                j.phase=Phase.WALK_SECOND;
                return Result.MOVING;
            }
            case READY -> {
                BlockPos finalScaffold=j.bridge(j.bridgeSpan);
                if(!onOwnedScaffoldTop(level,worker,table,finalScaffold)
                        ||!close(worker,finalScaffold.above())){
                    // A real Builder can sink back INTO the climb-through
                    // scaffold just after a horizontal step. Re-enter the
                    // physical last bridge step instead of leaving a READY
                    // plan with no active motion for thousands of ticks.
                    j.phase=j.bridgeSpan==1
                            ?Phase.WALK_FIRST:Phase.WALK_SECOND;
                    if(j.bridgeSpan>1)j.bridgeStep=j.bridgeSpan;
                    System.out.println("[PLAN A FOOTING] rejoin unfinished "
                            +"scaffold bridge at "+finalScaffold.toShortString()
                            +" feetY="+worker.getY()
                            +" grounded="+worker.onGround());
                    return Result.MOVING;
                }
                if(workPose&&!safeTemporaryWorkPose(level,worker,table,target)){
                    // Construction can close the sight line or remove part
                    // of a bridge after the route has already been planned.
                    // Preserve the actual return route and try a new entry.
                    rejectEntry(level,worker,j.base);
                    if(BuilderScaffolding.stepOffAtFootHeight(level,worker,table,target)){
                        j.phase=Phase.DISMOUNT;
                        return Result.MOVING;
                    }
                    return returnToGround(level,worker,table)
                            ?Result.MOVING:Result.NO_SAFE_ROUTE;
                }
                return Result.READY;
            }
            case RETURN_FIRST,RETURN_TOWER,DESCEND,DISMOUNT -> {
                // A Builder may still hold the SAME work cell after its
                // scaffold return began. Service the physical return here
                // as well as during a change of target; otherwise DISMOUNT
                // remains a motionless MOVING result indefinitely.
                return returnToGround(level,worker,table)
                        ?Result.MOVING:Result.NO_SAFE_ROUTE;
            }
            case WALK_FIRST,WALK_SECOND -> {return Result.MOVING;}
            default -> {}
        }
        if(j.phase==Phase.BUILD)return shaft(level,worker,table,j);
        return Result.MOVING;
    }
    public static boolean returnToGround(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table){
        Job j=jobs.get(worker);
        if(j==null)return false;
        if(j.phase==Phase.APPROACH
                ||j.phase==Phase.BUILD&&worker.getY()<=j.base.getY()+.2){
            jobs.remove(worker);
            return false;
        }

        if(j.phase==Phase.READY||j.phase==Phase.WALK_SECOND){
            // Retrace every real bridge cell before descending the
            // reserved shaft; never jump from the far end to the base.
            j.returnStep=Math.max(1,j.bridgeStep-1);
            j.phase=Phase.RETURN_FIRST;
            return true;
        }
        if(j.phase==Phase.EXTEND_SECOND){
            j.returnStep=Math.max(1,j.bridgeStep-1);
            j.phase=Phase.RETURN_FIRST;
            return true;
        }
        if(j.phase==Phase.WALK_FIRST){
            j.phase=Phase.RETURN_TOWER;
            return true;
        }
        if(j.phase==Phase.EXTEND_FIRST){
            j.phase=Phase.DESCEND;
            return true;
        }
        if(j.phase==Phase.RETURN_FIRST||j.phase==Phase.RETURN_TOWER)
            return true;
        if(j.phase==Phase.CLIMB)j.phase=Phase.DESCEND;
        if(j.phase==Phase.DESCEND){
            if(!refreshExit(level,worker,table,j)){
                if(escapeBlockedExit(level,worker,table,j))return true;
                // If the side exits closed, finish lowering within the
                // paid shaft onto its real foundation. The grounded
                // dismount can recover its own snag without dropping.
                double dx=worker.getX()-j.base.getX()-.5;
                double dz=worker.getZ()-j.base.getZ()-.5;
                if(dx*dx+dz*dz>.16||!present(level,table,worker,j.base)
                        ||!level.getBlockState(j.base.below()).isFaceSturdy(
                                level,j.base.below(),Direction.UP))return true;
                if(worker.getY()>j.base.getY()+.16){
                    BuilderScaffolding.begin(level,worker,j.base,j.base.getY(),true);
                    return true;
                }
                j.phase=Phase.DISMOUNT;
            }else{
                // A drop of up to two blocks to real solid ground is allowed.
                // Larger drops first descend farther through the paid shaft.
                double safeStop=Math.min(j.base.getY()+1.0,
                        j.exit.getY()+2.0);
                if(worker.getY()>safeStop+.16){
                    BuilderScaffolding.begin(level,worker,j.base,safeStop,true);
                    return true;
                }
                j.phase=Phase.DISMOUNT;
            }
        }
        if(j.phase==Phase.DISMOUNT){
            // Never release a high grounded worker merely because its
            // feet Y is above the tower base. Scaffolding can report
            // onGround=true while it is still the only support. The
            // verified non-scaffold-solid-footing check below handles
            // upper roof exits, ordinary ground, and lower safe landings.

            // Once the worker is physically standing on SOLID ground at
            // the tower's base elevation, its descent is complete; the
            // exact compass-side exit is no longer a mandatory position.
            // Continuing to steer toward a specific exit can push a
            // landed worker through its own standing scaffold tower.
            BlockPos below=worker.blockPosition().below();
            // A valid descent is not automatically a valid dismount.
            // The Builder may be on the stone floor but still INSIDE its
            // own scaffold footprint. Cleanup must never tear scaffolds
            // from under a worker; physically move onto an adjacent solid
            // exit BEFORE releasing this Plan A job.
            boolean clearOfOwnedScaffold=true;
            for(BlockPos scaffold:table.scaffoldPositions(worker.getUUID())){
                // Compare all THREE axes. A horizontal bridge at Y+3
                // is overhead, not underneath the grounded worker.
                // Blocking dismount based only on X/Z strands a builder
                // forever beneath its own safe return bridge.
                if(level.getBlockState(scaffold).is(Blocks.SCAFFOLDING)
                        &&Math.abs(scaffold.getX()+.5-worker.getX())<.9
                        &&Math.abs(scaffold.getZ()+.5-worker.getZ())<.9
                        &&scaffold.getY()+1.0>worker.getBoundingBox().minY-.1
                        &&scaffold.getY()<worker.getBoundingBox().maxY+.1){
                    clearOfOwnedScaffold=false;
                    break;
                }
            }
            // A grounded Builder can be caught in the SIDE of its own
            // scaffold despite having sturdy, non-scaffold footing. Remove
            // only the intersecting paid scaffold, not the whole tower:
            // other workers may still be using the remaining structure.
            if(worker.onGround()&&!clearOfOwnedScaffold
                    &&!level.getBlockState(below).is(Blocks.SCAFFOLDING)
                    &&level.getBlockState(below).isFaceSturdy(
                            level,below,Direction.UP)
                    &&level.getGameTime()-j.lastProgress>40){
                int removed=BuilderScaffolding.clearGroundedScaffoldSnag(
                        level,worker,table);
                if(removed>0){
                    j.lastProgress=level.getGameTime();
                    System.out.println("[PLAN A DISMOUNT RECOVERY] cleared "
                            +removed+" owned scaffold snag(s) at "
                            +worker.blockPosition().toShortString());
                    jobs.remove(worker);
                    return false;
                }
            }
            // Stop repeating a dismount that has made no progress. On real
            // solid footing the worker can safely abandon this scaffold
            // job, reclaim unoccupied scaffolding and replan from scratch.
            // Reject the old base so the next plan cannot recreate the
            // exact same failed tower immediately.
            if(worker.onGround()
                    &&!worker.isNoGravity()
                    &&!level.getBlockState(below).is(Blocks.SCAFFOLDING)
                    &&level.getBlockState(below).isFaceSturdy(
                            level,below,Direction.UP)
                    &&level.getGameTime()-j.lastProgress>160){
                if(!clearOfOwnedScaffold){
                    BuilderScaffolding.clearGroundedScaffoldSnag(
                            level,worker,table);
                }
                rejectEntry(level,worker,j.base);
                System.out.println("[PLAN A DISMOUNT RESET] grounded stall; "
                        +"abandoning scaffold base="
                        +j.base.toShortString()+" worker="
                        +worker.blockPosition().toShortString());
                jobs.remove(worker);
                BuilderScaffolding.retrieve(level,worker,table,false);
                return false;
            }
            BlockPos feet=worker.blockPosition();
            double floor=WorkerNavigation.surveyFloorHeight(level,feet);
            if(worker.onGround()&&!worker.isNoGravity()&&clearOfOwnedScaffold
                    &&Math.abs(worker.getY()-floor)<.12
                    &&BuilderScaffolding.walkableExit(level,worker,feet)){
                if(worker.getY()<j.base.getY()-.35){
                    rejectEntry(level,worker,j.base);
                    System.out.println("[PLAN A DISMOUNT] released stale tower "
                            +j.base.toShortString()+" from lower real footing "
                            +feet.toShortString());
                }
                jobs.remove(worker);
                return false;
            }
            if(!refreshExit(level,worker,table,j)){
                escapeBlockedExit(level,worker,table,j);
                return true;
            }
            if(!BuilderScaffolding.hasPhysicalMotion(worker)
                    &&!BuilderScaffolding.dismountAtBase(
                            level,worker,j.base,j.exit)){
                // The physical two-block side drop was obstructed.
                // Retreat ONE level inside owned scaffold and re-attempt
                // the shorter natural drop. Never walk through a wall.
                double dx=worker.getX()-j.base.getX()-.5;
                double dz=worker.getZ()-j.base.getZ()-.5;
                if(present(level,table,worker,j.base)
                        &&dx*dx+dz*dz<1.32
                        &&worker.getY()>j.exit.getY()+1.35){
                    BuilderScaffolding.begin(level,worker,j.base,
                            j.exit.getY()+1.0,true);
                    if(level.getGameTime()-j.lastRepairLog>=80)
                        repairLog(level,worker,table,j,
                                ScaffoldFault.EXIT_DROP_OBSTRUCTED,
                                "side drop blocked; lower real scaffold stance");
                }
            }
            return true;
        }
        return true;
    }
    /** Recover the return route when the paid bridge outlives its work plan. */
    static boolean recoverBridgeReturn(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table){
        if(jobs.containsKey(worker)||BuilderScaffolding.hasPhysicalMotion(worker))
            return false;
        BlockPos feet=worker.blockPosition();
        BlockPos start=present(level,table,worker,feet)?feet:feet.below();
        if(!present(level,table,worker,start)||!structurallySupported(level,start))
            return false;
        var distanceProperty=net.minecraft.world.level.block.ScaffoldingBlock.DISTANCE;
        if(level.getBlockState(start).getValue(distanceProperty)==0)return false;
        var queue=new ArrayDeque<BlockPos>();
        var previous=new HashMap<BlockPos,BlockPos>();
        queue.add(start);
        previous.put(start,start);
        while(!queue.isEmpty()){
            BlockPos cell=queue.removeFirst();
            int distance=level.getBlockState(cell).getValue(distanceProperty);
            if(distance==0){
                BlockPos base=cell;
                while(level.hasChunkAt(base.below())
                        &&present(level,table,worker,base.below()))
                    base=base.below();
                if(!level.getBlockState(base.below()).isFaceSturdy(
                        level,base.below(),Direction.UP))continue;
                BlockPos exit=BuilderScaffolding.safeGroundExit(
                        level,worker,base,Set.of());
                if(exit==null)continue;
                var bridge=new ArrayList<BlockPos>();
                for(BlockPos next=previous.get(cell);;next=previous.get(next)){
                    bridge.add(next);
                    if(next.equals(start))break;
                }
                Job job=new Job(feet,base,exit,table.getBlockPos(),bridge,
                        Set.of(),cell.getY(),level.getGameTime());
                job.returnStep=bridge.size()-1;
                job.phase=job.returnStep>0?Phase.RETURN_FIRST:Phase.RETURN_TOWER;
                jobs.put(worker,job);
                WorkerNavigation.stop(worker);
                System.out.println("[PLAN A RETURN RECOVERED] paid bridge from "
                        +start.toShortString()+" to "+base.toShortString());
                return true;
            }
            for(Direction side:Direction.Plane.HORIZONTAL){
                BlockPos next=cell.relative(side);
                if(previous.containsKey(next)||!present(level,table,worker,next)
                        ||level.getBlockState(next).getValue(distanceProperty)>=distance
                        ||!bridgeCellClear(level,next,Set.of(),table,worker))continue;
                previous.put(next,cell);
                queue.addLast(next);
            }
        }
        return false;
    }

}
