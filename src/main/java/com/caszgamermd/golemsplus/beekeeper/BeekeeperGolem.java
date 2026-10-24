package com.caszgamermd.golemsplus.beekeeper;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import com.caszgamermd.golemsplus.worker.ProfessionGolemNames;
import com.caszgamermd.golemsplus.worker.ProfessionGolemNamePool;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Containers;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/** Small, fully suited, peaceful worker. Actual shears and harvest persist on the mob. */
public final class BeekeeperGolem extends PathfinderMob {

    private BlockPos home=BlockPos.ZERO, targetHive=BlockPos.ZERO;
    private UUID owner,mateA,mateB;
    private ItemStack breedFlowers=ItemStack.EMPTY;
    private ItemStack tool=ItemStack.EMPTY, harvest=ItemStack.EMPTY;
    // 0=search, 1=pick tool, 2=harvest, 3=deposit, 4=return shears
    private int job;
    private int routeMisses;
    private boolean bottles;
    public BeekeeperGolem(EntityType<? extends PathfinderMob> type,Level level){
        super(type,level);setPersistenceRequired();
        // A personal name is picked on the logical server when assigned.
    }
    public static AttributeSupplier.Builder attributes(){
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH,16)
                .add(Attributes.MOVEMENT_SPEED,.27)
                .add(Attributes.FOLLOW_RANGE,28);
    }
    @Override protected void registerGoals() {
        // Job logic exclusively controls pathing. Never attack bees.
    }
    public void assign(BlockPos jobBlock,UUID player) {
        home=jobBlock.immutable();owner=player;
        ProfessionGolemNames.ensureNamed(this,ProfessionGolemNamePool.Profession.BEEKEEPER);
    }
    public BlockPos home(){return home;}
    public UUID owner(){return owner;}
    public int job(){return job;}
    public BlockPos hive(){return targetHive;}
    public boolean bottles(){return bottles;}
    public ItemStack tool(){return tool;}
    public ItemStack harvest(){return harvest;}
    public UUID mateA(){return mateA;}
    public UUID mateB(){return mateB;}
    public ItemStack breedFlowers(){return breedFlowers;}
    public void setBreedFlowers(ItemStack value){breedFlowers=value.copy();}
    public void startBreeding(UUID a,UUID b){
        if(job!=0||a==null||b==null||a.equals(b))return;
        mateA=a;mateB=b;routeMisses=0;job=5;
    }
    public void finishBreeding(){mateA=null;mateB=null;job=0;routeMisses=0;}
    public void setTool(ItemStack stack){tool=stack.copy();}
    public void setHarvest(ItemStack stack){harvest=stack.copy();}
    public void start(BlockPos target,boolean bottle){
        if(job!=0||target==null)throw new IllegalStateException("Beekeeper already busy");
        targetHive=target.immutable();bottles=bottle;routeMisses=0;job=1;
    }
    public void stage(int next){job=next;routeMisses=0;}
    public boolean moveTo(BlockPos pos,double speed){
        if(distanceToSqr(pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5)<=5.5){
            routeMisses=0;getNavigation().stop();return true;
        }
        if(level() instanceof ServerLevel server
                &&!com.caszgamermd.golemsplus.worker.WorkerNavigation
                        .routeTo(server,this,pos,speed))
            routeMisses++;
        return false;
    }
    public boolean stuck(){return routeMisses>15;}
    @Override public void tick(){
        super.tick();
        // Also covers Golems created with a spawn egg, command or an older save.
        if(tickCount==1)ProfessionGolemNames.ensureNamed(this,ProfessionGolemNamePool.Profession.BEEKEEPER);
        if(level() instanceof ServerLevel server)
            com.caszgamermd.golemsplus.worker.WorkerNavigation.tick(server,this);
        if(tickCount%12==0 && level() instanceof ServerLevel server)
            BeekeeperWorkService.tickWorker(server,this);
    }
    /** Never discard live stock when its workstation breaks. */
    public void dropCargo(ServerLevel level){
        if(!tool.isEmpty())Containers.dropItemStack(level,getX(),getY(),getZ(),tool);
        if(!harvest.isEmpty())Containers.dropItemStack(level,getX(),getY(),getZ(),harvest);
        if(!breedFlowers.isEmpty())Containers.dropItemStack(level,getX(),getY(),getZ(),breedFlowers);
        tool=harvest=breedFlowers=ItemStack.EMPTY;
    }
    @Override public void die(DamageSource damage){
        if(level() instanceof ServerLevel server)dropCargo(server);
        super.die(damage);
    }
    @Override protected void addAdditionalSaveData(ValueOutput out){
        super.addAdditionalSaveData(out);
        out.store("beekeeper_home",BlockPos.CODEC,home);
        out.store("beekeeper_hive",BlockPos.CODEC,targetHive);
        if(owner!=null)out.putString("beekeeper_owner",owner.toString());
        out.putInt("beekeeper_job",job);out.putBoolean("beekeeper_bottle",bottles);
        if(mateA!=null)out.putString("beekeeper_mate_a",mateA.toString());
        if(mateB!=null)out.putString("beekeeper_mate_b",mateB.toString());
        if(!breedFlowers.isEmpty())out.store("beekeeper_breed_flowers",ItemStack.CODEC,breedFlowers);
        if(!tool.isEmpty())out.store("beekeeper_tool",ItemStack.CODEC,tool);
        if(!harvest.isEmpty())out.store("beekeeper_harvest",ItemStack.CODEC,harvest);
    }
    @Override protected void readAdditionalSaveData(ValueInput in){
        super.readAdditionalSaveData(in);
        // Vanilla persists custom names; migrate old generic names, but keep
        // names given by players with a name tag unchanged.
        ProfessionGolemNames.ensureNamed(this,ProfessionGolemNamePool.Profession.BEEKEEPER);
        home=in.read("beekeeper_home",BlockPos.CODEC).orElse(BlockPos.ZERO);
        targetHive=in.read("beekeeper_hive",BlockPos.CODEC).orElse(BlockPos.ZERO);
        try{owner=UUID.fromString(in.getStringOr("beekeeper_owner",""));}
        catch(IllegalArgumentException ex){owner=null;}
        job=Math.clamp(in.getIntOr("beekeeper_job",0),0,7);
        try{mateA=UUID.fromString(in.getStringOr("beekeeper_mate_a",""));}
        catch(IllegalArgumentException ex){mateA=null;}
        try{mateB=UUID.fromString(in.getStringOr("beekeeper_mate_b",""));}
        catch(IllegalArgumentException ex){mateB=null;}
        breedFlowers=in.read("beekeeper_breed_flowers",ItemStack.CODEC).orElse(ItemStack.EMPTY);
        bottles=in.getBooleanOr("beekeeper_bottle",false);
        tool=in.read("beekeeper_tool",ItemStack.CODEC).orElse(ItemStack.EMPTY);
        harvest=in.read("beekeeper_harvest",ItemStack.CODEC).orElse(ItemStack.EMPTY);
    }
}
