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
    private static final int MAX_EXAMINED=32;
    private static final int MAX_FEASIBLE=5;
    /** A worker must EVENTUALLY inspect the whole current layer, not
     * repeatedly inspect the same nearest 32 inaccessible cells. This
     * is search progress (not a saved route) and resets on a Y change.
     */
    private record SearchWindow(int layer,int offset){}
    private static final Map<BuilderGolem,SearchWindow> windows=
            new WeakHashMap<>();
    private static final int REACH_SQUARED=14;

    /** Ray from a real Builder's eye at the planned stance to the block
     * center. Solid walls are never transparent just because A* could
     * reach a nearby standing tile. The target block itself may be
     * incorrect non-air and is allowed to be the final hit.
     */
    static boolean lineOfSight(ServerLevel world,BuilderGolem builder,
            BlockPos stance,BlockPos target){
        Vec3 eye=new Vec3(stance.getX()+.5,
                stance.getY()+builder.getEyeHeight(),stance.getZ()+.5);
        Vec3 block=Vec3.atCenterOf(target);
        var hit=world.clip(new ClipContext(eye,block,
                ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,builder));
        return hit.getType()==HitResult.Type.MISS
                ||hit.getType()==HitResult.Type.BLOCK
                    &&((net.minecraft.world.phys.BlockHitResult)hit)
                        .getBlockPos().equals(target);
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
                        ||stance.getY()==target.getY()+1)
                &&lineOfSight(world,builder,stance,target)
                &&exitAfterPlacement(world,builder,stance,target);
    }
    private static double score(BuilderGolem worker,BlockPos target,
            BlockPos stance,boolean sky,boolean grapple){
        BlockPos reference=stance==null?target:stance;
        return reference.distSqr(worker.blockPosition())
                +target.distSqr(reference)*.65
                +(sky?0:12)             // prefer direct skylight
                +(grapple?24:0);        // never hook if walking works
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
                    ||!table.builderStepReadyForClaim(idx))continue;
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
                    ||!table.builderStepReadyForClaim(idx)
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
        // Prefer a near reachable frontier; scan bounded work even for
        // 100,000-block schematics. Future calls search new geometry.
        pending.sort(Comparator
                .comparingDouble((Integer idx)->{
                    var step=steps.get(idx);
                    BlockPos target=new BlockPos(step.x(),step.y(),step.z());
                    return target.distSqr(worker.blockPosition())
                            +(world.canSeeSky(target.above())?0:8);
                })
                .thenComparingInt(Integer::intValue));
        Choice best=null;
        double bestScore=Double.POSITIVE_INFINITY;
        SearchWindow previous=windows.get(worker);
        int offset=previous!=null&&previous.layer()==y
                ?previous.offset():0;
        int examined=0,feasible=0;
        int count=Math.min(MAX_EXAMINED,pending.size());
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
                    &&target.getY()-worker.blockPosition().getY()>1
                    &&worker.carriedCount(WorksiteContent.GRAPPLE_HOOK)>0
                    &&BuilderGrappleAccess.canPlanAscent(world,worker,target))
                grapple=true;
            if(stance==null&&!grapple)continue;
            boolean sky=stance==null
                    ?world.canSeeSky(target.above())
                    :world.canSeeSky(stance.above());
            double cost=score(worker,target,stance,sky,grapple);
            if(cost<bestScore){
                bestScore=cost;
                best=new Choice(idx,stance,grapple,sky,examined);
            }
            if(++feasible>=MAX_FEASIBLE)break;
        }
        // A fully blocked nearby strip must never monopolize this Y.
        // Subsequent passes explore another bounded window until every
        // pending cell gets its own physical work-position opportunity.
        windows.put(worker,new SearchWindow(y,best!=null?0
                :(offset+Math.max(1,examined))%Math.max(1,pending.size())));
        return best;
    }
}
