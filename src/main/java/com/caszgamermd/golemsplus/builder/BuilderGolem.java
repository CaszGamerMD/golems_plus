package com.caszgamermd.golemsplus.builder;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Physical construction courier. Every placement is checked server-side. */
public final class BuilderGolem extends PathfinderMob {
    private BlockPos home=BlockPos.ZERO;
    private UUID owner;
    private ItemStack carriedSupplyBox=ItemStack.EMPTY;
    public BuilderGolem(EntityType<? extends PathfinderMob> type,Level world){
        super(type,world);
        setPersistenceRequired();
        setInvulnerable(true);
        setCustomName(Component.literal("Builder Golem"));
    }
    public static AttributeSupplier.Builder attributes(){
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH,20)
                .add(Attributes.MOVEMENT_SPEED,.30)
                .add(Attributes.FOLLOW_RANGE,48);
    }
    @Override protected void registerGoals(){}
    public void assign(BlockPos table,UUID player){
        home=table.immutable();owner=player;
    }
    public BlockPos home(){return home;}
    public UUID owner(){return owner;}
    public boolean hasSupplyBox(){return !carriedSupplyBox.isEmpty();}
    public ItemStack supplyBox(){return carriedSupplyBox.copy();}
    public void holdSupplyBox(ItemStack box){
        if(hasSupplyBox()||box.isEmpty())
            throw new IllegalStateException("Builder already carrying supplies");
        carriedSupplyBox=box.copyWithCount(1);
    }
    public void clearSupplyBox(){carriedSupplyBox=ItemStack.EMPTY;}
    @Override public void tick(){
        super.tick();
        if(tickCount%12==0 && level() instanceof ServerLevel server)
            BuilderBuildService.tick(server,this);
    }
    @Override protected void addAdditionalSaveData(ValueOutput out){
        super.addAdditionalSaveData(out);
        out.store("builder_home",BlockPos.CODEC,home);
        if(owner!=null)out.putString("builder_owner",owner.toString());
        if(!carriedSupplyBox.isEmpty())
            out.store("builder_carried_shulker",ItemStack.CODEC,carriedSupplyBox);
    }
    @Override protected void readAdditionalSaveData(ValueInput in){
        super.readAdditionalSaveData(in);
        home=in.read("builder_home",BlockPos.CODEC).orElse(BlockPos.ZERO);
        try{owner=UUID.fromString(in.getStringOr("builder_owner",""));}
        catch(IllegalArgumentException ex){owner=null;}
        carriedSupplyBox=in.read("builder_carried_shulker",ItemStack.CODEC)
                .orElse(ItemStack.EMPTY);
    }
}
