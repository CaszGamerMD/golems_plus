package com.caszgamermd.golemsplus.cooking;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import com.caszgamermd.golemsplus.worker.WorkerPhase;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** Upper half of a true 2-block kiosk. All state belongs to the lower block. */
public final class CookingKioskTopBlock extends Block{
    private final MapCodec<CookingKioskTopBlock> codec=MapCodec.unit(this);
    public CookingKioskTopBlock(BlockBehaviour.Properties properties){super(properties);}
    @Override protected MapCodec<? extends Block> codec(){return codec;}
    @Override protected InteractionResult useItemOn(ItemStack stack,BlockState state,
            Level level,BlockPos pos,Player player,InteractionHand hand,BlockHitResult hit){
        if(stack.getItem() instanceof com.caszgamermd.golemsplus.worker.WorkerGolemItem)
            return InteractionResult.PASS;
        return InteractionResult.TRY_WITH_EMPTY_HAND;
    }
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,
            BlockPos pos,Player player,BlockHitResult hit){
        BlockPos base=pos.below();
        if(!level.getBlockState(base).is(WorksiteContent.COOKING_STATION))
            return InteractionResult.PASS;
        if(level.isClientSide())return InteractionResult.SUCCESS;
        if(!(level.getBlockEntity(base) instanceof WorkerStationBlockEntity station))
            return InteractionResult.PASS;
        if(!station.claimIfUnowned(player.getUUID())
                &&!station.canConfigure(player.getUUID())){
            player.sendSystemMessage(Component.literal("Kiosk belongs to another player."));
            return InteractionResult.SUCCESS;
        }
        if(player.isShiftKeyDown()){
            station.setPhase(station.phase()==WorkerPhase.PAUSED
                    ?WorkerPhase.IDLE:WorkerPhase.PAUSED);
        }else if(player instanceof ServerPlayer server)server.openMenu(station);
        return InteractionResult.SUCCESS;
    }
    @Override public BlockState playerWillDestroy(Level level,BlockPos pos,
            BlockState state,Player player){
        BlockPos bottom=pos.below();
        if(!level.isClientSide()&&level.getBlockState(bottom).is(WorksiteContent.COOKING_STATION)){
            // The lower half drops the actual kiosk item; this block has no item.
            level.destroyBlock(bottom,true,player);
        }
        return super.playerWillDestroy(level,pos,state,player);
    }
}
