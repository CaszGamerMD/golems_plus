package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/** Focused in-server physics regressions for failed Builder dismounts. */
public final class BuilderScaffoldExitRecoveryGameTest {
    @GameTest(maxTicks=80,skyAccess=true)
    public void rejectsAirborneDismountAcrossLowerRoof(GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        for(int x=0;x<7;x++)for(int z=0;z<7;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        helper.setBlock(3,2,3,Blocks.SCAFFOLDING);
        BlockPos base=helper.absolutePos(new BlockPos(3,2,3));
        BlockPos lowerExit=helper.absolutePos(new BlockPos(4,2,3));
        BuilderGolem builder=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        // Feet are still more than 1.35m above the chosen Y=2 exit.
        // The old onGround-only guard accepted an impossible midair EXIT.
        builder.setPos(base.getX()+.5,base.getY()+1.55,base.getZ()+.5);
        level.addFreshEntity(builder);
        if(!WorkerNavigation.canStandOn(level,builder,lowerExit)){
            helper.fail("Test setup lacks a real supported lower exit");return;
        }
        if(BuilderScaffolding.dismountAtBase(level,builder,base)){
            helper.fail("Midair Builder accepted an unreachable lower EXIT");
            return;
        }
        helper.succeed();
    }

    @GameTest(maxTicks=80,skyAccess=true)
    public void invalidatedExitIsNotImmediatelyReissued(GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        for(int x=0;x<7;x++)for(int z=0;z<7;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        BlockPos from=helper.absolutePos(new BlockPos(3,2,3));
        BlockPos destination=helper.absolutePos(new BlockPos(4,2,3));
        BuilderGolem builder=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        builder.setPos(from.getX()+.5,from.getY(),from.getZ()+.5);
        level.addFreshEntity(builder);
        if(!BuilderScaffolding.stepToPlatform(level,builder,destination)){
            helper.fail("Could not start supported physical dismount");return;
        }
        level.setBlock(destination,Blocks.STONE.defaultBlockState(),3);
        BuilderScaffolding.tickMotion(level,builder);
        level.setBlock(destination,Blocks.AIR.defaultBlockState(),3);
        if(BuilderScaffolding.stepToPlatform(level,builder,destination)){
            helper.fail("Blocked EXIT recreated at the same landing immediately");
            return;
        }
        helper.succeed();
    }
}
