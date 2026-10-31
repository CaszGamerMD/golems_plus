package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Construction-aware frontier selection, not a replacement for trained A*.
 *
 * A claimed block must have an actual, reachable grounded work pose with
 * safe headroom, 0.8m horizontal margin, clear line of sight, and a
 * walkable escape after the proposed block is placed. A real anchored hook
 * may provide access to a higher pose, but never invents walking or flight.
 *
 * The planner respects the persistent strict shared Y-layer gate. Scanning
 * never completes, reassigns or buys a block: the Planner BE owns claims and
 * accounting and the actual Builder still uses vanilla physical movement.
 */
public final class BuilderConstructionPlanner {
    public record Choice(int index, BlockPos stance, boolean needsGrapple,
            boolean directSky, int examined){
        public boolean alreadyBuilt(){return stance==null&&!needsGrapple;}
    }
    private BuilderConstructionPlanner(){}
    private static final int MAX_EXAMINED=BuilderFrontierTuning.SCAN_LIMIT;
    private static final int MAX_FEASIBLE=BuilderFrontierTuning.FEASIBLE_LIMIT;
    /** A worker must EVENTUALLY inspect the whole current layer, not
     * repeatedly inspect the same nearest 32 inaccessible cells. This
     * is search progress (not a saved route) and resets on a Y change.
     */
    private record SearchWindow(int layer,int offset,int pending,
            int examined,int feasible,int chosen,int startOffset){}
    public static String scanStatus(BuilderGolem worker){
        SearchWindow w=windows.get(worker);
        return w==null?"not yet scanned":
                "Y="+w.layer()+" pending="+w.pending()
                +" from="+w.startOffset()+" checked="+w.examined()
                +" possible="+w.feasible()+" lastChosen="+w.chosen()
                +" nextOffset="+w.offset();
    }
    private static final Map<BuilderGolem,SearchWindow> windows=
            new WeakHashMap<>();
    private static final int REACH_SQUARED=14;

    /** Ray from a real Builder's eye at the planned stance to the block
     * center or face centers. Solid walls are never transparent just because A* could
     * reach a nearby standing tile. The target block itself may be
     * incorrect non-air and is allowed to be the final hit.
     */
    static boolean lineOfSight(ServerLevel world,BuilderGolem builder,
            BlockPos stance,BlockPos target){
        Vec3 eye=new Vec3(stance.getX()+.5,
                stance.getY()+builder.getEyeHeight(),stance.getZ()+.5);
        Vec3 center=Vec3.atCenterOf(target);
        // Inset face samples remain inside the target cell. This permits
        // visible floor gaps without allowing rays through adjacent walls.
        Vec3[] samples={center,center.add(0,.499,0),center.add(0,-.499,0),
                center.add(.499,0,0),center.add(-.499,0,0),
                center.add(0,0,.499),center.add(0,0,-.499)};
        for(Vec3 sample:samples){
            var hit=world.clip(new ClipContext(eye,sample,
                    ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,builder));
            if(hit.getType()==HitResult.Type.MISS
                    ||hit.getType()==HitResult.Type.BLOCK
                        &&((net.minecraft.world.phys.BlockHitResult)hit)
                            .getBlockPos().equals(target))return true;
        }
        return false;
    }

