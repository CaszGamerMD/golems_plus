package com.caszgamermd.golemsplus.builder;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ScaffoldingBlock;

/** Assert Minecraft's real six-block horizontal cantilever rule.
 * BOTTOM=true is allowed: only distance SEVEN is unsupported.
 */
public final class BuilderScaffoldSpanGameTest {
    @GameTest(maxTicks=100,skyAccess=true)
    public void horizontalScaffoldAllowsSixButSeventhFalls(GameTestHelper helper) {
        helper.setBlock(0,1,3,Blocks.STONE);
        helper.setBlock(0,2,3,Blocks.SCAFFOLDING.defaultBlockState()
                .setValue(ScaffoldingBlock.DISTANCE,0)
                .setValue(ScaffoldingBlock.BOTTOM,false));
        // Model real placements with the same native distance and bottom
        // properties as a player/Builder placing from a supported tower.
        for(int n=1;n<=7;n++)
            helper.setBlock(n,2,3,Blocks.SCAFFOLDING.defaultBlockState()
                    .setValue(ScaffoldingBlock.DISTANCE,n)
                    .setValue(ScaffoldingBlock.BOTTOM,true));
        helper.onEachTick(()->{
            if(helper.getTick()<25)return;
            for(int n=1;n<=BuilderScaffoldPlanA.MAX_HORIZONTAL_SCAFFOLD_SPAN;n++){
                BlockPos pos=helper.absolutePos(new BlockPos(n,2,3));
                var block=helper.getLevel().getBlockState(pos);
                if(!block.is(Blocks.SCAFFOLDING)
                        ||block.getValue(ScaffoldingBlock.DISTANCE)!=n) {
                    helper.fail("Anchored scaffold span "+n
                            +" should survive; found "+block);
                    return;
                }
            }
            BlockPos seventh=helper.absolutePos(new BlockPos(7,2,3));
            if(helper.getLevel().getBlockState(seventh).is(Blocks.SCAFFOLDING)) {
                helper.fail("Seventh unsupported scaffold did not fall");
                return;
            }
            helper.succeed();
        });
    }
}
