package com.caszgamermd.golemsplus.trader;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.golem.IronGolem;
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
public final class TraderGolem extends IronGolem {
    private BlockPos home = BlockPos.ZERO;
    private UUID owner, order, villagerId;
    private int offerIndex=-1, stage=0, navigationFailures;
    private ItemStack paymentA=ItemStack.EMPTY,paymentB=ItemStack.EMPTY,returning=ItemStack.EMPTY;
    private boolean hasTraded;

    public TraderGolem(EntityType<? extends IronGolem> type, Level world){
        super(type,world);
        setPersistenceRequired();
        setInvulnerable(true); // no free iron drops or accidental cargo loss
        setCustomName(Component.literal("Trader Golem"));
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
    public void begin(UUID order,UUID villager,int offer,ItemStack a,ItemStack b) {
        if(stage!=0 || this.order!=null)throw new IllegalStateException("Trader busy");
        if(order==null||villager==null||offer<0||a.isEmpty())throw new IllegalArgumentException();
        this.order=order;villagerId=villager;offerIndex=offer;
        paymentA=a.copy();paymentB=b.copy();
        returning=ItemStack.EMPTY;hasTraded=false;stage=1;navigationFailures=0;
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
            if(distanceToSqr(home.getX()+.5,home.getY()+.5,home.getZ()+.5)>9)
                getNavigation().moveTo(home.getX()+.5,home.getY()+1,home.getZ()+.5,.8);
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
        if(distanceToSqr(villager)>8){
            if(!getNavigation().moveTo(villager,.87)&&++navigationFailures>15){
                stage=2;depot.setProblem("Cannot reach villager");
            }
            return;
        }
        getNavigation().stop();
        if(villager.isTrading())return; // do not interrupt players trading
        if(!order.equals(depot.activeOrder())){
            stage=2;return;
        }
        var offers=villager.getOffers();
        if(offerIndex<0||offerIndex>=offers.size()){
            stage=2;depot.setProblem("Villager offer changed");return;
        }
        MerchantOffer offer=offers.get(offerIndex);
        ItemStack desired=offer.getResult();
        if(offer.isOutOfStock()||desired.isEmpty()
                ||!TraderWorkService.itemId(desired).equals(depot.wantedItem())
                ||!offer.satisfiedBy(paymentA,paymentB)){
            stage=2;depot.setProblem("Offer changed or out of stock");return;
        }
        Map<String,Integer> costs=TraderWorkService.totalCost(paymentA,paymentB);
        for(var e:costs.entrySet())if(depot.budgetRemaining(e.getKey())<e.getValue()){
            stage=2;depot.setProblem("Spending limit reached");return;
        }
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
        double distance=distanceToSqr(home.getX()+.5,home.getY()+.5,home.getZ()+.5);
        if(distance>9){
            getNavigation().moveTo(home.getX()+.5,home.getY()+1,home.getZ()+.5,.9);
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
        order=null;villagerId=null;offerIndex=-1;hasTraded=false;stage=0;
    }
    private void dropEscrow(ServerLevel level) {
        for(ItemStack stack:new ItemStack[]{paymentA,paymentB,returning})
            if(!stack.isEmpty())
                Containers.dropItemStack(level,getX(),getY(),getZ(),stack.copy());
        paymentA=paymentB=returning=ItemStack.EMPTY;
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
        out.putBoolean("trader_done",hasTraded);
        if(!paymentA.isEmpty())out.store("trader_cost_a",ItemStack.CODEC,paymentA);
        if(!paymentB.isEmpty())out.store("trader_cost_b",ItemStack.CODEC,paymentB);
        if(!returning.isEmpty())out.store("trader_result",ItemStack.CODEC,returning);
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
        hasTraded=in.getBooleanOr("trader_done",false);
        paymentA=in.read("trader_cost_a",ItemStack.CODEC).orElse(ItemStack.EMPTY);
        paymentB=in.read("trader_cost_b",ItemStack.CODEC).orElse(ItemStack.EMPTY);
        returning=in.read("trader_result",ItemStack.CODEC).orElse(ItemStack.EMPTY);
        if(owner==null)stage=2;
    }
}
