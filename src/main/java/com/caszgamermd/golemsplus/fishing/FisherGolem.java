package com.caszgamermd.golemsplus.fishing;

import com.caszgamermd.golemsplus.beekeeper.BeeInventory;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
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
    private static final int POCKET_SLOTS=9,BAIT_LOAD=24;
    private BlockPos home=BlockPos.ZERO;
    private UUID owner;
    private ItemStack rod=ItemStack.EMPTY;
    private final List<ItemStack> pockets=new ArrayList<>();
    private int bait,phase,fishTicks,tries;
    private BlockPos chairSpot;

    public FisherGolem(EntityType<? extends PathfinderMob> type,Level world){
        super(type,world);
        setPersistenceRequired();
        setCustomName(Component.literal("Fisher Golem"));
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
    }
    @Override protected void registerGoals(){}
    public void assign(BlockPos at,UUID who){home=at.immutable();owner=who;}
    public BlockPos home(){return home;}
    public boolean hasRod(){return getEntityData().get(HAS_ROD);}
    public boolean hasCatch(){return getEntityData().get(HAS_CATCH);}
    public boolean seated(){return getEntityData().get(SEATED);}
    public int baitCount(){return bait;}
    private void syncCarried(){
        getEntityData().set(HAS_ROD,!rod.isEmpty());
        getEntityData().set(HAS_CATCH,pockets.stream().anyMatch(s->!s.isEmpty()));
    }
    private void seat(boolean value){
        if(getEntityData().get(SEATED)!=value)getEntityData().set(SEATED,value);
        if(value)getNavigation().stop();
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
            rod=ItemStack.EMPTY;seat(false);syncCarried();
            return InteractionResult.SUCCESS;
        }
        return super.mobInteract(player,hand);
    }
    @Override public void tick(){
        super.tick();
        if(!(level() instanceof ServerLevel level)||owner==null||tickCount%10!=0
                ||!level.hasChunkAt(home))return;
        if(!(level.getBlockEntity(home) instanceof WorkerStationBlockEntity station)
                ||!station.isFishing()||!owner.equals(station.owner())
                ||!station.fisherWorkerIds().contains(getUUID())){
            dropCargo(level);discard();return;
        }
        if(station.phase()==com.caszgamermd.golemsplus.worker.WorkerPhase.PAUSED){
            seat(false);getNavigation().stop();return;
        }
        if(phase==0&&station.fishingSpot()!=null)phase=1;
        if(anyFish()&&(full()||bait<=0))phase=3;
        if(rod.isEmpty()||bait==0&&phase!=3)phase=0;
        if(phase==0)restock(level,station);
        else if(phase==1)travel(level,station);
        else if(phase==2)fish(level,station);
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
        if(getNavigation().isDone()||tickCount%50==0)
            getNavigation().moveTo(pos.getX()+.5,pos.getY(),pos.getZ()+.5,speed);
        return false;
    }
    private static boolean safeStanding(ServerLevel level,BlockPos p){
        return level.hasChunkAt(p)&&level.hasChunkAt(p.below())
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
    private void travel(ServerLevel level,WorkerStationBlockEntity station){
        if(station.fishingSpot()==null){phase=0;return;}
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
        tries=0;phase=2;seat(true);fishTicks=30+getRandom().nextInt(80);
        station.setProblem("");
    }
    private void fish(ServerLevel level,WorkerStationBlockEntity station){
        seat(true);
        if(!hasRod()||bait<=0||full()){phase=3;seat(false);return;}
        if(station.fishingSpot()==null){phase=3;return;}
        if(fishTicks>0){fishTicks-=10;return;}
        // Only consume bait after a real fishing interval at an actual spot.
        bait--;
        double roll=getRandom().nextDouble();
        Item result=roll<.66?Items.COD:roll<.91?Items.SALMON:
                roll<.975?Items.TROPICAL_FISH:Items.PUFFERFISH;
        if(!store(new ItemStack(result))){phase=3;seat(false);return;}
        if(rod.isDamageableItem()){
            int damage=rod.getDamageValue()+1;
            if(damage>=rod.getMaxDamage())rod=ItemStack.EMPTY;
            else rod.setDamageValue(damage);
        }
        syncCarried();
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.SPLASH,
                station.fishingSpot().getX()+.5,station.fishingSpot().getY()+.85,
                station.fishingSpot().getZ()+.5,5,.23,.13,.23,.02);
        if(full()||bait==0||rod.isEmpty()){
            phase=3;seat(false);
        }else fishTicks=120+getRandom().nextInt(220);
    }
    private void returnCatch(ServerLevel level,WorkerStationBlockEntity station){
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
        for(int i=0;i<POCKET_SLOTS;i++)
            if(!pockets.get(i).isEmpty())
                out.store("fisher_pocket_"+i,ItemStack.CODEC,pockets.get(i));
    }
    @Override protected void readAdditionalSaveData(ValueInput in){
        super.readAdditionalSaveData(in);
        home=in.read("fisher_home",BlockPos.CODEC).orElse(BlockPos.ZERO);
        try{owner=UUID.fromString(in.getStringOr("fisher_owner",""));}
        catch(IllegalArgumentException ex){owner=null;}
        rod=in.read("fisher_rod",ItemStack.CODEC).orElse(ItemStack.EMPTY);
        bait=Math.clamp(in.getIntOr("fisher_bait",0),0,BAIT_LOAD);
        phase=Math.clamp(in.getIntOr("fisher_phase",0),0,3);
        fishTicks=Math.max(0,in.getIntOr("fisher_ticks",0));
        for(int i=0;i<POCKET_SLOTS;i++)
            pockets.set(i,in.read("fisher_pocket_"+i,ItemStack.CODEC).orElse(ItemStack.EMPTY));
        // Migrate pre-0.3.9 catches.
        ItemStack old=in.read("fisher_catch",ItemStack.CODEC).orElse(ItemStack.EMPTY);
        if(!old.isEmpty())store(old);
        syncCarried();
    }
}
