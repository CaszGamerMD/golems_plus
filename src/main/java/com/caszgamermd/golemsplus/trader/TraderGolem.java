package com.caszgamermd.golemsplus.trader;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
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
        setCustomName(Component.literal("Trader Golem"));
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
            closeDoorAfterPassing(server);
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
        manageApproachDoor(server,villager);
        closeDoorAfterPassing(server);
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
        closeDoorAfterPassing(server);
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
        if(!server.hasChunkAt(destination))return false;
        if(blockPosition().distSqr(lastProgress)>2){
            lastProgress=blockPosition();
            noProgressChecks=0;
            navigationFailures=0;
        }else noProgressChecks++;
        if(waitForNearbyTrader(server,destination))return true;
        if(!getNavigation().isDone() && noProgressChecks<7
                &&tickCount%48!=0)return true;
        boolean found=villager!=null
                ?getNavigation().moveTo(villager,speed)
                :getNavigation().moveTo(destination.getX()+.5,
                        destination.getY(),destination.getZ()+.5,speed);
        if(found){
            if(noProgressChecks>=7)noProgressChecks=0;
            return true;
        }
        // If a doorway or wall prevented finding a direct path, try
        // unobstructed approach cells on either side of the objective.
        BlockPos best=null;
        double distance=Double.MAX_VALUE;
        for(Direction direction:Direction.Plane.HORIZONTAL){
            BlockPos p=destination.relative(direction);
            if(!walkable(server,p)||occupiedByTrader(server,p))continue;
            if(p.distSqr(blockPosition())<distance){
                best=p;distance=p.distSqr(blockPosition());
            }
        }
        if(best!=null)return getNavigation().moveTo(best.getX()+.5,
                best.getY(),best.getZ()+.5,speed*.87);
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

    /**
     * Only the trailing worker waits if two traders are about to occupy a
     * door-width aisle. This avoids all of them repeatedly ramming each
     * other or recalculating the same obstructed path.
     */
    private boolean waitForNearbyTrader(ServerLevel server,BlockPos destination){
        Vec3 toward=new Vec3(destination.getX()+.5-getX(),0,
                destination.getZ()+.5-getZ());
        if(toward.lengthSqr()<.25)return false;
        Vec3 heading=toward.normalize();
        for(TraderGolem other:server.getEntitiesOfClass(TraderGolem.class,
                getBoundingBox().inflate(1.45),o->o!=this&&o.isAlive())){
            Vec3 between=new Vec3(other.getX()-getX(),0,other.getZ()-getZ());
            double ahead=heading.dot(between);
            if(ahead<.15||ahead>1.65||between.lengthSqr()>2.9)continue;
            // Allow the trader with the lexicographically smaller UUID to
            // continue. The other waits rather than both pushing forward.
            if(getUUID().compareTo(other.getUUID())>0){
                getNavigation().stop();
                return true;
            }
        }
        return false;
    }

    private void manageApproachDoor(ServerLevel server,Villager villager){
        if(openedDoor!=null)return;
        Vec3 direction=new Vec3(villager.getX()-getX(),0,
                villager.getZ()-getZ());
        if(direction.lengthSqr()<.05)return;
        direction=direction.normalize();
        BlockPos origin=blockPosition();
        BlockPos candidate=null;
        double best=Double.POSITIVE_INFINITY;
        for(int dx=-2;dx<=2;dx++)for(int dy=-1;dy<=1;dy++)
            for(int dz=-2;dz<=2;dz++){
                BlockPos at=origin.offset(dx,dy,dz);
                if(!server.hasChunkAt(at))continue;
                BlockState state=server.getBlockState(at);
                if(!(state.getBlock() instanceof DoorBlock door)
                        ||state.getValue(DoorBlock.OPEN)
                        ||!door.type().canOpenByHand())continue;
                Vec3 rel=new Vec3(at.getX()+.5-getX(),0,
                        at.getZ()+.5-getZ());
                double forward=rel.dot(direction);
                double side=Math.abs(rel.x*direction.z-rel.z*direction.x);
                if(forward<-.3||forward>2.6||side>1.15)continue;
                double score=rel.lengthSqr()+side*2;
                if(score<best){best=score;candidate=at;}
            }
        if(candidate!=null){
            var state=server.getBlockState(candidate);
            if(state.getBlock() instanceof DoorBlock door){
                door.setOpen(this,server,state,candidate,true);
                openedDoor=candidate.immutable();
                doorGraceChecks=0;
                // Immediately recalculate the formerly obstructed path.
                getNavigation().stop();
                noProgressChecks=8;
            }
        }
    }
    private void closeDoorAfterPassing(ServerLevel server){
        if(openedDoor==null)return;
        doorGraceChecks++;
        if(doorGraceChecks<3)return;
        BlockPos doorPos=openedDoor;
        if(distanceToSqr(doorPos.getX()+.5,doorPos.getY()+.5,
                doorPos.getZ()+.5)<6.25)return;
        // Never close a door on another trader entering or leaving.
        if(!server.getEntitiesOfClass(TraderGolem.class,
                new AABB(doorPos).inflate(1.25),
                other->other!=this&&other.isAlive()).isEmpty())return;
        if(server.hasChunkAt(doorPos)){
            var state=server.getBlockState(doorPos);
            if(state.getBlock() instanceof DoorBlock door
                    &&state.getValue(DoorBlock.OPEN))
                door.setOpen(this,server,state,doorPos,false);
        }
        openedDoor=null;
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
