package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Physical regression tests for vanilla-style enhanced scaffolding controls. */
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

    @GameTest(maxTicks=80,skyAccess=true)
    public void topClickExtendsHorizontalBridgeBeyondSevenBlocks(
            GameTestHelper helper){
        helper.setBlock(2,1,2,Blocks.STONE);
        for(int x=2;x<=10;x++)
            helper.setBlock(x,2,2,WorksiteContent.ENHANCED_SCAFFOLDING);
        ServerPlayer player=helper.makeMockServerPlayerInLevel();
        player.setYRot(-90f); // East.
        player.setItemInHand(InteractionHand.MAIN_HAND,
                new ItemStack(WorksiteContent.ENHANCED_SCAFFOLDING_ITEM,16));
        BlockPos clicked=helper.absolutePos(new BlockPos(2,2,2));
        BlockHitResult hit=new BlockHitResult(
                Vec3.atCenterOf(clicked).add(0,.5,0),
                Direction.UP,clicked,false);
        WorksiteContent.ENHANCED_SCAFFOLDING_ITEM.useOn(
                new UseOnContext(player,InteractionHand.MAIN_HAND,hit));
        BlockPos expected=helper.absolutePos(new BlockPos(11,2,2));
        if(!helper.getLevel().getBlockState(expected).is(
                WorksiteContent.ENHANCED_SCAFFOLDING)){
            helper.fail("Top click did not extend east to the first open cell"
                    +" past the vanilla 7-block limit");
            return;
        }
        helper.succeed();
    }

    @GameTest(maxTicks=80,skyAccess=true)
    public void sideClickExtendsExistingTowerUp(GameTestHelper helper){
        helper.setBlock(2,1,2,Blocks.STONE);
        for(int y=2;y<=4;y++)
            helper.setBlock(2,y,2,WorksiteContent.ENHANCED_SCAFFOLDING);
        ServerPlayer player=helper.makeMockServerPlayerInLevel();
        player.setYRot(-90f);
        player.setItemInHand(InteractionHand.MAIN_HAND,
                new ItemStack(WorksiteContent.ENHANCED_SCAFFOLDING_ITEM,16));
        BlockPos clicked=helper.absolutePos(new BlockPos(2,2,2));
        BlockHitResult hit=new BlockHitResult(
                Vec3.atCenterOf(clicked).add(.5,0,0),
                Direction.EAST,clicked,false);
        WorksiteContent.ENHANCED_SCAFFOLDING_ITEM.useOn(
                new UseOnContext(player,InteractionHand.MAIN_HAND,hit));
        BlockPos expected=helper.absolutePos(new BlockPos(2,5,2));
        if(!helper.getLevel().getBlockState(expected).is(
                WorksiteContent.ENHANCED_SCAFFOLDING)){
            helper.fail("Side click did not extend the tower vertically");
            return;
        }
        helper.succeed();
    }
}
