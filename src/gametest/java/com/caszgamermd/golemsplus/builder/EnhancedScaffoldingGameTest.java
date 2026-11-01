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
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Physical regression tests for vanilla-style enhanced scaffolding controls. */
public final class EnhancedScaffoldingGameTest {
    @GameTest(maxTicks=40,skyAccess=true)
    public void scaffoldCanBePickedClimbedAndHasBottomFloor(GameTestHelper helper){
        helper.setBlock(2,1,2,Blocks.STONE);
        helper.setBlock(2,2,2,WorksiteContent.ENHANCED_SCAFFOLDING);
        BlockPos pos=helper.absolutePos(new BlockPos(2,2,2));
        var state=helper.getLevel().getBlockState(pos);
        if(!state.is(BlockTags.CLIMBABLE)){
            helper.fail("Enhanced scaffolding must be in minecraft:climbable");return;
        }
        if(state.getBlock().asItem()!=WorksiteContent.ENHANCED_SCAFFOLDING_ITEM){
            helper.fail("Pick-block should return the enhanced scaffolding item");return;
        }
        var shape=state.getCollisionShape(helper.getLevel(),pos,CollisionContext.empty());
        if(shape.isEmpty()||shape.min(Direction.Axis.Y)>.01){
            helper.fail("Bottom scaffold should catch a falling player");return;
        }
        helper.succeed();
    }

    @GameTest(maxTicks=50,skyAccess=true)
    public void crouchingDescendsInsideTowerButBottomStillCatches(GameTestHelper helper){
        helper.setBlock(2,1,2,Blocks.STONE);
        helper.setBlock(2,2,2,WorksiteContent.ENHANCED_SCAFFOLDING);
        helper.setBlock(2,3,2,WorksiteContent.ENHANCED_SCAFFOLDING);
        ServerPlayer player=helper.makeMockServerPlayerInLevel();
        player.setShiftKeyDown(true);
        CollisionContext crouching=CollisionContext.of(player);
        BlockPos top=helper.absolutePos(new BlockPos(2,3,2));
        BlockPos base=helper.absolutePos(new BlockPos(2,2,2));
        if(!helper.getLevel().getBlockState(top)
                .getCollisionShape(helper.getLevel(),top,crouching).isEmpty()){
            helper.fail("Crouch must allow passing through upper scaffold floors");
            return;
        }
        var bottomShape=helper.getLevel().getBlockState(base)
                .getCollisionShape(helper.getLevel(),base,crouching);
        if(bottomShape.isEmpty()||bottomShape.min(Direction.Axis.Y)>.01){
            helper.fail("Crouch must not drop through the bottom of the tower");
            return;
        }
        helper.succeed();
    }

    @GameTest(maxTicks=60,skyAccess=true)
    public void bottomMeshAppearsOnlyWithNoFloorBelow(GameTestHelper helper){
        helper.setBlock(4,2,2,Blocks.STONE); // Side anchor for a hanging platform
        helper.setBlock(5,2,2,WorksiteContent.ENHANCED_SCAFFOLDING);
        BlockPos scaffold=helper.absolutePos(new BlockPos(5,2,2));
        helper.onEachTick(()->{
            long tick=helper.getTick();
            if(tick==10){
                if(!helper.getLevel().getBlockState(scaffold)
                        .getValue(ScaffoldingBlock.BOTTOM)){
                    helper.fail("Unsupported scaffold must show bottom net");return;
                }
                helper.setBlock(5,1,2,Blocks.STONE);
            }else if(tick==20){
                if(helper.getLevel().getBlockState(scaffold)
                        .getValue(ScaffoldingBlock.BOTTOM)){
                    helper.fail("Floor-supported scaffold must hide bottom net");return;
                }
                helper.setBlock(5,1,2,Blocks.AIR);
            }else if(tick==30){
                if(!helper.getLevel().getBlockState(scaffold)
                        .getValue(ScaffoldingBlock.BOTTOM)){
                    helper.fail("Bottom net must return when floor is removed");return;
                }
                helper.succeed();
            }
        });
    }

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
        var placement=WorksiteContent.ENHANCED_SCAFFOLDING_ITEM.useOn(
                new UseOnContext(player,InteractionHand.MAIN_HAND,hit));
        BlockPos expected=helper.absolutePos(new BlockPos(11,2,2));
        if(!helper.getLevel().getBlockState(expected).is(
                WorksiteContent.ENHANCED_SCAFFOLDING)){
            StringBuilder placed=new StringBuilder();
            for(int x=0;x<15;x++)
                if(helper.getLevel().getBlockState(helper.absolutePos(
                        new BlockPos(x,2,2))).is(WorksiteContent.ENHANCED_SCAFFOLDING))
                    placed.append(x).append(",");
            helper.fail("Top click did not extend east to the first open cell"
                    +" past the vanilla 7-block limit; facing="+player.getDirection()
                    +" result="+placement+" placedX="+placed
                    +" held="+player.getItemInHand(InteractionHand.MAIN_HAND).getCount());
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
