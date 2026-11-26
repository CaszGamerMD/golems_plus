package com.caszgamermd.golemsplus.builder;

import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Replace incidental flowing water at an exact paid schematic target.
 * Never drain a source, bubble column, waterlogged host, lava or a
 * neighboring cell. The ordinary placement transaction and owner checks
 * remain responsible for actual world changes. */
public final class BuilderFluidObstruction {
    private BuilderFluidObstruction(){}
    public static boolean displaceable(BlockState actual,BlockState desired){
        return actual.is(Blocks.WATER)&&!actual.getFluidState().isSource()
                &&!desired.isAir()&&desired.getFluidState().isEmpty();
    }
    /** A source occupying an exact planned generator or rail cell is not
     * an intended water cell. Allow physical replacement only when the
     * caller confirms the original schematic target and normal protection,
     * inventory and reach checks. Never remove waterlogged foreign hosts. */
    public static boolean plannedWaterRepair(BlockState actual,BlockState desired){
        return actual.is(Blocks.WATER)
                &&desired.getFluidState().isEmpty()
                &&(desired.is(Blocks.SOUL_SAND)||desired.is(Blocks.MAGMA_BLOCK)
                    ||desired.getBlock() instanceof net.minecraft.world.level.block.BaseRailBlock);
    }
    /** Only non-load-bearing attachments can safely wait for the entire
     * surrounding structure to be completed. Never postpone a support,
     * rail, container, water source, or fluid-dependent structural cell. */
    public static boolean deferable(BlockState desired){
        return desired.is(Blocks.GLOW_LICHEN)&&desired.getFluidState().isEmpty();
    }
    public static boolean waterObstruction(BlockState actual,BlockState desired){
        return !desired.isAir()&&desired.getFluidState().isEmpty()
                &&(actual.is(Blocks.WATER)||actual.is(Blocks.BUBBLE_COLUMN)
                    ||actual.getBlock()==desired.getBlock()&&!actual.getFluidState().isEmpty());
    }
    public static boolean recheck(BlockState actual,BlockState desired){
        return waterObstruction(actual,desired)
                ||deferable(desired)&&actual.isAir();
    }
}
