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
        // A previously finished lower bubble may have fallen back to
        // source water, flowing water or air. Rebuild the *lowest* missing
        // source in the original column before finishing the upper claim.
        if(planned!=null&&planned.is(Blocks.BUBBLE_COLUMN)){
            var lower=new java.util.ArrayList<BlockPos>();
            BlockPos scan=below;
            while(scan.getY()>=world.getMinY()&&world.hasChunkAt(scan)){
                BlockState want=schematic.get(scan);
                if(want==null||!want.is(Blocks.BUBBLE_COLUMN))break;
                if(want.getValue(BubbleColumnBlock.DRAG_DOWN)!=downward)return null;
                BlockState live=world.getBlockState(scan);
                if(!live.isAir()&&!live.is(Blocks.WATER)&&!live.is(Blocks.BUBBLE_COLUMN))return null;
                lower.add(scan);scan=scan.below();
            }
            if(!world.hasChunkAt(scan))return null;
            BlockState support=world.getBlockState(scan);
            BlockState wanted=schematic.get(scan);
            if(wanted!=null&&!wanted.is(base))return null;
            if(!support.is(base)){
                if(!support.isAir()&&!support.is(Blocks.SOUL_SAND)&&!support.is(Blocks.MAGMA_BLOCK))return null;
                String id=BuiltInRegistries.BLOCK.getKey(base).toString();
                return new BuilderConstructionPlan.Step(scan.getX(),scan.getY(),scan.getZ(),id,id);
            }
            for(int k=lower.size()-1;k>=0;k--){
                BlockPos at=lower.get(k);BlockState live=world.getBlockState(at);
                if(live.is(Blocks.BUBBLE_COLUMN)||live.is(Blocks.WATER)&&live.getFluidState().isSource())continue;
                return new BuilderConstructionPlan.Step(at.getX(),at.getY(),at.getZ(),step.encodedState(),WATER_BUCKET);
            }
            // All source water exists. A real generator tick can restore
            // the whole chain; there is no artificial bubble placement.
            return step;
        }
        if(planned==null&&!actual.isAir()&&!actual.is(Blocks.MAGMA_BLOCK)
                &&!actual.is(Blocks.SOUL_SAND))return null;
        String id=BuiltInRegistries.BLOCK.getKey(base).toString();
        return new BuilderConstructionPlan.Step(below.getX(),below.getY(),below.getZ(),id,id);
    }

    /** Wake the genuine generator at the bottom of a contiguous source
     * column, rather than scheduling a no-op tick on an ordinary water block. */
    static boolean refreshBubble(net.minecraft.server.level.ServerLevel level,BlockPos pos,BlockState desired){
        if(!desired.is(Blocks.BUBBLE_COLUMN))return false;
        boolean down=desired.getValue(BubbleColumnBlock.DRAG_DOWN);
        var generator=down?Blocks.MAGMA_BLOCK:Blocks.SOUL_SAND;
        BlockPos at=pos.below();
        while(at.getY()>=level.getMinY()&&level.hasChunkAt(at)){
            BlockState actual=level.getBlockState(at);
            if(actual.is(generator)){
                level.scheduleTick(at,generator,1);return true;
            }
            if(!actual.is(Blocks.BUBBLE_COLUMN)
                    &&!(actual.is(Blocks.WATER)&&actual.getFluidState().isSource()))return false;
            at=at.below();
        }
        return false;
    }
    public static boolean samePhase(String a,String b){
        return BuilderDeferredState.verification(a)==BuilderDeferredState.verification(b)
                &&isWaterStep(a)==isWaterStep(b);
    }
}
