package com.caszgamermd.golemsplus.builder;

import java.util.Map;
import net.minecraft.core.BlockPos;
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
    /** A water pass checks the actual paid host, not its transient open
     * or powered state. Orientation/half/type and real fluid still matter. */
    public static boolean hostMatches(BlockState actual,BlockState dry){
        if(actual.getBlock()!=dry.getBlock()||!actual.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED))return false;
        BlockState host=actual.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED,false);
        if(host.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN)){
            var open=net.minecraft.world.level.block.state.properties.BlockStateProperties.OPEN;
            host=host.setValue(open,dry.getValue(open));
        }
        return BuilderPlacementState.matches(host,dry);
    }
    static BlockState wetHost(BlockState actual){
        return actual.setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED,true);
    }
    /** The effective action keeps the original column's saved claim index. */
    static BuilderConstructionPlan.Step bubbleAction(
            Level world, BlockPos column, BuilderConstructionPlan.Step step,
            Map<BlockPos,BlockState> schematic) {
        if(!isBubbleColumn(step.encodedState()))return step;
        // A column is a source-water requirement, not an instruction
        // to invent or replace its generator. A planned base has its own
        // paid claim and support dependency. Preserve lowest-source repair.
        var lower=new java.util.ArrayList<BlockPos>();
        BlockPos at=column.below();
        while(at.getY()>=world.getMinY()&&world.hasChunkAt(at)){
            BlockState want=schematic.get(at);
            if(want==null||!want.is(Blocks.BUBBLE_COLUMN))break;
            lower.add(at);at=at.below();
        }
        for(int k=lower.size()-1;k>=0;k--){
            BlockPos p=lower.get(k);BlockState live=world.getBlockState(p);
            if(BuilderPlacementState.matches(live,schematic.get(p)))continue;
            // Never replace unrelated solids as an implicit water repair.
            if(!live.isAir()&&!live.is(Blocks.WATER)&&!live.is(Blocks.BUBBLE_COLUMN))continue;
            return new BuilderConstructionPlan.Step(p.getX(),p.getY(),p.getZ(),
                    step.encodedState(),WATER_BUCKET);
        }
        return step;
    }

    /** A paid source can exist without a live bubble generator. The
     * actual placement still requires reach, permissions and a clear cell. */
    static boolean sourceReady(Level level,BlockPos pos,BlockState desired){
        return desired.is(Blocks.BUBBLE_COLUMN)&&level.hasChunkAt(pos);
    }
    /** Wake the genuine generator at the bottom of a contiguous source
     * column, rather than scheduling a no-op tick on an ordinary water block. */
    static boolean refreshBubble(net.minecraft.server.level.ServerLevel level,BlockPos pos,BlockState desired){
        if(!desired.is(Blocks.BUBBLE_COLUMN))return false;
        boolean down=desired.getValue(BubbleColumnBlock.DRAG_DOWN);
        var generator=down?Blocks.MAGMA_BLOCK:Blocks.SOUL_SAND;
        BlockPos at=pos.below();
        BlockPos liveBubble=null;
        while(at.getY()>=level.getMinY()&&level.hasChunkAt(at)){
            BlockState actual=level.getBlockState(at);
            if(actual.is(generator)){
                // Vanilla can propagate from both the real base and an
                // existing lower bubble. Wake the nearest bubble as well
                // as the generator; do not manufacture a block state.
                level.scheduleTick(at,generator,1);
                if(liveBubble!=null)level.scheduleTick(liveBubble,Blocks.BUBBLE_COLUMN,1);
                return true;
            }
            if(actual.is(Blocks.BUBBLE_COLUMN)){
                if(actual.getValue(BubbleColumnBlock.DRAG_DOWN)!=down)return false;
                if(liveBubble==null)liveBubble=at.immutable();
            }else if(!(actual.is(Blocks.WATER)&&actual.getFluidState().isSource()))return false;
            at=at.below();
        }
        return false;
    }
    public static boolean samePhase(String a,String b){
        return BuilderDeferredState.verification(a)==BuilderDeferredState.verification(b)
                &&isWaterStep(a)==isWaterStep(b);
    }
}