    /** Verify a one-step PHYSICAL exit after the intended block exists.
     * Builders can walk cardinally/diagonally and can climb/descend one
     * block without a hook. A same-Y-only cardinal check wrongly rejected
     * almost every useful dome ledge and pinned entire construction zones.
     *
     * This is only a local exit proof. We never claim this proves every
     * possible maze has a path all the way back to OnSite.
     */
    static boolean exitAfterPlacement(ServerLevel world,BuilderGolem builder,
            BlockPos stance,BlockPos target){
        var future=new net.minecraft.world.phys.AABB(target)
                .inflate(.10,0,.10);
        var base=builder.getBoundingBox().move(
                stance.getX()+.5-builder.getX(),
                stance.getY()-builder.getY(),
                stance.getZ()+.5-builder.getZ());
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
            if(dx==0&&dz==0)continue;
            for(int dy:new int[]{0,-1,1}){
                BlockPos dest=stance.offset(dx,dy,dz);
                if(!WorkerNavigation.canStandOn(world,builder,dest))
                    continue;
                // Every candidate uses exactly the real 0.8m clearance,
                // including when its feet stand above or below the new cube.
                // The 0.8 m placement margin applies to the work pose,
                // NOT to a later escape move. A physically collidable
                // 0.65-wide Golem can safely pass beside the new block
                // with less arm/placement clearance. The swept AABB
                // against the FUTURE block below is the safety check.
                boolean free=true;
                for(int i=1;i<=4;i++){
                    double t=i/4.0;
                    // A real jump rises BEFORE moving across the next
                    // block's solid face. A drop steps over the rim and
                    // then lowers. Plain walks interpolate both axes.
                    double horizontal=dy>0?Math.max(0,2*t-1):
                            dy<0?Math.min(1,2*t):t;
                    double rise=dy>0?Math.min(1,2*t):
                            dy<0?-Math.max(0,2*t-1):0;
                    var moved=base.move(dx*horizontal,rise,dz*horizontal);
                    if(moved.intersects(future)
                            ||!world.noCollision(builder,moved)){
                        free=false;break;
                    }
                }
                if(free)return true;
            }
        }
        return false;
    }
    static boolean safeStand(ServerLevel world,BuilderGolem builder,
            BlockPos target,BlockPos stance){
        return stance.distSqr(target)<REACH_SQUARED
                &&(stance.getY()==target.getY()
                        ||stance.getY()==target.getY()+1
                        ||stance.getY()==target.getY()-1
                        ||stance.getY()==target.getY()-2)
                &&lineOfSight(world,builder,stance,target)
                &&exitAfterPlacement(world,builder,stance,target);
    }
    /**
     * Physical contact with a CORRECT, already-built schematic cell is a
     * construction advantage. Favor extending established roof supports
     * and walls before starting isolated islands on the same Y. Nearby
     * terrain or unrelated player blocks do NOT count as completed work.
     *
     * Only used as a score AFTER the real work pose, escape and placement
     * support were checked. This never authorizes a floating block.
     */
    static int connectedPlacementBonus(ServerLevel world,BlockPos target,
            Map<BlockPos,BlockState> schematic){
        int side=0,below=0,above=0;
        for(Direction direction:Direction.values()){
            BlockPos neighbor=target.relative(direction);
            BlockState expected=schematic.get(neighbor);
            if(expected==null||!world.hasChunkAt(neighbor)
                    ||!BuilderPlacementState.matches(
                            world.getBlockState(neighbor),expected))continue;
            if(direction==Direction.DOWN)below++;
            else if(direction==Direction.UP)above++;
            else side++;
        }
        // A built block UNDER the next placement is a particularly useful
        // foothold. Side connections allow outward growth along the roof.
        return Math.min(100,below*75+side*42+above*10);
    }
    private static final Map<List<BuilderConstructionPlan.Step>,
            Map<BlockPos,BlockState>> schematicStates=new WeakHashMap<>();
    private static Map<BlockPos,BlockState> schematicStates(
            List<BuilderConstructionPlan.Step> steps,
            BuildSite.Rotation rotation,BuildSite.Mirror mirror){
        // Never cache by file path: the same placed schematic has different
        // world coordinates after moving or rotating its placement.
        return schematicStates.computeIfAbsent(steps,unused->{
            Map<BlockPos,BlockState> expected=new java.util.HashMap<>();
            for(var cell:steps){
                if(cell.ignorePlant())continue;
                var state=BuilderBlockResolver.resolve(
                        cell.encodedState(),rotation,mirror);
                if(state!=null)expected.put(
                        new BlockPos(cell.x(),cell.y(),cell.z()),state.state());
            }
            return Map.copyOf(expected);
        });
    }
    private static double score(BuilderGolem worker,BlockPos target,
            BlockPos stance,boolean sky,boolean grapple){
        BlockPos reference=stance==null?target:stance;
        return reference.distSqr(worker.blockPosition())
                +target.distSqr(reference)*BuilderFrontierTuning.TARGET_WEIGHT
                +(sky?0:BuilderFrontierTuning.SKY_PENALTY) // favor open sky
                +(grapple?BuilderFrontierTuning.GRAPPLE_PENALTY:0); // rank valid hook access
    }

    /** Human-readable diagnostic in SCHEMATIC-LOCAL XYZ. This never
     * changes claims or tests artificial standability. It records exact
     * unfinished inputs and their real-world support so failures are
     * actionable rather than "pathfinding failed" with random test XZ.
     */
    public static String blockedSummary(ServerLevel world,
            WorkerStationBlockEntity table,
            List<BuilderConstructionPlan.Step> steps,List<Integer> zone){
        int layer=table.builderActiveLayer(steps);
        BlockPos origin=table.builderOrigin();
        int pending=0;
        List<String> examples=new ArrayList<>();
        for(int idx:zone){
            if(idx<0||idx>=steps.size()
                    ||steps.get(idx).y()!=layer
                    ||table.builderZoneDone(List.of(idx)))continue;
            pending++;
            if(examples.size()>=4)continue;
            var step=steps.get(idx);
            BlockPos p=new BlockPos(step.x(),step.y(),step.z());
            var expected=BuilderBlockResolver.resolve(step.encodedState(),
                    BuildSite.Rotation.values()[table.builderRotation()/90],
                    BuildSite.Mirror.values()[table.builderMirror()]);
            String reason=step.ignorePlant()
                    ?"ignored vegetation: no physical support required":
                    !world.hasChunkAt(p)?"chunk unloaded":
                    expected==null?"unsupported block state":
                    !expected.state().canSurvive(world,p)
                        ?"missing physical support":
                    "no confirmed reachable work stance/exit";
            examples.add("#"+idx+" XYZ="+p.subtract(origin).toShortString()
                    +" state="+step.encodedState()
                    +" reason="+reason);
        }
        return "Y="+(layer-origin.getY())+" pending="+pending
                +" next=["+String.join("; ",examples)+"]";
    }

    /** Diagnose the exact first supported frontier obstruction with the
     * current world and trained route finder; never move or alter blocks.
     * This is for failure reports, not the 5-tick claim hot path.
     */
    public static String diagnoseBlockedZone(ServerLevel world,
            BuilderGolem worker,WorkerStationBlockEntity table,
            List<BuilderConstructionPlan.Step> steps,List<Integer> zone){
        int y=table.builderActiveLayer(steps);
        for(int idx:zone){
            if(idx<0||idx>=steps.size()||steps.get(idx).y()!=y
                    ||table.builderZoneDone(List.of(idx))
                    ||steps.get(idx).ignorePlant())continue;
            var step=steps.get(idx);
            BlockPos target=new BlockPos(step.x(),step.y(),step.z());
            if(!world.hasChunkAt(target))continue;
            var state=BuilderBlockResolver.resolve(step.encodedState(),
                    BuildSite.Rotation.values()[table.builderRotation()/90],
                    BuildSite.Mirror.values()[table.builderMirror()]);
            if(state==null||!state.state().canSurvive(world,target))
                continue;
            int feasible=0,feet=0,sight=0,escapes=0,routes=0,probes=0;
            for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++)
                for(int dy:new int[]{0,1}){
                    BlockPos at=target.offset(dx,dy,dz);
                    if(at.equals(target)||at.distSqr(target)>=REACH_SQUARED)
                        continue;
                    double px=at.getX()+.5,pz=at.getZ()+.5;
                    double ex=Math.max(0,Math.max(target.getX()-px,
                            px-target.getX()-1));
                    double ez=Math.max(0,Math.max(target.getZ()-pz,
                            pz-target.getZ()-1));
                    if(Math.hypot(ex,ez)<.8)continue;
                    feasible++;
                    if(!WorkerNavigation.canStandOn(world,worker,at))continue;
                    feet++;
                    if(!lineOfSight(world,worker,at,target))continue;
                    sight++;
                    if(!exitAfterPlacement(world,worker,at,target))continue;
                    escapes++;
                    if(probes++<8&&WorkerNavigation.canReachExact(
                            world,worker,at))routes++;
                }
            return " | firstSupported #"+idx+" localXYZ="
                    +target.subtract(table.builderOrigin()).toShortString()
                    +" poses="+feasible+" standable="+feet+" LOS="+sight
                    +" exits="+escapes+" reachable="+routes
                    +" | "+BuilderPlacementSpace.diagnoseApproach(
                            world,worker,target,
                            at->safeStand(world,worker,target,at));
        }
        return " | no supported pending target on current Y";
    }

    /** Select the most promising same-layer cell in the worker's OWN
     * zone. Readiness is checked against persistent step completion and
     * cooldown state. We look beyond the first blocked strip element;
     * failure is NOT permission to skip the current Y layer.
     */
    public static Choice choose(ServerLevel world,BuilderGolem worker,
            WorkerStationBlockEntity table,
            List<BuilderConstructionPlan.Step> steps,List<Integer> zone){
        int y=table.builderActiveLayer(steps);
        if(y==Integer.MAX_VALUE)return null;
        List<Integer> pending=new ArrayList<>();
        for(int idx:zone){
            if(idx<0||idx>=steps.size()
                    ||steps.get(idx).y()!=y
                    ||!table.builderStepReadyForClaim(idx))continue;
            pending.add(idx);
        }
        // Ignored vegetation is paperwork, not a world placement.
        // Complete these regardless of footing/line of sight. This
        // prevents a full 32-cell blocked strip from starving azaleas
        // and making an irrelevant "missing support" a permanent stop.
        for(int idx:pending)
            if(steps.get(idx).ignorePlant())
                return new Choice(idx,null,false,true,0);
        // Keep the scan window stable as the worker moves. Re-sorting by
        // distance on every claim changes the meaning of its saved offset
        // and can repeatedly hide unfinished sections of the current layer.
        // Distance, skylight and grapple cost still rank feasible choices
        // below; scan fairness must not depend on the worker's position.
        pending.sort(Integer::compareTo);
        Choice best=null;
        double bestScore=Double.POSITIVE_INFINITY;
        SearchWindow previous=windows.get(worker);
        int offset=previous!=null&&previous.layer()==y
                ?previous.offset():0;
        int examined=0,feasible=0;
        int count=Math.min(MAX_EXAMINED,pending.size());
        Map<BlockPos,BlockState> builtSchematic=schematicStates(steps,
                BuildSite.Rotation.values()[table.builderRotation()/90],
                BuildSite.Mirror.values()[table.builderMirror()]);
        for(int attempt=0;attempt<count;attempt++){
            int idx=pending.get((offset+attempt)%pending.size());
            examined++;
            var step=steps.get(idx);
            BlockPos target=new BlockPos(step.x(),step.y(),step.z());
            if(!world.hasChunkAt(target))continue;
            // Already matching cells and ignored plants are NO-OP work:
            // the normal build service will confirm them in the world
            // and mark completion without walking or consuming items.
            if(step.ignorePlant()){
                return new Choice(idx,null,false,true,examined);
            }
            var state=BuilderBlockResolver.resolve(step.encodedState(),
                    BuildSite.Rotation.values()[table.builderRotation()/90],
                    BuildSite.Mirror.values()[table.builderMirror()]);
            if(state==null)continue;
            BlockState actual=world.getBlockState(target);
            if(BuilderPlacementState.matches(actual,state.state()))
                return new Choice(idx,null,false,true,examined);
            if(!state.state().canSurvive(world,target))continue;
            BlockPos stance=BuilderPlacementSpace.approachWithRules(
                    world,worker,target,
                    at->safeStand(world,worker,target,at));
            boolean grapple=false;
            if(stance==null
                    &&target.getY()-worker.blockPosition().getY()>0
                    &&worker.carriedCount(WorksiteContent.GRAPPLE_HOOK)>0
                    &&BuilderGrappleAccess.canPlanWorkAscent(world,worker,target))
                grapple=true;
            // Read-only scaffold preflight: a future work pose can be
            // reached by a real paid tower/bridge, even if neither native
            // A* nor a rope can currently get across an unfinished roof.
            boolean floating=BuilderScaffoldPlanA
                    .needsTemporaryWorkPlatform(world,target);
            boolean scaffold=stance==null&&!grapple
                    // An occupied same-layer roof can obstruct all walking
                    // stances too. A real inventory-paid work bridge is
                    // an access option, even when target sides are joined
                    // to other blocks and the rise is only one block.
                    &&(BuilderScaffoldPlanA.canPlanWorkPose(
                            world,worker,table,target,steps)
                        ||(target.getY()-worker.blockPosition().getY()>1
                            &&!floating&&BuilderScaffolding.canPlanAccess(
                                    world,worker,table,target,steps)));
            if(stance==null&&!grapple&&!scaffold)continue;
            boolean sky=stance==null
                    ?world.canSeeSky(target.above())
                    :world.canSeeSky(stance.above());
            // Valid same-layer frontier candidates that TOUCH existing
            // completed architecture get scheduled first, making finished
            // roof ledges and columns useful access infrastructure.
            double cost=score(worker,target,stance,sky,grapple)
                    -connectedPlacementBonus(world,target,builtSchematic)
                    +(scaffold?BuilderFrontierTuning.GRAPPLE_PENALTY*2:0);
            if(cost<bestScore){
                bestScore=cost;
                best=new Choice(idx,stance,grapple,sky,examined);
            }
            if(++feasible>=MAX_FEASIBLE)break;
        }
        // A fully blocked nearby strip must never monopolize this Y.
        // Subsequent passes explore another bounded window until every
        // pending cell gets its own physical work-position opportunity.
        // A selected job may be deferred when its initially valid approach
        // becomes blocked during the real walk. Retain search progress
        // across each claim so the NEXT claim tries another frontier
        // instead of restarting at the same five nearest cells forever.
        windows.put(worker,new SearchWindow(y,
                (offset+Math.max(1,examined))%Math.max(1,pending.size()),
                pending.size(),examined,feasible,best==null?-1:best.index(),
                offset));
        return best;
    }
}
