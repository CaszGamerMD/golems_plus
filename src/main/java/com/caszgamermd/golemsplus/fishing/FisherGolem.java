package com.caszgamermd.golemsplus.fishing;

import com.caszgamermd.golemsplus.worker.WorkerIdleRoam;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Craftable physical Fisher with genuine tool and fish inventories.
 * The visible fish comes from an actual item handed to or collected by
 * the worker; no synthetic catches or fishing loot are ever generated.
 * A future bobber/loot integration can feed the same returning inventory.
 */
public final class FisherGolem extends PathfinderMob {
    private static final EntityDataAccessor<Boolean> HAS_ROD =
            SynchedEntityData.defineId(FisherGolem.class,EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> HAS_CATCH =
            SynchedEntityData.defineId(FisherGolem.class,EntityDataSerializers.BOOLEAN);
    private BlockPos home=BlockPos.ZERO;
    private UUID owner;
    private ItemStack rod=ItemStack.EMPTY,catchItem=ItemStack.EMPTY;

    public FisherGolem(EntityType<? extends PathfinderMob> type,Level world){
        super(type,world);
        setPersistenceRequired();
        setCustomName(Component.literal("Fisher Golem"));
    }
    public static AttributeSupplier.Builder attributes(){
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH,16)
                .add(Attributes.MOVEMENT_SPEED,.27)
                .add(Attributes.FOLLOW_RANGE,30);
    }
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder){
        super.defineSynchedData(builder);
        builder.define(HAS_ROD,false);
        builder.define(HAS_CATCH,false);
    }
    @Override protected void registerGoals(){}
    public void assign(BlockPos pos,UUID player){
        home=pos.immutable();owner=player;
    }
    public BlockPos home(){return home;}
    public boolean hasRod(){return getEntityData().get(HAS_ROD);}
    public boolean hasCatch(){return getEntityData().get(HAS_CATCH);}
    private void syncCarried(){
        getEntityData().set(HAS_ROD,!rod.isEmpty());
        getEntityData().set(HAS_CATCH,!catchItem.isEmpty());
    }
    private static boolean fish(ItemStack stack){
        return stack.is(Items.COD)||stack.is(Items.SALMON)
                ||stack.is(Items.TROPICAL_FISH)||stack.is(Items.PUFFERFISH);
    }
    @Override protected InteractionResult mobInteract(Player player,InteractionHand hand){
        if(owner==null||!owner.equals(player.getUUID()))
            return super.mobInteract(player,hand);
        ItemStack held=player.getItemInHand(hand);
        if(!(level() instanceof ServerLevel))return InteractionResult.SUCCESS;
        if(held.is(Items.FISHING_ROD)&&rod.isEmpty()){
            rod=held.copyWithCount(1);
            if(!player.getAbilities().instabuild)held.shrink(1);
            syncCarried();
            return InteractionResult.SUCCESS;
        }
        // A real fish can be handed to the golem to test the return pose,
        // or it can collect one already dropped near its route.
        if(fish(held)&&catchItem.isEmpty()){
            catchItem=held.copyWithCount(1);
            if(!player.getAbilities().instabuild)held.shrink(1);
            syncCarried();
            return InteractionResult.SUCCESS;
        }
        if(held.isEmpty()&&player.isShiftKeyDown()&&!rod.isEmpty()){
            Containers.dropItemStack(level(),getX(),getY(),getZ(),rod.copy());
            rod=ItemStack.EMPTY;syncCarried();
            return InteractionResult.SUCCESS;
        }
        return super.mobInteract(player,hand);
    }
    @Override public void tick(){
        super.tick();
        if(tickCount%12!=0||!(level() instanceof ServerLevel server)
                ||owner==null||!server.hasChunkAt(home))return;
        if(!(server.getBlockEntity(home) instanceof WorkerStationBlockEntity station)
                ||!station.isFishing()||!owner.equals(station.owner())
                ||!station.fisherWorkerIds().contains(getUUID())){
            dropItems(server);discard();return;
        }
        if(station.phase()==com.caszgamermd.golemsplus.worker.WorkerPhase.PAUSED){
            getNavigation().stop();return;
        }
        if(rod.isEmpty()){
            for(int i=0;i<WorkerStationBlockEntity.INPUT_SLOTS;i++){
                if(station.getItem(i).is(Items.FISHING_ROD)){
                    rod=station.removeItem(i,1);
                    syncCarried();break;
                }
            }
        }
        if(!catchItem.isEmpty()){
            double x=home.getX()+.5,y=home.getY()+1,z=home.getZ()+.5;
            if(distanceToSqr(x,y,z)>7){
                getNavigation().moveTo(x,y,z,.8);
                return;
            }
            if(station.canReceive(catchItem)&&station.deposit(catchItem)){
                catchItem=ItemStack.EMPTY;
                syncCarried();
            }else station.setProblem("Fisher output full; still carrying catch");
            return;
        }
        // Only retrieve genuine dropped fish. Hook loot must be supplied by a
        // fishing event or a player; wandering itself never conjures items.
        if(!rod.isEmpty())for(ItemEntity item:server.getEntitiesOfClass(
                ItemEntity.class,getBoundingBox().inflate(1.3),e->fish(e.getItem()))){
            catchItem=item.getItem().copy();
            item.discard();
            syncCarried();
            break;
        }
        if(catchItem.isEmpty())WorkerIdleRoam.tick(server,this,home);
    }
    private void dropItems(ServerLevel server){
        if(!rod.isEmpty())Containers.dropItemStack(server,getX(),getY(),getZ(),rod.copy());
        if(!catchItem.isEmpty())Containers.dropItemStack(server,getX(),getY(),getZ(),catchItem.copy());
        rod=ItemStack.EMPTY;catchItem=ItemStack.EMPTY;syncCarried();
    }
    @Override public void die(DamageSource damage){
        if(level() instanceof ServerLevel server)dropItems(server);
        super.die(damage);
    }
    @Override protected void addAdditionalSaveData(ValueOutput out){
        super.addAdditionalSaveData(out);
        out.store("fisher_home",BlockPos.CODEC,home);
        if(owner!=null)out.putString("fisher_owner",owner.toString());
        if(!rod.isEmpty())out.store("fisher_rod",ItemStack.CODEC,rod);
        if(!catchItem.isEmpty())out.store("fisher_catch",ItemStack.CODEC,catchItem);
    }
    @Override protected void readAdditionalSaveData(ValueInput in){
        super.readAdditionalSaveData(in);
        home=in.read("fisher_home",BlockPos.CODEC).orElse(BlockPos.ZERO);
        try{owner=UUID.fromString(in.getStringOr("fisher_owner",""));}
        catch(IllegalArgumentException ex){owner=null;}
        rod=in.read("fisher_rod",ItemStack.CODEC).orElse(ItemStack.EMPTY);
        catchItem=in.read("fisher_catch",ItemStack.CODEC).orElse(ItemStack.EMPTY);
        syncCarried();
    }
}
