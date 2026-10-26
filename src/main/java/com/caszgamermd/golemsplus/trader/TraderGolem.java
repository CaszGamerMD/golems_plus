package com.caszgamermd.golemsplus.trader;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import com.caszgamermd.golemsplus.worker.ProfessionGolemNames;
import com.caszgamermd.golemsplus.worker.ProfessionGolemNamePool;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * Visible physical courier. All payment stacks are REMOVED from its
 * workstation before departure. The worker persists them across saves
 * and retains them when villagers go out of stock.
 */
public final class TraderGolem extends PathfinderMob {

    private static final EntityDataAccessor<Boolean> CASE_OPEN =
            SynchedEntityData.defineId(TraderGolem.class,EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<ItemStack> CASE_ITEM =
            SynchedEntityData.defineId(TraderGolem.class,EntityDataSerializers.ITEM_STACK);
    @Override protected void defineSynchedData(SynchedEntityData.Builder builder){
        super.defineSynchedData(builder);
        builder.define(CASE_OPEN,false);
        builder.define(CASE_ITEM,ItemStack.EMPTY);
    }
    public boolean presenting(){return getEntityData().get(CASE_OPEN);}
    private void openCase(boolean show){getEntityData().set(CASE_OPEN,show);}
    public ItemStack displayedOffer(){return getEntityData().get(CASE_ITEM);}
    private void displayedOffer(ItemStack item){getEntityData().set(CASE_ITEM,item.copy());}
    private BlockPos home = BlockPos.ZERO;
    private UUID owner, order, villagerId;
    private int offerIndex=-1, stage=0, navigationFailures, quotedGoods;
    private ItemStack paymentA=ItemStack.EMPTY,paymentB=ItemStack.EMPTY,returning=ItemStack.EMPTY;
    private boolean hasTraded;
    private int presentationTicks;
    private BlockPos openedDoor;
    private BlockPos lastProgress=BlockPos.ZERO;
    private int noProgressChecks,doorGraceChecks,doorRecheckTicks;

    public TraderGolem(EntityType<? extends PathfinderMob> type, Level world){
        super(type,world);
        setPersistenceRequired();
        setInvulnerable(true); // no free iron drops or accidental cargo loss
        // A personal name is picked on the logical server when assigned.
        if(getNavigation() instanceof GroundPathNavigation path){
            path.setCanOpenDoors(true);
        }
    }
    public static AttributeSupplier.Builder attributes(){
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH,20)
                .add(Attributes.MOVEMENT_SPEED,.30)
                .add(Attributes.FOLLOW_RANGE,48);
    }
    @Override protected void registerGoals() {
        // All movement is through the explicit order controller, never an
        // Iron Golem's random wandering or auto-attacking villagers.
    }
    public void assign(BlockPos home,UUID owner) {
        this.home=home.immutable();this.owner=owner;
        ProfessionGolemNames.ensureNamed(this,ProfessionGolemNamePool.Profession.TRADER);
    }
    public BlockPos home(){return home;}
    public boolean idle(){return stage==0;}
    public boolean busy(){return stage!=0;}
    public boolean assignedTo(BlockPos p){return p.equals(home);}
    public boolean worksOn(UUID active){return active!=null&&active.equals(order);}
    public int expectedGoods(){
        if(stage==1)return quotedGoods;
        return stage==2?returning.getCount():0;
    }
    public Map<String,Integer> reservedPayments(){
        return stage==1?TraderWorkService.totalCost(paymentA,paymentB):Map.of();
    }
    public void begin(UUID order,UUID villager,int offer,ItemStack a,ItemStack b,
            int expectedGoods) {
        if(stage!=0 || this.order!=null)throw new IllegalStateException("Trader busy");
        if(order==null||villager==null||offer<0||a.isEmpty())throw new IllegalArgumentException();
        this.order=order;villagerId=villager;offerIndex=offer;
        openCase(false);displayedOffer(ItemStack.EMPTY);
        presentationTicks=0;
        quotedGoods=expectedGoods;
        paymentA=a.copy();paymentB=b.copy();
        returning=ItemStack.EMPTY;hasTraded=false;stage=1;navigationFailures=0;
        noProgressChecks=0;lastProgress=blockPosition();
    }
    @Override public void tick(){
        super.tick();
        // Also covers Golems created with a spawn egg, command or an older save.
        if(tickCount==1)ProfessionGolemNames.ensureNamed(this,ProfessionGolemNamePool.Profession.TRADER);
        if(level() instanceof ServerLevel level)
            com.caszgamermd.golemsplus.worker.WorkerNavigation.tick(level,this);
        if(!(level() instanceof ServerLevel server)||tickCount%12!=0)return;
        if(!server.hasChunkAt(home))return;
        if(!(server.getBlockEntity(home) instanceof WorkerStationBlockEntity depot)
                || !depot.isTrader() || owner==null || !owner.equals(depot.owner())){
            dropEscrow(server);discard();return;
        }
        if(stage==1 && depot.phase()==com.caszgamermd.golemsplus.worker.WorkerPhase.PAUSED){
            getNavigation().stop();return;
        }
        if(stage==0){
            openCase(false);displayedOffer(ItemStack.EMPTY);
            com.caszgamermd.golemsplus.worker.WorkerIdleRoam.tick(server,this,home);
            return;
        }
        if(stage==1)visit(server,depot);
        else if(stage==2)comeHome(server,depot);
    }
    private void visit(ServerLevel server,WorkerStationBlockEntity depot){
        var entity=server.getEntity(villagerId);
        if(!(entity instanceof Villager villager) || !villager.isAlive()){
            stage=2;depot.setProblem("Villager unloaded or unavailable");return;
        }
        if(!TraderWorkService.withinVillage(server,depot,villager.blockPosition())){
            stage=2;depot.setProblem("Villager outside approved village");return;
        }
        // A closed wooden door can obstruct the path to a trading villager.
        // Open it as we approach, then close it once both feet have cleared.
        // Door opening/closing now belongs to the same controller as
        // Builder, Beekeeper, Chef and Fisher navigation.
        if(distanceToSqr(villager)>8||!hasLineOfSight(villager)){
            presentationTicks=0;
            openCase(false);
            displayedOffer(ItemStack.EMPTY);
            getLookControl().setLookAt(villager,30f,30f);
            if(!navigateAroundObstacles(server,villager.blockPosition(),villager,.85)
                    &&++navigationFailures>35){
                stage=2;depot.setProblem("Cannot path to villager after repeated detours");
            }
            return;
        }
        // Face one another in clear sight before presenting an offer.
        getNavigation().stop();
        getLookControl().setLookAt(villager,30f,30f);
        villager.getLookControl().setLookAt(this,30f,30f);
        if(villager.isTrading()){openCase(false);return;} // never interrupt players
        if(!order.equals(depot.activeOrder())){
            stage=2;return;
        }
        var offers=villager.getOffers();
        if(offerIndex<0||offerIndex>=offers.size()){
            stage=2;depot.setProblem("Villager offer changed");return;
        }
        MerchantOffer offer=offers.get(offerIndex);
        ItemStack desired=offer.getResult();
        displayedOffer(desired);
        openCase(true);
        if(offer.isOutOfStock()||desired.isEmpty()
                ||!TraderWorkService.itemId(desired).equals(depot.wantedItem())
                ||!offer.satisfiedBy(paymentA,paymentB)){
            stage=2;depot.setProblem("Offer changed or out of stock");return;
        }
        Map<String,Integer> costs=TraderWorkService.totalCost(paymentA,paymentB);
        for(var e:costs.entrySet())if(depot.budgetRemaining(e.getKey())<e.getValue()){
            stage=2;depot.setProblem("Spending limit reached");return;
        }
        // Hold the case open long enough for clients to clearly see the
        // offer inside it, rather than flashing for a single server tick.
        if(++presentationTicks<4)return;
        // Result is held by THIS entity until a physical depot return.
        // MerchantOffer.take consumes actual escrowed stacks. notifyTrade
        // applies vanilla uses, XP and restock behavior exactly once.
        ItemStack originalA=paymentA.copy(),originalB=paymentB.copy();
        if(!offer.take(paymentA,paymentB)){
            stage=2;depot.setProblem("Villager rejected payment");return;
        }
        Map<String,Integer> actualCosts=TraderWorkService.totalCost(
                originalA.copyWithCount(originalA.getCount()-paymentA.getCount()),
                originalB.isEmpty()?ItemStack.EMPTY:originalB.copyWithCount(
                        originalB.getCount()-paymentB.getCount()));
        returning=offer.assemble();
        hasTraded=true;
        depot.recordTrade(actualCosts);
        villager.notifyTrade(offer);
        depot.setProblem("");
        stage=2;
    }
    private void comeHome(ServerLevel server,WorkerStationBlockEntity depot){
        openCase(false);displayedOffer(ItemStack.EMPTY);
        presentationTicks=0;
        double distance=distanceToSqr(home.getX()+.5,home.getY()+.5,home.getZ()+.5);
        if(distance>9){
            BlockPos access=accessibleHomeSide(server);
            if(access!=null){
                navigateAroundObstacles(server,access,null,.83);
            }else if(++navigationFailures>35){
                depot.setProblem("No walkable route beside Trader Counter");
            }
            return;
        }
        getNavigation().stop();
        if(!returning.isEmpty()&&!depot.canReceive(returning)){
            depot.setProblem("Trader returns full; clear output slots");return;
        }
        if(!TraderWorkService.canReturnPayments(depot,paymentA,paymentB)){
            depot.setProblem("Trader input full; clear stock slots");return;
        }
        if(!returning.isEmpty()){
            int count=returning.getCount();
            if(!depot.deposit(returning))return;
            returning=ItemStack.EMPTY;
            if(order.equals(depot.activeOrder()))depot.recordDelivered(count);
        }
        // Refund unused escrow when a villager refuses / changes an offer.
        if(!paymentA.isEmpty())TraderWorkService.returnPayment(depot,paymentA);
        if(!paymentB.isEmpty())TraderWorkService.returnPayment(depot,paymentB);
        paymentA=ItemStack.EMPTY;paymentB=ItemStack.EMPTY;
        order=null;villagerId=null;offerIndex=-1;quotedGoods=0;
        hasTraded=false;stage=0;
    }
    /**
     * A route is normally allowed to continue for several ticks. The old
     * controller restarted the villager path every 12 ticks, causing
     * repeated wall-hugging and collisions when navigating buildings.
     */
    private boolean navigateAroundObstacles(ServerLevel server,BlockPos destination,
                                             Villager villager,double speed){
        // Reuse the shared route tracker. The old per-Trader controller
        // rebuilt the path when a worker simply paused behind a door.
        // That reset vanilla obstacle avoidance on every evaluation.
        boolean path=com.caszgamermd.golemsplus.worker.WorkerNavigation
                .routeTo(server,this,destination,speed);
        if(path){navigationFailures=0;return true;}
        return false;
    }

