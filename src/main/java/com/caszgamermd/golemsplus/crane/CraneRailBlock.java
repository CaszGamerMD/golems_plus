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
import java.util.*;

/**
 * Vine-style crane track. Every block cell can contain rails attached to
 * several separate backing faces. Repeated placement adds exactly ONE face
 * without replacing the earlier rails. Each attached face offers the four
 * directions in its own plane, with supported floor/wall bend transitions.
 *
 * The six face flags are authoritative; no per-neighbor booleans are stored.
 * Connections/outline use the CURRENT world and update when neighbors change.
 * FACING remains only as the preferred chassis orientation for old saves.
 */
public final class CraneRailBlock extends Block {
    public static final EnumProperty<Direction> FACING=BlockStateProperties.FACING;
    public static final BooleanProperty FACE_NORTH=BooleanProperty.create("face_north");
    public static final BooleanProperty FACE_EAST=BooleanProperty.create("face_east");
    public static final BooleanProperty FACE_SOUTH=BooleanProperty.create("face_south");
    public static final BooleanProperty FACE_WEST=BooleanProperty.create("face_west");
    public static final BooleanProperty FACE_UP=BooleanProperty.create("face_up");
    public static final BooleanProperty FACE_DOWN=BooleanProperty.create("face_down");
    // Visual arms must follow ACTUAL neighboring rails; a face-mounted
    // centre by itself is not an unconditional four-way cross.
    // Six global link axes keep the blockstate finite (not 24 booleans
    // per face). A rail may still have up to six distinct attached faces.
    public static final BooleanProperty LINK_NORTH=BooleanProperty.create("link_north");
    public static final BooleanProperty LINK_EAST=BooleanProperty.create("link_east");
    public static final BooleanProperty LINK_SOUTH=BooleanProperty.create("link_south");
    public static final BooleanProperty LINK_WEST=BooleanProperty.create("link_west");
    public static final BooleanProperty LINK_UP=BooleanProperty.create("link_up");
    public static final BooleanProperty LINK_DOWN=BooleanProperty.create("link_down");

