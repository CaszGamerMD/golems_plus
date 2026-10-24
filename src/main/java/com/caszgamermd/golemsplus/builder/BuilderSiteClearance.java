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

    /**
     * One worker clears; other assigned workers may fetch supplies in
     * parallel. At most 128 already-correct/air tiles are scanned per tick,
     * and at most ONE actual obstruction is physically removed per tick.
     * Progress is saved on the Planner so reloads can't restart/desync.
     */
    public static boolean tick(ServerLevel world,BuilderGolem worker,
            WorkerStationBlockEntity table,ServerPlayer owner,List<Target> plan,
            List<BuilderConstructionPlan.Step> construction){
        int cursor=table.builderClearCursor(),end=Math.min(plan.size(),
                cursor+MAX_CHECKS_PER_TICK);
        for(int i=cursor;i<end;i++){
            Target step=plan.get(i);
            BlockPos pos=step.pos();
            if(!world.hasChunkAt(pos)){
                table.advanceBuilderClearing(i);
                table.setProblem("CLEARING: build chunk unloaded "+pos);
                return false;
            }
            if(!world.mayInteract(owner,pos)){
                table.advanceBuilderClearing(i);
                table.setProblem("CLEARING: protected site location "+pos);
                return false;
            }
            BlockState expected;
            if(step.state().equals("minecraft:air"))expected=Blocks.AIR.defaultBlockState();
            else{
                var resolved=BuilderBlockResolver.resolve(step.state(),
                        BuildSite.Rotation.values()[table.builderRotation()/90],
                        BuildSite.Mirror.values()[table.builderMirror()]);
                if(resolved==null){
                    table.advanceBuilderClearing(i);
                    table.setProblem("CLEARING: unsupported schematic state "+
                            step.state()+" at "+pos);
                    return false;
                }
                expected=resolved.state();
            }
            BlockState existing=world.getBlockState(pos);
            if(existing.isAir()||existing.equals(expected))continue;
            table.advanceBuilderClearing(i);
            // Reach above-head blocks from real supplied scaffolding. This
            // is the same non-creative, trackable, reclaimable system used
            // later for layer-by-layer construction.
            if(pos.getY()-worker.getY()>2.5){
                var reach=BuilderScaffolding.reach(
                        world,worker,table,owner,pos,construction);
                if(reach==BuilderScaffolding.Result.NEED_MATERIALS){
                    table.setProblem("CLEARING: needs supplied scaffolding at "+pos);
                    return false;
                }
                if(reach!=BuilderScaffolding.Result.READY){
                    if(reach==BuilderScaffolding.Result.NO_SAFE_TOWER)
                        table.setProblem("CLEARING: no safe scaffold column "+pos);
                    return false;
                }
            }
            var status=BuilderClearance.clear(world,worker,table,owner,pos,existing);
            if(status==BuilderClearance.Result.CLEARED){
                table.advanceBuilderClearing(i+1);
                // No further destructive action this tick.
            }
            return false;
        }
        table.advanceBuilderClearing(end);
        if(end==plan.size()){
            table.setProblem("");
            return true;
        }
        return false;
    }
}
