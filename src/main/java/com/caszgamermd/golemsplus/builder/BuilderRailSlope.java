package com.caszgamermd.golemsplus.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.BaseRailBlock;
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

    /** Independent rail geometry check: a physically correct ascending
     * rail can still be missing redstone POWER or waterlogging. Rebuilding
     * the temporary incline for a powered-only mismatch is wasteful and
     * repeatedly strands Builders on the Matrix floor.
     */
    public static boolean shapeMatches(BlockState actual,BlockState expected){
        return actual.getBlock()==expected.getBlock()
                &&expected.getBlock() instanceof BaseRailBlock
                &&!shapeName(expected).isEmpty()
                &&shapeName(actual).equals(shapeName(expected));
    }
    public static String railStateDifference(BlockState actual,
            BlockState expected){
        if(actual.getBlock()!=expected.getBlock())return "different rail item";
        if(!shapeMatches(actual,expected))
            return "rail shape expected="+shapeName(expected)
                    +" actual="+shapeName(actual);
        var powered=net.minecraft.world.level.block.state.properties
                .BlockStateProperties.POWERED;
        if(expected.hasProperty(powered)&&actual.hasProperty(powered)
                &&!actual.getValue(powered).equals(expected.getValue(powered)))
            return "redstone power expected="+expected.getValue(powered)
                    +" actual="+actual.getValue(powered);
        var waterlogged=net.minecraft.world.level.block.state.properties
                .BlockStateProperties.WATERLOGGED;
        if(expected.hasProperty(waterlogged)
                &&actual.hasProperty(waterlogged)
                &&!actual.getValue(waterlogged).equals(expected.getValue(waterlogged)))
            return "waterlogging expected="+expected.getValue(waterlogged)
                    +" actual="+actual.getValue(waterlogged);
        return "other rail properties";
    }
    private static String shapeName(BlockState rail){
        return rail.getValues()
                .filter(v->v.property().getName().equals("shape"))
                .map(v->v.valueName()).findFirst().orElse("");
    }
    public static boolean needsExtraStock(String encoded){
        return encoded!=null&&encoded.contains("shape=ascending_")
                &&encoded.contains("rail[");
    }
}
