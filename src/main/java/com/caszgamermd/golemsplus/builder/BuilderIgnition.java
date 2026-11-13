package com.caszgamermd.golemsplus.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** The golem uses its own real tool, not the remote owner's hand. */
public final class BuilderIgnition {
    private BuilderIgnition(){}
    public static boolean igniteSoulFire(ServerLevel level,BuilderGolem worker,ServerPlayer owner,BlockPos pos){
        BlockPos support=pos.below();
        if(!level.hasChunkAt(pos)||!level.hasChunkAt(support)
                ||!level.mayInteract(owner,pos)||!level.mayInteract(owner,support)
                ||!level.getBlockState(pos).isAir()
                ||!level.getBlockState(support).is(Blocks.SOUL_SAND)
                    &&!level.getBlockState(support).is(Blocks.SOUL_SOIL)
                ||!BuilderFlight.safeWorkPose(level,worker,pos,worker.position())
                    &&!BuilderConstructionPlanner.safeStand(level,worker,pos,worker.blockPosition())
                ||worker.position().distanceToSqr(Vec3.atCenterOf(pos))>25)return false;
        for(int slot=0;slot<worker.getContainerSize();slot++){
            ItemStack actual=worker.getItem(slot);
            if(!actual.is(Items.FLINT_AND_STEEL))continue;
            ItemStack tool=actual.copy();
            var hit=new BlockHitResult(new Vec3(support.getX()+.5,support.getY()+1,support.getZ()+.5),
                    Direction.UP,support,false);
            // Null player means no remote player's equipment can be changed.
            // The native item chooses the fire type from the actual support.
            tool.useOn(new UseOnContext(level,null,InteractionHand.MAIN_HAND,tool,hit){});
            if(!level.getBlockState(pos).is(Blocks.SOUL_FIRE))return false;
            // Native flint-and-steel charges a player-held stack when there
            // is a player; the Builder's stack must pay that same wear.
            if(!tool.isEmpty()&&tool.getDamageValue()==actual.getDamageValue()){
                tool.setDamageValue(tool.getDamageValue()+1);
                if(tool.getDamageValue()>=tool.getMaxDamage())tool=ItemStack.EMPTY;
            }
            worker.setItem(slot,tool);worker.setChanged();
            return true;
        }
        return false;
    }
}
