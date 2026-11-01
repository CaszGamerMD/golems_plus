package com.caszgamermd.golemsplus.world;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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

/** Unlimited OnSite supply for creative construction; never a survival warehouse. */
public final class CreativeCrateBlock extends Block implements EntityBlock {
    private final MapCodec<CreativeCrateBlock> codec=MapCodec.unit(this);

    public CreativeCrateBlock(BlockBehaviour.Properties properties){super(properties);}
    @Override protected MapCodec<? extends Block> codec(){return codec;}
    @Override public @Nullable BlockEntity newBlockEntity(BlockPos pos,BlockState state){
        return new CreativeCrateBlockEntity(pos,state);
    }
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,
            BlockPos pos,Player player,BlockHitResult hit){
        if(!level.isClientSide())
            player.displayClientMessage(Component.literal(
                    "Creative Crate: link this block as the Builder's OnSite Storage. "
                    +"It supplies unlimited items at pickup and voids returned items."),true);
        return InteractionResult.SUCCESS;
    }
}