    public CraneRailBlock(Properties p){
        super(p);
        BlockState state=stateDefinition.any().setValue(FACING,Direction.UP);
        for(Direction direction:Direction.values())
            state=state.setValue(faceProperty(direction),direction==Direction.UP);
        for(Direction direction:Direction.values())
            state=state.setValue(linkProperty(direction),false);
        registerDefaultState(state);
    }
    @Override protected MapCodec<? extends Block> codec(){
        return simpleCodec(CraneRailBlock::new);
    }
    @Override protected void createBlockStateDefinition(
            StateDefinition.Builder<Block,BlockState> b){
        b.add(FACING,FACE_NORTH,FACE_EAST,FACE_SOUTH,FACE_WEST,FACE_UP,FACE_DOWN,
                LINK_NORTH,LINK_EAST,LINK_SOUTH,LINK_WEST,LINK_UP,LINK_DOWN);
    }
    private static BooleanProperty faceProperty(Direction d){
        return switch(d){
            case NORTH->FACE_NORTH;
            case EAST->FACE_EAST;
            case SOUTH->FACE_SOUTH;
            case WEST->FACE_WEST;
            case UP->FACE_UP;
            case DOWN->FACE_DOWN;
        };
    }
    private static BooleanProperty linkProperty(Direction d){
        return switch(d){
            case NORTH->LINK_NORTH;
            case EAST->LINK_EAST;
            case SOUTH->LINK_SOUTH;
            case WEST->LINK_WEST;
            case UP->LINK_UP;
            case DOWN->LINK_DOWN;
        };
    }
    public static boolean attached(BlockState s,Direction face){
        return s.getBlock() instanceof CraneRailBlock
                &&s.getValue(faceProperty(face));
    }
    /** At most six occupied faces, but each rail face has exactly four arms. */
    public static int faceCount(BlockState s){
        int count=0;
        for(Direction face:Direction.values())if(attached(s,face))count++;
        return count;
    }
    public static boolean supportsFace(LevelReader world,BlockPos p,Direction face){
        BlockPos backing=p.relative(face.getOpposite());
        return world.getBlockState(backing).isFaceSturdy(world,backing,face);
    }
    /** Same normal means continuous rail in the same plane. */
    private static boolean straight(LevelReader world,BlockPos p,
            Direction face,Direction travel){
        if(face.getAxis()==travel.getAxis())return false;
        BlockPos other=p.relative(travel);
        return attached(world.getBlockState(other),face);
    }
    /** Floor -> wall, ceiling -> wall, wall -> floor/ceiling transitions. */
    private static boolean corner(LevelReader world,BlockPos p,
            Direction face,Direction travel){
        if(face.getAxis()==travel.getAxis())return false;
        if(!(face.getAxis()==Direction.Axis.Y
                ^travel.getAxis()==Direction.Axis.Y))return false;
        BlockPos bend=p.relative(travel).relative(face);
        return attached(world.getBlockState(bend),travel.getOpposite());
    }
    public static boolean hasConnection(LevelReader world,BlockPos pos,
            Direction travel){
        BlockState s=world.getBlockState(pos);
        for(Direction face:Direction.values()){
            if(!attached(s,face)||face.getAxis()==travel.getAxis())continue;
            if(straight(world,pos,face,travel)
                    ||corner(world,pos,face,travel))return true;
        }
        return false;
    }
    public static int connections(LevelReader world,BlockPos pos){
        int count=0;
        for(Direction dir:Direction.values())
            if(hasConnection(world,pos,dir))count++;
        return count;
    }
    public static boolean linked(LevelReader world,BlockPos a,BlockPos b){
        BlockState s=world.getBlockState(a);
        if(!(s.getBlock() instanceof CraneRailBlock)
                ||!(world.getBlockState(b).getBlock() instanceof CraneRailBlock))
            return false;
        for(Direction face:Direction.values()){
            if(!attached(s,face))continue;
            for(Direction dir:Direction.values()){
                if(face.getAxis()==dir.getAxis())continue;
                if(a.relative(dir).equals(b)
                        &&straight(world,a,face,dir))return true;
                if(a.relative(dir).relative(face).equals(b)
                        &&corner(world,a,face,dir))return true;
            }
        }
        return false;
    }
    /** Rail paths work across every active face, including same-block
     * attachments, without duplicated neighboring positions.
     */
    public static List<BlockPos> linkedNeighbors(LevelReader world,BlockPos p){
        LinkedHashSet<BlockPos> result=new LinkedHashSet<>();
        BlockState s=world.getBlockState(p);
        if(!(s.getBlock() instanceof CraneRailBlock))return List.of();
        for(Direction face:Direction.values()){
            if(!attached(s,face))continue;
            for(Direction dir:Direction.values()){
                if(face.getAxis()==dir.getAxis())continue;
                BlockPos next=p.relative(dir);
                if(straight(world,p,face,dir))result.add(next);
                if(corner(world,p,face,dir))result.add(next.relative(face));
            }
        }
        return List.copyOf(result);
    }
    private static BlockState withSupportedFaces(BlockState s,LevelReader world,
            BlockPos p){
        if(!(s.getBlock() instanceof CraneRailBlock))return s;
        Direction preferred=s.getValue(FACING);
        Direction first=null;
        for(Direction d:Direction.values()){
            boolean keep=attached(s,d)&&supportsFace(world,p,d);
            if(attached(s,d)!=keep)s=s.setValue(faceProperty(d),keep);
            if(keep&&first==null)first=d;
        }
        if(first==null)return Blocks.AIR.defaultBlockState();
        if(!attached(s,preferred))s=s.setValue(FACING,first);
        // Recalculate on every neighbor update. Never render an arm that
        // has no real connected rail at the adjoining world coordinate.
        // Derive from the current retained face set (NOT stale world state
        // at pos, which can be replaced during support loss).
        for(Direction dir:Direction.values()){
            boolean connects=false;
            for(Direction face:Direction.values()){
                if(!attached(s,face)||face.getAxis()==dir.getAxis())continue;
                if(straight(world,p,face,dir)||corner(world,p,face,dir)){
                    connects=true;break;
                }
            }
            s=s.setValue(linkProperty(dir),connects);
        }
        return s;
    }
    @Override public BlockState getStateForPlacement(BlockPlaceContext c){
        BlockPos pos=c.getClickedPos();
        Direction face=c.getClickedFace();
        if(!supportsFace(c.getLevel(),pos,face))return null;
        BlockState existing=c.getLevel().getBlockState(pos);
        if(existing.is(this)){
            if(attached(existing,face))return null;
            return withSupportedFaces(existing.setValue(faceProperty(face),true),
                    c.getLevel(),pos);
        }
        BlockState state=defaultBlockState().setValue(FACING,face);
        for(Direction d:Direction.values())
            state=state.setValue(faceProperty(d),d==face);
        return withSupportedFaces(state,c.getLevel(),pos);
    }
    @Override protected boolean canBeReplaced(BlockState s,BlockPlaceContext ctx){
        // Like vines, right-click a different supported face with another
        // rail item to grow this one cell's set of attachments. An ordinary
        // block or repeated click on the same face must not overwrite it.
        return ctx.getItemInHand().is(this.asItem())
                &&!attached(s,ctx.getClickedFace())
                &&supportsFace(ctx.getLevel(),ctx.getClickedPos(),ctx.getClickedFace());
    }
    @Override protected boolean canSurvive(BlockState s,LevelReader world,BlockPos p){
        for(Direction face:Direction.values())
            if(attached(s,face)&&supportsFace(world,p,face))return true;
        return false;
    }
    @Override protected BlockState updateShape(BlockState s,LevelReader world,
            ScheduledTickAccess ticks,BlockPos pos,Direction direction,
            BlockPos neighborPos,BlockState neighborState,RandomSource random){
        return withSupportedFaces(s,world,pos);
    }

