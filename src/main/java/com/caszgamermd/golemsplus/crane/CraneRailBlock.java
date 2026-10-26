package com.caszgamermd.golemsplus.crane;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.*;
import net.minecraft.world.level.block.state.properties.*;
import net.minecraft.world.phys.shapes.*;
import net.minecraft.world.ticks.ScheduledTickAccess;

/**
 * Redstone-style rail junctions on any sturdy face. There are six possible
 * world directions, but exactly FOUR lie in the plane of any mounted rail.
 * Connections are computed in world coordinates so wall rails can go up,
 * down, left and right; floor/ceiling rails have four horizontal branches.
 */
public final class CraneRailBlock extends Block {
    public static final EnumProperty<Direction> FACING=BlockStateProperties.FACING;
    public static final BooleanProperty NORTH=BooleanProperty.create("north");
    public static final BooleanProperty EAST=BooleanProperty.create("east");
    public static final BooleanProperty SOUTH=BooleanProperty.create("south");
    public static final BooleanProperty WEST=BooleanProperty.create("west");
    public static final BooleanProperty UP=BooleanProperty.create("up");
    public static final BooleanProperty DOWN=BooleanProperty.create("down");

    public CraneRailBlock(Properties p){
        super(p);
        BlockState s=stateDefinition.any().setValue(FACING,Direction.UP);
        for(Direction d:Direction.values())s=s.setValue(property(d),false);
        registerDefaultState(s);
    }
    @Override protected MapCodec<? extends Block> codec(){return simpleCodec(CraneRailBlock::new);}
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block,BlockState> b){
        b.add(FACING,NORTH,EAST,SOUTH,WEST,UP,DOWN);
    }
    private static BooleanProperty property(Direction d){
        return switch(d){
            case NORTH->NORTH; case EAST->EAST;case SOUTH->SOUTH;
            case WEST->WEST;case UP->UP;case DOWN->DOWN;
        };
    }
    private static boolean planar(Direction face,Direction travel){
        return face.getAxis()!=travel.getAxis();
    }
    public static int connections(BlockState state){
        int count=0;
        for(Direction d:Direction.values())if(planar(state.getValue(FACING),d)
                &&state.getValue(property(d)))count++;
        return count;
    }
    /** An edge can only run in the plane of BOTH pieces. */
    private static boolean straight(LevelReader world,BlockPos a,Direction d){
        BlockState s=world.getBlockState(a),n=world.getBlockState(a.relative(d));
        return s.getBlock() instanceof CraneRailBlock
                &&n.getBlock() instanceof CraneRailBlock
                &&planar(s.getValue(FACING),d)
                &&planar(n.getValue(FACING),d.getOpposite())
                &&n.canSurvive(world,a.relative(d));
    }
    /**
     * A rising two-axis corner connects a floor/ceiling rail to a wall rail.
     * The rail at the corner sits one step in its travel direction PLUS one
     * step along the mounting normal: floor WEST -> wall WEST+UP; the wall
     * points back toward the floor. The opposite wall DOWN -> floor
     * DOWN+wall-normal is exactly reciprocal.
     */
    private static boolean corner(LevelReader world,BlockPos a,Direction d){
        BlockState s=world.getBlockState(a);
        if(!(s.getBlock() instanceof CraneRailBlock))return false;
        Direction face=s.getValue(FACING);
        if(!planar(face,d))return false;
        BlockPos b=a.relative(d).relative(face);
        BlockState n=world.getBlockState(b);
        if(!(n.getBlock() instanceof CraneRailBlock)
                ||n.getValue(FACING)!=d.getOpposite()||!n.canSurvive(world,b))
            return false;
        return face.getAxis()==Direction.Axis.Y ^ d.getAxis()==Direction.Axis.Y;
    }
    public static boolean hasConnection(LevelReader world,BlockPos pos,Direction d){
        BlockState s=world.getBlockState(pos);
        if(!(s.getBlock() instanceof CraneRailBlock)||!planar(s.getValue(FACING),d))
            return false;
        return straight(world,pos,d)||corner(world,pos,d);
    }
    public static boolean linked(LevelReader world,BlockPos a,BlockPos b){
        BlockState s=world.getBlockState(a);
        if(!(s.getBlock() instanceof CraneRailBlock)
                ||!(world.getBlockState(b).getBlock() instanceof CraneRailBlock))return false;
        for(Direction d:Direction.values()){
            if(!planar(s.getValue(FACING),d))continue;
            if(a.relative(d).equals(b)&&straight(world,a,d))return true;
            if(a.relative(d).relative(s.getValue(FACING)).equals(b)
                    &&corner(world,a,d))return true;
        }
        return false;
    }
    /** Includes the eight climb/descend bends used for ground-to-wall travel. */
    public static java.util.List<BlockPos> linkedNeighbors(LevelReader world,BlockPos p){
        java.util.List<BlockPos> result=new java.util.ArrayList<>(4);
        BlockState s=world.getBlockState(p);
        if(!(s.getBlock() instanceof CraneRailBlock))return result;
        for(Direction d:Direction.values()){
            if(!planar(s.getValue(FACING),d))continue;
            BlockPos adjacent=p.relative(d);
            if(straight(world,p,d))result.add(adjacent);
            else if(corner(world,p,d))result.add(adjacent.relative(s.getValue(FACING)));
        }
        return result;
    }
    private static BlockState reconnect(BlockState state,LevelReader world,BlockPos p){
        // Refresh this state's connections even during placement, before the
        // rail itself exists in the world (neighbor helpers assume it does).
        Direction face=state.getValue(FACING);
        for(Direction d:Direction.values()){
            boolean connected=false;
            if(planar(face,d)){
                BlockPos a=p.relative(d);
                BlockState n=world.getBlockState(a);
                connected=n.getBlock() instanceof CraneRailBlock
                        &&planar(n.getValue(FACING),d.getOpposite())
                        &&n.canSurvive(world,a);
                if(!connected){
                    BlockPos b=a.relative(face);
                    BlockState bend=world.getBlockState(b);
                    connected=bend.getBlock() instanceof CraneRailBlock
                            &&bend.getValue(FACING)==d.getOpposite()
                            &&bend.canSurvive(world,b)
                            &&(face.getAxis()==Direction.Axis.Y
                                    ^d.getAxis()==Direction.Axis.Y);
                }
            }
            state=state.setValue(property(d),connected);
        }
        return state;
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext c){
        BlockState s=defaultBlockState().setValue(FACING,c.getClickedFace());
        return s.canSurvive(c.getLevel(),c.getClickedPos())
                ?reconnect(s,c.getLevel(),c.getClickedPos()):null;
    }
    @Override protected boolean canSurvive(BlockState s,LevelReader world,BlockPos p){
        Direction d=s.getValue(FACING);BlockPos support=p.relative(d.getOpposite());
        return world.getBlockState(support).isFaceSturdy(world,support,d);
    }
    @Override protected BlockState updateShape(BlockState state,LevelReader world,
            ScheduledTickAccess ticks,BlockPos pos,Direction direction,
            BlockPos neighborPos,BlockState neighborState,RandomSource random){
        if(!state.canSurvive(world,pos))return Blocks.AIR.defaultBlockState();
        return reconnect(state,world,pos);
    }
    /**
     * Diagonal edge joins do not receive vanilla six-neighbor notifications.
     * Refresh the small radius around each placement/removal explicitly;
     * same-block state updates do not recurse through onPlace/onRemove.
     */
    private static void refresh(Level world,BlockPos pos){
        for(int dx=-1;dx<=1;dx++)for(int dy=-1;dy<=1;dy++)
            for(int dz=-1;dz<=1;dz++){
                if(Math.abs(dx)+Math.abs(dy)+Math.abs(dz)>2)continue;
                BlockPos p=pos.offset(dx,dy,dz);
                BlockState at=world.getBlockState(p);
                if(at.getBlock() instanceof CraneRailBlock){
                    BlockState next=reconnect(at,world,p);
                    if(next!=at)world.setBlock(p,next,2);
                }
            }
    }
    @Override protected void onPlace(BlockState state,Level level,BlockPos pos,
            BlockState oldState,boolean movedByPiston){
        super.onPlace(state,level,pos,oldState,movedByPiston);
        if(oldState.getBlock()!=state.getBlock())refresh(level,pos);
    }
    @Override protected void onRemove(BlockState state,Level level,BlockPos pos,
            BlockState newState,boolean movedByPiston){
        super.onRemove(state,level,pos,newState,movedByPiston);
        if(newState.getBlock()!=state.getBlock())refresh(level,pos);
    }

    private static VoxelShape arm(Direction face,Direction direction){
        int min=0,max=16;
        return switch(face){
            case UP-> switch(direction){
                case NORTH->Block.box(6,0,0,10,2,8);
                case SOUTH->Block.box(6,0,8,10,2,16);
                case EAST->Block.box(8,0,6,16,2,10);
                case WEST->Block.box(0,0,6,8,2,10);
                default->Shapes.empty();
            };
            case DOWN->switch(direction){
                case NORTH->Block.box(6,14,0,10,16,8);
                case SOUTH->Block.box(6,14,8,10,16,16);
                case EAST->Block.box(8,14,6,16,16,10);
                case WEST->Block.box(0,14,6,8,16,10);
                default->Shapes.empty();
            };
            case NORTH,SOUTH->{
                int lo=face==Direction.NORTH?0:14,hi=lo+2;
                yield switch(direction){
                    case UP->Block.box(6,8,lo,10,16,hi);
                    case DOWN->Block.box(6,0,lo,10,8,hi);
                    case EAST->Block.box(8,6,lo,16,10,hi);
                    case WEST->Block.box(0,6,lo,8,10,hi);
                    default->Shapes.empty();
                };
            }
            case EAST,WEST->{
                int lo=face==Direction.EAST?14:0,hi=lo+2;
                yield switch(direction){
                    case UP->Block.box(lo,8,6,hi,16,10);
                    case DOWN->Block.box(lo,0,6,hi,8,10);
                    case NORTH->Block.box(lo,6,0,hi,10,8);
                    case SOUTH->Block.box(lo,6,8,hi,10,16);
                    default->Shapes.empty();
                };
            }
        };
    }
    @Override protected VoxelShape getShape(BlockState s,BlockGetter w,BlockPos p,CollisionContext c){
        Direction face=s.getValue(FACING);
        VoxelShape shape=switch(face){
            case UP->Block.box(5,0,5,11,2,11);
            case DOWN->Block.box(5,14,5,11,16,11);
            case NORTH->Block.box(5,5,0,11,11,2);
            case SOUTH->Block.box(5,5,14,11,11,16);
            case EAST->Block.box(14,5,5,16,11,11);
            case WEST->Block.box(0,5,5,2,11,11);
        };
        for(Direction d:Direction.values())if(planar(face,d)&&s.getValue(property(d)))
            shape=Shapes.or(shape,arm(face,d));
        return shape;
    }
}
