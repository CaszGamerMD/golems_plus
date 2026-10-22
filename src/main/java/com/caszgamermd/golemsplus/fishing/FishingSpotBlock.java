package com.caszgamermd.golemsplus.fishing;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Shore flag, or floating fishing-spot buoy on water surfaces. */
public final class FishingSpotBlock extends Block implements SimpleWaterloggedBlock {
    public static final BooleanProperty WATERLOGGED=BlockStateProperties.WATERLOGGED;
    private final MapCodec<FishingSpotBlock> codec=MapCodec.unit(this);
    public FishingSpotBlock(BlockBehaviour.Properties props){
        super(props);
        registerDefaultState(defaultBlockState().setValue(WATERLOGGED,false));
    }
    @Override protected MapCodec<? extends Block> codec(){return codec;}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b){
        b.add(WATERLOGGED);
    }
    @Override protected VoxelShape getCollisionShape(BlockState state,BlockGetter level,
            BlockPos pos,CollisionContext ctx){return Shapes.empty();}
    @Override protected FluidState getFluidState(BlockState state){
        return state.getValue(WATERLOGGED)?Fluids.WATER.getSource(false):super.getFluidState(state);
    }
    @Override protected BlockState updateShape(BlockState state,LevelAccessor world,
            net.minecraft.world.level.redstone.Orientation orientation,
            Direction face,BlockPos near,BlockState other,
            net.minecraft.util.RandomSource random){
        if(state.getValue(WATERLOGGED))
            world.scheduleTick(near,Fluids.WATER,Fluids.WATER.getTickDelay(world));
        return super.updateShape(state,world,orientation,face,near,other,random);
    }
}
