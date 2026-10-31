package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/** Real-world regression: enhanced scaffolding survives a long unsupported bridge. */
public final class EnhancedScaffoldingGameTest {
    @GameTest(maxTicks=120,skyAccess=true)
    public void enhancedScaffoldingBridgeSurvivesWithoutFooting(GameTestHelper helper){
        helper.setBlock(1,1,2,Blocks.STONE);
        for(int n=1;n<=10;n++)
            helper.setBlock(n,2,2,WorksiteContent.ENHANCED_SCAFFOLDING);
        helper.onEachTick(()->{
            if(helper.getTick()<60)return;
            for(int n=1;n<=10;n++){
                BlockPos p=helper.absolutePos(new BlockPos(n,2,2));
                if(!helper.getLevel().getBlockState(p).is(
                        WorksiteContent.ENHANCED_SCAFFOLDING)){
                    helper.fail("Enhanced scaffold bridge collapsed at segment "+n);
                    return;
                }
            }
            helper.succeed();
        });
    }
}
