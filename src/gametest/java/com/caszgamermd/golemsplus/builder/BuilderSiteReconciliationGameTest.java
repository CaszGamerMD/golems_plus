package com.caszgamermd.golemsplus.builder;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/** No existing correct schematic blocks may be removed when a job begins. */
public final class BuilderSiteReconciliationGameTest {
    @GameTest(maxTicks=60,skyAccess=true)
    public void preservesAlreadyMatchingBlocksButFlagsObstructions(
            GameTestHelper helper){
        var level=helper.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++){
            helper.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=4;y++)helper.setBlock(x,y,z,Blocks.AIR);
        }
        BlockPos matching=helper.absolutePos(new BlockPos(2,2,3));
        BlockPos incorrect=helper.absolutePos(new BlockPos(3,2,3));
        BlockPos alreadyAir=helper.absolutePos(new BlockPos(4,2,3));
        BlockPos obstructedAir=helper.absolutePos(new BlockPos(5,2,3));
        level.setBlock(matching,Blocks.STONE.defaultBlockState(),3);
        level.setBlock(incorrect,Blocks.DIRT.defaultBlockState(),3);
        level.setBlock(obstructedAir,Blocks.COBBLESTONE.defaultBlockState(),3);
        var none=BuildSite.Rotation.NONE;
        var mirror=BuildSite.Mirror.NONE;
        boolean correct=BuilderSiteReconciliation.noClearingRequired(level,
                new BuilderSiteClearance.Target(matching,"minecraft:stone"),
                none,mirror,false);
        boolean wrong=BuilderSiteReconciliation.noClearingRequired(level,
                new BuilderSiteClearance.Target(incorrect,"minecraft:stone"),
                none,mirror,false);
        boolean empty=BuilderSiteReconciliation.noClearingRequired(level,
                new BuilderSiteClearance.Target(alreadyAir,"minecraft:stone"),
                none,mirror,false);
        boolean shouldClearAir=BuilderSiteReconciliation.noClearingRequired(level,
                new BuilderSiteClearance.Target(obstructedAir,"minecraft:air"),
                none,mirror,false);
        if(!correct||wrong||!empty||shouldClearAir){
            helper.fail("Wrong preflight match results: existing="+correct
                    +" dirt="+wrong+" air="+empty+" obstruction="+shouldClearAir);
            return;
        }
        if(!level.getBlockState(matching).is(Blocks.STONE)
                ||!level.getBlockState(incorrect).is(Blocks.DIRT)
                ||!level.getBlockState(obstructedAir).is(Blocks.COBBLESTONE)){
            helper.fail("Preflight unexpectedly edited world blocks");
            return;
        }
        helper.succeed();
    }
}
