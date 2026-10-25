package com.caszgamermd.golemsplus.builder;

import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.IronBarsBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;

/** World block states may change their neighbour-derived connection/shape
 * after an otherwise correct schematic placement. Treating those mutations
 * as wrong blocks caused Builders to mine their OWN correctly placed walls
 * and stairs, then wait forever for a supporting block.
 *
 * Preserve orientation and functional properties. This is NOT a generic
 * "same block type is good enough" check: stairs keep facing/half and
 * walls/panes/fences can differ ONLY by auto-connectivity.
 */
public final class BuilderPlacementState {
    private BuilderPlacementState(){}

    public static boolean matches(BlockState actual,BlockState schematic){
        if(actual.equals(schematic))return true;
        if(actual.getBlock()!=schematic.getBlock())return false;

        if(actual.getBlock() instanceof StairBlock)
            return actual.getValue(StairBlock.FACING)
                        ==schematic.getValue(StairBlock.FACING)
                    &&actual.getValue(StairBlock.HALF)
                        ==schematic.getValue(StairBlock.HALF)
                    &&actual.getValue(StairBlock.WATERLOGGED)
                        ==schematic.getValue(StairBlock.WATERLOGGED);

        if(actual.getBlock() instanceof WallBlock
                ||actual.getBlock() instanceof FenceBlock
                ||actual.getBlock() instanceof IronBarsBlock){
            // Those blocks have only neighbour-connection geometry plus
            // waterlogged. The resolver rejects waterlogged builds, and
            // connectivity is recomputed when neighbours are built.
            return !actual.getFluidState().isSource()
                    &&!schematic.getFluidState().isSource();
        }
        return false;
    }
}
