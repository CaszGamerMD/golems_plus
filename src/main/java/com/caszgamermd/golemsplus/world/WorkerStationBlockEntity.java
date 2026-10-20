package com.caszgamermd.golemsplus.world;

import com.caszgamermd.golemsplus.trader.VillageSearchArea;
import com.caszgamermd.golemsplus.worker.WorkerPhase;
import com.caszgamermd.golemsplus.worker.WorkerSpecialty;
import com.caszgamermd.golemsplus.warehouse.WarehouseAssignment;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.menu.v1.ExtendedMenuProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.jspecify.annotations.Nullable;

/**
 * One server-owned depot. Slots 0..26 = payments/materials, 27..35 =
 * acquired items. The worksite and the worker never own the same stacks.
 */
public final class WorkerStationBlockEntity extends BlockEntity
        implements Container, ExtendedMenuProvider<BlockPos> {
    public static final int INPUT_SLOTS = 27, SLOTS = 36;
    private NonNullList<ItemStack> items = NonNullList.withSize(SLOTS, ItemStack.EMPTY);
    private UUID owner, activeOrder, workerId;
    private WorkerPhase phase = WorkerPhase.IDLE;
    private WarehouseAssignment warehouse;
    private VillageSearchArea village;
    private String wantedItem = "";
    private int wantedCount, deliveredCount;
    private final Map<String,Integer> budgets = new HashMap<>();
    private final Map<String,Integer> spent = new HashMap<>();
    private String problem = "";
    private String builderFile = "";
    private int builderBlocks, builderUnresolved;
    private int builderRotation;

    public WorkerStationBlockEntity(BlockPos pos, BlockState state) {
        super(WorksiteContent.STATION_ENTITY, pos, state);
    }
    public boolean isTrader() {
        return getBlockState().getBlock() instanceof WorkerStationBlock station
                && station.specialty() == WorkerSpecialty.TRADER;
    }
    public UUID owner() { return owner; }
    public UUID activeOrder() { return activeOrder; }
    public UUID workerId() { return workerId; }
    public WorkerPhase phase() { return phase; }
    public WarehouseAssignment warehouse() { return warehouse; }
    public VillageSearchArea village() { return village; }
    public String wantedItem() { return wantedItem; }
    public int wantedCount() { return wantedCount; }
    public int deliveredCount() { return deliveredCount; }
    public String problem() { return problem; }
    public String builderFile() { return builderFile; }
    public int builderBlocks() { return builderBlocks; }
    public int builderUnresolved() { return builderUnresolved; }
    public int builderRotation() { return builderRotation; }
    public boolean setBuilderFile(UUID actor,String filename,int blocks,int unresolved) {
        if(!canConfigure(actor)||isTrader()||filename==null||filename.isBlank()
                ||blocks<0||unresolved<0)return false;
        builderFile=filename;builderBlocks=blocks;builderUnresolved=unresolved;
        phase=WorkerPhase.PLANNING;sync();return true;
    }
    public boolean setBuilderRotation(UUID actor,int degrees) {
        if(!canConfigure(actor)||isTrader()||(degrees!=0&&degrees!=90
                &&degrees!=180&&degrees!=270))return false;
        builderRotation=degrees;sync();return true;
    }
    public Map<String,Integer> budgets() { return Map.copyOf(budgets); }
    public Map<String,Integer> spent() { return Map.copyOf(spent); }
    public int budgetRemaining(String item) {
        return Math.max(0,budgets.getOrDefault(item,0)-spent.getOrDefault(item,0));
    }
    public void setWorkerId(UUID id) { workerId = id; sync(); }
    public boolean canConfigure(UUID actor) { return actor != null && actor.equals(owner); }
    public boolean claimIfUnowned(UUID actor) {
        if (owner != null) return owner.equals(actor);
        if (actor == null) return false;
        owner = actor; sync(); return true;
    }
    public boolean setWarehouse(UUID actor, WarehouseAssignment selection) {
        if (!canConfigure(actor)) return false;
        warehouse = selection; sync(); return true;
    }
    public boolean setVillage(UUID actor, VillageSearchArea selection) {
        if (!canConfigure(actor) || !isTrader()) return false;
        village = selection; sync(); return true;
    }
    public boolean setTradeOrder(UUID actor,String item,int count) {
        if (!canConfigure(actor) || !isTrader() || item == null || item.isBlank()
                || count < 1 || count > 4096 || activeOrder != null) return false;
        wantedItem=item; wantedCount=count; deliveredCount=0;
        budgets.clear(); spent.clear(); problem="";
        activeOrder=UUID.randomUUID();
        phase=WorkerPhase.PLANNING; sync(); return true;
    }
    public boolean setBudget(UUID actor,String item,int max) {
        if (!canConfigure(actor) || !isTrader() || activeOrder==null
                || item==null || item.isBlank() || max<1 || max>65536) return false;
        if (max < spent.getOrDefault(item,0)) return false;
        budgets.put(item,max); sync(); return true;
    }
    public void recordTrade(Map<String,Integer> payments) {
        for (var e: payments.entrySet()) {
            if (e.getValue()<0 || e.getValue() > budgetRemaining(e.getKey()))
                throw new IllegalArgumentException("Trade payment exceeds authorized budget");
        }
        payments.forEach((id,count)->spent.merge(id,count,Integer::sum));
        sync();
    }
    public void recordDelivered(int count) {
        if (count<0) throw new IllegalArgumentException("Invalid delivery");
        deliveredCount=Math.min(wantedCount,Math.addExact(deliveredCount,count));
        if (deliveredCount>=wantedCount) phase=WorkerPhase.COMPLETE;
        sync();
    }
    public void setProblem(String reason) {
        String next=reason==null?"":reason;
        if (!next.equals(problem)) { problem=next; sync(); }
    }
    public boolean beginOrder(UUID actor,UUID newOrder) {
        if (!canConfigure(actor) || newOrder==null || activeOrder!=null || phase==WorkerPhase.PAUSED)
            return false;
        activeOrder=newOrder; phase=WorkerPhase.PLANNING; sync(); return true;
    }
    public boolean cancelOrder(UUID actor) {
        if (!canConfigure(actor)) return false;
        clearOrder();return true;
    }
    public void setPhase(WorkerPhase next) {
        if (next==null) throw new IllegalArgumentException("Missing phase");
        if (phase!=next) { phase=next; sync(); }
    }
    public void clearOrder() {
        activeOrder=null; wantedItem=""; wantedCount=0; deliveredCount=0;
        budgets.clear(); spent.clear(); phase=WorkerPhase.IDLE; problem="";
        sync();
    }
    public void sync() {
        setChanged();
        if (level!=null) {
            BlockState state=getBlockState();
            level.sendBlockUpdated(worldPosition,state,state,3);
        }
    }

    @Override public Component getDisplayName() {
        return Component.literal(isTrader()?"Trader Counter":"Golem Workstation");
    }
    @Override public @Nullable AbstractContainerMenu createMenu(int id,Inventory inv,Player player) {
        return canConfigure(player.getUUID()) ? new WorkerStationMenu(id,inv,this) : null;
    }
    @Override public BlockPos getScreenOpeningData(ServerPlayer player) {return worldPosition;}
    @Override public int getContainerSize() {return SLOTS;}
    @Override public boolean isEmpty() {return items.stream().allMatch(ItemStack::isEmpty);}
    @Override public ItemStack getItem(int slot) {
        return slot>=0&&slot<SLOTS?items.get(slot):ItemStack.EMPTY;
    }
    @Override public ItemStack removeItem(int slot,int amount) {
        if(slot<0||slot>=SLOTS||amount<=0)return ItemStack.EMPTY;
        ItemStack stack=ContainerHelper.removeItem(items,slot,amount);
        if(!stack.isEmpty())sync();
        return stack;
    }
    @Override public ItemStack removeItemNoUpdate(int slot) {
        if(slot<0||slot>=SLOTS)return ItemStack.EMPTY;
        ItemStack stack=ContainerHelper.takeItem(items,slot);
        if(!stack.isEmpty())sync();
        return stack;
    }
    @Override public void setItem(int slot,ItemStack stack) {
        if(slot<0||slot>=SLOTS)return;
        items.set(slot,stack.isEmpty()?ItemStack.EMPTY:stack.copy());
        sync();
    }
    @Override public boolean canPlaceItem(int slot,ItemStack stack) {
        return slot>=0&&slot<INPUT_SLOTS;
    }
    @Override public void clearContent() {
        items=NonNullList.withSize(SLOTS,ItemStack.EMPTY);sync();
    }
    @Override public boolean stillValid(Player player) {
        return Container.stillValidBlockEntity(this,player,6.0F) && canConfigure(player.getUUID());
    }
    /** Add exact real results, without changing input slots or destroying overflow. */
    public boolean canReceive(ItemStack result) {
        if(result.isEmpty())return false;
        int left=result.getCount();
        for(int i=INPUT_SLOTS;i<SLOTS;i++){
            ItemStack previous=items.get(i);
            if(previous.isEmpty())left-=result.getMaxStackSize();
            else if(ItemStack.isSameItemSameComponents(previous,result))
                left-=Math.max(0,previous.getMaxStackSize()-previous.getCount());
            if(left<=0)return true;
        }
        return false;
    }
    public boolean deposit(ItemStack result) {
        if(!canReceive(result))return false;
        int left=result.getCount();
        for(int i=INPUT_SLOTS;i<SLOTS&&left>0;i++){
            ItemStack previous=items.get(i);
            if(previous.isEmpty()){
                int take=Math.min(result.getMaxStackSize(),left);
                items.set(i,result.copyWithCount(take));left-=take;
            } else if(ItemStack.isSameItemSameComponents(previous,result)){
                int take=Math.min(previous.getMaxStackSize()-previous.getCount(),left);
                if(take>0){previous.grow(take);left-=take;}
            }
        }
        sync();return true;
    }
    /** Non-mutating check for payment coverage, potentially across several slots. */
    public boolean containsPayment(ItemStack required) {
        if(required.isEmpty())return true;
        int left=required.getCount();
        for(int i=0;i<INPUT_SLOTS;i++){
            ItemStack stack=items.get(i);
            if(ItemStack.isSameItemSameComponents(stack,required))left-=stack.getCount();
            if(left<=0)return true;
        }
        return false;
    }
    /** Requires prior full validation. Does not change any other slot. */
    public ItemStack takePayment(ItemStack required) {
        if(required.isEmpty())return ItemStack.EMPTY;
        if(!containsPayment(required))return ItemStack.EMPTY;
        int left=required.getCount();
        for(int i=0;i<INPUT_SLOTS&&left>0;i++){
            ItemStack stack=items.get(i);
            if(!ItemStack.isSameItemSameComponents(stack,required))continue;
            int used=Math.min(left,stack.getCount());
            stack.shrink(used);left-=used;
            if(stack.isEmpty())items.set(i,ItemStack.EMPTY);
        }
        sync();
        return required.copy();
    }
    @Override protected void saveAdditional(ValueOutput out){
        super.saveAdditional(out);
        if(owner!=null)out.putString("owner",owner.toString());
        if(activeOrder!=null)out.putString("active_order",activeOrder.toString());
        if(workerId!=null)out.putString("trader_worker",workerId.toString());
        out.putString("phase",phase.name());out.putString("trade_wanted",wantedItem);
        out.putInt("trade_count",wantedCount);out.putInt("trade_delivered",deliveredCount);
        out.putString("trade_problem",problem);
        out.putString("builder_schematic",builderFile);
        out.putInt("builder_blocks",builderBlocks);
        out.putInt("builder_unresolved",builderUnresolved);
        out.putInt("builder_rotation",builderRotation);
        out.putInt("budget_size",Math.min(64,budgets.size()));
        int i=0;
        for(var e:budgets.entrySet()){
            if(i>=64)break;
            out.putString("budget_item_"+i,e.getKey());
            out.putInt("budget_max_"+i,e.getValue());
            out.putInt("budget_spent_"+i,spent.getOrDefault(e.getKey(),0));
            i++;
        }
        if(village!=null){
            out.putString("village_dimension",village.dimension());
            out.putInt("village_x",village.x());out.putInt("village_y",village.y());
            out.putInt("village_z",village.z());out.putInt("village_radius",village.radius());
        }
        if(warehouse!=null){
            out.putString("warehouse_dimension",warehouse.dimension());
            out.putInt("warehouse_x",warehouse.x());out.putInt("warehouse_y",warehouse.y());
            out.putInt("warehouse_z",warehouse.z());
        }
        ContainerHelper.saveAllItems(out,items);
    }
    private static UUID uuid(ValueInput in,String field){
        try{return UUID.fromString(in.getStringOr(field,""));}
        catch(IllegalArgumentException ex){return null;}
    }
    @Override protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        owner=uuid(in,"owner");activeOrder=uuid(in,"active_order");
        workerId=uuid(in,"trader_worker");
        try{phase=WorkerPhase.valueOf(in.getStringOr("phase","IDLE"));}
        catch(IllegalArgumentException ignored){phase=WorkerPhase.BLOCKED;}
        wantedItem=in.getStringOr("trade_wanted","");
        wantedCount=Math.clamp(in.getIntOr("trade_count",0),0,4096);
        deliveredCount=Math.clamp(in.getIntOr("trade_delivered",0),0,wantedCount);
        problem=in.getStringOr("trade_problem","");
        builderFile=in.getStringOr("builder_schematic","");
        builderBlocks=Math.max(0,in.getIntOr("builder_blocks",0));
        builderUnresolved=Math.max(0,in.getIntOr("builder_unresolved",0));
        builderRotation=in.getIntOr("builder_rotation",0);
        if(builderRotation!=0&&builderRotation!=90&&builderRotation!=180
                &&builderRotation!=270)builderRotation=0;
        budgets.clear();spent.clear();
        int n=Math.clamp(in.getIntOr("budget_size",0),0,64);
        for(int i=0;i<n;i++){
            String key=in.getStringOr("budget_item_"+i,"");
            int limit=Math.clamp(in.getIntOr("budget_max_"+i,0),0,65536);
            int used=Math.clamp(in.getIntOr("budget_spent_"+i,0),0,limit);
            if(!key.isBlank()&&limit>0){budgets.put(key,limit);spent.put(key,used);}
        }
        String dim=in.getStringOr("village_dimension","");
        village=null;
        if(!dim.isBlank())try{
            village=new VillageSearchArea(dim,in.getIntOr("village_x",0),
                    in.getIntOr("village_y",0),in.getIntOr("village_z",0),
                    in.getIntOr("village_radius",32));
        }catch(IllegalArgumentException ignored){}
        dim=in.getStringOr("warehouse_dimension","");
        warehouse=dim.isBlank()?null:new WarehouseAssignment(dim,in.getIntOr("warehouse_x",0),
                in.getIntOr("warehouse_y",0),in.getIntOr("warehouse_z",0));
        items=NonNullList.withSize(SLOTS,ItemStack.EMPTY);
        ContainerHelper.loadAllItems(in,items);
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider p){return saveWithoutMetadata(p);}
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket(){
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
