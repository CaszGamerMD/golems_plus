package com.caszgamermd.golemsplus.builder;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Player;
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
        setCustomName(Component.literal("Builder Golem"));
    }
    public static AttributeSupplier.Builder attributes(){
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH,20)
                .add(Attributes.MOVEMENT_SPEED,.30)
                .add(Attributes.FOLLOW_RANGE,128);
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
        super.tick();
        if(level() instanceof ServerLevel server)
            com.caszgamermd.golemsplus.worker.WorkerNavigation.tick(server,this);
        if(tickCount%12==0 && level() instanceof ServerLevel server)
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
