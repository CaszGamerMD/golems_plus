package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.PlayerGrappleController;
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

/** Actual server player grapple use, release, motion, and anchor safety. */
public final class PlayerGrappleGameTest {
    private static ServerPlayer equipped(GameTestHelper helper){
        ServerPlayer player=helper.makeMockServerPlayerInLevel();
        BlockPos start=helper.absolutePos(new BlockPos(2,2,5));
        player.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        player.setItemInHand(InteractionHand.MAIN_HAND,
                new ItemStack(WorksiteContent.GRAPPLE_HOOK));
        return player;
    }
    private static BlockHitResult hit(GameTestHelper helper){
        BlockPos anchor=helper.absolutePos(new BlockPos(2,4,2));
        return new BlockHitResult(Vec3.atCenterOf(anchor).add(0,0,.5),
                Direction.SOUTH,anchor,false);
    }

    @GameTest(maxTicks=80,skyAccess=true)
    public void playerHookAttachesAndMovesWithoutTeleport(GameTestHelper helper){
        helper.setBlock(2,1,5,Blocks.STONE);
        helper.setBlock(2,4,2,Blocks.STONE);
        ServerPlayer player=equipped(helper);
        Vec3 before=player.position();
        WorksiteContent.GRAPPLE_HOOK.useOn(
                new UseOnContext(player,InteractionHand.MAIN_HAND,hit(helper)));
        if(!PlayerGrappleController.active(player)){
            helper.fail("Player could not attach a hook to the real stone ledge");
            return;
        }
        if(player.position().distanceToSqr(before)>.0001){
            helper.fail("Grapple teleported player instead of applying motion");
            return;
        }
        helper.onEachTick(()->{
            if(helper.getTick()<6)return;
            if(player.position().distanceToSqr(before)<.01
                    &&player.getDeltaMovement().lengthSqr()<.002){
                helper.fail("Attached player received no physical reel motion");
                return;
            }
            helper.succeed();
        });
    }

    @GameTest(maxTicks=40,skyAccess=true)
    public void secondUseRetractsPlayerHook(GameTestHelper helper){
        helper.setBlock(2,1,5,Blocks.STONE);
        helper.setBlock(2,4,2,Blocks.STONE);
        ServerPlayer player=equipped(helper);
        var ctx=new UseOnContext(player,InteractionHand.MAIN_HAND,hit(helper));
        WorksiteContent.GRAPPLE_HOOK.useOn(ctx);
        if(!PlayerGrappleController.active(player)){
            helper.fail("Could not attach before retract");return;
        }
        WorksiteContent.GRAPPLE_HOOK.useOn(ctx);
        if(PlayerGrappleController.active(player)){
            helper.fail("Second click did not retract player hook");return;
        }
        helper.succeed();
    }

    @GameTest(maxTicks=40,skyAccess=true)
    public void removingAnchorReleasesPlayer(GameTestHelper helper){
        helper.setBlock(2,1,5,Blocks.STONE);
        helper.setBlock(2,4,2,Blocks.STONE);
        ServerPlayer player=equipped(helper);
        WorksiteContent.GRAPPLE_HOOK.useOn(
                new UseOnContext(player,InteractionHand.MAIN_HAND,hit(helper)));
        if(!PlayerGrappleController.active(player)){
            helper.fail("Could not attach before anchor removal");return;
        }
        helper.setBlock(2,4,2,Blocks.AIR);
        helper.onEachTick(()->{
            if(helper.getTick()<4)return;
            if(PlayerGrappleController.active(player)){
                helper.fail("Hook remained attached to an air anchor");return;
            }
            helper.succeed();
        });
    }

    @GameTest(maxTicks=40,skyAccess=true)
    public void hookRejectsMissingAnchor(GameTestHelper helper){
        helper.setBlock(2,1,5,Blocks.STONE);
        ServerPlayer player=equipped(helper);
        WorksiteContent.GRAPPLE_HOOK.useOn(
                new UseOnContext(player,InteractionHand.MAIN_HAND,hit(helper)));
        if(PlayerGrappleController.active(player)){
            helper.fail("Hook attached to empty air");return;
        }
        helper.succeed();
    }
}
