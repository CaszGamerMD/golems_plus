package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Physical, server-authorized site preparation.
 *
 * Clearance targets are processed in ascending world Y. Large jobs can
 * pipeline this work: one Builder may continue checking the full footprint
 * while the rest of the crew starts construction as soon as the active Y
 * layer has been positively verified. This preserves the shared height gate
 * and the exact same protected-block rules.
 */
public final class BuilderSiteClearance {
    public record Target(BlockPos pos,String state){}
    private static final int MAX_CHECKS_PER_TICK=128;
    private BuilderSiteClearance(){}

    public static List<Target> targets(LitematicFileReader.Schematic schematic,
                                       BuildSite site) {
        // Multiple Litematica regions can overlap. The last region wins as
        // in-world placement semantics; never clear the same coordinate
        // twice, and never clear outside the exact imported palette.
        LinkedHashMap<BlockPos,String> positions=new LinkedHashMap<>();
        for(var block:schematic.airBlocks()){
            var pos=site.locate(new BuilderMaterialPlan.Placement(
                    block.x(),block.y(),block.z(),block.state(),null));
            positions.put(new BlockPos(pos.x(),pos.y(),pos.z()),"minecraft:air");
        }
        for(var block:schematic.blocks()){
            var pos=site.locate(new BuilderMaterialPlan.Placement(
                    block.x(),block.y(),block.z(),block.state(),null));
            positions.put(new BlockPos(pos.x(),pos.y(),pos.z()),block.state());
        }
        List<Target> result=new ArrayList<>(positions.size());
        positions.forEach((p,v)->result.add(new Target(p,v)));
        result.sort(Comparator.comparingInt(t->t.pos().getY()));
        return List.copyOf(result);
    }

    /** First index whose target is ABOVE the supplied Y. targets() sorts
     * by Y, so this binary search is stable even for large schematic volumes.
     * The saved clearance cursor remains global; later layers can be verified
     * ahead without letting a Builder build through an uncleared active layer.
     */
    public static int endIndexThroughLayer(List<Target> plan,int maxY){
        int low=0,high=plan.size();
        while(low<high){
            int mid=(low+high)>>>1;
            if(plan.get(mid).pos().getY()<=maxY)low=mid+1;
            else high=mid;
        }
        return low;
    }

    /** Full-footprint clearance, retained for small jobs and a dedicated
     * clearance lead on large jobs.
     */
    public static boolean tick(ServerLevel world,BuilderGolem worker,
            WorkerStationBlockEntity table,ServerPlayer owner,List<Target> plan,
            List<BuilderConstructionPlan.Step> construction){
        return tickUntil(world,worker,table,owner,plan,construction,plan.size());
    }

    /** Clear only through the current construction layer. Other Builder
     * members may start placing this Y while a dedicated lead works on
     * higher layers. The contiguous saved cursor guarantees every lower
     * coordinate and every cell through maxY has been verified first.
     */
    public static boolean tickThroughLayer(ServerLevel world,BuilderGolem worker,
            WorkerStationBlockEntity table,ServerPlayer owner,List<Target> plan,
            List<BuilderConstructionPlan.Step> construction,int maxY){
        return tickUntil(world,worker,table,owner,plan,construction,
                endIndexThroughLayer(plan,maxY));
    }

