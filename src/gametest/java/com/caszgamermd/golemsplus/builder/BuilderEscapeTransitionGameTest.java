package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/** Isolated fixture registered in the default physical test suite. */
public final class BuilderEscapeTransitionGameTest {
    @GameTest(maxTicks=90,skyAccess=true)
    public void escapeMustNotDependOnDiagonalOneBlockJump(GameTestHelper h){
        // Two isolated pads: the only apparent exit is an unsupported
        // diagonal jump that the production ground planner cannot execute.
        h.setBlock(3,1,3,Blocks.STONE);
        h.setBlock(4,2,4,Blocks.STONE);
        var stance=h.absolutePos(new BlockPos(3,2,3));
        var target=h.absolutePos(new BlockPos(1,2,3));
        var mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,h.getLevel());
        mob.setPos(stance.getX()+.5,stance.getY(),stance.getZ()+.5);
        h.getLevel().addFreshEntity(mob);
        h.onEachTick(()->{
            if(h.getTick()!=16)return;
            if(BuilderConstructionPlanner.exitAfterPlacement(
                    h.getLevel(),mob,stance,target)){
                h.fail("Builder accepted a diagonal full-block jump as its exit");
                return;
            }
            h.setBlock(4,2,3,Blocks.STONE);
            if(!BuilderConstructionPlanner.exitAfterPlacement(
                    h.getLevel(),mob,stance,target)){
                h.fail("A physical cardinal step-up must remain available");
                return;
            }
            h.succeed();
        });
    }
}
