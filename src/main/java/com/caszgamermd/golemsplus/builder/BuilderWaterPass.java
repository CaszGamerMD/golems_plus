package com.caszgamermd.golemsplus.builder;

import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.state.BlockState;

/** Internal, second construction action at an already-built waterloggable cell.
 * These markers are never written into the source schematic. */
public final class BuilderWaterPass {
    private static final String PREFIX="golems_plus:waterlog|";
    public static final String WATER_BUCKET="golems_plus:infinite_water_bucket";
    private BuilderWaterPass(){}

    public static boolean isWaterlogged(String encoded){
        return encoded!=null&&encoded.matches(
                ".*(?:\\[|,)waterlogged=true(?:,|\\]).*");
    }
    public static String dryState(String wet){
        return isWaterlogged(wet)?wet.replace("waterlogged=true","waterlogged=false"):wet;
    }
    public static String waterlog(String wet){return PREFIX+wet;}
    public static boolean isWaterlogPass(String encoded){
        return encoded!=null&&encoded.startsWith(PREFIX);
    }
    public static String originalState(String encoded){
        return isWaterlogPass(encoded)?encoded.substring(PREFIX.length()):encoded;
    }
    public static boolean isWaterStep(String encoded){
        return BuilderDeferredState.flowVerification(encoded)||isWaterlogPass(encoded)||"minecraft:water".equals(encoded)
                ||encoded!=null&&encoded.startsWith("minecraft:water[")
                ||isBubbleColumn(encoded);
    }
    public static boolean isBubbleColumn(String encoded){
        return "minecraft:bubble_column".equals(encoded)
                ||encoded!=null&&encoded.startsWith("minecraft:bubble_column[");
    }
    /** The effective action keeps the original column's saved claim index. */
    static BuilderConstructionPlan.Step bubbleAction(
            Level world, BlockPos column, BuilderConstructionPlan.Step step,
            Map<BlockPos,BlockState> schematic) {
        if(!isBubbleColumn(step.encodedState()))return step;
        BlockPos below=column.below();
        BlockState actual=world.getBlockState(below);
        BlockState planned=schematic.get(below);
        boolean downward=step.encodedState().contains("drag=true")
                ||!step.encodedState().contains("drag=false")
                    &&Blocks.BUBBLE_COLUMN.defaultBlockState().getValue(BubbleColumnBlock.DRAG_DOWN);
        var base=downward?Blocks.MAGMA_BLOCK:Blocks.SOUL_SAND;
        if(planned!=null&&!planned.is(base)&&!planned.is(Blocks.BUBBLE_COLUMN))return null;
        if(actual.is(base)||actual.is(Blocks.BUBBLE_COLUMN)
                &&actual.getValue(BubbleColumnBlock.DRAG_DOWN)==downward)return step;
        // A planned lower column must stay fluid. Outside the schematic
        // only air or the opposite bubble generator is eligible.
        if(planned!=null&&planned.is(Blocks.BUBBLE_COLUMN))return null;
        if(planned==null&&!actual.isAir()&&!actual.is(Blocks.MAGMA_BLOCK)
                &&!actual.is(Blocks.SOUL_SAND))return null;
        String id=BuiltInRegistries.BLOCK.getKey(base).toString();
        return new BuilderConstructionPlan.Step(below.getX(),below.getY(),below.getZ(),id,id);
    }

    public static boolean samePhase(String a,String b){
        return BuilderDeferredState.verification(a)==BuilderDeferredState.verification(b)
                &&isWaterStep(a)==isWaterStep(b);
    }
}
