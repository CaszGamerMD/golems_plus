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
    private int builderMirror;
    private BlockPos builderOrigin=BlockPos.ZERO;
    private String placedSchematicName="";
    private int builderCursor;
    private UUID builderWorkerId;
    private int crewTarget=1;
    private final java.util.List<UUID> additionalBuilderWorkers=new java.util.ArrayList<>();
    private final java.util.List<UUID> additionalTraderWorkers=new java.util.ArrayList<>();
    private final java.util.Map<UUID,Integer> builderClaims=new java.util.LinkedHashMap<>();
    private long completedAhead;
    private final java.util.Map<UUID,java.util.List<BlockPos>> trackedScaffolds=new java.util.LinkedHashMap<>();
    private boolean builderPlaced;
    private BlockPos beeToolbox,beeOutput;
    private UUID chefWorkerId;
    private final java.util.List<UUID> chefExtras=new java.util.ArrayList<>();
    // Saved kitchen work order and ingredient reservation.
    private BlockPos kitchenLink;
    private String chefFood="";
    private String chefPreviewFood="";
    private final java.util.List<String> chefPreviewLines=new java.util.ArrayList<>();
    private int chefQuantity,chefStepIndex,chefStepTicks;
    private final java.util.List<String> chefSteps=new java.util.ArrayList<>();
    private final java.util.Map<String,Integer> chefEscrow=new java.util.LinkedHashMap<>();
    private final java.util.Map<String,Integer> chefSurplus=new java.util.LinkedHashMap<>();
    private final java.util.Map<String,Integer> chefStock=new java.util.LinkedHashMap<>();
    private final java.util.List<UUID> fisherIds=new java.util.ArrayList<>();
    private int beeRadius=12,beeMode=0;
    private final java.util.List<UUID> beekeeperIds=new java.util.ArrayList<>();
    private UUID supplyRequest;
    private UUID supplyRunner;
    private BlockPos supplyPtp=BlockPos.ZERO;
    private String supplyNotice="";
    private final Map<String,Integer> supplyManifest=new java.util.LinkedHashMap<>();

    public WorkerStationBlockEntity(BlockPos pos, BlockState state) {
        super(WorksiteContent.STATION_ENTITY, pos, state);
    }
    public boolean isCooking(){
        return getBlockState().getBlock() instanceof WorkerStationBlock station
                &&station.specialty()==WorkerSpecialty.COOKING;
    }
    public UUID chefWorkerId(){return chefWorkerId;}
    public java.util.List<UUID> chefWorkerIds(){
        var result=new java.util.ArrayList<UUID>();
        if(chefWorkerId!=null)result.add(chefWorkerId);
        result.addAll(chefExtras);
        return java.util.List.copyOf(result);
    }
    public boolean chefBusy(){return !chefFood.isBlank();}
    public String chefPreviewFood(){return chefPreviewFood;}
    public java.util.List<String> chefPreviewLines(){return java.util.List.copyOf(chefPreviewLines);}
    public void setChefPreview(UUID actor,String food,java.util.List<String> lines){
        if(!isCooking()||!canConfigure(actor)||lines.size()>50)return;
        chefPreviewFood=food;chefPreviewLines.clear();
        for(String line:lines){
            if(line.length()>150)line=line.substring(0,150);
            chefPreviewLines.add(line);
        }
        sync();
    }
    public BlockPos kitchenLink(){return kitchenLink;}
    public boolean setKitchenLink(UUID actor,BlockPos link){
        if(!isCooking()||!canConfigure(actor)||link==null
                ||worldPosition.distSqr(link)>16L*16L)return false;
        kitchenLink=link.immutable();sync();return true;
    }
    public String chefFood(){return chefFood;}
    public int chefQuantity(){return chefQuantity;}
    public int chefStepIndex(){return chefStepIndex;}
    public int chefStepCount(){return chefSteps.size();}
    public String chefCurrentStep(){return chefStepIndex<chefSteps.size()?
            chefSteps.get(chefStepIndex):"Serving";}
    public java.util.Map<String,Integer> chefStock(){return java.util.Map.copyOf(chefStock);}
    public java.util.Map<String,Integer> chefSurplus(){return java.util.Map.copyOf(chefSurplus);}
    public void updateChefStock(java.util.Map<String,Integer> next){
        if(chefStock.equals(next))return;
        chefStock.clear();
        next.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey())
                .limit(80).forEach(e->chefStock.put(e.getKey(),e.getValue()));
        sync();
    }
    public boolean startChefOrder(java.util.UUID actor,String food,int quantity,
            java.util.List<String> steps,java.util.Map<String,Integer> escrow,
            java.util.Map<String,Integer> leftovers){
        if(!isCooking()||!canConfigure(actor)||chefBusy()||quantity<1||quantity>16
                ||steps.isEmpty()||steps.size()>160)return false;
        chefFood=food;chefQuantity=quantity;chefStepIndex=chefStepTicks=0;
        chefSteps.clear();chefSteps.addAll(steps);
        chefEscrow.clear();chefEscrow.putAll(escrow);
        chefSurplus.clear();chefSurplus.putAll(leftovers);
        setProblem("");sync();return true;
    }
    public void tickChefStep(){
        if(!chefBusy())return;
        if(++chefStepTicks>=50){
            chefStepTicks=0;
            chefStepIndex=Math.min(chefStepIndex+1,chefSteps.size());
            sync();
        }else if(chefStepTicks%10==0)setChanged();
    }
    public boolean chefStepsFinished(){return chefBusy()&&chefStepIndex>=chefSteps.size();}
    public java.util.Map<String,Integer> clearChefOrder(){
        java.util.Map<String,Integer> refund=java.util.Map.copyOf(chefEscrow);
        chefFood="";chefQuantity=chefStepIndex=chefStepTicks=0;
        chefSteps.clear();chefEscrow.clear();chefSurplus.clear();sync();
        return refund;
    }
    public void setChefWorkerId(UUID id){
        if(isCooking()){chefWorkerId=id;sync();}
    }
    public boolean isFishing(){
        return getBlockState().getBlock() instanceof WorkerStationBlock station
                &&station.specialty()==WorkerSpecialty.FISHING;
    }
    public java.util.List<UUID> fisherWorkerIds(){
        return java.util.List.copyOf(fisherIds);
    }
    public boolean registerFisher(UUID id){
        if(!isFishing()||id==null||fisherIds.contains(id)||fisherIds.size()>=4)
            return false;
        fisherIds.add(id);sync();return true;
    }
    public boolean isBeekeeper(){
        return getBlockState().getBlock() instanceof WorkerStationBlock station
                &&station.specialty()==WorkerSpecialty.BEEKEEPER;
    }
    public BlockPos beeToolbox(){return beeToolbox;}
    public BlockPos beeOutput(){return beeOutput;}
    public int beeRadius(){return beeRadius;}
    /** 0=both, 1=comb only, 2=honey bottles only */
    public int beeMode(){return beeMode;}
    public java.util.List<UUID> beekeeperWorkers(){return java.util.List.copyOf(beekeeperIds);}
    public boolean registerBeekeeper(UUID id){
        if(!isBeekeeper()||id==null||beekeeperIds.contains(id)
                ||beekeeperIds.size()>=crewTarget)return false;
        beekeeperIds.add(id);sync();return true;
    }
    public boolean setBeeRadius(UUID actor,int radius){
        if(!isBeekeeper()||!canConfigure(actor)||radius<4||radius>24)return false;
        beeRadius=radius;sync();return true;
    }
    public boolean setBeeMode(UUID actor,int mode){
        if(!isBeekeeper()||!canConfigure(actor)||mode<0||mode>2)return false;
        beeMode=mode;sync();return true;
    }
    public boolean setBeeLink(UUID actor,boolean tools,BlockPos at){
        if(!isBeekeeper()||!canConfigure(actor)||at==null
                ||worldPosition.distSqr(at)>64L*64L||at.equals(worldPosition))
            return false;
        if(tools)beeToolbox=at.immutable();else beeOutput=at.immutable();
        sync();return true;
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
    public int builderMirror(){return builderMirror;}
    public BlockPos builderOrigin(){return builderOrigin;}
    public String placedSchematicName(){return placedSchematicName;}
    public int builderCursor(){return builderCursor;}
    public UUID builderWorkerId(){return builderWorkerId;}
    public int crewTarget(){return crewTarget;}
    /**
     * A crafted worker claims one of up to four physical crew slots. Raising
     * the target is only capacity bookkeeping, never permission to spawn a
     * free golem. Previously registered workers are retained on world reload.
     */
    public boolean registerCraftedWorker(WorkerSpecialty specialty,UUID worker){
        if(owner==null||worker==null||specialty==null)return false;
        if(specialty==WorkerSpecialty.COOKING){
            if(!isCooking()||chefWorkerIds().size()>=4)return false;
            if(chefWorkerId==null)chefWorkerId=worker;else chefExtras.add(worker);
            crewTarget=Math.max(crewTarget,chefWorkerIds().size());sync();return true;
        }
        int count=switch(specialty){
            case BUILDER -> isBuilder()?builderWorkerIds().size():4;
            case TRADER -> isTrader()?traderWorkerIds().size():4;
            case BEEKEEPER -> isBeekeeper()?beekeeperWorkers().size():4;
            case FISHING -> isFishing()?fisherWorkerIds().size():4;
            default -> 4;
        };
        if(count>=4)return false;
        crewTarget=Math.max(crewTarget,count+1);
        return switch(specialty) {
            case BUILDER -> registerBuilderWorker(worker);
            case TRADER -> registerTraderWorker(worker);
            case BEEKEEPER -> registerBeekeeper(worker);
            case FISHING -> registerFisher(worker);
            default -> false;
        };
    }

    public boolean setCrewTarget(UUID actor,int count){
        if(!canConfigure(actor)||(!isBuilder()&&!isTrader()&&!isBeekeeper()&&!isFishing()&&!isCooking())||count<1||count>4)return false;
        crewTarget=count;sync();return true;
    }
    public java.util.List<UUID> traderWorkerIds(){
        java.util.List<UUID> ids=new java.util.ArrayList<>();
        if(workerId!=null)ids.add(workerId);
        ids.addAll(additionalTraderWorkers);
        return java.util.List.copyOf(ids);
    }
    public boolean registerTraderWorker(UUID id){
        if(!isTrader()||id==null||traderWorkerIds().contains(id)
                ||traderWorkerIds().size()>=crewTarget)return false;
        if(workerId==null)workerId=id;
        else additionalTraderWorkers.add(id);
        sync();return true;
    }
    public java.util.List<UUID> builderWorkerIds(){
        java.util.List<UUID> ids=new java.util.ArrayList<>();
        if(builderWorkerId!=null)ids.add(builderWorkerId);
        ids.addAll(additionalBuilderWorkers);
        return java.util.List.copyOf(ids);
    }
    public boolean registerBuilderWorker(UUID id){
        if(id==null||builderWorkerIds().contains(id)
                ||builderWorkerIds().size()>=crewTarget)return false;
        if(builderWorkerId==null)builderWorkerId=id;
        else additionalBuilderWorkers.add(id);
        sync();return true;
    }
    public int claimedStep(UUID worker){
        return builderClaims.getOrDefault(worker,-1);
    }
    /** All callbacks execute on the Minecraft server thread. Every index
     * has one owner; the head cursor only moves over confirmed completions. */
    public int claimBuilderStep(UUID worker){
        if(!builderWorkerIds().contains(worker)||activeOrder==null)return -1;
        if(builderClaims.containsKey(worker))return builderClaims.get(worker);
        int next=com.caszgamermd.golemsplus.builder.BuilderCrewSchedule.next(
                builderCursor,builderBlocks,completedAhead,builderClaims.values());
        if(next>=0){builderClaims.put(worker,next);sync();}
        return next;
    }
    public boolean completeBuilderStep(UUID worker,int index){
        if(!builderClaims.containsKey(worker)||builderClaims.get(worker)!=index)
            return false;
        var updated=com.caszgamermd.golemsplus.builder.BuilderCrewSchedule.finish(
                builderCursor,builderBlocks,completedAhead,index);
        builderClaims.remove(worker);
        builderCursor=updated.cursor();
        completedAhead=updated.finishedAhead();
        if(builderCursor>=builderBlocks){
            phase=WorkerPhase.COMPLETE;activeOrder=null;builderClaims.clear();
        }
        sync();return true;
    }
    public java.util.List<BlockPos> scaffoldPositions(UUID worker){
        return java.util.List.copyOf(trackedScaffolds.getOrDefault(worker,java.util.List.of()));
    }
    public boolean isTrackedScaffold(BlockPos pos){
        return trackedScaffolds.values().stream().anyMatch(list->list.contains(pos));
    }
    public boolean trackScaffold(UUID worker,BlockPos pos){
        if(!builderWorkerIds().contains(worker)||isTrackedScaffold(pos)
                ||trackedScaffolds.values().stream().mapToInt(java.util.List::size).sum()>=256)
            return false;
        trackedScaffolds.computeIfAbsent(worker,k->new java.util.ArrayList<>())
                .add(pos.immutable());sync();return true;
    }
    public boolean untrackScaffold(UUID worker,BlockPos pos){
        var owned=trackedScaffolds.get(worker);
        if(owned==null||!owned.remove(pos))return false;
        if(owned.isEmpty())trackedScaffolds.remove(worker);
        sync();return true;
    }

    public UUID supplyRequest(){return supplyRequest;}
    public UUID supplyRunner(){return supplyRunner;}
    public boolean claimSupplyRunner(UUID worker){
        if(!builderWorkerIds().contains(worker)||supplyRequest==null)return false;
        if(supplyRunner==null){supplyRunner=worker;sync();}
        return supplyRunner.equals(worker);
    }
    public BlockPos supplyPtp(){return supplyPtp;}
    public String supplyNotice(){return supplyNotice;}
    public Map<String,Integer> supplyManifest(){return Map.copyOf(supplyManifest);}
    public void prepareSupplyRequest(UUID ticket,Map<String,Integer> manifest){
        if(!isBuilder()||activeOrder==null||supplyRequest!=null
                ||ticket==null||manifest==null||manifest.isEmpty()
                ||manifest.size()>16)throw new IllegalArgumentException("Invalid warehouse request");
        supplyRequest=ticket;supplyRunner=null;
        supplyPtp=BlockPos.ZERO;
        supplyNotice="";
        supplyManifest.clear();supplyManifest.putAll(manifest);sync();
    }
    public void setSupplyPtp(BlockPos output){supplyPtp=output.immutable();sync();}
    public void setSupplyNotice(String notice){
        if(!notice.equals(supplyNotice)){supplyNotice=notice;sync();}
    }
    public void clearSupplyRequest(){
        supplyRequest=null;supplyRunner=null;supplyPtp=BlockPos.ZERO;
        supplyManifest.clear();supplyNotice="";sync();
    }
    public boolean hasPlacedSchematic(){return builderPlaced&&!builderFile.isBlank();}
    public boolean isBuilder(){
        return getBlockState().getBlock() instanceof WorkerStationBlock block
                &&block.specialty()==WorkerSpecialty.BUILDER;
    }
    public boolean setPlacedBuilderPlan(UUID actor,String filename,String display,
            BlockPos origin,int rotation,int mirror,int blocks,int unresolved){
        if(!canConfigure(actor)||!isBuilder()||activeOrder!=null
                ||supplyRequest!=null||filename==null||!filename.startsWith("placed-")
                ||!filename.endsWith(".litematic")
                ||filename.contains("/")||filename.contains("..")||filename.contains("\\")
                ||origin==null||rotation<0||rotation>3||mirror<0||mirror>2
                ||blocks<1||blocks>100000||unresolved<0)return false;
        builderFile=filename;placedSchematicName=display;builderOrigin=origin.immutable();
        builderRotation=rotation*90;builderMirror=mirror;builderBlocks=blocks;
        builderUnresolved=unresolved;builderCursor=0;completedAhead=0;
        builderClaims.clear();builderPlaced=true;
        phase=WorkerPhase.READY;sync();return true;
    }
    public boolean startPlacedBuilder(UUID actor){
        if(!canConfigure(actor)||!isBuilder()||!hasPlacedSchematic()
                ||supplyRequest!=null||builderUnresolved!=0||activeOrder!=null)return false;
        activeOrder=UUID.randomUUID();builderCursor=0;completedAhead=0;
        builderClaims.clear();phase=WorkerPhase.WORKING;sync();return true;
    }
    public void advanceBuilder(){
        if(builderCursor<builderBlocks){
            builderCursor++;setChanged();
            if(builderCursor>=builderBlocks){
                phase=WorkerPhase.COMPLETE;activeOrder=null;sync();
            }
        }
    }
    public void setBuilderWorkerId(UUID id){builderWorkerId=id;sync();}
    public boolean cancelBuilder(UUID actor){
        if(!canConfigure(actor)||!isBuilder())return false;
        activeOrder=null;builderClaims.clear();completedAhead=0;
        phase=WorkerPhase.IDLE;sync();return true;
    }
    public boolean setBuilderFile(UUID actor,String filename,int blocks,int unresolved) {
        if(!canConfigure(actor)||isTrader()||filename==null||filename.isBlank()
                ||blocks<0||unresolved<0||activeOrder!=null)return false;
        builderFile=filename;builderBlocks=blocks;builderUnresolved=unresolved;
        builderPlaced=false;placedSchematicName="";builderCursor=0;
        completedAhead=0;builderClaims.clear();
        phase=WorkerPhase.PLANNING;sync();return true;
    }
    public boolean setBuilderRotation(UUID actor,int degrees) {
        if(!canConfigure(actor)||isTrader()||builderPlaced||(degrees!=0&&degrees!=90
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
        if (!canConfigure(actor)||supplyRequest!=null) return false;
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
        if(isTrader())return Component.literal("Trader Counter");
        if(isBeekeeper())return Component.literal("Beekeeper Workstation");
        if(isCooking())return Component.literal("Caszual Kitchen Ordering Kiosk");
        if(isBuilder())return Component.literal("Builder's Planning Table");
        if(getBlockState().getBlock() instanceof WorkerStationBlock block)
            return Component.literal(block.specialty().name()+" Workstation");
        return Component.literal("Golem Workstation");
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
        return !isBeekeeper()&&!isCooking()&&slot>=0&&slot<INPUT_SLOTS;
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
        if(chefWorkerId!=null)out.putString("cooking_chef_worker",chefWorkerId.toString());
        out.putInt("chef_extras",Math.min(3,chefExtras.size()));
        for(int i=0;i<chefExtras.size()&&i<3;i++)
            out.putString("chef_extra_"+i,chefExtras.get(i).toString());
        if(kitchenLink!=null)
            out.store("chef_kitchen_link",BlockPos.CODEC,kitchenLink);
        out.putString("chef_preview_food",chefPreviewFood);
        out.putInt("chef_preview_size",Math.min(50,chefPreviewLines.size()));
        for(int i=0;i<chefPreviewLines.size()&&i<50;i++)
            out.putString("chef_preview_"+i,chefPreviewLines.get(i));
        out.putString("chef_food",chefFood);
        out.putInt("chef_quantity",chefQuantity);
        out.putInt("chef_step_index",chefStepIndex);
        out.putInt("chef_step_ticks",chefStepTicks);
        out.putInt("chef_step_total",Math.min(160,chefSteps.size()));
        for(int i=0;i<chefSteps.size()&&i<160;i++)
            out.putString("chef_step_"+i,chefSteps.get(i));
        out.putInt("chef_escrow_count",Math.min(80,chefEscrow.size()));
        int ec=0;
        for(var e:chefEscrow.entrySet()){
            if(ec>=80)break;
            out.putString("chef_escrow_id_"+ec,e.getKey());
            out.putInt("chef_escrow_qty_"+ec,e.getValue());ec++;
        }
        out.putInt("chef_surplus_count",Math.min(64,chefSurplus.size()));
        int bc=0;
        for(var e:chefSurplus.entrySet()){
            if(bc>=64)break;
            out.putString("chef_surplus_id_"+bc,e.getKey());
            out.putInt("chef_surplus_qty_"+bc,e.getValue());bc++;
        }
        out.putInt("chef_stock_count",Math.min(80,chefStock.size()));
        int sc=0;
        for(var e:chefStock.entrySet()){
            if(sc>=80)break;
            out.putString("chef_stock_id_"+sc,e.getKey());
            out.putInt("chef_stock_qty_"+sc,e.getValue());sc++;
        }
        out.putInt("fisher_workers",Math.min(4,fisherIds.size()));
        for(int i=0;i<fisherIds.size()&&i<4;i++)
            out.putString("fisher_worker_"+i,fisherIds.get(i).toString());
        if(beeToolbox!=null)out.store("beekeeper_toolbox",BlockPos.CODEC,beeToolbox);
        if(beeOutput!=null)out.store("beekeeper_output",BlockPos.CODEC,beeOutput);
        out.putInt("beekeeper_radius",beeRadius);
        out.putInt("beekeeper_mode",beeMode);
        out.putInt("beekeeper_workers",Math.min(4,beekeeperIds.size()));
        for(int ix=0;ix<beekeeperIds.size()&&ix<4;ix++)
            out.putString("beekeeper_worker_"+ix,beekeeperIds.get(ix).toString());
        out.putInt("trade_count",wantedCount);out.putInt("trade_delivered",deliveredCount);
        out.putString("trade_problem",problem);
        out.putString("builder_schematic",builderFile);
        out.putInt("builder_blocks",builderBlocks);
        out.putInt("builder_unresolved",builderUnresolved);
        out.putInt("builder_rotation",builderRotation);
        out.putInt("builder_mirror",builderMirror);
        out.store("builder_origin",BlockPos.CODEC,builderOrigin);
        out.putString("builder_placement_name",placedSchematicName);
        out.putBoolean("builder_placed",builderPlaced);
        out.putInt("builder_cursor",builderCursor);
        if(builderWorkerId!=null)out.putString("builder_worker",builderWorkerId.toString());
        out.putInt("trader_extra_workers",Math.min(3,additionalTraderWorkers.size()));
        for(int n=0;n<additionalTraderWorkers.size()&&n<3;n++)
            out.putString("trader_extra_worker_"+n,additionalTraderWorkers.get(n).toString());
        out.putInt("builder_crew_target",crewTarget);
        out.putInt("builder_extra_workers",Math.min(3,additionalBuilderWorkers.size()));
        for(int n=0;n<additionalBuilderWorkers.size()&&n<3;n++)
            out.putString("builder_extra_worker_"+n,additionalBuilderWorkers.get(n).toString());
        out.putLong("builder_ahead_bits",completedAhead);
        out.putInt("builder_claim_count",Math.min(4,builderClaims.size()));
        int cix=0;
        for(var entry:builderClaims.entrySet()){
            if(cix>=4)break;
            out.putString("builder_claim_worker_"+cix,entry.getKey().toString());
            out.putInt("builder_claim_index_"+cix,entry.getValue());cix++;
        }
        int scix=0;
        for(var entry:trackedScaffolds.entrySet())
            for(BlockPos pos:entry.getValue()){
                if(scix>=256)break;
                out.putString("builder_scaffold_owner_"+scix,entry.getKey().toString());
                out.store("builder_scaffold_pos_"+scix,BlockPos.CODEC,pos);scix++;
            }
        out.putInt("builder_scaffold_count",scix);
        if(supplyRequest!=null)out.putString("builder_supply_request",supplyRequest.toString());
        if(supplyRunner!=null)out.putString("builder_supply_runner",supplyRunner.toString());
        out.store("builder_supply_ptp",BlockPos.CODEC,supplyPtp);
        out.putString("builder_supply_notice",supplyNotice);
        out.putInt("builder_supply_types",Math.min(16,supplyManifest.size()));
        int supplyIndex=0;
        for(var e:supplyManifest.entrySet()){
            if(supplyIndex>=16)break;
            out.putString("builder_supply_id_"+supplyIndex,e.getKey());
            out.putInt("builder_supply_amount_"+supplyIndex,e.getValue());
            supplyIndex++;
        }
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
        additionalTraderWorkers.clear();
        int traderExtra=Math.clamp(in.getIntOr("trader_extra_workers",0),0,3);
        for(int j=0;j<traderExtra;j++){
            UUID trader=uuid(in,"trader_extra_worker_"+j);
            if(trader!=null&&!trader.equals(workerId)
                    &&!additionalTraderWorkers.contains(trader))
                additionalTraderWorkers.add(trader);
        }
        try{phase=WorkerPhase.valueOf(in.getStringOr("phase","IDLE"));}
        catch(IllegalArgumentException ignored){phase=WorkerPhase.BLOCKED;}
        chefWorkerId=uuid(in,"cooking_chef_worker");
        chefExtras.clear();
        for(int i=0,n=Math.clamp(in.getIntOr("chef_extras",0),0,3);i<n;i++){
            UUID id=uuid(in,"chef_extra_"+i);
            if(id!=null&&!id.equals(chefWorkerId))chefExtras.add(id);
        }
        kitchenLink=in.read("chef_kitchen_link",BlockPos.CODEC).orElse(null);
        chefPreviewFood=in.getStringOr("chef_preview_food","");
        chefPreviewLines.clear();
        for(int i=0,n=Math.clamp(in.getIntOr("chef_preview_size",0),0,50);i<n;i++)
            chefPreviewLines.add(in.getStringOr("chef_preview_"+i,""));
        chefFood=in.getStringOr("chef_food","");
        chefQuantity=Math.clamp(in.getIntOr("chef_quantity",0),0,16);
        chefStepIndex=Math.clamp(in.getIntOr("chef_step_index",0),0,160);
        chefStepTicks=Math.clamp(in.getIntOr("chef_step_ticks",0),0,49);
        chefSteps.clear();
        for(int i=0,n=Math.clamp(in.getIntOr("chef_step_total",0),0,160);i<n;i++)
            chefSteps.add(in.getStringOr("chef_step_"+i,"Prepare"));
        chefStepIndex=Math.min(chefStepIndex,chefSteps.size());
        chefEscrow.clear();
        for(int i=0,n=Math.clamp(in.getIntOr("chef_escrow_count",0),0,80);i<n;i++){
            String id=in.getStringOr("chef_escrow_id_"+i,"");
            int qty=in.getIntOr("chef_escrow_qty_"+i,0);
            if(!id.isBlank()&&qty>0)chefEscrow.put(id,qty);
        }
        chefSurplus.clear();
        for(int i=0,n=Math.clamp(in.getIntOr("chef_surplus_count",0),0,64);i<n;i++){
            String id=in.getStringOr("chef_surplus_id_"+i,"");
            int qty=in.getIntOr("chef_surplus_qty_"+i,0);
            if(!id.isBlank()&&qty>0)chefSurplus.put(id,qty);
        }
        chefStock.clear();
        for(int i=0,n=Math.clamp(in.getIntOr("chef_stock_count",0),0,80);i<n;i++){
            String id=in.getStringOr("chef_stock_id_"+i,"");
            int qty=in.getIntOr("chef_stock_qty_"+i,0);
            if(!id.isBlank()&&qty>0)chefStock.put(id,qty);
        }
        fisherIds.clear();
        int fisherCount=Math.clamp(in.getIntOr("fisher_workers",0),0,4);
        for(int i=0;i<fisherCount;i++){
            UUID id=uuid(in,"fisher_worker_"+i);
            if(id!=null&&!fisherIds.contains(id))fisherIds.add(id);
        }
        beeToolbox=in.read("beekeeper_toolbox",BlockPos.CODEC).orElse(null);
        beeOutput=in.read("beekeeper_output",BlockPos.CODEC).orElse(null);
        beeRadius=Math.clamp(in.getIntOr("beekeeper_radius",12),4,24);
        beeMode=Math.clamp(in.getIntOr("beekeeper_mode",0),0,2);
        beekeeperIds.clear();
        int beeCount=Math.clamp(in.getIntOr("beekeeper_workers",0),0,4);
        for(int i=0;i<beeCount;i++){
            UUID id=uuid(in,"beekeeper_worker_"+i);
            if(id!=null&&!beekeeperIds.contains(id))beekeeperIds.add(id);
        }
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
        builderMirror=Math.clamp(in.getIntOr("builder_mirror",0),0,2);
        builderOrigin=in.read("builder_origin",BlockPos.CODEC).orElse(BlockPos.ZERO);
        placedSchematicName=in.getStringOr("builder_placement_name","");
        builderPlaced=in.getBooleanOr("builder_placed",false);
        builderCursor=Math.clamp(in.getIntOr("builder_cursor",0),0,builderBlocks);
        builderWorkerId=uuid(in,"builder_worker");
        crewTarget=Math.clamp(in.getIntOr("builder_crew_target",1),1,4);
        additionalBuilderWorkers.clear();
        int extra=Math.clamp(in.getIntOr("builder_extra_workers",0),0,3);
        for(int j=0;j<extra;j++){
            UUID id=uuid(in,"builder_extra_worker_"+j);
            if(id!=null&&!additionalBuilderWorkers.contains(id)
                    &&!id.equals(builderWorkerId))additionalBuilderWorkers.add(id);
        }
        completedAhead=in.getLongOr("builder_ahead_bits",0);
        builderClaims.clear();
        int claims=Math.clamp(in.getIntOr("builder_claim_count",0),0,4);
        for(int j=0;j<claims;j++){
            UUID id=uuid(in,"builder_claim_worker_"+j);
            int index=in.getIntOr("builder_claim_index_"+j,-1);
            if(id!=null&&index>=builderCursor&&index<builderBlocks
                    &&index-builderCursor<32&&builderWorkerIds().contains(id))
                builderClaims.put(id,index);
        }
        trackedScaffolds.clear();
        int scaffoldCount=Math.clamp(in.getIntOr("builder_scaffold_count",0),0,256);
        for(int j=0;j<scaffoldCount;j++){
            UUID id=uuid(in,"builder_scaffold_owner_"+j);
            var position=in.read("builder_scaffold_pos_"+j,BlockPos.CODEC);
            if(id!=null&&position.isPresent())
                trackedScaffolds.computeIfAbsent(id,k->new java.util.ArrayList<>())
                        .add(position.get());
        }
        supplyRequest=uuid(in,"builder_supply_request");
        supplyRunner=uuid(in,"builder_supply_runner");
        supplyPtp=in.read("builder_supply_ptp",BlockPos.CODEC).orElse(BlockPos.ZERO);
        supplyNotice=in.getStringOr("builder_supply_notice","");
        supplyManifest.clear();
        int supplies=Math.clamp(in.getIntOr("builder_supply_types",0),0,16);
        for(int i=0;i<supplies;i++){
            String id=in.getStringOr("builder_supply_id_"+i,"");
            int qty=in.getIntOr("builder_supply_amount_"+i,0);
            if(!id.isBlank()&&qty>0&&qty<=4096)supplyManifest.put(id,qty);
        }
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
