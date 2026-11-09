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
