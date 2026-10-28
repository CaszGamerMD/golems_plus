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
    private BlockPos builderMaterialStorage;
    // Keep the deleted job's physical chest available for unloading the
    // departing crew, even after its saved job entry is gone.
    private BlockPos builderRetirementStorage;
    private final java.util.Map<String,Long> builderRequired=new java.util.LinkedHashMap<>();
    private final java.util.Map<String,Long> builderRemaining=new java.util.LinkedHashMap<>();
    private final java.util.Map<String,Integer> builderWarehouseStock=new java.util.LinkedHashMap<>();
    private final java.util.Map<String,Integer> builderStagedStock=new java.util.LinkedHashMap<>();
    private WarehouseAssignment pendingBuilderWarehouse;
    private boolean builderStockChecked;
    private boolean builderIgnorePlants;
    private String builderWarehouseName="";
    public record BuilderJob(String file,String name,BlockPos origin,int rotation,
            int mirror,int blocks,int unsupported,int cursor,
            Map<String,Long> required,Map<String,Long> remaining,
            WarehouseAssignment warehouse,BlockPos storage,String phase,
            boolean ignorePlants){
        public BuilderJob{
            required=Map.copyOf(required);remaining=Map.copyOf(remaining);
        }
        // Older callers/saved job formats default to normal vegetation
        // placement. Existing worlds retain their previous behavior.
        public BuilderJob(String file,String name,BlockPos origin,int rotation,
                int mirror,int blocks,int unsupported,int cursor,
                Map<String,Long> required,Map<String,Long> remaining,
                WarehouseAssignment warehouse,BlockPos storage,String phase){
            this(file,name,origin,rotation,mirror,blocks,unsupported,cursor,
                    required,remaining,warehouse,storage,phase,false);
        }
    }
    private final java.util.List<BuilderJob> builderJobs=new java.util.ArrayList<>();
    private int selectedBuilderJob=-1;
    private String placedSchematicName="";
    private int builderCursor;
    private int builderClearCursor;
    // Separate claims let eight Builders clear different schematic cells
    // without advancing the saved contiguous verification cursor early.
    private final Map<UUID,Integer> builderClearClaims=new HashMap<>();
    private final Map<UUID,Long> builderClearClaimTick=new HashMap<>();
    private final java.util.Set<Integer> builderClearVerified=new java.util.HashSet<>();
    private final java.util.Map<String,Integer> builderClearByFile=
            new java.util.HashMap<>();
    private final java.util.Map<Integer,Long> builderMissingRetries=
            new java.util.HashMap<>();
    private UUID builderWorkerId;
    private boolean builderRecallRequested;
    // Saved chunk positions of actual crafted Builders, never substitute entities.
    // Allows an active Planner to reload a temporarily absent crew member.
    private final Map<UUID,BlockPos> builderLastPositions=new HashMap<>();
    private int crewTarget=1;
    private final java.util.List<UUID> additionalBuilderWorkers=new java.util.ArrayList<>();
    private final java.util.List<UUID> additionalTraderWorkers=new java.util.ArrayList<>();
    private final java.util.Map<UUID,Integer> builderClaims=new java.util.LinkedHashMap<>();
    /** Persistent, disjoint ten-coordinate work orders. No worker must
     * walk to the Planning Table to request an order: the block entity
     * assigns coordinates on the server when a Builder becomes available.
     */
    private final Map<UUID,java.util.List<Integer>> builderWorkOrders=new java.util.LinkedHashMap<>();
    // Work orders now stage up to 32 adjacent tasks out of each worker's
    // COMPLETE precompiled zone plan. They do not create new assignments.
    private static final int BUILDER_STAGE_SIZE=32;
    private long builderProgressNotifyTick=-1;
    private final java.util.Set<UUID> builderPreparedOrders=new java.util.HashSet<>();
    // True once the current job has used strict X/Z crew ownership.
    private boolean builderZoned;
    /** Out-of-order completion beyond the previous 32-step bit window.
     * This permits eight distinct ten-coordinate crew assignments.
     */
    private final java.util.Set<Integer> builderFinishedSteps=new java.util.HashSet<>();
    private long completedAhead;
    private final java.util.Map<UUID,java.util.List<BlockPos>> trackedScaffolds=new java.util.LinkedHashMap<>();
    private boolean builderPlaced;
    private BlockPos beeToolbox,beeOutput;
    private BlockPos fisherOutput,fishingSpot;
    private boolean fishingBuoy;
    private UUID chefWorkerId;
    private final java.util.List<UUID> chefExtras=new java.util.ArrayList<>();
    // Saved kitchen work order and ingredient reservation.
    private BlockPos kitchenLink;
    private String chefFood="";
    private boolean chefOvenLoaded;
    private boolean chefDeliveryStarted;
    // Delivery happens ONCE before cookware restoration. Keeping this flag
    // on disk prevents a full pantry from duplicating an already served meal.
    private boolean chefMealServed;
    private String chefPreviewFood="";
    private final java.util.List<String> chefPreviewLines=new java.util.ArrayList<>();
    private int chefQuantity,chefStepIndex,chefStepTicks;
    private final java.util.List<String> chefSteps=new java.util.ArrayList<>();
    private final java.util.Map<String,Integer> chefEscrow=new java.util.LinkedHashMap<>();
    private final java.util.Map<String,Integer> chefSurplus=new java.util.LinkedHashMap<>();
    private final java.util.List<com.caszgamermd.golemsplus.cooking.KitchenSupplies.Origin>
            chefOrigins=new java.util.ArrayList<>();
    // Fluid-backed CFB milk jars do not produce physical empty buckets or
    // bottles. Only originally withdrawn physical milk containers can do so.
    private final java.util.Map<String,Integer> physicalMilkRemaining=new java.util.HashMap<>();
    private final java.util.Map<String,Integer> chefStock=new java.util.LinkedHashMap<>();
    private final java.util.List<UUID> fisherIds=new java.util.ArrayList<>();
    private int beeRadius=12,beeMode=0;
    private final java.util.List<UUID> beekeeperIds=new java.util.ArrayList<>();
    private UUID supplyRequest;
    private boolean builderCancelShipment;
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
        if(!isCooking()||!canConfigure(actor)||lines.size()>180)return;
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
            chefSteps.get(chefStepIndex).split("\\|",2)[0]:"Serving";}
    public String chefStepData(){return chefStepIndex<chefSteps.size()?
            chefSteps.get(chefStepIndex):"";}
    public int chefWorkTicks(){return chefStepTicks;}
    public java.util.Map<String,Integer> chefReserved(){return java.util.Map.copyOf(chefEscrow);}
    public java.util.List<com.caszgamermd.golemsplus.cooking.KitchenSupplies.Origin>
            chefOrigins(){return java.util.List.copyOf(chefOrigins);}
    public void setChefOrigins(java.util.List<com.caszgamermd.golemsplus.cooking.KitchenSupplies.Origin> origins){
        if(!isCooking()||!chefBusy())return;
        chefOrigins.clear();chefOrigins.addAll(origins.stream().limit(256).toList());
        physicalMilkRemaining.clear();
        for(var origin:chefOrigins){
            var key=net.minecraft.resources.Identifier.tryParse(origin.itemId());
            if(key!=null&&net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(key)){
                var item=net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(key);
                if(com.caszgamermd.golemsplus.cooking.ChefCraftingRemainders
                        .isVirtualMilkCandidate(item))
                    physicalMilkRemaining.merge(origin.itemId(),origin.quantity(),Integer::sum);
            }
        }
        sync();
    }
    public void progressChef(){
        if(!chefBusy()||chefStepIndex>=chefSteps.size())return;
        if(++chefStepTicks%10==0)sync();
        else setChanged();
    }
    /**
     * A completed step atomically consumes its reserved ingredient stacks
     * and moves the real recipe output into the job's sealed work buffer.
     * Job state is persisted, and never reads a player's inventory.
     */
    public boolean completeChefWork(com.caszgamermd.golemsplus.cooking.ChefRecipePlanner.WorkStep step){
        if(!chefBusy()||chefStepIndex>=chefSteps.size())return false;
        for(var e:step.inputs().entrySet()){
            String id=net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(e.getKey()).toString();
            if(chefEscrow.getOrDefault(id,0)<e.getValue())return false;
        }
        for(var e:step.inputs().entrySet()){
            String id=net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(e.getKey()).toString();
            int remains=chefEscrow.get(id)-e.getValue();
            if(remains==0)chefEscrow.remove(id);else chefEscrow.put(id,remains);
        }
        // Real recipe remainders are reusable tools and containers.
        // Track them in escrow exactly once per actually completed step.
        if(step.method().equals("Assemble"))
            for(var entry:step.inputs().entrySet()){
                ItemStack remains=com.caszgamermd.golemsplus.cooking.ChefCraftingRemainders
                        .reusableUtensil(entry.getKey())?
                            new ItemStack(entry.getKey()):
                            com.caszgamermd.golemsplus.cooking.ChefCraftingRemainders
                                    .forIngredient(entry.getKey());
                int sourceCount=entry.getValue();
                // Some milk inputs came from fluid-backed Cow/Milk Jars:
                // their fluid is real, but no physical milk bucket/bottle
                // entered the kitchen, so none may be invented as a remainder.
                if(com.caszgamermd.golemsplus.cooking.ChefCraftingRemainders
                        .isVirtualMilkCandidate(entry.getKey())){
                    String source=net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .getKey(entry.getKey()).toString();
                    int available=physicalMilkRemaining.getOrDefault(source,0);
                    sourceCount=Math.min(sourceCount,available);
                    if(sourceCount==available)physicalMilkRemaining.remove(source);
                    else physicalMilkRemaining.put(source,available-sourceCount);
                }
                if(!remains.isEmpty()&&sourceCount>0){
                    String key=net.minecraft.core.registries.BuiltInRegistries.ITEM
                            .getKey(remains.getItem()).toString();
                    chefEscrow.merge(key,remains.getCount()*sourceCount,Integer::sum);
                }
            }
        String out=net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(step.result()).toString();
        chefEscrow.merge(out,step.resultCount(),Integer::sum);
        chefStepIndex++;chefStepTicks=0;sync();return true;
    }
    public boolean finishChefNonRecipe(){
        if(!chefBusy()||chefStepIndex>=chefSteps.size())return false;
        chefStepIndex++;chefStepTicks=0;sync();return true;
    }
    /** Record transfer of reserved raw food into a real oven. */
    public boolean loadChefOven(com.caszgamermd.golemsplus.cooking.ChefRecipePlanner.WorkStep step){
        if(!chefBusy()||chefOvenLoaded||chefStepIndex>=chefSteps.size())return false;
        for(var e:step.inputs().entrySet()){
            String id=net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(e.getKey()).toString();
            if(chefEscrow.getOrDefault(id,0)<e.getValue())return false;
        }
        for(var e:step.inputs().entrySet()){
            String id=net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(e.getKey()).toString();
            int remaining=chefEscrow.get(id)-e.getValue();
            if(remaining==0)chefEscrow.remove(id);
            else chefEscrow.put(id,remaining);
        }
        chefOvenLoaded=true;sync();return true;
    }
    /** The output is retrieved from the oven, not fabricated by a timer. */
    public boolean collectChefOven(com.caszgamermd.golemsplus.cooking.ChefRecipePlanner.WorkStep step){
        if(!chefBusy()||!chefOvenLoaded||chefStepIndex>=chefSteps.size())return false;
        chefEscrow.merge(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(step.result()).toString(),
                step.resultCount(),Integer::sum);
        chefOvenLoaded=false;
        chefStepIndex++;chefStepTicks=0;sync();return true;
    }
    public boolean chefDeliveryStarted(){return chefDeliveryStarted;}
    public boolean chefMealServed(){return chefMealServed;}
    public boolean markChefMealServed(){
        if(!chefBusy()||chefMealServed||!chefStepsFinished())return false;
        String key=chefFood;
        int available=chefEscrow.getOrDefault(key,0);
        if(available<chefQuantity)return false;
        if(available==chefQuantity)chefEscrow.remove(key);
        else chefEscrow.put(key,available-chefQuantity);
        chefMealServed=true;sync();return true;
    }
    public void setChefDeliveryStarted(boolean started){
        if(chefDeliveryStarted!=started){chefDeliveryStarted=started;sync();}
    }
    public boolean chefOvenLoaded(){return chefOvenLoaded;}
    public void setChefOvenLoaded(boolean loaded){
        if(chefOvenLoaded==loaded)return;
        chefOvenLoaded=loaded;sync();
    }
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
        if(!isCooking()||!canConfigure(actor)||chefBusy()||!chefEscrow.isEmpty()
                ||quantity<1||quantity>16
                ||steps.isEmpty()||steps.size()>160)return false;
        chefFood=food;chefQuantity=quantity;chefStepIndex=chefStepTicks=0;
        chefOvenLoaded=false;chefDeliveryStarted=false;chefMealServed=false;
        chefSteps.clear();chefSteps.addAll(steps);
        chefEscrow.clear();chefEscrow.putAll(escrow);
        chefSurplus.clear();chefSurplus.putAll(leftovers);
        chefOrigins.clear();physicalMilkRemaining.clear();
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
        chefOvenLoaded=false;chefDeliveryStarted=false;chefMealServed=false;
        chefSteps.clear();chefEscrow.clear();chefSurplus.clear();
        chefOrigins.clear();physicalMilkRemaining.clear();sync();
        return refund;
    }
    public void setChefWorkerId(UUID id){
        if(isCooking()){chefWorkerId=id;sync();}
    }
    public boolean isFishing(){
        return getBlockState().getBlock() instanceof WorkerStationBlock station
                &&station.specialty()==WorkerSpecialty.FISHING;
    }
    public BlockPos fisherOutput(){return fisherOutput;}
    public BlockPos fishingSpot(){return fishingSpot;}
    public boolean fishingBuoy(){return fishingBuoy;}
    public boolean setFisherOutput(UUID actor,BlockPos output){
        if(!isFishing()||!canConfigure(actor)||output==null
                ||worldPosition.distSqr(output)>64L*64L)return false;
        fisherOutput=output.immutable();sync();return true;
    }
    public boolean setFishingSpot(UUID actor,BlockPos location,boolean buoy){
        if(!isFishing()||!canConfigure(actor)||location==null
                ||worldPosition.distSqr(location)>128L*128L)return false;
        fishingSpot=location.immutable();fishingBuoy=buoy;sync();return true;
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
        if(tools)return false; // bag inventory replaces the removed toolbox link
        beeOutput=at.immutable();
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
    public BlockPos builderMaterialStorage(){return builderMaterialStorage;}
    public BlockPos builderUnloadingStorage(){
        return builderRecallRequested&&builderRetirementStorage!=null
                ?builderRetirementStorage:builderMaterialStorage;
    }
    public Map<String,Long> builderRequired(){return Map.copyOf(builderRequired);}
    public Map<String,Long> builderRemaining(){return Map.copyOf(builderRemaining);}
    public Map<String,Integer> builderWarehouseStock(){return Map.copyOf(builderWarehouseStock);}
    public Map<String,Integer> builderStagedStock(){return Map.copyOf(builderStagedStock);}
    public void updateBuilderStagedStock(Map<String,Integer> stock){
        if(!isBuilder()||builderStagedStock.equals(stock))return;
        builderStagedStock.clear();builderStagedStock.putAll(stock);sync();
    }
    public boolean builderStockChecked(){return builderStockChecked;}
    public String builderWarehouseName(){return builderWarehouseName;}
    public void updateBuilderWarehouseName(String name){
        String resolved=name==null?"":name;
        if(!builderWarehouseName.equals(resolved)){
            builderWarehouseName=resolved;sync();
        }
    }
    public java.util.List<BuilderJob> builderJobs(){return java.util.List.copyOf(builderJobs);}
    public int selectedBuilderJob(){return selectedBuilderJob;}
    public boolean builderIgnorePlants(){return builderIgnorePlants;}
    /** Job-specific, can be changed even while an existing build is
     * blocked on an unsupported tall flower. We retain all indexed
     * assignments; the new schematic cache marks only plant steps no-op.
     */
    public boolean setBuilderIgnorePlants(UUID actor,boolean enabled){
        if(!isBuilder()||!canConfigure(actor)||!hasPlacedSchematic())return false;
        if(builderIgnorePlants==enabled)return true;
        builderIgnorePlants=enabled;
        if(selectedBuilderJob>=0&&selectedBuilderJob<builderJobs.size())
            builderJobs.set(selectedBuilderJob,snapshotBuilderJob());
        // Existing packed shulkers belong to the old manifest. Request
        // safe warehouse cancellation instead of deleting physical cargo;
        // the normal job tick will resend current material demand.
        if(supplyRequest!=null)requestBuilderShipmentCancellation();
        sync();return true;
    }
    /** Reconcile material summary with a new optional placement filter,
     * preserving how many real blocks were already constructed.
     */
    public void reconcileBuilderMaterials(Map<String,Long> updated){
        if(!isBuilder()||updated==null)return;
        var before=new java.util.LinkedHashMap<>(builderRequired);
        var pending=new java.util.LinkedHashMap<>(builderRemaining);
        builderRequired.clear();builderRemaining.clear();
        for(var e:updated.entrySet()){
            if(e.getKey()==null||e.getValue()==null||e.getValue()<=0)continue;
            long completed=Math.max(0,before.getOrDefault(e.getKey(),0L)
                    -pending.getOrDefault(e.getKey(),0L));
            builderRequired.put(e.getKey(),e.getValue());
            builderRemaining.put(e.getKey(),Math.max(0,e.getValue()-completed));
        }
        builderWarehouseStock.clear();builderStockChecked=false;
        if(selectedBuilderJob>=0&&selectedBuilderJob<builderJobs.size())
            builderJobs.set(selectedBuilderJob,snapshotBuilderJob());
        sync();
    }
    public boolean setBuilderMaterialStorage(UUID actor,BlockPos target){
        if(!isBuilder()||!canConfigure(actor)||target==null
                ||builderOrigin.distSqr(target)>128L*128L)return false;
        builderMaterialStorage=target.immutable();sync();return true;
    }
    public void setBuilderWarehouseStock(Map<String,Integer> stock,boolean verified){
        if(!isBuilder())return;
        if(!verified){
            if(builderStockChecked){builderStockChecked=false;builderWarehouseStock.clear();sync();}
            return;
        }
        java.util.Map<String,Integer> normalized=new java.util.LinkedHashMap<>();
        for(var entry:stock.entrySet()){
            if(builderRemaining.containsKey(entry.getKey())&&entry.getValue()!=null)
                normalized.put(entry.getKey(),Math.max(0,entry.getValue()));
        }
        if(!builderStockChecked||!builderWarehouseStock.equals(normalized)){
            builderStockChecked=true;builderWarehouseStock.clear();
            builderWarehouseStock.putAll(normalized);sync();
        }
    }
    public void setBuilderMaterials(java.util.Map<String,Long> materials){
        if(!isBuilder()||materials==null)return;
        builderRequired.clear();builderRemaining.clear();
        materials.entrySet().stream().filter(e->e.getKey()!=null&&e.getValue()!=null
                &&e.getValue()>0).limit(1024).forEach(e->{
                    builderRequired.put(e.getKey(),e.getValue());
                    builderRemaining.put(e.getKey(),e.getValue());
                });
        builderWarehouseStock.clear();builderStockChecked=false;sync();
    }
    public void setBuilderMaterialsForFile(String file,Map<String,Long> materials){
        if(!isBuilder()||file==null||materials==null)return;
        if(file.equals(builderFile)&&activeOrder==null){
            setBuilderMaterials(materials);
            return;
        }
        for(int i=0;i<builderJobs.size();i++){
            BuilderJob job=builderJobs.get(i);
            if(!file.equals(job.file()))continue;
            Map<String,Long> copy=new java.util.LinkedHashMap<>();
            materials.entrySet().stream().filter(e->e.getKey()!=null&&e.getValue()!=null
                    &&e.getValue()>0).limit(1024).forEach(e->copy.put(e.getKey(),e.getValue()));
            builderJobs.set(i,new BuilderJob(job.file(),job.name(),job.origin(),
                    job.rotation(),job.mirror(),job.blocks(),job.unsupported(),
                    job.cursor(),copy,copy,job.warehouse(),job.storage(),
                    job.phase(),job.ignorePlants()));
            sync();return;
        }
    }
    public void backfillBuilderMaterials(Map<String,Long> materials){
        if(!isBuilder()||!builderRequired.isEmpty()||materials==null)return;
        builderRequired.putAll(materials);
        builderRemaining.putAll(materials);
        // Pre-0.3.25 construction cursor may already have placed some
        // blocks; exact per-item history is unknown. Avoid guessing and
        // retain a conservative overestimate rather than reducing stock.
        sync();
    }
    public void recordBuilderPlacement(String material){
        if(material==null||material.isBlank())return;
        builderRemaining.computeIfPresent(material,(key,v)->Math.max(0L,v-1));
        // The step completion immediately following this writes and
        // batches the Planner's progress. Avoid two full block-entity
        // network updates for every single cobblestone placement.
        setChanged();
    }
    private BuilderJob snapshotBuilderJob(){
        return new BuilderJob(builderFile,placedSchematicName,builderOrigin,
                builderRotation,builderMirror,builderBlocks,builderUnresolved,
                builderCursor,builderRequired,builderRemaining,warehouse,
                builderMaterialStorage,phase.name(),builderIgnorePlants);
    }
    public boolean selectBuilderJob(UUID actor,int index){
        if(!isBuilder()||!canConfigure(actor)||index<0||index>=builderJobs.size()
                ||activeOrder!=null)return false;
        if(selectedBuilderJob>=0&&selectedBuilderJob<builderJobs.size())
            builderJobs.set(selectedBuilderJob,snapshotBuilderJob());
        var selected=builderJobs.get(index);
        selectedBuilderJob=index;
        builderFile=selected.file();placedSchematicName=selected.name();
        builderOrigin=selected.origin();builderRotation=selected.rotation();
        builderMirror=selected.mirror();builderBlocks=selected.blocks();
        builderUnresolved=selected.unsupported();builderCursor=selected.cursor();
        builderClearCursor=builderClearByFile.getOrDefault(builderFile,0);
        builderClearClaims.clear();builderClearClaimTick.clear();
        builderClearVerified.clear();
        builderMissingRetries.clear();
        builderRequired.clear();builderRequired.putAll(selected.required());
        builderRemaining.clear();builderRemaining.putAll(selected.remaining());
        warehouse=selected.warehouse();builderMaterialStorage=selected.storage();
        builderIgnorePlants=selected.ignorePlants();
        builderPlaced=true;completedAhead=0;builderClaims.clear();builderWorkOrders.clear();builderPreparedOrders.clear();builderFinishedSteps.clear();builderWorkOrders.clear();builderPreparedOrders.clear();builderFinishedSteps.clear();
        builderWarehouseStock.clear();builderStockChecked=false;builderWarehouseName="";
        try{phase=WorkerPhase.valueOf(selected.phase());}
        catch(IllegalArgumentException ex){phase=WorkerPhase.READY;}
        sync();return true;
    }
    /** Delete only inactive job metadata. Never destroy on-site blocks,
     * real staged items, or a worker's material inventory. A warehouse
     * shipment must be settled first, so deleting a job cannot orphan
     * reserved shulkers. The uploaded snapshot file is left on disk since
     * it may be referenced by another Planning Table.
     */
    public boolean deleteBuilderJob(UUID actor,int index){
        if(!isBuilder()||!canConfigure(actor)||activeOrder!=null
                ||supplyRequest!=null||pendingBuilderWarehouse!=null
                ||index<0||index>=builderJobs.size())return false;
        if(selectedBuilderJob>=0&&selectedBuilderJob<builderJobs.size())
            builderJobs.set(selectedBuilderJob,snapshotBuilderJob());
        // Capture the deleted job's depot BEFORE selectBuilderJob() switches
        // the Planner to another job (or clears the final stored link).
        BlockPos oldSite=builderJobs.get(index).storage();
        if(oldSite!=null)builderRetirementStorage=oldSite.immutable();
        String deleted=builderJobs.remove(index).file();
        builderRecallRequested=true;
        builderClearByFile.remove(deleted);
        if(builderJobs.isEmpty()){
            selectedBuilderJob=-1;
            builderFile="";placedSchematicName="";builderPlaced=false;
            builderBlocks=0;builderUnresolved=0;builderRotation=0;builderMirror=0;
            builderOrigin=BlockPos.ZERO;builderCursor=0;builderClearCursor=0;
            builderMaterialStorage=null;warehouse=null;
            builderRequired.clear();builderRemaining.clear();
            builderWarehouseStock.clear();builderStagedStock.clear();
            builderStockChecked=false;builderWarehouseName="";
            builderClaims.clear();completedAhead=0;phase=WorkerPhase.IDLE;builderWorkOrders.clear();builderPreparedOrders.clear();builderFinishedSteps.clear();
            sync();return true;
        }
        // Selecting a saved job automatically persists its snapshot.
        // Do not allow the removed job to be reintroduced by sync().
        selectedBuilderJob=-1;
        int successor=Math.min(index,builderJobs.size()-1);
        return selectBuilderJob(actor,successor);
    }
    public int builderBlocks() { return builderBlocks; }
    public int builderUnresolved() { return builderUnresolved; }
    public int builderRotation() { return builderRotation; }
    public int builderMirror(){return builderMirror;}
    public BlockPos builderOrigin(){return builderOrigin;}
    public String placedSchematicName(){return placedSchematicName;}
    public int builderCursor(){return builderCursor;}
    public int builderClearCursor(){return builderClearCursor;}
    /** The job block owns the clear cursor, verified cells and claims.
     * A stalled claim expires after five seconds; a different Builder can
     * retry the same cell without any premature world modifications.
     */
    /** Read-only inspection of an individual Builder's current clearing
     * position; never grants or steals a clearance claim.
     */
    public int builderClearClaim(UUID worker){
        return builderClearClaims.getOrDefault(worker,-1);
    }
    public int claimBuilderClear(UUID worker,int limit){
        if(activeOrder==null||!builderWorkerIds().contains(worker))return -1;
        long now=level==null?0:level.getGameTime();
        for(UUID id:new java.util.ArrayList<>(builderClearClaims.keySet()))
            if(now-builderClearClaimTick.getOrDefault(id,now)>100){
                builderClearClaims.remove(id);builderClearClaimTick.remove(id);
            }
        Integer previous=builderClearClaims.get(worker);
        if(previous!=null)return previous;
        int upper=Math.min(limit,builderClearCursor+256);
        for(int i=builderClearCursor;i<upper;i++){
            if(builderClearVerified.contains(i)||builderClearClaims.containsValue(i))
                continue;
            builderClearClaims.put(worker,i);
            builderClearClaimTick.put(worker,now);
            return i;
        }
        return -1;
    }
    public void completeBuilderClear(UUID worker,int index,boolean brokeBlock){
        if(!java.util.Objects.equals(builderClearClaims.get(worker),index))return;
        builderClearClaims.remove(worker);builderClearClaimTick.remove(worker);
        builderClearVerified.add(index);
        int old=builderClearCursor;
        while(builderClearVerified.remove(builderClearCursor))
            builderClearCursor++;
        builderClearByFile.put(builderFile,builderClearCursor);
        // Physical clearance saves immediately; fast verification of
        // already-empty cells batches network syncs every 32 positions.
        if(brokeBlock||builderClearCursor-old>0&&builderClearCursor%32==0)
            sync();
        else setChanged();
    }
    public void releaseBuilderClear(UUID worker){
        builderClearClaims.remove(worker);builderClearClaimTick.remove(worker);
    }
    public void advanceBuilderClearing(int index){
        if(index<=builderClearCursor)return;
        builderClearCursor=index;
        builderClearByFile.put(builderFile,index);
        sync();
    }
    /** Temporarily skip a construction step that is missing stock, without
     * skipping its layer or calling it completed. Other available blocks in
     * the same Y layer can still be placed.
     */
    public void waitForBuilderMaterial(UUID worker,int step,long tick){
        if(builderClaims.getOrDefault(worker,-1)!=step)return;
        builderClaims.remove(worker);
        builderMissingRetries.put(step,tick+60);
        sync();
    }
    /** Support-dependent schematic cells must not hold a claim forever.
     * Preserve the same layer and its completed bits, but allow OTHER
     * Builders to finish available support cells before retrying.
     */
    public void waitForBuilderSupport(UUID worker,int step,long tick){
        if(builderClaims.getOrDefault(worker,-1)!=step)return;
        builderClaims.remove(worker);
        builderMissingRetries.put(step,tick+40);
        sync();
    }
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
            case BUILDER -> isBuilder()?builderWorkerIds().size():8;
            case TRADER -> isTrader()?traderWorkerIds().size():4;
            case BEEKEEPER -> isBeekeeper()?beekeeperWorkers().size():4;
            case FISHING -> isFishing()?fisherWorkerIds().size():4;
            default -> 4;
        };
        if(count>=(specialty==WorkerSpecialty.BUILDER?8:4))return false;
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
        if(!canConfigure(actor)||(!isBuilder()&&!isTrader()&&!isBeekeeper()&&!isFishing()&&!isCooking())||count<1||count>(isBuilder()?8:4))return false;
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
    public BlockPos builderLastPosition(UUID worker){
        return builderLastPositions.get(worker);
    }
    public void rememberBuilderPosition(UUID worker,BlockPos position){
        if(worker==null||position==null||!builderWorkerIds().contains(worker))return;
        BlockPos before=builderLastPositions.get(worker);
        if(before!=null&&before.getX() >> 4 == position.getX() >> 4
                &&before.getZ() >> 4 == position.getZ() >> 4)return;
        builderLastPositions.put(worker,position.immutable());
        setChanged(); // One disk write per chunk transition, not per Golem tick.
    }
    public boolean builderRecallRequested(){return builderRecallRequested;}
    /** Called only after an empty physical Builder has been deposited as
     * a real worker item at the Unemployed Center. */
    public void retireBuilderWorker(UUID worker){
        if(worker==null||!builderWorkerIds().contains(worker))return;
        if(worker.equals(builderWorkerId)){
            builderWorkerId=additionalBuilderWorkers.isEmpty()?null:
                    additionalBuilderWorkers.removeFirst();
        }else additionalBuilderWorkers.remove(worker);
        builderLastPositions.remove(worker);
        builderClaims.remove(worker);
        builderWorkOrders.remove(worker);
        builderPreparedOrders.remove(worker);
        releaseBuilderClear(worker);
        trackedScaffolds.remove(worker);
        if(worker.equals(supplyRunner))supplyRunner=null;
        if(builderWorkerIds().isEmpty()){
            builderRecallRequested=false;builderRetirementStorage=null;
        }
        sync();
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
        return claimBuilderStep(worker,Integer.MAX_VALUE);
    }
    public int claimBuilderStep(UUID worker,int layerExclusive){
        return claimBuilderStep(worker,layerExclusive,java.util.List.of(),
                java.util.Set.of());
    }
    public java.util.List<Integer> builderWorkOrder(UUID worker){
        return java.util.List.copyOf(builderWorkOrders.getOrDefault(worker,
                java.util.List.of()));
    }
    public boolean builderOrderPrepared(UUID worker){
        return builderPreparedOrders.contains(worker);
    }
    public void prepareBuilderOrder(UUID worker){
        if(builderWorkOrders.containsKey(worker)){
            builderPreparedOrders.add(worker);sync();
        }
    }
    public void releaseBuilderWorkOrder(UUID worker){
        builderClaims.remove(worker);
        builderWorkOrders.remove(worker);
        builderPreparedOrders.remove(worker);
        sync();
    }
    /** Preserve existing single-step API for compatibility with old worlds.
     * A worker now receives up to ten exclusive same-layer coordinates.
     * Assignments are stable across ticks, saved/reloaded with the Planner,
     * and fully separate from the physical Builder's nine-slot inventory.
     */
    public int claimBuilderStep(UUID worker,int layerExclusive,
            java.util.List<com.caszgamermd.golemsplus.builder.BuilderConstructionPlan.Step> steps,
            java.util.Set<String> heldItems){
        return claimBuilderStep(worker,layerExclusive,steps,heldItems,null);
    }
    /** A Builder owns one rectangular X/Z zone. Each zone works upward
     * from its own lowest unfinished Y, without waiting for another zone.
     * Old ten-position orders remain valid after loading an existing save.
     */
    public int claimBuilderStep(UUID worker,int layerExclusive,
            java.util.List<com.caszgamermd.golemsplus.builder.BuilderConstructionPlan.Step> steps,
            java.util.Set<String> heldItems,
            com.caszgamermd.golemsplus.builder.BuilderZones.Layout footprint){
        if(!builderWorkerIds().contains(worker)||activeOrder==null)return -1;
        long now=level==null?0:level.getGameTime();
        int workerZone=-1,activeCount=0;
        if(footprint!=null&&level instanceof net.minecraft.server.level.ServerLevel world){
            for(UUID id:builderWorkerIds()){
                if(activeCount>=crewTarget)break;
                if(!(world.getEntity(id) instanceof
                        com.caszgamermd.golemsplus.builder.BuilderGolem golem)
                        ||!golem.isAlive())continue;
                if(id.equals(worker))workerZone=activeCount;
                activeCount++;
            }
        }
        boolean zoned=workerZone>=0;
        if(zoned)builderZoned=true;
        var order=builderWorkOrders.get(worker);
        if(order==null||order.isEmpty()){
            order=new java.util.ArrayList<>(10);
            if(zoned){
                int lowest=Integer.MAX_VALUE;
                // Locate the lowest outstanding layer IN THIS ZONE, not
                // the lowest layer in the entire schematic.
                for(int i=builderCursor;i<steps.size();i++){
                    if(builderFinishedSteps.contains(i))continue;
                    var step=steps.get(i);
                    if(footprint.contains(step,workerZone,activeCount))
                        lowest=Math.min(lowest,step.y());
                }
                if(lowest==Integer.MAX_VALUE)return -1;
                for(int i=builderCursor;i<steps.size()&&order.size()<10;i++){
                    if(steps.get(i).y()!=lowest
                            ||!footprint.contains(steps.get(i),workerZone,activeCount)
                            ||builderFinishedSteps.contains(i)
                            ||builderClaims.containsValue(i)
                            ||builderMissingRetries.getOrDefault(i,0L)>now)continue;
                    boolean reserved=false;
                    for(var assignment:builderWorkOrders.values())
                        if(assignment.contains(i)){reserved=true;break;}
                    if(!reserved)order.add(i);
                }
            }else{
                // Legacy direct callers without a footprint retain bounded,
                // serial-layer assignment behavior.
                int upper=Math.min(Math.min(builderBlocks,layerExclusive),
                        builderCursor+80);
                for(int i=builderCursor;i<upper&&order.size()<10;i++){
                    if(builderFinishedSteps.contains(i)
                            ||builderClaims.containsValue(i)
                            ||builderMissingRetries.getOrDefault(i,0L)>now)continue;
                    boolean reserved=false;
                    for(var assignment:builderWorkOrders.values())
                        if(assignment.contains(i)){reserved=true;break;}
                    if(!reserved)order.add(i);
                }
            }
            if(order.isEmpty())return -1;
            builderWorkOrders.put(worker,order);
            builderPreparedOrders.remove(worker);
            sync();
        }
        int preferred=-1,first=-1;
        for(int i:order){
            if((!zoned&&i>=layerExclusive)||builderFinishedSteps.contains(i)
                    ||builderMissingRetries.getOrDefault(i,0L)>now)continue;
            if(first<0)first=i;
            if(i<steps.size()){
                String material=steps.get(i).requiredItem();
                if(material==null||heldItems.contains(material)){
                    preferred=i;break;
                }
            }
        }
        int chosen=preferred>=0?preferred:first;
        if(chosen<0){
            builderClaims.remove(worker);return -1;
        }
        builderClaims.put(worker,chosen);
        return chosen;
    }
    /** Switch a running job to immutable whole-zone worker schedules.
     * Existing actual completed cells and physical pouch inventories are
     * preserved; old per-ten-cell leases are only staging reservations.
     * Called once when the roster/file changes, never on every worker tick.
     */
    public void beginBuilderZonePlan(){
        if(!isBuilder()||activeOrder==null)return;
        builderClaims.clear();
        builderWorkOrders.clear();
        builderPreparedOrders.clear();
        builderZoned=true;
        sync();
    }

    /** A finished zone has NO outstanding schematic coordinates, across
     * ALL heights. This is not the same as a temporarily empty stage after
     * a support/access/material retry. A finished worker returns OnSite.
     */
    public boolean builderZoneDone(java.util.List<Integer> fullZone){
        if(fullZone==null||fullZone.isEmpty())return true;
        for(int idx:fullZone)
            if(idx>=builderCursor&&!builderFinishedSteps.contains(idx))
                return false;
        return true;
    }

    /** Crew-wide construction height gate. The placed schematic is
     * compiled strictly bottom-to-top. builderCursor is the FIRST
     * unfinished step, even when other workers have completed positions
     * farther ahead on this same Y. A deferred, inaccessible or missing
     * material step keeps its entire layer active until actually done.
     * This does not restrict safe walking/grappling between heights.
     */
    public int builderActiveLayer(
            java.util.List<com.caszgamermd.golemsplus.builder.BuilderConstructionPlan.Step> steps){
        if(steps==null||builderCursor<0||builderCursor>=steps.size())
            return Integer.MAX_VALUE;
        return steps.get(builderCursor).y();
    }

    /** Each Builder owns its X/Z zone. ALL workers must finish ALL
     * unfinished coordinates on the current shared Y before the Planner
     * assigns any block on a higher Y. A blocked step is retried in
     * the same layer (not discarded); idle workers return OnSite.
     */
    public int claimBuilderZoneStep(UUID worker,java.util.List<Integer> fullZone,
            java.util.List<com.caszgamermd.golemsplus.builder.BuilderConstructionPlan.Step> steps){
        if(activeOrder==null||!builderWorkerIds().contains(worker)
                ||fullZone==null||fullZone.isEmpty()
                ||steps==null||builderCursor>=steps.size())return -1;
        int layer=builderActiveLayer(steps);
        int current=builderClaims.getOrDefault(worker,-1);
        long now=level==null?0:level.getGameTime();
        if(current>=builderCursor&&current<steps.size()
                &&steps.get(current).y()==layer
                &&!builderFinishedSteps.contains(current)
                &&builderMissingRetries.getOrDefault(current,0L)<=now)
            return current;
        builderClaims.remove(worker);
        var stage=builderWorkOrders.get(worker);
        if(stage!=null){
            // An old persisted or in-flight batch may include a prior
            // layer or higher-Y cells from the former free-Y policy.
            // Restage with ONLY current-layer tasks, without losing any
            // real saved completion or materials in the worker's pouch.
            boolean wrongLayer=false,runnable=false;
            for(int idx:stage){
                if(idx<0||idx>=steps.size()
                        ||steps.get(idx).y()!=layer){
                    wrongLayer=true;break;
                }
                if(idx>=builderCursor&&!builderFinishedSteps.contains(idx)
                        &&builderMissingRetries.getOrDefault(idx,0L)<=now)
                    runnable=true;
            }
            if(wrongLayer||!runnable){
                builderWorkOrders.remove(worker);
                builderPreparedOrders.remove(worker);
                stage=null;
            }
        }
        if(stage==null||stage.isEmpty()){
            var newStage=new java.util.ArrayList<Integer>(BUILDER_STAGE_SIZE);
            for(int idx:fullZone){
                if(idx<builderCursor||idx>=steps.size()
                        ||steps.get(idx).y()!=layer
                        ||builderFinishedSteps.contains(idx)
                        ||builderMissingRetries.getOrDefault(idx,0L)>now)continue;
                newStage.add(idx);
                if(newStage.size()>=BUILDER_STAGE_SIZE)break;
            }
            if(newStage.isEmpty())return -1;
            builderWorkOrders.put(worker,newStage);
            builderPreparedOrders.remove(worker);
            stage=newStage;
            sync();
        }
        for(int idx:stage){
            if(idx<builderCursor||idx>=steps.size()
                    ||steps.get(idx).y()!=layer
                    ||builderFinishedSteps.contains(idx)
                    ||builderMissingRetries.getOrDefault(idx,0L)>now)continue;
            builderClaims.put(worker,idx);
            return idx;
        }
        return -1;
    }
    /** The Frontier Planner may inspect pending work without claiming it.
     * All previous completion, retries and strict shared-Y checks still
     * belong to this authoritative persistent Planner, never the client.
     */
    public boolean builderStepReadyForClaim(int index){
        long now=level==null?0:level.getGameTime();
        return activeOrder!=null&&index>=builderCursor
                &&index<builderBlocks&&!builderFinishedSteps.contains(index)
                &&builderMissingRetries.getOrDefault(index,0L)<=now;
    }

    /** Claim one PREVALIDATED same-Y frontier cell instead of blindly
     * taking the first indexed block in the ten-block batch.
     *
     * No remote item movement: restaging clears the prepared flag only.
     * The real Builder pouch and any already completed cell remain saved.
     * A previously blocked worker can select another physically feasible
     * block from its OWN X/Z zone, on the SAME strict global Y.
     */
    public int claimPreferredBuilderZoneStep(UUID worker,List<Integer> fullZone,
            List<com.caszgamermd.golemsplus.builder.BuilderConstructionPlan.Step> steps,
            int preferred){
        if(activeOrder==null||!builderWorkerIds().contains(worker)
                ||steps==null||fullZone==null||preferred<0
                ||preferred>=steps.size()||!fullZone.contains(preferred)
                ||steps.get(preferred).y()!=builderActiveLayer(steps)
                ||!builderStepReadyForClaim(preferred))return -1;
        int active=builderClaims.getOrDefault(worker,-1);
        if(active==preferred)return active;
        var stage=builderWorkOrders.get(worker);
        boolean inStage=stage!=null&&stage.contains(preferred);
        if(!inStage){
            // The incoming frontier cell must be the FIRST staged item,
            // so it can begin while the other nine are merely stocked.
            var fresh=new java.util.ArrayList<Integer>(BUILDER_STAGE_SIZE);
            fresh.add(preferred);
            for(int idx:fullZone){
                if(fresh.size()>=BUILDER_STAGE_SIZE)break;
                if(idx==preferred||idx<0||idx>=steps.size()
                        ||steps.get(idx).y()!=steps.get(preferred).y()
                        ||!builderStepReadyForClaim(idx))continue;
                fresh.add(idx);
            }
            builderWorkOrders.put(worker,fresh);
            builderPreparedOrders.remove(worker);
        }
        builderClaims.put(worker,preferred);
        sync();
        return preferred;
    }

    /** No route or anchor is currently valid for a claimed cell. Keep
     * the exact unfinished index to retry, while other Builders may
     * work on the SAME shared Y. Higher Y remains locked until done.
     */
    public void waitForBuilderAccess(UUID worker,int step,long tick){
        if(builderClaims.getOrDefault(worker,-1)!=step)return;
        builderClaims.remove(worker);
        builderMissingRetries.put(step,tick+60);
        setChanged();
    }

    /** Move one exact same-layer coordinate to a worker who already holds
     * the material physically. Ten-coordinate work orders remain disjoint:
     * when the stocked worker already has ten cells, exchange an unfinished
     * non-active cell back to the original worker. No item moves remotely.
     */
    public boolean reassignBuilderStep(UUID requester,UUID stocked,int index){
        if(builderZoned)return false; // Never transfer a cell across strict X/Z zones.
        if(requester.equals(stocked)||!builderWorkerIds().contains(requester)
                ||!builderWorkerIds().contains(stocked)||activeOrder==null)
            return false;
        var waiting=builderWorkOrders.get(requester);
        if(waiting==null||!waiting.contains(index)
                ||builderFinishedSteps.contains(index))return false;
        var destination=builderWorkOrders.computeIfAbsent(stocked,
                k->new java.util.ArrayList<>(10));
        if(destination.contains(index))return false;
        int exchange=-1;
        if(destination.size()>=10){
            for(int j=destination.size()-1;j>=0;j--){
                int candidate=destination.get(j);
                if(builderClaims.getOrDefault(stocked,-1)!=candidate
                        &&!builderFinishedSteps.contains(candidate)){
                    exchange=candidate;break;
                }
            }
            if(exchange<0)return false;
            destination.remove(Integer.valueOf(exchange));
        }
        waiting.remove(Integer.valueOf(index));
        if(exchange>=0)waiting.add(exchange);
        destination.add(index);
        builderClaims.remove(requester);
        // The swapped-in coordinate may require different physical stock.
        builderPreparedOrders.remove(requester);
        if(waiting.isEmpty()){
            builderWorkOrders.remove(requester);
            builderPreparedOrders.remove(requester);
        }
        // An already prepared, stocked Builder may begin its new assigned
        // cell immediately. The claim will be selected on its next tick.
        sync();
        return true;
    }

    public boolean completeBuilderStep(UUID worker,int index){
        if(builderClaims.getOrDefault(worker,-1)!=index)return false;
        builderClaims.remove(worker);
        builderMissingRetries.remove(index);
        var order=builderWorkOrders.get(worker);
        if(order!=null){
            order.remove(Integer.valueOf(index));
            if(order.isEmpty()){
                builderWorkOrders.remove(worker);
                builderPreparedOrders.remove(worker);
            }
        }
        builderFinishedSteps.add(index);
        while(builderFinishedSteps.remove(builderCursor))builderCursor++;
        if(builderCursor>=builderBlocks){
            phase=WorkerPhase.COMPLETE;activeOrder=null;builderClaims.clear();
            builderWorkOrders.clear();builderPreparedOrders.clear();
            builderFinishedSteps.clear();
        }
        // Persist every completed position immediately, but publish the
        // huge job/staging inventory to the client at most once a second.
        // Multiple independent Builders can now place without taking
        // turns serializing the entire Planner on each worker's tick.
        if(selectedBuilderJob>=0&&selectedBuilderJob<builderJobs.size())
            builderJobs.set(selectedBuilderJob,snapshotBuilderJob());
        setChanged();
        long now=level==null?0:level.getGameTime();
        if(activeOrder==null||builderProgressNotifyTick<0
                ||now-builderProgressNotifyTick>=20){
            builderProgressNotifyTick=now;
            if(level!=null){
                BlockState block=getBlockState();
                level.sendBlockUpdated(worldPosition,block,block,3);
            }
        }
        return true;
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
    public boolean builderCancelShipment(){return builderCancelShipment;}
    public void requestBuilderShipmentCancellation(){
        if(isBuilder()&&supplyRequest!=null){
            builderCancelShipment=true;sync();
        }
    }
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
        builderCancelShipment=false;
        supplyManifest.clear();supplyNotice="";
        // Apply a scanner re-link only AFTER the previous warehouse's
        // physical reserved shulkers were delivered/released safely.
        if(pendingBuilderWarehouse!=null){
            warehouse=pendingBuilderWarehouse;pendingBuilderWarehouse=null;
            builderStockChecked=false;builderWarehouseStock.clear();
            builderWarehouseName="";
        }
        sync();
    }
    public boolean hasPlacedSchematic(){return builderPlaced&&!builderFile.isBlank();}
    public boolean isBuilder(){
        return getBlockState().getBlock() instanceof WorkerStationBlock block
                &&block.specialty()==WorkerSpecialty.BUILDER;
    }
    public boolean setPlacedBuilderPlan(UUID actor,String filename,String display,
            BlockPos origin,int rotation,int mirror,int blocks,int unresolved){
        if(!canConfigure(actor)||!isBuilder()
                ||supplyRequest!=null||filename==null||!filename.startsWith("placed-")
                ||!filename.endsWith(".litematic")
                ||filename.contains("/")||filename.contains("..")||filename.contains("\\")
                ||origin==null||rotation<0||rotation>3||mirror<0||mirror>2
                ||blocks<1||blocks>100000||unresolved<0
                ||builderJobs.size()>=12)return false;
        if(activeOrder!=null){
            // Queue a future placement without disturbing the running
            // worker's active file, cursor or material escrow.
            builderJobs.add(new BuilderJob(filename,display,origin.immutable(),
                    rotation*90,mirror,blocks,unresolved,0,
                    Map.of(),Map.of(),warehouse,null,"READY"));
            sync();return true;
        }
        builderFile=filename;placedSchematicName=display;builderOrigin=origin.immutable();
        builderRotation=rotation*90;builderMirror=mirror;builderBlocks=blocks;
        builderUnresolved=unresolved;builderCursor=0;completedAhead=0;
        builderClearCursor=0;builderClearByFile.put(builderFile,0);
        builderClearClaims.clear();builderClearClaimTick.clear();
        builderClearVerified.clear();
        builderMissingRetries.clear();
        builderClaims.clear();builderPlaced=true;builderWorkOrders.clear();builderPreparedOrders.clear();builderFinishedSteps.clear();
        builderRequired.clear();builderRemaining.clear();
        builderWarehouseStock.clear();builderStockChecked=false;
        builderWarehouseName="";
        builderMaterialStorage=null;
        builderIgnorePlants=false;
        selectedBuilderJob=builderJobs.size();
        builderJobs.add(snapshotBuilderJob());
        phase=WorkerPhase.READY;sync();return true;
    }
    public boolean startPlacedBuilder(UUID actor){
        if(!canConfigure(actor)||!isBuilder()||!hasPlacedSchematic()
                ||activeOrder!=null||(warehouse==null&&builderMaterialStorage==null)
                ||builderWorkerIds().isEmpty()||builderBlocks<1)return false;
        activeOrder=UUID.randomUUID();builderCursor=0;completedAhead=0;
        builderClearCursor=0;builderClearByFile.put(builderFile,0);
        builderClearClaims.clear();builderClearClaimTick.clear();
        builderClearVerified.clear();
        builderMissingRetries.clear();
        builderClaims.clear();builderRecallRequested=false;builderWorkOrders.clear();builderPreparedOrders.clear();builderFinishedSteps.clear();
        phase=WorkerPhase.WORKING;sync();return true;
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
        activeOrder=null;builderClaims.clear();completedAhead=0;builderWorkOrders.clear();builderPreparedOrders.clear();builderFinishedSteps.clear();
        builderRecallRequested=true;
        builderCancelShipment=supplyRequest!=null;
        phase=WorkerPhase.IDLE;sync();return true;
    }
    public boolean setBuilderFile(UUID actor,String filename,int blocks,int unresolved) {
        if(!canConfigure(actor)||isTrader()||filename==null||filename.isBlank()
                ||blocks<0||unresolved<0||activeOrder!=null)return false;
        builderFile=filename;builderBlocks=blocks;builderUnresolved=unresolved;
        builderPlaced=false;placedSchematicName="";builderCursor=0;
        completedAhead=0;builderClaims.clear();builderWorkOrders.clear();builderPreparedOrders.clear();builderFinishedSteps.clear();
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
        if(!canConfigure(actor)||selection==null)return false;
        if(isBuilder()&&supplyRequest!=null){
            // Never orphan a supply ticket or its real packed cargo.
            pendingBuilderWarehouse=selection;
            setProblem("Warehouse switch queued until current shulker shipment completes");
            sync();return true;
        }
        if(supplyRequest!=null)return false;
        warehouse=selection;
        if(isBuilder()){
            pendingBuilderWarehouse=null;
            builderStockChecked=false;builderWarehouseStock.clear();
            builderWarehouseName="";
        }
        sync();return true;
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
        // Completion must also close the persisted job UUID: returning
        // workers cannot recycle while a phantom activeOrder survives.
        if(isBuilder()&&next==WorkerPhase.COMPLETE){
            activeOrder=null;
            builderClaims.clear();
        }
        if (phase!=next) { phase=next; sync(); }
        else if(isBuilder()&&next==WorkerPhase.COMPLETE)sync();
    }
    public void clearOrder() {
        activeOrder=null; wantedItem=""; wantedCount=0; deliveredCount=0;
        budgets.clear(); spent.clear(); phase=WorkerPhase.IDLE; problem="";
        sync();
    }
    public void sync() {
        if(isBuilder()&&selectedBuilderJob>=0&&selectedBuilderJob<builderJobs.size())
            builderJobs.set(selectedBuilderJob,snapshotBuilderJob());
        setChanged();
        if (level!=null) {
            BlockState state=getBlockState();
            level.sendBlockUpdated(worldPosition,state,state,3);
        }
    }

    @Override public void setRemoved(){
        if(level instanceof net.minecraft.server.level.ServerLevel server)
            com.caszgamermd.golemsplus.builder.BuilderChunkTickets
                    .release(server,worldPosition);
        super.setRemoved();
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
        if(isBeekeeper())return slot>=0&&slot<INPUT_SLOTS
                &&(stack.is(net.minecraft.world.item.Items.SHEARS)
                    ||stack.is(net.minecraft.world.item.Items.GLASS_BOTTLE)
                    ||stack.is(net.minecraft.tags.TagKey.create(
                            net.minecraft.core.registries.Registries.ITEM,
                            net.minecraft.resources.Identifier.parse("minecraft:bee_food"))));
        if(isFishing())return slot>=0&&slot<INPUT_SLOTS
                &&(stack.is(net.minecraft.world.item.Items.FISHING_ROD)
                     ||stack.is(WorksiteContent.FISHING_BAIT)
                    ||stack.is(net.minecraft.world.item.Items.COD)
                    ||stack.is(net.minecraft.world.item.Items.SALMON)
                    ||stack.is(net.minecraft.world.item.Items.TROPICAL_FISH)
                    ||stack.is(net.minecraft.world.item.Items.PUFFERFISH));
        // Preserve old stored stacks so a player can remove them; newly
        // deposited Builder materials/tools belong in selected OnSite Storage.
        return !isCooking()&&!isBuilder()&&slot>=0&&slot<INPUT_SLOTS;
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
        out.putInt("chef_preview_size",Math.min(180,chefPreviewLines.size()));
        for(int i=0;i<chefPreviewLines.size()&&i<180;i++)
            out.putString("chef_preview_"+i,chefPreviewLines.get(i));
        out.putString("chef_food",chefFood);
        out.putInt("chef_quantity",chefQuantity);
        out.putInt("chef_step_index",chefStepIndex);
        out.putInt("chef_step_ticks",chefStepTicks);
        out.putBoolean("chef_oven_loaded",chefOvenLoaded);
        out.putBoolean("chef_delivery_started",chefDeliveryStarted);
        out.putBoolean("chef_meal_served",chefMealServed);
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
        out.putInt("chef_origin_count",Math.min(256,chefOrigins.size()));
        for(int i=0;i<chefOrigins.size()&&i<256;i++){
            var origin=chefOrigins.get(i);
            out.putString("chef_origin_id_"+i,origin.itemId());
            out.store("chef_origin_pos_"+i,BlockPos.CODEC,origin.container());
            out.putInt("chef_origin_slot_"+i,origin.slot());
            out.putInt("chef_origin_quantity_"+i,origin.quantity());
        }
        out.putInt("chef_physical_milk_count",Math.min(64,physicalMilkRemaining.size()));
        int milkIndex=0;
        for(var entry:physicalMilkRemaining.entrySet()){
            if(milkIndex>=64)break;
            out.putString("chef_physical_milk_id_"+milkIndex,entry.getKey());
            out.putInt("chef_physical_milk_qty_"+milkIndex,entry.getValue());
            milkIndex++;
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
        if(fisherOutput!=null)out.store("fisher_output",BlockPos.CODEC,fisherOutput);
        if(fishingSpot!=null)out.store("fisher_spot",BlockPos.CODEC,fishingSpot);
        out.putBoolean("fisher_buoy",fishingBuoy);
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
        out.putInt("builder_staged_count",Math.min(1024,builderStagedStock.size()));
        int stagedIndex=0;
        for(var e:builderStagedStock.entrySet()){
            if(stagedIndex>=1024)break;
            out.putString("builder_staged_item_"+stagedIndex,e.getKey());
            out.putInt("builder_staged_qty_"+stagedIndex,e.getValue());stagedIndex++;
        }
        if(pendingBuilderWarehouse!=null){
            out.putString("builder_pending_warehouse_dim",pendingBuilderWarehouse.dimension());
            out.putInt("builder_pending_warehouse_x",pendingBuilderWarehouse.x());
            out.putInt("builder_pending_warehouse_y",pendingBuilderWarehouse.y());
            out.putInt("builder_pending_warehouse_z",pendingBuilderWarehouse.z());
        }
        out.putString("builder_warehouse_name",builderWarehouseName);
        out.putInt("builder_stock_checked",builderStockChecked?1:0);
        out.putInt("builder_stock_count",Math.min(1024,builderWarehouseStock.size()));
        int stockIndex=0;
        for(var e:builderWarehouseStock.entrySet()){
            if(stockIndex>=1024)break;
            out.putString("builder_stock_item_"+stockIndex,e.getKey());
            out.putInt("builder_stock_qty_"+stockIndex,e.getValue());stockIndex++;
        }
        out.putInt("builder_job_count",Math.min(12,builderJobs.size()));
        out.putInt("builder_selected_job",selectedBuilderJob);
        for(int i=0;i<builderJobs.size()&&i<12;i++){
            BuilderJob job=builderJobs.get(i);
            String key="builder_job_"+i+"_";
            out.putString(key+"file",job.file());
            out.putString(key+"name",job.name());
            out.store(key+"origin",BlockPos.CODEC,job.origin());
            out.putInt(key+"rotation",job.rotation());
            out.putInt(key+"mirror",job.mirror());
            out.putInt(key+"blocks",job.blocks());
            out.putInt(key+"unsupported",job.unsupported());
            out.putInt(key+"cursor",job.cursor());
            out.putInt(key+"clearing",builderClearByFile.getOrDefault(job.file(),0));
            out.putString(key+"phase",job.phase());
            out.putBoolean(key+"ignore_plants",job.ignorePlants());
            if(job.storage()!=null)out.store(key+"storage",BlockPos.CODEC,job.storage());
            if(job.warehouse()!=null){
                out.putString(key+"warehouse_dim",job.warehouse().dimension());
                out.putInt(key+"warehouse_x",job.warehouse().x());
                out.putInt(key+"warehouse_y",job.warehouse().y());
                out.putInt(key+"warehouse_z",job.warehouse().z());
            }
            out.putInt(key+"material_count",Math.min(1024,job.required().size()));
            int n=0;
            for(var e:job.required().entrySet()){
                if(n>=1024)break;
                out.putString(key+"item_"+n,e.getKey());
                out.putLong(key+"required_"+n,e.getValue());
                out.putLong(key+"remaining_"+n,job.remaining().getOrDefault(e.getKey(),0L));
                n++;
            }
        }
        out.putString("builder_schematic",builderFile);
        out.putBoolean("builder_ignore_plants",builderIgnorePlants);
        out.putInt("builder_clear_cursor",builderClearCursor);
        int vix=0;
        for(int verified:builderClearVerified){
            if(vix>=256)break;
            out.putInt("builder_clear_verified_"+vix,verified);vix++;
        }
        out.putInt("builder_clear_verified_count",vix);
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
        out.putBoolean("builder_recall_requested",builderRecallRequested);
        if(builderRetirementStorage!=null)
            out.store("builder_retirement_storage",BlockPos.CODEC,
                    builderRetirementStorage);
        int lastIndex=0;
        for(UUID id:builderWorkerIds()){
            if(lastIndex>=8)break;
            BlockPos last=builderLastPositions.get(id);
            if(last==null)continue;
            out.putString("builder_last_worker_"+lastIndex,id.toString());
            out.store("builder_last_position_"+lastIndex,BlockPos.CODEC,last);
            lastIndex++;
        }
        out.putInt("builder_last_count",lastIndex);
        out.putInt("builder_extra_workers",Math.min(7,additionalBuilderWorkers.size()));
        for(int n=0;n<additionalBuilderWorkers.size()&&n<7;n++)
            out.putString("builder_extra_worker_"+n,additionalBuilderWorkers.get(n).toString());
        out.putLong("builder_ahead_bits",completedAhead);
        // Compressed finished positions: zones may advance thousands of
        // sorted indices ahead of the global cursor. The former 80-index
        // save limit caused already-built cells to be repeated after reload.
        var finishedBits=new java.util.BitSet();
        for(int done:builderFinishedSteps)
            if(done>=builderCursor&&done<builderBlocks)
                finishedBits.set(done-builderCursor);
        out.putString("builder_finished_bits_v2",java.util.Base64.getEncoder()
                .encodeToString(finishedBits.toByteArray()));
        out.putInt("builder_done_count",Math.min(80,builderFinishedSteps.size()));
        int doneIndex=0;
        for(int done:builderFinishedSteps){
            if(doneIndex>=80)break;
            out.putInt("builder_done_"+doneIndex++,done);
        }
        out.putInt("builder_batch_count",Math.min(8,builderWorkOrders.size()));
        int batchIndex=0;
        for(var entry:builderWorkOrders.entrySet()){
            if(batchIndex>=8)break;
            out.putString("builder_batch_uuid_"+batchIndex,
                    entry.getKey().toString());
            out.putBoolean("builder_batch_prepared_"+batchIndex,
                    builderPreparedOrders.contains(entry.getKey()));
            var steps=entry.getValue();
            out.putInt("builder_batch_size_"+batchIndex,
                    Math.min(BUILDER_STAGE_SIZE,steps.size()));
            for(int j=0;j<steps.size()&&j<BUILDER_STAGE_SIZE;j++)
                out.putInt("builder_batch_step_"+batchIndex+"_"+j,
                        steps.get(j));
            batchIndex++;
        }
        out.putInt("builder_claim_count",Math.min(8,builderClaims.size()));
        int cix=0;
        for(var entry:builderClaims.entrySet()){
            if(cix>=8)break;
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
        out.putBoolean("builder_cancel_shipment",builderCancelShipment);
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
        for(int i=0,n=Math.clamp(in.getIntOr("chef_preview_size",0),0,180);i<n;i++)
            chefPreviewLines.add(in.getStringOr("chef_preview_"+i,""));
        chefFood=in.getStringOr("chef_food","");
        chefQuantity=Math.clamp(in.getIntOr("chef_quantity",0),0,16);
        chefStepIndex=Math.clamp(in.getIntOr("chef_step_index",0),0,160);
        chefStepTicks=Math.clamp(in.getIntOr("chef_step_ticks",0),0,24000);
        chefOvenLoaded=in.getBooleanOr("chef_oven_loaded",false);
        chefDeliveryStarted=in.getBooleanOr("chef_delivery_started",false);
        chefMealServed=in.getBooleanOr("chef_meal_served",false);
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
        chefOrigins.clear();
        for(int i=0,n=Math.clamp(in.getIntOr("chef_origin_count",0),0,256);i<n;i++){
            String itemId=in.getStringOr("chef_origin_id_"+i,"");
            BlockPos pos=in.read("chef_origin_pos_"+i,BlockPos.CODEC).orElse(null);
            int slot=in.getIntOr("chef_origin_slot_"+i,-1);
            if(!itemId.isBlank()&&pos!=null&&slot>=0&&slot<512)
                chefOrigins.add(new com.caszgamermd.golemsplus.cooking.KitchenSupplies.Origin(
                        itemId,pos,slot,Math.clamp(in.getIntOr("chef_origin_quantity_"+i,1),1,64)));
        }
        physicalMilkRemaining.clear();
        for(int i=0,n=Math.clamp(in.getIntOr("chef_physical_milk_count",0),0,64);i<n;i++){
            String item=in.getStringOr("chef_physical_milk_id_"+i,"");
            int count=in.getIntOr("chef_physical_milk_qty_"+i,0);
            if(!item.isBlank()&&count>0&&count<=4096)
                physicalMilkRemaining.put(item,count);
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
        fisherOutput=in.read("fisher_output",BlockPos.CODEC).orElse(null);
        fishingSpot=in.read("fisher_spot",BlockPos.CODEC).orElse(null);
        fishingBuoy=in.getBooleanOr("fisher_buoy",false);
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
        builderJobs.clear();builderClearByFile.clear();
        int jobCount=Math.clamp(in.getIntOr("builder_job_count",0),0,12);
        for(int i=0;i<jobCount;i++){
            String key="builder_job_"+i+"_";
            String file=in.getStringOr(key+"file","");
            if(file.isBlank()||file.contains("/")||file.contains("\\")
                    ||file.contains(".."))continue;
            java.util.Map<String,Long> materials=new java.util.LinkedHashMap<>();
            java.util.Map<String,Long> pending=new java.util.LinkedHashMap<>();
            int size=Math.clamp(in.getIntOr(key+"material_count",0),0,1024);
            for(int n=0;n<size;n++){
                String item=in.getStringOr(key+"item_"+n,"");
                long count=Math.max(0,in.getLongOr(key+"required_"+n,0));
                if(!item.isBlank()&&count>0){
                    materials.put(item,count);
                    pending.put(item,Math.clamp(in.getLongOr(key+"remaining_"+n,0),0,count));
                }
            }
            String dim=in.getStringOr(key+"warehouse_dim","");
            WarehouseAssignment wh=dim.isBlank()?null:new WarehouseAssignment(dim,
                    in.getIntOr(key+"warehouse_x",0),in.getIntOr(key+"warehouse_y",0),
                    in.getIntOr(key+"warehouse_z",0));
            builderClearByFile.put(file,Math.max(0,in.getIntOr(key+"clearing",0)));
            builderJobs.add(new BuilderJob(file,in.getStringOr(key+"name",file),
                    in.read(key+"origin",BlockPos.CODEC).orElse(BlockPos.ZERO),
                    in.getIntOr(key+"rotation",0),in.getIntOr(key+"mirror",0),
                    in.getIntOr(key+"blocks",0),in.getIntOr(key+"unsupported",0),
                    in.getIntOr(key+"cursor",0),materials,pending,wh,
                    in.read(key+"storage",BlockPos.CODEC).orElse(null),
                    in.getStringOr(key+"phase","READY"),
                    in.getBooleanOr(key+"ignore_plants",false)));
        }
        selectedBuilderJob=Math.clamp(in.getIntOr("builder_selected_job",-1),-1,
                Math.max(-1,builderJobs.size()-1));
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
        builderIgnorePlants=in.getBooleanOr("builder_ignore_plants",false);
        builderRequired.clear();builderRemaining.clear();
        if(selectedBuilderJob>=0){
            BuilderJob job=builderJobs.get(selectedBuilderJob);
            builderRequired.putAll(job.required());
            builderRemaining.putAll(job.remaining());
            builderMaterialStorage=job.storage();
            builderIgnorePlants=job.ignorePlants();
        }else builderMaterialStorage=null;
        builderWarehouseStock.clear();builderStagedStock.clear();
        for(int i=0,n=Math.clamp(in.getIntOr("builder_staged_count",0),0,1024);i<n;i++){
            String id=in.getStringOr("builder_staged_item_"+i,"");
            int count=in.getIntOr("builder_staged_qty_"+i,0);
            if(!id.isBlank()&&count>=0)builderStagedStock.put(id,count);
        }
        builderStockChecked=in.getIntOr("builder_stock_checked",0)==1;
        builderWarehouseName=in.getStringOr("builder_warehouse_name","");
        String queuedDim=in.getStringOr("builder_pending_warehouse_dim","");
        pendingBuilderWarehouse=queuedDim.isBlank()?null:new WarehouseAssignment(queuedDim,
                in.getIntOr("builder_pending_warehouse_x",0),
                in.getIntOr("builder_pending_warehouse_y",0),
                in.getIntOr("builder_pending_warehouse_z",0));
        for(int i=0,n=Math.clamp(in.getIntOr("builder_stock_count",0),0,1024);i<n;i++){
            String id=in.getStringOr("builder_stock_item_"+i,"");
            int count=in.getIntOr("builder_stock_qty_"+i,0);
            if(!id.isBlank()&&count>=0)builderWarehouseStock.put(id,count);
        }
        builderCursor=Math.clamp(in.getIntOr("builder_cursor",0),0,builderBlocks);
        builderClearCursor=Math.max(0,in.getIntOr("builder_clear_cursor",
                builderClearByFile.getOrDefault(builderFile,0)));
        builderClearClaims.clear();builderClearClaimTick.clear();
        builderClearVerified.clear();
        for(int i=0,n=Math.clamp(in.getIntOr("builder_clear_verified_count",0),0,256);
                i<n;i++){
            int idx=in.getIntOr("builder_clear_verified_"+i,-1);
            if(idx>=builderClearCursor&&idx<builderClearCursor+256)
                builderClearVerified.add(idx);
        }
        builderMissingRetries.clear();
        builderWorkerId=uuid(in,"builder_worker");
        crewTarget=Math.clamp(in.getIntOr("builder_crew_target",1),1,isBuilder()?8:4);
        builderRecallRequested=in.getBooleanOr("builder_recall_requested",false);
        builderRetirementStorage=in.read("builder_retirement_storage",
                BlockPos.CODEC).orElse(null);
        additionalBuilderWorkers.clear();
        int extra=Math.clamp(in.getIntOr("builder_extra_workers",0),0,7);
        for(int j=0;j<extra;j++){
            UUID id=uuid(in,"builder_extra_worker_"+j);
            if(id!=null&&!additionalBuilderWorkers.contains(id)
                    &&!id.equals(builderWorkerId))additionalBuilderWorkers.add(id);
        }
        builderLastPositions.clear();
        int lastCount=Math.clamp(in.getIntOr("builder_last_count",0),0,8);
        for(int n=0;n<lastCount;n++){
            UUID id=uuid(in,"builder_last_worker_"+n);
            BlockPos pos=in.read("builder_last_position_"+n,BlockPos.CODEC)
                    .orElse(null);
            if(id!=null&&pos!=null&&builderWorkerIds().contains(id))
                builderLastPositions.put(id,pos.immutable());
        }
        builderFinishedSteps.clear();
        for(int d=0,n=Math.clamp(in.getIntOr("builder_done_count",0),0,80);
                d<n;d++){
            int finished=in.getIntOr("builder_done_"+d,-1);
            if(finished>=builderCursor&&finished<builderCursor+80)
                builderFinishedSteps.add(finished);
        }
        String encodedFinished=in.getStringOr("builder_finished_bits_v2","");
        if(!encodedFinished.isBlank()&&encodedFinished.length()<=18000)try{
            var bits=java.util.BitSet.valueOf(
                    java.util.Base64.getDecoder().decode(encodedFinished));
            for(int bit=bits.nextSetBit(0);bit>=0&&
                    bit<builderBlocks-builderCursor;bit=bits.nextSetBit(bit+1))
                builderFinishedSteps.add(builderCursor+bit);
        }catch(IllegalArgumentException ignored){}
        // Migrates saved 0.3.40-era ahead bits without losing completed
        // physical block placements when upgrading an active world.
        long legacyAhead=in.getLongOr("builder_ahead_bits",0L);
        for(int d=0;d<32;d++)
            if(((legacyAhead>>>d)&1L)!=0)
                builderFinishedSteps.add(builderCursor+d);
        builderWorkOrders.clear();builderPreparedOrders.clear();
        for(int bi=0,n=Math.clamp(in.getIntOr("builder_batch_count",0),0,8);
                bi<n;bi++){
            UUID id=uuid(in,"builder_batch_uuid_"+bi);
            if(id==null)continue;
            var list=new java.util.ArrayList<Integer>();
            for(int j=0,k=Math.clamp(in.getIntOr("builder_batch_size_"+bi,0),0,BUILDER_STAGE_SIZE);
                    j<k;j++){
                int index=in.getIntOr("builder_batch_step_"+bi+"_"+j,-1);
                if(index>=builderCursor&&index<builderBlocks
                        &&!builderFinishedSteps.contains(index)&&!list.contains(index))
                    list.add(index);
            }
            if(!list.isEmpty()){
                builderWorkOrders.put(id,list);
                if(in.getBooleanOr("builder_batch_prepared_"+bi,false))
                    builderPreparedOrders.add(id);
            }
        }
        completedAhead=in.getLongOr("builder_ahead_bits",0);
        builderClaims.clear();
        int claims=Math.clamp(in.getIntOr("builder_claim_count",0),0,8);
        for(int j=0;j<claims;j++){
            UUID id=uuid(in,"builder_claim_worker_"+j);
            int index=in.getIntOr("builder_claim_index_"+j,-1);
            if(id!=null&&index>=builderCursor&&index<builderBlocks
                    &&builderWorkerIds().contains(id)){
                builderClaims.put(id,index);
                // Upgrade a previously running one-coordinate assignment
                // to a real batch, or recover one missing from saved NBT.
                var batch=builderWorkOrders.computeIfAbsent(id,
                        k->new java.util.ArrayList<>());
                if(!batch.contains(index))batch.add(0,index);
            }
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
        builderCancelShipment=in.getBooleanOr("builder_cancel_shipment",false)
                &&supplyRequest!=null;
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
        // Migration for Builder tables from 0.3.24 and earlier: their one
        // selected schematic predated the job list and manifest format.
        if(builderJobs.isEmpty()&&builderPlaced&&!builderFile.isBlank()){
            selectedBuilderJob=0;
            builderJobs.add(snapshotBuilderJob());
        }
        items=NonNullList.withSize(SLOTS,ItemStack.EMPTY);
        ContainerHelper.loadAllItems(in,items);
    }
    @Override public CompoundTag getUpdateTag(HolderLookup.Provider p){return saveWithoutMetadata(p);}
    @Override public ClientboundBlockEntityDataPacket getUpdatePacket(){
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
