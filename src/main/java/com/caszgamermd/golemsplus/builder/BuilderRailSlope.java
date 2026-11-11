package com.caszgamermd.golemsplus.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.Property;
import java.util.Map;
import net.minecraft.world.level.block.state.BlockState;

/** Temporary raised rail endpoint for vanilla ascending rail connections.
 * The neighbor is a BUILD AID, not a schematic step; it must be removed
 * before construction can report the intended rail as complete. */
public final class BuilderRailSlope {
    private BuilderRailSlope(){}

    public static BlockPos raisedEnd(BlockPos target,BlockState state){
        if(!(state.getBlock() instanceof BaseRailBlock))return null;
        String shape=state.getValues()
                .filter(v->v.property().getName().equals("shape"))
                .map(v->v.valueName()).findFirst().orElse("");
        return switch(shape){
            case "ascending_east"->target.east().above();
            case "ascending_west"->target.west().above();
            case "ascending_north"->target.north().above();
            case "ascending_south"->target.south().above();
            default->null;
        };
    }

    /** Vanilla ascending rails need the raised-end connector to sit on
     * physical support. A saved ascending-west shape next to a same-Y
     * rail is NOT proof that the west raised endpoint can survive.
     * Check before buying a replacement: a temporary raised rail is
     * permitted only where a real matching rail could survive.
     */
    public static boolean hasRaisedEndSupport(Level world,BlockPos pos,
            BlockState desired){
        BlockPos raised=raisedEnd(pos,desired);
        return raised==null
                ||desired.getBlock().defaultBlockState().canSurvive(world,raised);
    }

    /** An existing paid slope can be corrected without constructing a
     * raised connector. Its own support must still survive in the world.
     * An air/fluid/different block is a new placement, never a free repair.
     */
    public static boolean canReshapePaidSlope(Level world,BlockPos pos,
            BlockState desired){
        return raisedEnd(pos,desired)!=null
                &&world.getBlockState(pos).getBlock()==desired.getBlock()
                &&desired.canSurvive(world,pos);
    }

    /** The source schematic can encode an ascending rail whose elevated
     * neighbour is NOT another rail (for example the Matrix's iron
     * trapdoor). Vanilla rail updates may immediately flatten the paid rail
     * when a temporary connector is removed. Preserve the requested shape
     * on the ALREADY PLACED rail with a client-visible, no-neighbour-update
     * state correction. Never create a rail out of air, replace a foreign
     * block, bypass support checks, or invent another item. Later vanilla
     * updates may legitimately change its shape again; final world-state
     * verification must still detect that, rather than faking success. */
    public static boolean restorePaidSlope(Level world,BlockPos pos,
            BlockState desired){
        if(raisedEnd(pos,desired)==null)return false;
        BlockState actual=world.getBlockState(pos);
        if(actual.getBlock()!=desired.getBlock()
                ||!desired.canSurvive(world,pos))return false;
        if(BuilderPlacementState.matches(actual,desired))return true;
        if(!world.setBlock(pos,desired,2))return false;
        return BuilderPlacementState.matches(world.getBlockState(pos),desired);
    }

    /** A job can temporarily lift its own flat track to make room for
     * the jig footing. Other blocks and unrelated track stay untouched.
     */
    static boolean canStageFoundation(Level world,BlockPos pos,BlockState desired,
            Map<BlockPos,BlockState> planned){
        BlockPos raised=raisedEnd(pos,desired);
        if(raised==null||!world.hasChunkAt(raised)
                ||!world.getBlockState(raised).isAir())return false;
        BlockPos footing=raised.below();
        BlockState existing=world.getBlockState(footing);
        BlockState expected=planned.get(footing);
        return existing.isAir()
                ||expected!=null&&existing.getBlock() instanceof BaseRailBlock
                    &&raisedEnd(footing,existing)==null
                    &&existing.getFluidState().isEmpty()
                    &&BuilderPlacementState.matches(existing,expected);
    }

    /** Place an ordinary flat rail beside a real raised connector and let
     * Minecraft form the slope. Lift the formed rail while recovering the
     * jig, then reseat that same paid state without reshaping it.
     */
    static boolean placeWithJig(Level world,BlockPos pos,BlockState desired,
            boolean temporaryFoundation){
        BlockPos raised=raisedEnd(pos,desired);
        if(raised==null||!world.getBlockState(raised).isAir())return false;
        BlockPos footing=raised.below();
        BlockState previousFooting=world.getBlockState(footing);
        BlockState previousRail=world.getBlockState(pos);
        int quiet=Block.UPDATE_CLIENTS|Block.UPDATE_KNOWN_SHAPE
                |Block.UPDATE_MOVE_BY_PISTON|Block.UPDATE_SKIP_ON_PLACE;
        BlockState formed=null;
        try{
            if(temporaryFoundation
                    &&!world.setBlock(footing,Blocks.COBBLESTONE.defaultBlockState(),quiet))
                return false;
            // Force a fresh placement callback even when correcting a
            // rail of the same type already present in the target cell.
            if(previousRail.getBlock()==desired.getBlock())
                world.setBlock(pos,Blocks.AIR.defaultBlockState(),quiet);
            BlockState connector=desired.getBlock().defaultBlockState();
            if(!connector.canSurvive(world,raised)
                    ||!world.setBlock(raised,connector,Block.UPDATE_ALL))return false;
            BlockState flat=BuilderBlockInteractions.placementState(desired);
            for(var property:flat.getProperties()){
                if(property.getName().equals("shape")){
                    String axis=raised.getX()==pos.getX()?"north_south":"east_west";
                    flat=withValue(flat,property,axis);
                    break;
                }
            }
            world.setBlock(pos,flat,Block.UPDATE_ALL);
            BlockState candidate=world.getBlockState(pos);
            if(BuilderPlacementState.matches(candidate,desired))formed=candidate;
        }finally{
            // Rail removal has its own neighbor callbacks even with
            // UPDATE_NEIGHBORS disabled. Keep the paid rail in hand
            // during those callbacks so the temporary geometry cannot
            // cause it to drop a second item or flatten the slope.
            if(world.getBlockState(pos).getBlock()==desired.getBlock())
                world.setBlock(pos,Blocks.AIR.defaultBlockState(),quiet);
            if(!world.getBlockState(raised).isAir())
                world.setBlock(raised,Blocks.AIR.defaultBlockState(),quiet);
            if(temporaryFoundation)
                world.setBlock(footing,previousFooting,quiet);
            world.setBlock(pos,formed==null?previousRail:formed,quiet);
        }
        return formed!=null&&BuilderPlacementState.matches(world.getBlockState(pos),desired);
    }

    private static <T extends Comparable<T>> BlockState withValue(
            BlockState state,Property<T> property,String name){
        return property.getValue(name).map(value->state.setValue(property,value))
                .orElse(state);
    }

    public static boolean needsExtraStock(String encoded){
        return encoded!=null&&encoded.contains("shape=ascending_")
                &&encoded.contains("rail[");
    }
}
