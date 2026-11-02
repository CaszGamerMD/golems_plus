package com.caszgamermd.golemsplus.breakroom;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.*;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.shapes.*;
public final class BreakRoomBlock extends HorizontalDirectionalBlock {
 private final String kind;
 public BreakRoomBlock(Properties p,String kind){super(p);this.kind=kind;registerDefaultState(stateDefinition.any().setValue(FACING,Direction.NORTH));}
 @Override protected MapCodec<? extends HorizontalDirectionalBlock> codec(){return simpleCodec(p->new BreakRoomBlock(p,kind));}
 @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b){b.add(FACING);}
 @Override public BlockState getStateForPlacement(BlockPlaceContext c){return defaultBlockState().setValue(FACING,c.getHorizontalDirection().getOpposite());}
 @Override protected VoxelShape getShape(BlockState s,BlockGetter w,BlockPos p,CollisionContext c){
  if(kind.equals("break_room_table"))return Shapes.or(Block.box(0,12,0,16,16,16),Block.box(6,0,6,10,12,10));
  if(kind.equals("break_room_chair"))return Shapes.or(Block.box(2,6,2,14,9,14),Block.box(3,0,3,5,6,5),Block.box(11,0,3,13,6,5),Block.box(3,0,11,5,6,13),Block.box(11,0,11,13,6,13));
  return Block.box(1,0,1,15,16,15);
 }
}
