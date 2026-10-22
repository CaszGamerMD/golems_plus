package com.caszgamermd.golemsplus.cooking;

import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/** A real, saved prepared meal. Plate is visual only; food is not duplicated. */
public final class MealCounterBlockEntity extends BlockEntity{
    private ItemStack food=ItemStack.EMPTY;
    public MealCounterBlockEntity(BlockPos pos,BlockState state){
        super(WorksiteContent.MEAL_COUNTER_ENTITY,pos,state);
    }
    public ItemStack food(){return food.copy();}
    public boolean serve(ItemStack stack){
        if(stack.isEmpty()||!food.isEmpty())return false;
        food=stack.copy();sync();return true;
    }
    public ItemStack take(){
        ItemStack result=food;
        food=ItemStack.EMPTY;sync();return result;
    }
    private void sync(){
        setChanged();
        if(level!=null&&!level.isClientSide())
            level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),3);
    }
    @Override protected void saveAdditional(ValueOutput out){
        super.saveAdditional(out);
        if(!food.isEmpty())out.store("meal",ItemStack.CODEC,food);
    }
    @Override protected void loadAdditional(ValueInput in){
        super.loadAdditional(in);
        food=in.read("meal",ItemStack.CODEC).orElse(ItemStack.EMPTY);
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider provider){
        return saveWithoutMetadata(provider);
    }
    @Override public @Nullable ClientboundBlockEntityDataPacket getUpdatePacket(){
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
