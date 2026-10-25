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

/** Physical, server-authorized site-preparation phase.
 *
 * The exact schematic positions, including explicitly recorded air spaces,
 * are cleared before ANY material is placed. Existing blocks that already
 * match the blueprint are preserved. Nothing outside a selected Litematica
 * region is touched; chests, protection, fluids and player structures with
 * inventories remain protected by BuilderClearance.
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

    /** Every assigned non-courier Builder can verify and clear distinct
     * schematic cells. The Planning Table allocates per-worker claims, so
     * the saved head cursor advances only over positively verified cells.
     * There is no shared single cleaner and no ability to clear a cell
     * another Builder already owns.
     */
    public static boolean tick(ServerLevel world,BuilderGolem worker,
            WorkerStationBlockEntity table,ServerPlayer owner,List<Target> plan,
            List<BuilderConstructionPlan.Step> construction){
        int checks=0;
        while(checks++<MAX_CHECKS_PER_TICK){
            int index=table.claimBuilderClear(worker.getUUID(),plan.size());
            if(index<0)return table.builderClearCursor()>=plan.size();
            Target step=plan.get(index);
            BlockPos pos=step.pos();
            if(!world.hasChunkAt(pos))
                BuilderChunkTickets.destination(world,worker,pos);
            if(!world.hasChunkAt(pos)){
                table.setProblem("CLEARING: loading construction chunk "+pos);
                return false;
            }
            if(!world.mayInteract(owner,pos)){
                table.setProblem("CLEARING: protected site location "+pos);
                return false;
            }
            // An empty position needs no schematic-state parser at all.
            // Previously a spruce door at a future empty position was
            // rejected before checking the existing world block.
            BlockState existing=world.getBlockState(pos);
            if(existing.isAir()){
                table.completeBuilderClear(worker.getUUID(),index,false);
                continue;
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
            if(BuilderPlacementState.matches(existing,expected)){
                // A saved wall/stair shape may have recomputed when nearby
                // blocks were placed. It is still an existing correct
                // schematic block, never an obstruction to tear down.
                table.completeBuilderClear(worker.getUUID(),index,false);
                continue;
            }
            BuilderChunkTickets.destination(world,worker,pos);
            boolean surfaceSnow=existing.is(Blocks.SNOW);
            if(!surfaceSnow&&pos.getY()-worker.getY()>2.5){
                var reach=BuilderScaffolding.reach(
                        world,worker,table,owner,pos,construction);
                if(reach==BuilderScaffolding.Result.NEED_MATERIALS){
                    table.setProblem("CLEARING: needs supplied scaffolding at "+pos);
                    BuilderWarehouseCoordinator.requestScaffolding(
                            world,worker,table,owner,construction);
                    return false;
                }
                if(reach!=BuilderScaffolding.Result.NOT_NEEDED){
                    if(reach==BuilderScaffolding.Result.NO_SAFE_TOWER)
                        table.setProblem("CLEARING: no safe scaffold column "+pos);
                    return false;
                }
            }
            var status=BuilderClearance.clear(
                    world,worker,table,owner,pos,existing);
            if(status==BuilderClearance.Result.CLEARED)
                table.completeBuilderClear(worker.getUUID(),index,true);
            return false; // Only one destructive action per worker tick.
        }
        return table.builderClearCursor()>=plan.size();
    }
}
