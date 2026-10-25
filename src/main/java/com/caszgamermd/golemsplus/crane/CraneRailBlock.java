package com.caszgamermd.golemsplus.crane;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.shapes.*;

/** Rails attach to a solid face, including walls. Facing points away from support. */
public final class CraneRailBlock extends Block {
    public static final EnumProperty<Direction> FACING=BlockStateProperties.FACING;
    public CraneRailBlock(Properties p){super(p);registerDefaultState(stateDefinition.any().setValue(FACING,Direction.UP));}
    @Override protected MapCodec<? extends Block> codec(){return simpleCodec(CraneRailBlock::new);}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b){b.add(FACING);}
    @Override public BlockState getStateForPlacement(BlockPlaceContext c){
        BlockState s=defaultBlockState().setValue(FACING,c.getClickedFace());
        return s.canSurvive(c.getLevel(),c.getClickedPos())?s:null;
    }
    @Override protected boolean canSurvive(BlockState s,LevelReader world,BlockPos p){
        Direction d=s.getValue(FACING);BlockPos support=p.relative(d.getOpposite());
        return world.getBlockState(support).isFaceSturdy(world,support,d);
    }
    @Override protected VoxelShape getShape(BlockState s,BlockGetter w,BlockPos p,CollisionContext c){
        return switch(s.getValue(FACING)){
            case UP->Block.box(0,0,0,16,2,16);case DOWN->Block.box(0,14,0,16,16,16);
            case NORTH->Block.box(0,0,14,16,16,16);case SOUTH->Block.box(0,0,0,16,16,2);
            case EAST->Block.box(0,0,0,2,16,16);case WEST->Block.box(14,0,0,16,16,16);
        };
    }
}
