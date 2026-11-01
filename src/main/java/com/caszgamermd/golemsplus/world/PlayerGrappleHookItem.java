package com.caszgamermd.golemsplus.world;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Reusable player-operated version of the Builders' 20-block grapple tool. */
public final class PlayerGrappleHookItem extends Item {
    public PlayerGrappleHookItem(Properties properties){super(properties);}

    @Override public InteractionResult useOn(UseOnContext context){
        Player player=context.getPlayer();
        if(player==null)return InteractionResult.PASS;
        if(!(context.getLevel() instanceof ServerLevel level)
                ||!(player instanceof ServerPlayer serverPlayer))
            return InteractionResult.SUCCESS;
        if(PlayerGrappleController.active(serverPlayer)){
            PlayerGrappleController.release(serverPlayer,true);
            return InteractionResult.SUCCESS;
        }
        BlockPos pos=context.getClickedPos();
        BlockHitResult hit=new BlockHitResult(context.getClickLocation(),
                context.getClickedFace(),pos,false);
        return PlayerGrappleController.attach(level,serverPlayer,hit)
                ?InteractionResult.SUCCESS:InteractionResult.FAIL;
    }

    @Override public InteractionResult use(Level world,Player player,
            InteractionHand hand){
        if(!(world instanceof ServerLevel level)
                ||!(player instanceof ServerPlayer serverPlayer))
            return InteractionResult.SUCCESS;
        if(PlayerGrappleController.active(serverPlayer)){
            PlayerGrappleController.release(serverPlayer,true);
            return InteractionResult.SUCCESS;
        }
        Vec3 eye=player.getEyePosition();
        Vec3 end=eye.add(player.getViewVector(1.0f)
                .scale(PlayerGrappleController.MAX_REACH));
        BlockHitResult hit=world.clip(new ClipContext(eye,end,
                ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,player));
        if(hit.getType()!=HitResult.Type.BLOCK){
            PlayerGrappleController.noAnchor(serverPlayer);
            return InteractionResult.FAIL;
        }
        return PlayerGrappleController.attach(level,serverPlayer,hit)
                ?InteractionResult.SUCCESS:InteractionResult.FAIL;
    }
}
