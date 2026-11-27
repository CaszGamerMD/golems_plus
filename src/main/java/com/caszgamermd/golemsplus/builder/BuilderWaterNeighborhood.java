package com.caszgamermd.golemsplus.builder;

import java.util.*;
import java.util.function.IntPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** A local water barrier, independent of work zones and scheduling Y.
 * Only planned physical dry cells are prerequisites. Planned air,
 * generated piston heads and other fluid actions cannot create cycles.
 * Waterlogged hosts use their separate dry placement step. */
public final class BuilderWaterNeighborhood {
    private record DryCell(int index,String state){}
    private record Neighbor(BlockPos pos,DryCell cell){}
    private final Map<BlockPos,DryCell> dry=new HashMap<>();
    private final List<List<Neighbor>> requirements;
    public BuilderWaterNeighborhood(List<BuilderConstructionPlan.Step> steps){
        for(int i=0;i<steps.size();i++){
            var step=steps.get(i);
            if(step.ignorePlant()||BuilderWaterPass.isWaterStep(step.encodedState())
                    ||BuilderDeferredState.verification(step.encodedState()))continue;
            dry.put(new BlockPos(step.x(),step.y(),step.z()),new DryCell(i,step.encodedState()));
            if(step.companion()!=null){
                var c=step.companion();
                dry.put(new BlockPos(c.x(),c.y(),c.z()),new DryCell(i,c.encodedState()));
            }
        }
        requirements=new ArrayList<>(steps.size());
        for(var step:steps)requirements.add(releasesWater(step)?neighbors(step):List.of());
    }
    public static boolean releasesWater(BuilderConstructionPlan.Step step){
        return !step.ignorePlant()&&BuilderWaterPass.isWaterStep(step.encodedState())
                &&!BuilderDeferredState.verification(step.encodedState());
    }
    private List<Neighbor> neighbors(BuilderConstructionPlan.Step step){
        BlockPos pos=new BlockPos(step.x(),step.y(),step.z());
        var result=new ArrayList<Neighbor>(7);
        for(Direction side:Direction.values()){
            BlockPos adjacent=pos.relative(side);
            DryCell cell=dry.get(adjacent);
            if(cell!=null)result.add(new Neighbor(adjacent,cell));
        }
        if(BuilderWaterPass.isWaterlogPass(step.encodedState())){
            DryCell host=dry.get(pos);
            if(host!=null)result.add(new Neighbor(pos,host));
        }
        return result;
    }
    public boolean ready(int index,IntPredicate completed){
        for(var neighbor:requirements.get(index))
            if(!completed.test(neighbor.cell().index()))return false;
        return true;
    }
    public boolean ready(BuilderConstructionPlan.Step step,IntPredicate completed){
        if(!releasesWater(step))return true;
        for(var neighbor:neighbors(step))
            if(!completed.test(neighbor.cell().index()))return false;
        return true;
    }
    /** Recheck the live block type at the actual write location. A paid
     * wall that was washed out or pushed away is not a physical barrier.
     * Dynamic redstone properties and waterlogging of neighbors are fine. */
    public boolean physicallyReady(Level world,BuilderConstructionPlan.Step step,
            BuildSite.Rotation rotation,BuildSite.Mirror mirror){
        if(!releasesWater(step))return true;
        for(var neighbor:neighbors(step)){
            if(!world.hasChunkAt(neighbor.pos()))return false;
            BlockState expected=BuilderBlockResolver.expectedState(neighbor.cell().state(),rotation,mirror);
            if(expected==null||world.getBlockState(neighbor.pos()).getBlock()!=expected.getBlock())return false;
        }
        if(BuilderWaterPass.isWaterlogPass(step.encodedState())){
            var host=BuilderBlockResolver.expectedState(BuilderWaterPass.dryState(
                    BuilderWaterPass.originalState(step.encodedState())),rotation,mirror);
            if(host==null||!world.hasChunkAt(new BlockPos(step.x(),step.y(),step.z()))
                    ||world.getBlockState(new BlockPos(step.x(),step.y(),step.z())).getBlock()!=host.getBlock())return false;
        }
        return true;
    }
}