    private static VoxelShape center(Direction face){
        return switch(face){
            case UP->Block.box(5,0,5,11,2,11);
            case DOWN->Block.box(5,14,5,11,16,11);
            case NORTH->Block.box(5,5,0,11,11,2);
            case SOUTH->Block.box(5,5,14,11,11,16);
            case EAST->Block.box(14,5,5,16,11,11);
            case WEST->Block.box(0,5,5,2,11,11);
        };
    }
    private static VoxelShape arm(Direction face,Direction direction){
        return switch(face){
            case UP->switch(direction){
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
    /** Crane rails are surface-mounted guidance, not trip hazards.
     * Keep the thin selection/rendered outline while permitting workers
     * and players to walk across floor-mounted rails normally.
     * Chassis / basket still run their separate full swept collision
     * checks against every SOLID environmental block.
     */
    @Override protected VoxelShape getCollisionShape(BlockState state,
            BlockGetter world,BlockPos position,CollisionContext context){
        return Shapes.empty();
    }
    @Override protected VoxelShape getShape(BlockState s,BlockGetter w,
            BlockPos p,CollisionContext c){
        VoxelShape result=Shapes.empty();
        if(!(w instanceof LevelReader world))return result;
        for(Direction face:Direction.values()){
            if(!attached(s,face))continue;
            VoxelShape part=center(face);
            for(Direction travel:Direction.values()){
                if(face.getAxis()==travel.getAxis())continue;
                if(straight(world,p,face,travel)
                        ||corner(world,p,face,travel))
                    part=Shapes.or(part,arm(face,travel));
            }
            result=Shapes.or(result,part);
        }
        return result;
    }
}
