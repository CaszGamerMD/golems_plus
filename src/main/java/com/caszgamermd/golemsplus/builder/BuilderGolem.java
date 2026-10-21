package com.caszgamermd.golemsplus.builder;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Physical construction courier. Every placement is checked server-side. */
public final class BuilderGolem extends IronGolem {
    private BlockPos home=BlockPos.ZERO;
    private UUID owner;
    public BuilderGolem(EntityType<? extends IronGolem> type,Level world){
        super(type,world);
        setPersistenceRequired();
        setInvulnerable(true);
        setCustomName(Component.literal("Builder Golem"));
    }
    @Override protected void registerGoals(){}
    public void assign(BlockPos table,UUID player){
        home=table.immutable();owner=player;
    }
    public BlockPos home(){return home;}
    public UUID owner(){return owner;}
    @Override public void tick(){
        super.tick();
        if(tickCount%12==0 && level() instanceof ServerLevel server)
            BuilderBuildService.tick(server,this);
    }
    @Override protected void addAdditionalSaveData(ValueOutput out){
        super.addAdditionalSaveData(out);
        out.store("builder_home",BlockPos.CODEC,home);
        if(owner!=null)out.putString("builder_owner",owner.toString());
    }
    @Override protected void readAdditionalSaveData(ValueInput in){
        super.readAdditionalSaveData(in);
        home=in.read("builder_home",BlockPos.CODEC).orElse(BlockPos.ZERO);
        try{owner=UUID.fromString(in.getStringOr("builder_owner",""));}
        catch(IllegalArgumentException ex){owner=null;}
    }
}
