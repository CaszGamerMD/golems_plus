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
    private static final class Job {
        final BlockPos target,base,exit,planner;
        final List<BlockPos> bridgePath;
        final int top,bridgeSpan;
        int bridgeStep=2,returnStep=1;
        Phase phase=Phase.APPROACH;
        long lastProgress;
        int lastTick;
        int bridgeTicks,reclimbs;
        double highest=-Double.MAX_VALUE;
        double lastDistance=Double.POSITIVE_INFINITY;
        double closestApproach=Double.POSITIVE_INFINITY;
        Job(BlockPos target,BlockPos base,BlockPos exit,
                BlockPos planner,List<BlockPos> bridgePath,int top,long now){
            this.target=target.immutable();
            this.base=base.immutable();
            this.exit=exit.immutable();
            this.planner=planner.immutable();
            this.bridgePath=List.copyOf(bridgePath);
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
        if(j==null||j.phase!=Phase.READY||!j.target.equals(target)
                ||worker.isNoGravity()||!worker.onGround()
                ||Math.abs(worker.getY()-target.getY())>.18
                // The scaffold occupies targetY-1. Compare HORIZONTAL
                // reach to the actual work cube, not 3-D Manhattan
                // distance, which includes the intentional Y offset.
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
                        target.getY()+.5,target.getZ()+.5)<14
                &&BuilderConstructionPlanner.lineOfSight(
                        level,worker,worker.blockPosition(),target);
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
            case WALK_FIRST,WALK_SECOND,RETURN_FIRST,RETURN_TOWER->true;
            default->false;
        };
    }
    public static String status(BuilderGolem worker){
        Job j=jobs.get(worker);
        return j==null?"NONE":"PLAN_A "+j.phase+" tower="+j.base.toShortString()
                +" bridge="+j.first().toShortString()+".."+j.bridge(j.bridgeSpan).toShortString()
                +" step="+j.bridgeStep+"/"+j.bridgeSpan
                +" targetY="+j.target.getY()+" exit="+j.exit.toShortString()
                +" ticks="+j.bridgeTicks+" reclimbs="+j.reclimbs
                +" highest="+String.format(java.util.Locale.ROOT,"%.2f",j.highest);
    }
    public static void cancel(BuilderGolem worker){jobs.remove(worker);}

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
                if(!table.scaffoldPositions(worker.getUUID()).contains(p)
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
    private static Set<BlockPos> workStanceGoals(ServerLevel world,
            BuilderGolem worker,BlockPos target){
        Set<BlockPos> result=new HashSet<>();
        for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++){
            if(Math.max(Math.abs(dx),Math.abs(dz))<2
                    ||dx*dx+dz*dz>13)continue;
            BlockPos stance=target.offset(dx,0,dz);
            if(BuilderConstructionPlanner.lineOfSight(
                    world,worker,stance,target))
                result.add(stance.below());
        }
        return result;
    }
    private static List<BlockPos> bentBridge(ServerLevel world,
            BuilderGolem worker,WorkerStationBlockEntity table,
            BlockPos target,BlockPos base,Set<BlockPos> goals,
            Set<BlockPos> reserved){
        BlockPos start=base.atY(target.getY()-1);
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
                if(!table.scaffoldPositions(worker.getUUID()).contains(p))
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
        Map<BlockPos,Long> rejected=failedEntries.get(worker);
        boolean isolated=needsTemporaryWorkPlatform(level,target);
        List<Job> options=new ArrayList<>();
        Set<BlockPos> workGoals=workPose?
                workStanceGoals(level,worker,target):Set.of();
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
            boolean straightSight=!workPose||straight
                    &&BuilderConstructionPlanner.lineOfSight(level,worker,
                            target.relative(outward,2),target);
            // Search real standing elevations ABOVE the worker as well
            // as below. Existing pillars, stairs, slabs and complete roof
            // blocks can put a Builder onto a reachable elevated ledge.
            // The exact ground-path preflight below must prove the route.
            for(int dy=-4;dy<=5;dy++){
                BlockPos base=towerXZ.atY(worker.blockPosition().getY()-dy);
                int top=target.getY()-1;
                if(rejected!=null&&level.getGameTime()<
                        rejected.getOrDefault(base,Long.MIN_VALUE))continue;
                if(!shaftClear(level,base,top,reserved,table,worker))continue;
                List<BlockPos> bridge=new ArrayList<>();
                if(straight&&straightSight
                        &&bridgeClear(level,base,inward,target.getY(),
                        span,reserved,table,worker)){
                    for(int n=1;n<=span;n++)bridge.add(
                            base.relative(inward,n).atY(top));
                }else if(workPose){
                    bridge=bentBridge(level,worker,table,target,base,
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
                        table.getBlockPos(),bridge,top,level.getGameTime());
                candidate.lastDistance=score;
                options.add(candidate);
            }
        }
        options.sort(Comparator.comparingDouble(j->j.lastDistance));
        List<BlockPos> entryCandidates=new ArrayList<>();
        List<BlockPos> levelEntries=new ArrayList<>();
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
            if(!entryCandidates.contains(candidate.base))
                entryCandidates.add(candidate.base);
            if(candidate.base.getY()<=worker.blockPosition().getY()
                    &&!levelEntries.contains(candidate.base))
                levelEntries.add(candidate.base);
        }
        if(!workPose)return null;
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
        List<BlockPos> lowerBases=new ArrayList<>();
        String example="";
        for(Direction outward:Direction.Plane.HORIZONTAL)
            for(int distance=3;distance<=MAX_WORK_TOWER_DISTANCE;distance++)
                for(int dy=-4;dy<=5;dy++){
                    BlockPos base=target.relative(outward,distance)
                            .atY(worker.blockPosition().getY()-dy);
                    int top=target.getY()-1;
                    int span=distance-2;
                    if(!BuilderConstructionPlanner.lineOfSight(level,worker,
                            target.relative(outward,2),target))continue;
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
        return level.getBlockState(at).is(Blocks.SCAFFOLDING)
                &&table.scaffoldPositions(worker.getUUID()).contains(at);
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
        var load=BuilderSupplyInventory.loadScaffold(
                level,worker,table,count);
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
        BuilderScaffolding.begin(level,worker,j.base,top+1.0,false);
        return Result.MOVING;
    }
    private static boolean close(BuilderGolem worker,BlockPos p){
        return worker.distanceToSqr(p.getX()+.5,p.getY(),
                p.getZ()+.5)<.12
                &&Math.abs(worker.getY()-p.getY())<.30;
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
        BlockPos destination=switch(j.phase){
            case WALK_FIRST->j.first().above();
            case RETURN_FIRST->j.bridge(j.returnStep).above();
            case WALK_SECOND->j.second().above();
            case RETURN_TOWER->j.base.atY(j.target.getY());
            default->null;
        };
        if(destination==null)return false;
        if(!(level.getBlockEntity(j.planner)
                instanceof WorkerStationBlockEntity table)
                ||!present(level,table,worker,destination.below())){
            return true; // never deliberately step onto unsupported air
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
        if(close(worker,destination)){
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
            if(worker.blockPosition().getY()==target.getY()
                    &&worker.distanceToSqr(target.getX()+.5,
                            target.getY()+.5,target.getZ()+.5)<=4
                    &&j.phase==Phase.READY)return Result.READY;
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
                if(worker.getY()<j.target.getY()-.15){
                    // The scaffold controller abandons an impossible climb
                    // after three collision timeouts. A real Builder on the
                    // grounded lower ledge must not wait forever for a
                    // session which no longer exists. Replan another base
                    // instead of restarting the same blocked side entry.
                    if(!BuilderScaffolding.climbingNow(level,worker)
                            &&worker.onGround()
                            &&worker.getY()<j.base.getY()-.35){
                        rejectEntry(level,worker,j.base);
                        System.out.println("[PLAN A ENTRY] blocked climb, retry "
                                +"another tower from real ground at "
                                +worker.blockPosition().toShortString()
                                +" base="+j.base.toShortString());
                        jobs.remove(worker);
                        return Result.NO_SAFE_ROUTE;
                    }
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
                if(j.top>j.target.getY()-1)return Result.NO_SAFE_ROUTE;
                j.phase=Phase.EXTEND_FIRST;
            }
            case EXTEND_FIRST -> {
                Result result=place(level,worker,table,owner,j.first(),
                        j.base.atY(j.top));
                if(result!=Result.READY)return result;
                if(!structurallySupported(level,j.first()))
                    return Result.NO_SAFE_ROUTE;
                j.phase=Phase.WALK_FIRST;
                return Result.MOVING;
            }
            case EXTEND_SECOND -> {
                Result result=place(level,worker,table,owner,j.second(),
                        j.bridge(j.bridgeStep-1));
                if(result!=Result.READY)return result;
                if(!structurallySupported(level,j.second()))
                    return Result.NO_SAFE_ROUTE;
                j.phase=Phase.WALK_SECOND;
                return Result.MOVING;
            }
            case READY -> {
                if(worker.blockPosition().getY()!=target.getY())
                    return Result.MOVING;
                return Result.READY;
            }
            case RETURN_FIRST,RETURN_TOWER,DESCEND,DISMOUNT,
                    WALK_FIRST,WALK_SECOND-> {return Result.MOVING;}
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
        if(j.phase==Phase.CLIMB){
            if(worker.getY()>j.base.getY()+1.1)
                BuilderScaffolding.begin(level,worker,j.base,
                        j.base.getY()+1.0,true);
            j.phase=Phase.DESCEND;
            return true;
        }
        if(j.phase==Phase.DESCEND){
            if(worker.getY()>j.base.getY()+1.16){
                if(!BuilderScaffolding.climbingNow(level,worker))
                    BuilderScaffolding.begin(level,worker,j.base,
                            j.base.getY()+1.0,true);
                return true;
            }
            j.phase=Phase.DISMOUNT;
        }
        if(j.phase==Phase.DISMOUNT){
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
                if(Math.abs(scaffold.getX()+.5-worker.getX())<.9
                        &&Math.abs(scaffold.getZ()+.5-worker.getZ())<.9
                        &&scaffold.getY()+1.0>worker.getBoundingBox().minY-.1
                        &&scaffold.getY()<worker.getBoundingBox().maxY+.1){
                    clearOfOwnedScaffold=false;
                    break;
                }
            }
            if(worker.onGround()
                    &&Math.abs(worker.getY()-j.base.getY())<.26
                    &&clearOfOwnedScaffold
                    &&level.getBlockState(below).isFaceSturdy(
                            level,below,Direction.UP)){
                jobs.remove(worker);
                return false;
            }
            if(!BuilderScaffolding.isControllingMovement(worker))
                BuilderScaffolding.dismountAtBase(level,worker,j.base);
            return true;
        }
        return true;
    }
}
