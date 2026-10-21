package com.caszgamermd.golemsplus.beekeeper;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/** Actual separate 27-slot tool cabinet. Only shears and glass bottles enter. */
public final class BeeToolboxBlock extends Block implements EntityBlock {
    private final MapCodec<BeeToolboxBlock> codec=MapCodec.unit(this);
    public BeeToolboxBlock(BlockBehaviour.Properties props){super(props);}
    @Override protected MapCodec<? extends Block> codec(){return codec;}
    @Override public @Nullable BlockEntity newBlockEntity(BlockPos pos,BlockState state){
        return new BeeToolboxBlockEntity(pos,state);
    }
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,
            BlockPos pos,Player player,BlockHitResult hit){
        if(level.isClientSide())return InteractionResult.SUCCESS;
        if(level.getBlockEntity(pos) instanceof BeeToolboxBlockEntity box
                &&player instanceof ServerPlayer server)server.openMenu(box);
        return InteractionResult.SUCCESS;
    }
    @Override public BlockState playerWillDestroy(Level level,BlockPos pos,
            BlockState state,Player player){
        if(!level.isClientSide()&&level.getBlockEntity(pos) instanceof BeeToolboxBlockEntity box)
            Containers.dropContents(level,pos,box);
        return super.playerWillDestroy(level,pos,state,player);
    }
}
