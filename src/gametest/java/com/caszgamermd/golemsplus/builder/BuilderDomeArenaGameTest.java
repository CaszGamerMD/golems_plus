package com.caszgamermd.golemsplus.builder;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/** Full-size isolated test arena for the user's 31x39x34 Litematica Dome.
 * This setup check does NOT claim the Dome has yet been built.
 */
public final class BuilderDomeArenaGameTest {
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=200,skyAccess=true)
    public void providesEnoughSpaceForEntireDome(GameTestHelper helper) {
        // Dome is x=0..30, y=0..38, z=0..33; include a
        // margin for outside towers, workers and OnSite storage.
        // Default Fabric GameTests are 8x8 and physically block any
        // Builder that attempts to navigate outside their barriers.
        for(int x=4;x<=42;x++)for(int z=4;z<=42;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        var world=helper.getLevel();
        var corner=helper.absolutePos(new BlockPos(39,2,39));
        if(!world.getBlockState(corner.below()).is(Blocks.STONE)){
            helper.fail("Dome arena is too small; test boundary hides build");
            return;
        }
        var high=helper.absolutePos(new BlockPos(35,40,35));
        if(!world.getBlockState(high).isAir()){
            helper.fail("Dome needs clear overhead test volume");
            return;
        }
        helper.succeed();
    }
}
