package com.caszgamermd.golemsplus.builder;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Visible, non-colliding marker placed at a Builder's feet-level rally tile. */
public final class BuilderRallyFlagBlock extends Block {
    private static final VoxelShape SHAPE=Shapes.or(
            Block.box(7,0,7,9,16,9),
            Block.box(8,9,8,16,15,8.5));
    private final MapCodec<BuilderRallyFlagBlock> codec=MapCodec.unit(this);
    public BuilderRallyFlagBlock(BlockBehaviour.Properties properties){super(properties);}
    @Override protected MapCodec<? extends Block> codec(){return codec;}
    @Override protected VoxelShape getShape(BlockState state,BlockGetter level,
            BlockPos pos,net.minecraft.world.phys.shapes.CollisionContext context){
        return SHAPE;
    }
}