    private static boolean walkable(ServerLevel level,BlockPos pos){
        if(!level.hasChunkAt(pos)||!level.hasChunkAt(pos.below()))return false;
        return level.getBlockState(pos).getCollisionShape(level,pos).isEmpty()
                &&level.getBlockState(pos.above()).getCollisionShape(level,pos.above()).isEmpty()
                &&!level.getBlockState(pos.below())
                        .getCollisionShape(level,pos.below()).isEmpty();
    }
    private boolean occupiedByTrader(ServerLevel server,BlockPos pos){
        return !server.getEntitiesOfClass(TraderGolem.class,
                new AABB(pos).inflate(.12),
                other->other!=this&&other.isAlive()).isEmpty();
    }

    private BlockPos accessibleHomeSide(ServerLevel server){
        BlockPos best=null;
        double distance=Double.MAX_VALUE;
        for(Direction facing:Direction.Plane.HORIZONTAL){
            BlockPos p=home.relative(facing);
            if(!walkable(server,p)||occupiedByTrader(server,p))continue;
            double score=p.distSqr(blockPosition());
            if(score<distance){best=p;distance=score;}
        }
        return best;
    }

    private void dropEscrow(ServerLevel level) {
        for(ItemStack stack:new ItemStack[]{paymentA,paymentB,returning})
            if(!stack.isEmpty())
                Containers.dropItemStack(level,getX(),getY(),getZ(),stack.copy());
        paymentA=paymentB=returning=ItemStack.EMPTY;
        displayedOffer(ItemStack.EMPTY);
    }
    @Override public void die(DamageSource damage){
        if(level() instanceof ServerLevel server)dropEscrow(server);
        super.die(damage);
    }
    @Override protected void addAdditionalSaveData(ValueOutput out){
        super.addAdditionalSaveData(out);
        com.caszgamermd.golemsplus.breakroom.GolemBreakService.saveRest(this,out);
        out.store("trader_home",BlockPos.CODEC,home);
        if(owner!=null)out.putString("trader_owner",owner.toString());
        if(order!=null)out.putString("trader_order",order.toString());
        if(villagerId!=null)out.putString("trader_villager",villagerId.toString());
        out.putInt("trader_offer",offerIndex);out.putInt("trader_stage",stage);
        out.putInt("trader_quoted_goods",quotedGoods);
        out.putBoolean("trader_done",hasTraded);
        if(!paymentA.isEmpty())out.store("trader_cost_a",ItemStack.CODEC,paymentA);
        if(!paymentB.isEmpty())out.store("trader_cost_b",ItemStack.CODEC,paymentB);
        if(!returning.isEmpty())out.store("trader_result",ItemStack.CODEC,returning);
        if(openedDoor!=null)out.store("trader_opened_door",BlockPos.CODEC,openedDoor);
    }
    private static UUID parse(String s){
        try{return UUID.fromString(s);}catch(IllegalArgumentException e){return null;}
    }
    @Override protected void readAdditionalSaveData(ValueInput in){
        super.readAdditionalSaveData(in);
        com.caszgamermd.golemsplus.breakroom.GolemBreakService.loadRest(this,in);
        // Vanilla persists custom names; migrate old generic names, but keep
        // names given by players with a name tag unchanged.
        ProfessionGolemNames.ensureNamed(this,ProfessionGolemNamePool.Profession.TRADER);
        home=in.read("trader_home",BlockPos.CODEC).orElse(BlockPos.ZERO);
        owner=parse(in.getStringOr("trader_owner",""));
        order=parse(in.getStringOr("trader_order",""));
        villagerId=parse(in.getStringOr("trader_villager",""));
        offerIndex=in.getIntOr("trader_offer",-1);
        stage=Math.clamp(in.getIntOr("trader_stage",0),0,2);
        quotedGoods=Math.max(0,in.getIntOr("trader_quoted_goods",0));
        hasTraded=in.getBooleanOr("trader_done",false);
        paymentA=in.read("trader_cost_a",ItemStack.CODEC).orElse(ItemStack.EMPTY);
        paymentB=in.read("trader_cost_b",ItemStack.CODEC).orElse(ItemStack.EMPTY);
        returning=in.read("trader_result",ItemStack.CODEC).orElse(ItemStack.EMPTY);
        openedDoor=in.read("trader_opened_door",BlockPos.CODEC).orElse(null);
        displayedOffer(ItemStack.EMPTY);
        openCase(false);
        if(owner==null)stage=2;
    }
}

