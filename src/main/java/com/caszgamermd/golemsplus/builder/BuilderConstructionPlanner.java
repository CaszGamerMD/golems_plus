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

    /** The future target is treated as impassable now, before placing it.
     * At least one OTHER genuinely supported cardinal departure cell must
     * remain free, and must also keep the golem away from the target block.
     * This is a local exit guarantee, not a claim that an arbitrary maze
     * has a full A* route home after thousands of future placements.
     */
    static boolean exitAfterPlacement(ServerLevel world,BuilderGolem builder,
            BlockPos stance,BlockPos target){
        for(Direction side:Direction.Plane.HORIZONTAL){
            BlockPos exit=stance.relative(side);
            if(exit.getX()==target.getX()&&exit.getZ()==target.getZ()
                    &&Math.abs(exit.getY()-target.getY())<=1)continue;
            if(!WorkerNavigation.canStandOn(world,builder,exit))continue;
            // A full bounding box at the exit must not intersect the
            // future cube even across the Y-1/Y+1 boundary.
            var moved=builder.getBoundingBox().move(
                    exit.getX()+.5-builder.getX(),
                    exit.getY()-builder.getY(),
                    exit.getZ()+.5-builder.getZ());
            if(moved.intersects(new net.minecraft.world.phys.AABB(target)
                    .inflate(.12,0,.12)))continue;
            // Center-to-edge >=0.8, same exact margin as during placing.
            double dx=Math.max(0,Math.max(
                    target.getX()-(exit.getX()+.5),
                    exit.getX()+.5-(target.getX()+1)));
            double dz=Math.max(0,Math.max(
                    target.getZ()-(exit.getZ()+.5),
                    exit.getZ()+.5-(target.getZ()+1)));
            if(Math.hypot(dx,dz)<.80)continue;
            return true;
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
