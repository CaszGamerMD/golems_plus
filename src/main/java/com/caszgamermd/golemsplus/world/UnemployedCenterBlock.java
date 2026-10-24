package com.caszgamermd.golemsplus.world;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
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

/** Blue recycling bin: physical 27-slot store of reusable unemployed Golems. */
public final class UnemployedCenterBlock extends Block implements EntityBlock {
    private final MapCodec<UnemployedCenterBlock> codec=MapCodec.unit(this);
    public UnemployedCenterBlock(BlockBehaviour.Properties properties){super(properties);}
    @Override protected MapCodec<? extends Block> codec(){return codec;}
    @Override public @Nullable BlockEntity newBlockEntity(BlockPos pos,BlockState state){
        return new UnemployedCenterBlockEntity(pos,state);
    }
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,
            BlockPos pos,Player player,BlockHitResult hit){
        if(level.isClientSide())return InteractionResult.SUCCESS;
        if(player instanceof ServerPlayer server
                &&level.getBlockEntity(pos) instanceof UnemployedCenterBlockEntity bin)
            server.openMenu(bin);
        return InteractionResult.SUCCESS;
    }
    @Override public BlockState playerWillDestroy(Level level,BlockPos pos,
            BlockState state,Player player){
        if(!level.isClientSide()
                &&level.getBlockEntity(pos) instanceof UnemployedCenterBlockEntity bin)
            Containers.dropContents(level,pos,bin);
        return super.playerWillDestroy(level,pos,state,player);
    }
}
