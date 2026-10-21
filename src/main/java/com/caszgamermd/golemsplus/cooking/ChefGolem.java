package com.caszgamermd.golemsplus.cooking;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Visual cooking-station worker, already dressed for the future kitchen
 * system. Recipe planning and food processing are NOT enabled yet.
 */
public final class ChefGolem extends PathfinderMob {
    private BlockPos home=BlockPos.ZERO;
    private UUID owner;

    public ChefGolem(EntityType<? extends PathfinderMob> type,Level world){
        super(type,world);
        setPersistenceRequired();
        setCustomName(Component.literal("Chef Golem"));
    }
    public static AttributeSupplier.Builder attributes(){
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH,16)
                .add(Attributes.MOVEMENT_SPEED,.28)
                .add(Attributes.FOLLOW_RANGE,24);
    }
    @Override protected void registerGoals(){
        // Passive at the cooking station until food jobs are implemented.
    }
    public void assign(BlockPos position,UUID player){
        home=position.immutable();owner=player;
    }
    public BlockPos home(){return home;}
    public UUID owner(){return owner;}

    @Override public void tick(){
        super.tick();
        if(tickCount%40!=0||!(level() instanceof ServerLevel server)
                ||owner==null||!server.hasChunkAt(home))return;
        if(!(server.getBlockEntity(home) instanceof
                com.caszgamermd.golemsplus.world.WorkerStationBlockEntity station)
                ||!station.isCooking()||!owner.equals(station.owner())){
            discard();return;
        }
        if(distanceToSqr(home.getX()+.5,home.getY()+1,home.getZ()+.5)>9)
            getNavigation().moveTo(home.getX()+.5,home.getY()+1,
                    home.getZ()+.5,.75);
        else getNavigation().stop();
    }
    @Override protected void addAdditionalSaveData(ValueOutput out){
        super.addAdditionalSaveData(out);
        out.store("chef_home",BlockPos.CODEC,home);
        if(owner!=null)out.putString("chef_owner",owner.toString());
    }
    @Override protected void readAdditionalSaveData(ValueInput in){
        super.readAdditionalSaveData(in);
        home=in.read("chef_home",BlockPos.CODEC).orElse(BlockPos.ZERO);
        try{owner=UUID.fromString(in.getStringOr("chef_owner",""));}
        catch(IllegalArgumentException ex){owner=null;}
    }
}
