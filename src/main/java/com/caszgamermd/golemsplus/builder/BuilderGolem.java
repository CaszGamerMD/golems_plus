package com.caszgamermd.golemsplus.builder;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import com.caszgamermd.golemsplus.worker.ProfessionGolemNames;
import com.caszgamermd.golemsplus.worker.ProfessionGolemNamePool;
import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
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
public final class BuilderGolem extends PathfinderMob implements Container {
    private static final net.minecraft.network.syncher.EntityDataAccessor<Boolean> COURIER=
            net.minecraft.network.syncher.SynchedEntityData.defineId(
                    BuilderGolem.class,net.minecraft.network.syncher.EntityDataSerializers.BOOLEAN);
    @Override protected void defineSynchedData(net.minecraft.network.syncher.SynchedEntityData.Builder builder){
        super.defineSynchedData(builder);
        builder.define(COURIER,false);
    }
    public boolean deliveryRole(){return entityData.get(COURIER);}
    public void setDeliveryRole(boolean active){
        if(deliveryRole()!=active)entityData.set(COURIER,active);
    }

    private BlockPos home=BlockPos.ZERO;
    private UUID owner;
    private ItemStack carriedSupplyBox=ItemStack.EMPTY;
    // Nine real stacks: solo builders load once from their staging chest,
    // walk to the site, and pay for placed blocks from this persisted pouch.
    private final NonNullList<ItemStack> materials=
            NonNullList.withSize(9,ItemStack.EMPTY);
    public BuilderGolem(EntityType<? extends PathfinderMob> type,Level world){
        super(type,world);
        setPersistenceRequired();
        setInvulnerable(true);
        // A personal name is picked on the logical server when assigned.
    }
    public static AttributeSupplier.Builder attributes(){
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH,20)
                .add(Attributes.MOVEMENT_SPEED,.40)
                .add(Attributes.FOLLOW_RANGE,128);
    }
    @Override protected void registerGoals(){}
    /** Inspect a stationary Builder without commands or debug logging.
     * This intentionally does not change its current path or assignment.
     */
    @Override protected InteractionResult mobInteract(Player player,
            InteractionHand hand){
        if(com.caszgamermd.golemsplus.crane.CraneService
                .operatorClick(this,player,hand))return InteractionResult.SUCCESS;
        if(player.isShiftKeyDown()){
            if(player instanceof net.minecraft.server.level.ServerPlayer recipient
                    &&level() instanceof ServerLevel world){
                String status=BuilderBuildService.debugStatus(world,this);
                recipient.sendSystemMessage(
                        Component.literal("Builder "+getName().getString()+": "+status));
            }
            return InteractionResult.SUCCESS;
        }
        return super.mobInteract(player,hand);
    }
    public void assign(BlockPos table,UUID player){
        home=table.immutable();owner=player;
        ProfessionGolemNames.ensureNamed(this,ProfessionGolemNamePool.Profession.BUILDER);
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
    /** On loss of the job block, drop exact held items instead of
     * discarding inventory-bearing workers and deleting their cargo.
     */
    public void dropCargo(ServerLevel world){
        for(int i=0;i<materials.size();i++){
            ItemStack stack=materials.get(i);
            if(!stack.isEmpty()){
                net.minecraft.world.Containers.dropItemStack(world,
                        getX(),getY(),getZ(),stack.copy());
                materials.set(i,ItemStack.EMPTY);
            }
        }
        if(!carriedSupplyBox.isEmpty()){
            net.minecraft.world.Containers.dropItemStack(world,
                    getX(),getY(),getZ(),carriedSupplyBox.copy());
            carriedSupplyBox=ItemStack.EMPTY;
        }
    }
    @Override public int getContainerSize(){return materials.size();}
    @Override public boolean isEmpty(){return materials.stream().allMatch(ItemStack::isEmpty);}
    @Override public ItemStack getItem(int i){
        return i>=0&&i<materials.size()?materials.get(i):ItemStack.EMPTY;
    }
    @Override public ItemStack removeItem(int i,int qty){
        if(i<0||i>=materials.size())return ItemStack.EMPTY;
        return ContainerHelper.removeItem(materials,i,qty);
    }
    @Override public ItemStack removeItemNoUpdate(int i){
        if(i<0||i>=materials.size())return ItemStack.EMPTY;
        return ContainerHelper.takeItem(materials,i);
    }
    @Override public void setItem(int i,ItemStack item){
        if(i>=0&&i<materials.size())materials.set(i,item.copy());
    }
    @Override public void setChanged(){}
    @Override public boolean stillValid(Player player){
        return isAlive()&&distanceToSqr(player)<64;
    }
    @Override public void clearContent(){materials.replaceAll(item->ItemStack.EMPTY);}
    public int carriedCount(net.minecraft.world.item.Item item){
        int n=0;
        for(ItemStack stack:materials)if(stack.is(item))n+=stack.getCount();
        return n;
    }
    @Override public void tick(){
        // A Builder being carried by a crane must never try climbing a
        // scaffold or rerouting toward materials from inside the basket.
        if(getVehicle() instanceof com.caszgamermd.golemsplus.crane.BuildingCrane){
            super.tick();
            if(level() instanceof ServerLevel world)
                BuilderChunkTickets.track(world,this);
            return;
        }
        boolean climbing=level() instanceof ServerLevel climbWorld
                &&BuilderScaffolding.tickMotion(climbWorld,this);
        // Physical four-builder right-of-way: a worker waiting in a
        // teammate's future block can move one safe step EVERY entity tick,
        // independently of its slower construction task scheduler.
        boolean returning= !climbing&&level() instanceof ServerLevel floorWorld
                &&BuilderTeamGroundTraffic.tickMotion(floorWorld,this);
        boolean placing=false;
        if(!climbing&&!returning&&level() instanceof ServerLevel teamWorld)
            placing=BuilderPlacementSpace.tickMotion(teamWorld,this);
        // Separate physical movement owner for face-to-face worker traffic.
        // It must NEVER fight an existing scaffold, bridge, transport,
        // ledge descent or placement escape session.
        boolean clockwise=false;
        if(!climbing&&!returning&&!placing
                &&level() instanceof ServerLevel trafficWorld)
            clockwise=BuilderClockwiseTraffic.tickMotion(trafficWorld,this);
        // Never retain the old no-gravity state after switching jobs,
        // unloading, leaving a tower, or reloading an entity from disk.
        if(level() instanceof ServerLevel server
                &&(isNoGravity()||isShiftKeyDown())
                &&!BuilderScaffolding.climbingNow(server,this)
                // The paid scaffold bridge controls temporary creative-style
                // hover only while a real supported walking tile exists.
                &&!BuilderScaffoldBridge.moving(this)){
            setNoGravity(false);
            setShiftKeyDown(false);
            var velocity=getDeltaMovement();
            setDeltaMovement(velocity.x,Math.min(velocity.y,0),velocity.z);
        }
        super.tick();
        // Also covers Golems created with a spawn egg, command or an older save.
        if(tickCount==1){
            ProfessionGolemNames.ensureNamed(this,ProfessionGolemNamePool.Profession.BUILDER);
            // Upgrade old saved workers to the new nominal 4 m/s
            // ground-navigation movement attribute.
            var movement=getAttribute(Attributes.MOVEMENT_SPEED);
            if(movement!=null)movement.setBaseValue(.40);
        }
        if(level() instanceof ServerLevel server){
            BuilderChunkTickets.track(server,this);
            // A physical scaffold climb owns this worker's movement.
            // Normal A* anti-stall checks may not stop or redirect it.
            if(!climbing&&!clockwise)
                com.caszgamermd.golemsplus.worker.WorkerNavigation.tick(server,this);
        }
        // Each Builder evaluates its own long-lived task state four times
        // per second. Offset different entities across server ticks: a
        // large crew must not all scan inventories/pathfind/serialize the
        // Planner on the SAME tick (the original synchronized sputter).
        if((tickCount+getId())%5==0 && level() instanceof ServerLevel server)
            BuilderBuildService.tick(server,this);
    }
    @Override protected void addAdditionalSaveData(ValueOutput out){
        super.addAdditionalSaveData(out);
        out.store("builder_home",BlockPos.CODEC,home);
        if(owner!=null)out.putString("builder_owner",owner.toString());
        if(!carriedSupplyBox.isEmpty())
            out.store("builder_carried_shulker",ItemStack.CODEC,carriedSupplyBox);
        for(int i=0;i<materials.size();i++)
            if(!materials.get(i).isEmpty())
                out.store("builder_material_"+i,ItemStack.CODEC,materials.get(i));
    }
    @Override protected void readAdditionalSaveData(ValueInput in){
        super.readAdditionalSaveData(in);
        // Vanilla persists custom names; migrate old generic names, but keep
        // names given by players with a name tag unchanged.
        ProfessionGolemNames.ensureNamed(this,ProfessionGolemNamePool.Profession.BUILDER);
        home=in.read("builder_home",BlockPos.CODEC).orElse(BlockPos.ZERO);
        try{owner=UUID.fromString(in.getStringOr("builder_owner",""));}
        catch(IllegalArgumentException ex){owner=null;}
        carriedSupplyBox=in.read("builder_carried_shulker",ItemStack.CODEC)
                .orElse(ItemStack.EMPTY);
        for(int i=0;i<materials.size();i++)
            materials.set(i,in.read("builder_material_"+i,ItemStack.CODEC)
                    .orElse(ItemStack.EMPTY));
    }
}