    private static boolean tickUntil(ServerLevel world,BuilderGolem worker,
            WorkerStationBlockEntity table,ServerPlayer owner,List<Target> plan,
            List<BuilderConstructionPlan.Step> construction,int limit){
        if(table.builderClearCursor()>=limit)return true;
        int checks=0;
        while(checks++<MAX_CHECKS_PER_TICK){
            int index=table.claimBuilderClear(worker.getUUID(),limit);
            if(index<0)return table.builderClearCursor()>=limit;
            Target step=plan.get(index);
            if(step.state().equals("golems_plus:skip")){
                table.completeBuilderClear(worker.getUUID(),index,false);
                continue;
            }
            BlockPos pos=step.pos();
            if(!world.hasChunkAt(pos))
                BuilderChunkTickets.destination(world,worker,pos);
            if(!world.hasChunkAt(pos)){
                table.setProblem("CLEARING: loading construction chunk "+pos);
                return false;
            }
            // Air is already clear. Do not route a Builder, equip a tool,
            // check removal permissions, or attempt to destroy the cell.
            // Still verify schematic AIR cells: a non-air obstruction in
            // one of those cells MUST be removed before building.
            BlockState existing=world.getBlockState(pos);
            if(existing.isAir()){
                table.completeBuilderClear(worker.getUUID(),index,false);
                continue;
            }
            if(!world.mayInteract(owner,pos)){
                table.setProblem("CLEARING: protected site location "+pos);
                return false;
            }
            BlockState expected=Blocks.AIR.defaultBlockState();
            // The vegetation toggle skips only S C H E M A T I C plant
            // placements. Clearing stays fully active: if a flower, crop,
            // snow layer or other block occupies this ignored position,
            // remove that obstruction rather than preserving it because
            // it happens to match the original blueprint.
            boolean ignoredPlant=table.builderIgnorePlants()
                    &&BuilderVegetation.ignoreSchematicState(step.state());
            if(!ignoredPlant&&!step.state().equals("minecraft:air")){
                var resolved=BuilderBlockResolver.resolve(step.state(),
                        BuildSite.Rotation.values()[table.builderRotation()/90],
                        BuildSite.Mirror.values()[table.builderMirror()]);
                if(resolved==null){
                    // Never destroy an obstruction we cannot subsequently
                    // rebuild. Report the precise state, retaining its
                    // claimed cell for player/mod review.
                    table.setProblem("CLEARING: unsupported schematic state "+
                            step.state()+" at "+pos);
                    return false;
                }
                expected=resolved.state();
            }
            boolean dryWaterlog=BuilderWaterPass.isWaterlogged(step.state());
            var dryExpected=dryWaterlog?BuilderBlockResolver.resolve(
                    BuilderWaterPass.dryState(step.state()),
                    BuildSite.Rotation.values()[table.builderRotation()/90],
                    BuildSite.Mirror.values()[table.builderMirror()]):null;
            if(BuilderPlacementState.matches(existing,expected)
                    ||BuilderWaterPass.isBubbleColumn(step.state())
                        &&existing.is(Blocks.WATER)
                    ||dryExpected!=null&&BuilderPlacementState.matches(
                            existing,dryExpected.state())){
                // A saved wall/stair shape may have recomputed when nearby
                // blocks were placed. It is still an existing correct
                // schematic block, never an obstruction to tear down.
                table.completeBuilderClear(worker.getUUID(),index,false);
                continue;
            }
            BuilderChunkTickets.destination(world,worker,pos);
            boolean surfaceSnow=existing.is(Blocks.SNOW);
            if(!surfaceSnow&&pos.getY()-worker.getY()>2.5){
                var reach=BuilderGrappleAccess.reach(world,worker,table,pos);
                if(reach!=BuilderGrappleAccess.Result.READY){
                    if(reach==BuilderGrappleAccess.Result.NEED_HOOK)
                        table.setProblem("CLEARING: needs physical Grapple Hook "+pos);
                    else if(reach==BuilderGrappleAccess.Result.NO_SAFE_ANCHOR)
                        table.setProblem("CLEARING: no safe grapple anchor "+pos);
                    return false;
                }
            }
            var status=BuilderClearance.clear(
                    world,worker,table,owner,pos,existing);
            if(status==BuilderClearance.Result.CLEARED)
                table.completeBuilderClear(worker.getUUID(),index,true);
            return false; // Only one destructive action per worker tick.
        }
        return table.builderClearCursor()>=limit;
    }
}
