package com.caszgamermd.golemsplus.fishing;

import com.caszgamermd.golemsplus.beekeeper.BeeInventory;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import com.caszgamermd.golemsplus.worker.ProfessionGolemNames;
import com.caszgamermd.golemsplus.worker.ProfessionGolemNamePool;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

/** A real supply-and-return fisherman: rods and bait are acquired from the
 * Cooler job block, 9 pocket slots retain each caught stack, and catches
 * return to the separately linked chest. Fishing uses real consumable bait,
 * rod durability and a weighted fish catch after a variable delay.
 */
public final class FisherGolem extends PathfinderMob {

    private static final EntityDataAccessor<Boolean> HAS_ROD=
            SynchedEntityData.defineId(FisherGolem.class,EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> HAS_CATCH=
            SynchedEntityData.defineId(FisherGolem.class,EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> SEATED=
            SynchedEntityData.defineId(FisherGolem.class,EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> CASTING=
            SynchedEntityData.defineId(FisherGolem.class,EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> REELING=
            SynchedEntityData.defineId(FisherGolem.class,EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> CHAIR_COLOR=
            SynchedEntityData.defineId(FisherGolem.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<BlockPos> CAST_TARGET=
            SynchedEntityData.defineId(FisherGolem.class,EntityDataSerializers.BLOCK_POS);
    private static final int POCKET_SLOTS=9,BAIT_LOAD=24;
    private static final DustParticleOptions LINE_COLOR=new DustParticleOptions(0x9caca7,.33f);
    private static final DustParticleOptions FLOAT_WHITE=new DustParticleOptions(0xe8e8dc,.9f);
    private static final DustParticleOptions FLOAT_RED=new DustParticleOptions(0xe34437,.75f);
    private BlockPos home=BlockPos.ZERO;
    private UUID owner;
    private ItemStack rod=ItemStack.EMPTY;
    private final List<ItemStack> pockets=new ArrayList<>();
    private int bait,phase,fishTicks,tries,reelTicks;
    private BlockPos chairSpot;

    public FisherGolem(EntityType<? extends PathfinderMob> type,Level world){
        super(type,world);
        setPersistenceRequired();
        // A personal name is picked on the logical server when assigned.
        for(int i=0;i<POCKET_SLOTS;i++)pockets.add(ItemStack.EMPTY);
    }
    public static AttributeSupplier.Builder attributes(){
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH,16)
                .add(Attributes.MOVEMENT_SPEED,.27)
                .add(Attributes.STEP_HEIGHT,.6)
                .add(Attributes.FOLLOW_RANGE,34);
    }
    @Override protected void defineSynchedData(SynchedEntityData.Builder b){
        super.defineSynchedData(b);
        b.define(HAS_ROD,false);b.define(HAS_CATCH,false);
        b.define(SEATED,false);
        b.define(CHAIR_COLOR,0);
        b.define(CASTING,false);b.define(REELING,false);
        b.define(CAST_TARGET,BlockPos.ZERO);
    }
    @Override protected void registerGoals(){}
    public void assign(BlockPos at,UUID who){home=at.immutable();owner=who;
        ProfessionGolemNames.ensureNamed(this,ProfessionGolemNamePool.Profession.FISHER);}
    public BlockPos home(){return home;}
    public boolean hasRod(){return getEntityData().get(HAS_ROD);}
    public boolean hasCatch(){return getEntityData().get(HAS_CATCH);}
    public boolean seated(){return getEntityData().get(SEATED);}
    public int chairColor(){return getEntityData().get(CHAIR_COLOR);}
    public boolean casting(){return getEntityData().get(CASTING);}
    public boolean reeling(){return getEntityData().get(REELING);}
    public BlockPos castTarget(){return getEntityData().get(CAST_TARGET);}
    public int baitCount(){return bait;}
    private void syncCarried(){
        getEntityData().set(HAS_ROD,!rod.isEmpty());
        getEntityData().set(HAS_CATCH,pockets.stream().anyMatch(s->!s.isEmpty()));
    }
    private void seat(boolean value){
        boolean previous=getEntityData().get(SEATED);
        if(previous!=value){
            if(value)getEntityData().set(CHAIR_COLOR,getRandom().nextInt(12));
            getEntityData().set(SEATED,value);
        }
        if(value)getNavigation().stop();
    }
    private void stopCasting(){
        getEntityData().set(CASTING,false);
        getEntityData().set(REELING,false);
    }
    private boolean full(){
        return pockets.stream().noneMatch(ItemStack::isEmpty);
    }
    private boolean store(ItemStack caught){
        for(int i=0;i<POCKET_SLOTS;i++){
            ItemStack stack=pockets.get(i);
            if(!stack.isEmpty()&&ItemStack.isSameItemSameComponents(stack,caught)
                    &&stack.getCount()+caught.getCount()<=stack.getMaxStackSize()){
                pockets.set(i,stack.copyWithCount(stack.getCount()+caught.getCount()));
                syncCarried();return true;
            }
        }
        for(int i=0;i<POCKET_SLOTS;i++)if(pockets.get(i).isEmpty()){
            pockets.set(i,caught.copy());
            syncCarried();return true;
        }
        return false;
    }
    private boolean anyFish(){return pockets.stream().anyMatch(s->!s.isEmpty());}
    @Override protected InteractionResult mobInteract(Player player,InteractionHand hand){
        if(owner==null||!owner.equals(player.getUUID()))
            return super.mobInteract(player,hand);
        ItemStack held=player.getItemInHand(hand);
        if(!(level() instanceof ServerLevel))return InteractionResult.SUCCESS;
        // Existing saves can still give a Fisher a rod directly.
        if(held.is(Items.FISHING_ROD)&&rod.isEmpty()){
            rod=held.copyWithCount(1);
            if(!player.getAbilities().instabuild)held.shrink(1);
            syncCarried();return InteractionResult.SUCCESS;
        }
        if(held.isEmpty()&&player.isShiftKeyDown()&&!rod.isEmpty()){
            Containers.dropItemStack(level(),getX(),getY(),getZ(),rod.copy());
            rod=ItemStack.EMPTY;seat(false);stopCasting();syncCarried();
            return InteractionResult.SUCCESS;
        }
        return super.mobInteract(player,hand);
    }
    @Override public void tick(){
        super.tick();
        // Also covers Golems created with a spawn egg, command or an older save.
        if(tickCount==1)ProfessionGolemNames.ensureNamed(this,ProfessionGolemNamePool.Profession.FISHER);
        if(level() instanceof ServerLevel world){
            com.caszgamermd.golemsplus.worker.WorkerNavigation.tick(world,this);
            if(tickCount%5==0&&casting())renderFishingLine(world);
        }
        if(!(level() instanceof ServerLevel level)||owner==null||tickCount%10!=0
                ||!level.hasChunkAt(home))return;
        if(!(level.getBlockEntity(home) instanceof WorkerStationBlockEntity station)
                ||!station.isFishing()||!owner.equals(station.owner())
                ||!station.fisherWorkerIds().contains(getUUID())){
            dropCargo(level);discard();return;
        }
        if(station.phase()==com.caszgamermd.golemsplus.worker.WorkerPhase.PAUSED){
            if(com.caszgamermd.golemsplus.breakroom.GolemBreakService.takingBreak(this))return;
            // An off-duty Fisher rests in the camp chair when it has
            // returned to the coolers, rather than standing frozen.
            boolean waiting=distanceToSqr(home.getX()+.5,home.getY()+.5,
                    home.getZ()+.5)<9.0;
            stopCasting();
            seat(waiting);
            if(!waiting)walkToward(home,.75);
            else getNavigation().stop();
            return;
        }
        if(anyFish()&&(full()||bait<=0)&&phase!=4)phase=3;
        if((rod.isEmpty()||bait==0)&&phase!=3&&phase!=4)phase=0;
        if(phase==0)restock(level,station);
        else if(phase==1)travel(level,station);
        else if(phase==2)fish(level,station);
        else if(phase==4)reel(level,station);
        else returnCatch(level,station);
    }
    private static int takeBait(WorkerStationBlockEntity station,int want){
        int left=want;
        for(int i=0;i<WorkerStationBlockEntity.INPUT_SLOTS&&left>0;i++){
            if(!station.getItem(i).is(WorksiteContent.FISHING_BAIT))continue;
            ItemStack used=station.removeItem(i,left);
            left-=used.getCount();
        }
        return want-left;
    }
    private void restock(ServerLevel level,WorkerStationBlockEntity station){
        stopCasting();
        BlockPos home=station.getBlockPos();
        if(!walkToward(home,.8)){station.setProblem("Fisher returning to coolers");return;}
        if(rod.isEmpty())for(int i=0;i<WorkerStationBlockEntity.INPUT_SLOTS;i++)
            if(station.getItem(i).is(Items.FISHING_ROD)){
                rod=station.removeItem(i,1);break;
            }
        if(bait<2)bait+=takeBait(station,BAIT_LOAD-bait);
        syncCarried();
        if(rod.isEmpty()){station.setProblem("Fisher needs a fishing rod in the cooler");seat(true);return;}
        if(bait==0){station.setProblem("Fisher needs bait in the cooler");seat(true);return;}
        if(station.fishingSpot()==null){station.setProblem("Set a fishing spot with marker or X Y Z");seat(true);return;}
        seat(false);station.setProblem("");phase=1;
    }
    private boolean walkToward(BlockPos pos,double speed){
        if(pos==null)return false;
        double d=distanceToSqr(pos.getX()+.5,pos.getY(),pos.getZ()+.5);
        if(d<=7.5){getNavigation().stop();return true;}
        seat(false);
        if(level() instanceof ServerLevel world)
            com.caszgamermd.golemsplus.worker.WorkerNavigation
                    .routeTo(world,this,pos,speed);
        return false;
    }
    private static boolean safeStanding(ServerLevel level,BlockPos p){
        return level.hasChunkAt(p)&&level.hasChunkAt(p.below())
                &&level.getFluidState(p).isEmpty()
                &&level.getFluidState(p.above()).isEmpty()
                &&level.getBlockState(p).getCollisionShape(level,p).isEmpty()
                &&level.getBlockState(p.above()).getCollisionShape(level,p.above()).isEmpty()
                &&!level.getBlockState(p.below()).getCollisionShape(level,p.below()).isEmpty();
    }
    private BlockPos landing(ServerLevel level,BlockPos location){
        BlockPos closest=null;double score=Double.POSITIVE_INFINITY;
        for(int dx=-7;dx<=7;dx++)for(int dz=-7;dz<=7;dz++)
            for(int dy=-2;dy<=2;dy++){
                BlockPos p=location.offset(dx,dy,dz);
                if(!safeStanding(level,p)||p.distSqr(location)>74)continue;
                double dist=p.distSqr(location)+p.distSqr(blockPosition())*.04;
                if(dist<score){score=dist;closest=p;}
            }
        return closest;
    }
    private static boolean fishableWater(ServerLevel level,BlockPos near){
        for(int dx=-7;dx<=7;dx++)for(int dz=-7;dz<=7;dz++)
            for(int dy=-2;dy<=2;dy++){
                BlockPos pos=near.offset(dx,dy,dz);
                if(level.hasChunkAt(pos)
                        &&level.getFluidState(pos).is(net.minecraft.world.level.material.Fluids.WATER))
                    return true;
            }
        return false;
    }
    /** Find a visible water surface in rod range. Different crew members
     * favor slightly different landing points to avoid stacked bobbers.
     */
    private BlockPos chooseCastWater(ServerLevel level,BlockPos marker){
        if(marker==null)return null;
        int hash=getUUID().hashCode();
        int ox=((hash&3)-1)*2,oz=(((hash>>>2)&3)-1)*2;
        BlockPos preferred=marker.offset(ox,0,oz);
        double best=Double.POSITIVE_INFINITY;
        BlockPos chosen=null;
        for(int dx=-7;dx<=7;dx++)for(int dz=-7;dz<=7;dz++)
            for(int dy=-2;dy<=2;dy++){
                BlockPos at=marker.offset(dx,dy,dz);
                if(!level.hasChunkAt(at)||!level.hasChunkAt(at.above())
                        ||!level.getFluidState(at).is(net.minecraft.world.level.material.Fluids.WATER)
                        ||!level.getFluidState(at).isSource()
                        ||!level.getFluidState(at.above()).isEmpty()
                        ||at.distSqr(blockPosition())>95)continue;
                double score=at.distSqr(preferred)+.18*at.distSqr(blockPosition());
                if(score<best){best=score;chosen=at.immutable();}
            }
        return chosen;
    }
    private void faceCast(BlockPos water){
        double dx=water.getX()+.5-getX();
        double dz=water.getZ()+.5-getZ();
        float yaw=-(float)Math.toDegrees(Math.atan2(dx,dz));
        setYRot(yaw);setYHeadRot(yaw);setYBodyRot(yaw);
    }
    private boolean beginCast(ServerLevel level,WorkerStationBlockEntity station){
        BlockPos target=chooseCastWater(level,station.fishingSpot());
        if(target==null){
            stopCasting();
            station.setProblem("No open water surface within fishing rod range of the chair");
            phase=1;chairSpot=null;seat(false);
            return false;
        }
        faceCast(target);
        getEntityData().set(CAST_TARGET,target);
        getEntityData().set(REELING,false);
        getEntityData().set(CASTING,true);
        fishTicks=120+getRandom().nextInt(220);
        level.playSound(null,getX(),getY(),getZ(),SoundEvents.FISHING_BOBBER_THROW,
                SoundSource.NEUTRAL,.55f,.95f+getRandom().nextFloat()*.1f);
        station.setProblem("");
        return true;
    }
    private void renderFishingLine(ServerLevel level){
        BlockPos target=castTarget();
        if(target.equals(BlockPos.ZERO))return;
        double yaw=Math.toRadians(getYRot());
        Vec3 forward=new Vec3(-Math.sin(yaw),0,Math.cos(yaw));
        Vec3 side=new Vec3(forward.z,0,-forward.x);
        Vec3 tip=position().add(forward.scale(1.22)).add(side.scale(-.32)).add(0,1.64,0);
        double floatY=target.getY()+.89+.028*Math.sin(tickCount*.29);
        Vec3 bob=new Vec3(target.getX()+.5,floatY,target.getZ()+.5);
        for(int i=1;i<12;i++){
            double t=i/12.0;
            Vec3 at=tip.lerp(bob,t).add(0,-.22*Math.sin(t*Math.PI),0);
            level.sendParticles(LINE_COLOR,at.x,at.y,at.z,1,0,0,0,0);
        }
        level.sendParticles(FLOAT_WHITE,bob.x,bob.y,bob.z,1,0,0,0,0);
        level.sendParticles(FLOAT_RED,bob.x,bob.y+.13,bob.z,1,0,0,0,0);
        if(fishTicks<45&&tickCount%10==0){
            level.sendParticles(ParticleTypes.FISHING,bob.x,bob.y,bob.z,3,.24,0,.24,.01);
            level.sendParticles(ParticleTypes.BUBBLE,bob.x,bob.y-.13,bob.z,2,.18,.06,.18,.02);
        }
    }
    private void travel(ServerLevel level,WorkerStationBlockEntity station){
        stopCasting();
        if(station.fishingSpot()==null){phase=0;return;}
        if(!fishableWater(level,station.fishingSpot())){
            station.setProblem("Fishing flag needs accessible water nearby");
            seat(false);return;
        }
        if(chairSpot==null||tickCount%200==0)
            chairSpot=landing(level,station.fishingSpot());
        if(chairSpot==null){
            station.setProblem("No safe shoreline near fishing marker. Build a platform");
            seat(false);return;
        }
        if(!walkToward(chairSpot,.75)){
            if(++tries>60){
                station.setProblem("Cannot path to fishing spot; clear a floor-level route");
                tries=0;chairSpot=null;
            }return;
        }
        tries=0;phase=2;seat(true);
        beginCast(level,station);
    }
    private void fish(ServerLevel level,WorkerStationBlockEntity station){
        seat(true);
        if(!hasRod()||bait<=0||full()){phase=3;stopCasting();seat(false);return;}
        if(station.fishingSpot()==null){phase=3;stopCasting();return;}
        if(!casting()&&!beginCast(level,station))return;
        BlockPos bob=castTarget();
        if(!level.hasChunkAt(bob)
                ||!level.getFluidState(bob).is(net.minecraft.world.level.material.Fluids.WATER)){
            stopCasting();phase=1;chairSpot=null;
            station.setProblem("Fishing water disappeared; choosing a new shoreline");
            return;
        }
        faceCast(bob);
        if(fishTicks>0){fishTicks-=10;return;}
        // A visible bite has completed at a real water surface. Consume one
        // stored bait and one rod-use only when an actual catch fits pockets.
        double roll=getRandom().nextDouble();
        Item result=roll<.66?Items.COD:roll<.91?Items.SALMON:
                roll<.975?Items.TROPICAL_FISH:Items.PUFFERFISH;
        if(!store(new ItemStack(result))){phase=3;stopCasting();seat(false);return;}
        bait--;
        if(rod.isDamageableItem()){
            int damage=rod.getDamageValue()+1;
            if(damage>=rod.getMaxDamage())rod=ItemStack.EMPTY;
            else rod.setDamageValue(damage);
        }
        syncCarried();
        getEntityData().set(CASTING,false);
        getEntityData().set(REELING,true);
        phase=4;
        reelTicks=20;
        level.sendParticles(ParticleTypes.SPLASH,
                bob.getX()+.5,bob.getY()+.9,bob.getZ()+.5,12,.3,.12,.3,.06);
        level.sendParticles(ParticleTypes.FISHING,
                bob.getX()+.5,bob.getY()+.9,bob.getZ()+.5,8,.4,.05,.4,.03);
        level.playSound(null,getX(),getY(),getZ(),SoundEvents.FISHING_BOBBER_RETRIEVE,
                SoundSource.NEUTRAL,.8f,1f);
    }
    private void reel(ServerLevel level,WorkerStationBlockEntity station){
        seat(true);
        if(reelTicks>0){reelTicks-=10;return;}
        getEntityData().set(REELING,false);
        if(full()||bait<=0||rod.isEmpty()){
            phase=3;seat(false);
        }else{
            phase=2;
            beginCast(level,station);
        }
    }
    private void returnCatch(ServerLevel level,WorkerStationBlockEntity station){
        stopCasting();
        seat(false);
        if(!walkToward(home,.85))return;
        BlockPos dest=station.fisherOutput();
        if(dest==null||!level.hasChunkAt(dest)
                ||!(level.getBlockEntity(dest) instanceof Container output)){
            station.setProblem("Link an output chest from the Fisher cooler job menu");
            return;
        }
        for(int i=0;i<POCKET_SLOTS;i++){
            ItemStack fish=pockets.get(i);
            if(fish.isEmpty())continue;
            if(!BeeInventory.insert(output,fish)){
                station.setProblem("Fisher output chest is full; keeping catches");
                return;
            }
            pockets.set(i,ItemStack.EMPTY);
        }
        syncCarried();
        station.setProblem("");
        phase=0;
    }
    private void dropCargo(ServerLevel level){
        if(!rod.isEmpty())Containers.dropItemStack(level,getX(),getY(),getZ(),rod.copy());
        for(ItemStack fish:pockets)if(!fish.isEmpty())
            Containers.dropItemStack(level,getX(),getY(),getZ(),fish.copy());
        rod=ItemStack.EMPTY;
        stopCasting();
        pockets.replaceAll(item->ItemStack.EMPTY);
        syncCarried();
    }
    @Override public void die(DamageSource damage){
        if(level() instanceof ServerLevel server)dropCargo(server);
        super.die(damage);
    }
    @Override protected void addAdditionalSaveData(ValueOutput out){
        super.addAdditionalSaveData(out);
        out.store("fisher_home",BlockPos.CODEC,home);
        if(owner!=null)out.putString("fisher_owner",owner.toString());
        if(!rod.isEmpty())out.store("fisher_rod",ItemStack.CODEC,rod);
        out.putInt("fisher_bait",bait);
        out.putInt("fisher_phase",phase);
        out.putInt("fisher_ticks",fishTicks);
        out.putInt("fisher_reel_ticks",reelTicks);
        out.putInt("fisher_chair_color",chairColor());
        out.store("fisher_cast_target",BlockPos.CODEC,castTarget());
        for(int i=0;i<POCKET_SLOTS;i++)
            if(!pockets.get(i).isEmpty())
                out.store("fisher_pocket_"+i,ItemStack.CODEC,pockets.get(i));
    }
    @Override protected void readAdditionalSaveData(ValueInput in){
        super.readAdditionalSaveData(in);
        // Vanilla persists custom names; migrate old generic names, but keep
        // names given by players with a name tag unchanged.
        ProfessionGolemNames.ensureNamed(this,ProfessionGolemNamePool.Profession.FISHER);
        home=in.read("fisher_home",BlockPos.CODEC).orElse(BlockPos.ZERO);
        try{owner=UUID.fromString(in.getStringOr("fisher_owner",""));}
        catch(IllegalArgumentException ex){owner=null;}
        rod=in.read("fisher_rod",ItemStack.CODEC).orElse(ItemStack.EMPTY);
        bait=Math.clamp(in.getIntOr("fisher_bait",0),0,BAIT_LOAD);
        phase=Math.clamp(in.getIntOr("fisher_phase",0),0,4);
        fishTicks=Math.max(0,in.getIntOr("fisher_ticks",0));
        reelTicks=Math.clamp(in.getIntOr("fisher_reel_ticks",0),0,20);
        getEntityData().set(CHAIR_COLOR,Math.clamp(in.getIntOr("fisher_chair_color",0),0,11));
        getEntityData().set(SEATED,phase==2||phase==4);
        BlockPos target=in.read("fisher_cast_target",BlockPos.CODEC).orElse(BlockPos.ZERO);
        getEntityData().set(CAST_TARGET,target);
        getEntityData().set(CASTING,phase==2&&!target.equals(BlockPos.ZERO));
        getEntityData().set(REELING,phase==4&&reelTicks>0);
        for(int i=0;i<POCKET_SLOTS;i++)
            pockets.set(i,in.read("fisher_pocket_"+i,ItemStack.CODEC).orElse(ItemStack.EMPTY));
        // Migrate pre-0.3.9 catches.
        ItemStack old=in.read("fisher_catch",ItemStack.CODEC).orElse(ItemStack.EMPTY);
        if(!old.isEmpty())store(old);
        syncCarried();
    }
}

