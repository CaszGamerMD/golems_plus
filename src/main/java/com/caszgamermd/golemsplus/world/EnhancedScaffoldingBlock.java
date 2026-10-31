package com.caszgamermd.golemsplus.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** Scaffolding that never loses support as a bridge extends outward. */
public final class EnhancedScaffoldingBlock extends ScaffoldingBlock {
    public EnhancedScaffoldingBlock(BlockBehaviour.Properties properties){
        super(properties);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context){
        return defaultBlockState().setValue(DISTANCE,0).setValue(BOTTOM,false)
                .setValue(WATERLOGGED,context.getLevel().getFluidState(
                        context.getClickedPos()).isSource());
    }

    @Override
    protected void tick(BlockState state,ServerLevel level,BlockPos pos,
            RandomSource random){
        // Vanilla scaffolding falls when its horizontal support distance
        // reaches seven. Enhanced scaffolding intentionally never falls.
    }
}
