package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import java.util.ArrayList;
import java.util.Comparator;
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
        final Direction towardTarget;
        final int top,bridgeSpan;
        int bridgeStep=2,returnStep=1;
        Phase phase=Phase.APPROACH;
        long lastProgress;
        int lastTick;
        int bridgeTicks,reclimbs;
        double highest=-Double.MAX_VALUE;
        double lastDistance=Double.POSITIVE_INFINITY;
        Job(BlockPos target,BlockPos base,BlockPos exit,
                BlockPos planner,Direction direction,int top,int span,long now){
            this.target=target.immutable();
            this.base=base.immutable();
            this.exit=exit.immutable();
            this.planner=planner.immutable();
            this.towardTarget=direction;
            this.top=top;
            this.bridgeSpan=span;
            this.lastProgress=now;
        }
        BlockPos bridge(int n){return base.relative(towardTarget,n).atY(top);}
        BlockPos first(){return bridge(1);}
        BlockPos second(){return bridge(bridgeStep);}
    }
    // Vanilla scaffolding stays structurally anchored up to six
    // horizontal blocks from its supporting tower. Distance seven falls.
    // The underside/bottom flag describes its shape, NOT an invalid span.
    static final int MAX_HORIZONTAL_SCAFFOLD_SPAN=6;
    private static final Map<BuilderGolem,Job> jobs=new WeakHashMap<>();
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
     * A full two-block center offset is necessary: standing only one
     * tile from the target violates the 0.8m edge-clearance rule.
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
                ||Math.abs(j.bridge(j.bridgeSpan).getX()-target.getX())
                    +Math.abs(j.bridge(j.bridgeSpan).getZ()-target.getZ())!=2
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
                ||choose(level,worker,table,target,steps)!=null;
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
    /** Horizontal owned scaffolding: 2-5 real paid cells, supported
     * by a real vertical shaft. Protect the whole return route from
     * unfinished schematic cells and head collisions.
     */
    private static boolean bridgeClear(ServerLevel world,BlockPos base,
            Direction toward,int workY,int span,Set<BlockPos> reserved,
            WorkerStationBlockEntity table,BuilderGolem worker){
        for(int n=1;n<=span;n++){
            BlockPos p=base.relative(toward,n).atY(workY-1);
            // A sideways scaffold requires no blocks beneath it while
            // within six horizontal cells of its tower. Validate the
            // actual extension distance, ownership, headroom and blueprint
            // reservation, not the BOTTOM flag.
            if(n>MAX_HORIZONTAL_SCAFFOLD_SPAN
                    ||!world.hasChunkAt(p)||reserved.contains(p)
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
        }
        return true;
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
    /** Prefer a previously erected owned axis-3 tower; otherwise
     * choose a genuinely reachable ground shaft with a verified exit.
     * No diagonal tower in Plan A.
     */
    private static Job choose(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,BlockPos target,
            List<BuilderConstructionPlan.Step> steps){
        Set<BlockPos> reserved=future(level,steps);
        List<Job> options=new ArrayList<>();
        for(Direction outward:Direction.Plane.HORIZONTAL)
            // The worker's center must remain at least two whole cells
            // from the target, regardless of whether it is isolated or
            // attached to a wall. One cell gives only 0.5m edge clearance.
            for(int distance=3;distance<=MAX_HORIZONTAL_SCAFFOLD_SPAN;distance++){
            BlockPos towerXZ=target.relative(outward,distance);
            Direction inward=outward.getOpposite();
            // Stop two cells short of an unsupported floating block:
            // the last bridge surface must clear the Builder's 0.8 m
            // placement margin. Scaffolding serves as a stand, NOT a
            // replacement for or permanent attachment to the schematic.
            int span=distance-2;
            BlockPos stance=target.relative(outward,2);
            // The future bridge itself supplies the floor. The final
            // position must still see the actual target and be inside
            // placement reach; a neighboring wall may block this side.
            if(stance.distSqr(target)>=14
                    ||!BuilderConstructionPlanner.lineOfSight(
                            level,worker,stance,target))continue;
            // Search real standing elevations ABOVE the worker as well
            // as below. Existing pillars, stairs, slabs and complete roof
            // blocks can put a Builder onto a reachable elevated ledge.
            // The exact ground-path preflight below must prove the route.
            for(int dy=-4;dy<=5;dy++){
                BlockPos base=towerXZ.atY(worker.blockPosition().getY()-dy);
                int top=target.getY()-1;
                if(!shaftClear(level,base,top,reserved,table,worker)
                        ||!bridgeClear(level,base,inward,target.getY(),
                                span,reserved,table,worker))continue;
                BlockPos exit=BuilderScaffolding.safeGroundExit(
                        level,worker,base,reserved);
                if(exit==null)continue;
                int existing=0;
                for(int y=base.getY();y<=top;y++){
                    if(level.getBlockState(base.atY(y)).is(Blocks.SCAFFOLDING))
                        existing++;
                }
                for(int n=1;n<=span;n++)
                    if(level.getBlockState(base.relative(inward,n)
                            .atY(top)).is(Blocks.SCAFFOLDING))
                        existing++;
                double score=BuilderScaffoldSiteScore.score(base,
                        worker.blockPosition(),target,
                        Math.max(0,top-base.getY()+1+span-existing),0,0,
                        existing>0);
                // Extra credit for not changing X/Z aisle from worker.
                score+=Math.abs(worker.getX()-base.getX()-.5)
                        +Math.abs(worker.getZ()-base.getZ()-.5);
                Job candidate=new Job(target,base,exit,
                        table.getBlockPos(),inward,top,span,level.getGameTime());
                candidate.lastDistance=score;
                options.add(candidate);
            }
        }
        options.sort(Comparator.comparingDouble(j->j.lastDistance));
        int probes=0;
        List<BlockPos> entryCandidates=new ArrayList<>();
        for(Job candidate:options){
            if(++probes>96)break;
            // A scaffold already occupying its own base is not a native
            // walking goal; its owner can begin climbing in place.
            boolean onBase=worker.blockPosition().getX()==candidate.base.getX()
                    &&worker.blockPosition().getZ()==candidate.base.getZ()
                    &&Math.abs(worker.getY()-candidate.base.getY())<.30
                    &&worker.onGround();
            if(onBase)return candidate;
            if(!entryCandidates.contains(candidate.base))
                entryCandidates.add(candidate.base);
        }
        // Use the same trained/fractional movement preflight as work
        // approaches, not native canReachExact, which rejects some real
        // off-center detours on high roofs. One bounded multi-goal search
        // gives both Builders a fair shot at a reachable scaffold base.
        BlockPos entry=WorkerNavigation.selectExecutableStand(
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
        int shafts=0,bridges=0,exits=0,reached=0;
        String example="";
        for(Direction outward:Direction.Plane.HORIZONTAL)
            for(int distance=3;distance<=MAX_HORIZONTAL_SCAFFOLD_SPAN;distance++)
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
            j=choose(level,worker,table,target,steps);
            if(j==null)return Result.NO_SAFE_ROUTE;
            jobs.put(worker,j);
        }
        switch(j.phase){
            case APPROACH -> {
                if(Math.abs(worker.getX()-j.base.getX()-.5)>.3
                        ||Math.abs(worker.getZ()-j.base.getZ()-.5)>.3
                        ||Math.abs(worker.getY()-j.base.getY())>.4){
                    WorkerNavigation.routeToExact(level,worker,j.base,1.0);
                    return Result.MOVING;
                }
                j.phase=Phase.BUILD;
            }
            case CLIMB -> {
                if(worker.getY()<j.target.getY()-.15){
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
