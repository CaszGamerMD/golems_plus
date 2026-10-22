package com.caszgamermd.golemsplus.cooking;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.Containers;
import net.minecraft.world.phys.BlockHitResult;
import org.jspecify.annotations.Nullable;

/** Fast-food pickup shelf. Right-click to receive exactly the served stack. */
public final class MealCounterBlock extends Block implements EntityBlock{
    private final MapCodec<MealCounterBlock> codec=MapCodec.unit(this);
    public MealCounterBlock(BlockBehaviour.Properties properties){super(properties);}
    @Override protected MapCodec<? extends Block> codec(){return codec;}
    @Override public @Nullable BlockEntity newBlockEntity(BlockPos pos,BlockState state){
        return new MealCounterBlockEntity(pos,state);
    }
    @Override protected InteractionResult useItemOn(ItemStack held,BlockState state,Level level,
            BlockPos pos,Player player,InteractionHand hand,BlockHitResult hit){
        // Pickups are available even if the customer is holding an item.
        return InteractionResult.TRY_WITH_EMPTY_HAND;
    }
    @Override protected InteractionResult useWithoutItem(BlockState state,Level level,BlockPos pos,
                                                           Player player,BlockHitResult hit){
        if(!(level.getBlockEntity(pos) instanceof MealCounterBlockEntity counter))
            return InteractionResult.PASS;
        if(level.isClientSide())return InteractionResult.SUCCESS;
        if(counter.food().isEmpty()){
            player.sendSystemMessage(Component.literal("No order is ready for pickup"));
            return InteractionResult.SUCCESS;
        }
        ItemStack meal=counter.take();
        if(!player.getInventory().add(meal))
            Containers.dropItemStack(level,player.getX(),player.getY(),player.getZ(),meal);
        return InteractionResult.SUCCESS;
    }
    @Override public BlockState playerWillDestroy(Level level,BlockPos pos,BlockState state,Player player){
        if(!level.isClientSide()&&level.getBlockEntity(pos) instanceof MealCounterBlockEntity counter){
            ItemStack remaining=counter.take();
            if(!remaining.isEmpty())
                Containers.dropItemStack(level,pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5,remaining);
        }
        return super.playerWillDestroy(level,pos,state,player);
    }
}
