package com.caszgamermd.golemsplus.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/** Creative-only fluid source tool. Neither players nor Builders consume it. */
public final class InfiniteFluidBucketItem extends Item {
    private final Block fluid;
    public InfiniteFluidBucketItem(Block fluid,Properties properties){
        super(properties);
        if(fluid!=Blocks.WATER&&fluid!=Blocks.LAVA)
            throw new IllegalArgumentException("Expected water or lava");
        this.fluid=fluid;
    }
    @Override public InteractionResult useOn(UseOnContext context){
        BlockPos clicked=context.getClickedPos();
        BlockPos target=context.getLevel().getBlockState(clicked).canBeReplaced()
                ?clicked:clicked.relative(context.getClickedFace());
        if(!context.getLevel().getBlockState(target).canBeReplaced())
            return InteractionResult.FAIL;
        if(context.getLevel() instanceof ServerLevel level
                &&!level.setBlock(target,fluid.defaultBlockState(),3))
            return InteractionResult.FAIL;
        return InteractionResult.SUCCESS;
    }
}
