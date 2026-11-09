package com.caszgamermd.golemsplus.builder;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Isolated vanilla-physics diagnostic for the exact full-Matrix
 * ascending powered rail at source local (15,1,24). This test does NOT
 * assert the full build succeeds or alter the original litematic.
 */
public final class BuilderMatrixRailPhysicsGameTest {
    @GameTest(structure="golems_plus_gametest:matrix_full_arena",
            environment="golems_plus_gametest:matrix_full_e2e",
            maxTicks=160,skyAccess=true)
    public void raisedJigRemovalMustPreserveExpectedRail(GameTestHelper h){
        ServerLevel world=h.getLevel();
        BlockPos lower=h.absolutePos(new BlockPos(12,4,12));
        // The Matrix requests ascending WEST and its raised endpoint is
        // one block higher immediately to the west.
        BlockState expected=BuilderBlockResolver.resolve(
                "minecraft:powered_rail[powered=true,shape=ascending_west,waterlogged=false]",
                BuildSite.Rotation.NONE,BuildSite.Mirror.NONE).state();
        BlockPos upper=BuilderRailSlope.raisedEnd(lower,expected);
        if(upper==null){h.fail("Couldn't locate ascending rail end");return;}
        for(int y=2;y<=8;y++)for(int x=8;x<=15;x++)for(int z=9;z<=15;z++)
            world.setBlock(h.absolutePos(new BlockPos(x,y,z)),
                    Blocks.AIR.defaultBlockState(),3);
        world.setBlock(lower.below(),Blocks.STONE.defaultBlockState(),3);
        world.setBlock(upper.below(),Blocks.STONE.defaultBlockState(),3);
        BlockState jig=Blocks.POWERED_RAIL.defaultBlockState();
        if(!jig.canSurvive(world,upper)){
            h.fail("Isolated upper rail jig cannot survive on real stone");
            return;
        }
        boolean placedUpper=world.setBlock(upper,jig,3);
        boolean placedLower=world.setBlock(lower,expected,3);
        BlockState during=world.getBlockState(lower);
        boolean removedUpper=world.setBlock(upper,Blocks.AIR.defaultBlockState(),3);
        BlockState after=world.getBlockState(lower);
        System.out.println("[MATRIX RAIL PHYSICS] local=(15,1,24)"
                +" expected="+expected+" raised="+upper.toShortString()
                +" upperPlaced="+placedUpper+" lowerPlaced="+placedLower
                +" upperRemoved="+removedUpper
                +" duringJig="+during+" afterRemoval="+after
                +" stable="+BuilderPlacementState.matches(after,expected));
        if(!placedUpper||!placedLower||!removedUpper){
            h.fail("Vanilla failed isolated rail placement/removal");return;
        }
        // Report a factual, discrete physical outcome. The full build
        // must separately assert every final block state, not inherit
        // any success from this isolated diagnostic.
        h.succeed();
    }
}
