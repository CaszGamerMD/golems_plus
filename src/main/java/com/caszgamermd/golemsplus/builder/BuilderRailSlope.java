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

    public static boolean needsExtraStock(String encoded){
        return encoded!=null&&encoded.contains("shape=ascending_")
                &&encoded.contains("rail[");
    }
}
