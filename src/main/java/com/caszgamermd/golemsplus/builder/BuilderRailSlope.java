package com.caszgamermd.golemsplus.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.Level;
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

    public static boolean needsExtraStock(String encoded){
        return encoded!=null&&encoded.contains("shape=ascending_")
                &&encoded.contains("rail[");
    }
}
