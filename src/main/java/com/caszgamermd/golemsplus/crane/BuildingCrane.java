package com.caszgamermd.golemsplus.crane;

import java.util.*;
import com.caszgamermd.golemsplus.builder.BuilderGolem;
import com.caszgamermd.golemsplus.builder.BuilderChunkTickets;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.*;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.player.*;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.*;
import net.minecraft.world.phys.*;

/** One persistent vehicle: rail chassis, operator seat, telescopic boom and interchangeable load. */
public final class BuildingCrane extends Entity implements Container {
    private static final EntityDataAccessor<Integer> MODE=SynchedEntityData.defineId(BuildingCrane.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> UPGRADES=SynchedEntityData.defineId(BuildingCrane.class,EntityDataSerializers.INT);
    private static final EntityDataAccessor<Float> AX=SynchedEntityData.defineId(BuildingCrane.class,EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> AY=SynchedEntityData.defineId(BuildingCrane.class,EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> AZ=SynchedEntityData.defineId(BuildingCrane.class,EntityDataSerializers.FLOAT);
    private UUID owner,job,operator;
    private BlockPos table=BlockPos.ZERO,anchor=BlockPos.ZERO,home=BlockPos.ZERO;
    private boolean onsite;
    private String status="Link a job and assign a builder operator";
    private final CraneDispatch dispatch=new CraneDispatch();
    private final Map<UUID,BlockPos> destinations=new LinkedHashMap<>();
    private final Map<UUID,Long> requestedAt=new HashMap<>();
    private final List<UUID> batch=new ArrayList<>();
    private final Map<UUID,Long> retry=new HashMap<>();
    private final NonNullList<ItemStack> pallets=NonNullList.withSize(2,ItemStack.EMPTY);
    private final NonNullList<ItemStack> stock=NonNullList.withSize(432,ItemStack.EMPTY);
    private List<Vec3> path=new ArrayList<>();
    private List<BlockPos> railPath=new ArrayList<>();
    private Vec3 destination;
    private BlockPos supplyStop;
    private UUID pickup;
    private boolean delivering,recalling;
    private long planAfter,pickupStarted;
    private int page;
    public BuildingCrane(EntityType<? extends BuildingCrane> type,Level level){super(type,level);setNoGravity(true);}
    @Override protected void defineSynchedData(SynchedEntityData.Builder b){b.define(MODE,-1);b.define(UPGRADES,0);b.define(AX,0f);b.define(AY,0f);b.define(AZ,0f);}
    public boolean hasBox(){return entityData.get(MODE)>=0;}
    public boolean supplyMode(){return entityData.get(MODE)==1;}
    public int reach(){return CraneDispatch.reach(entityData.get(UPGRADES));}
    public BlockPos anchor(){return anchor;}
    public Vec3 anchorPoint(){return new Vec3(entityData.get(AX),entityData.get(AY),entityData.get(AZ));}
    private void anchorPoint(Vec3 p){entityData.set(AX,(float)p.x);entityData.set(AY,(float)p.y);entityData.set(AZ,(float)p.z);}
    public UUID owner(){return owner;}
    public UUID operator(){return operator;}
    public String status(){return status+" | reach="+reach()+" | waiting="+destinations.size()+" | riders="+builders().size();}
    public boolean parked(){return destination==null&&path.isEmpty()&&railPath.isEmpty();}
    public boolean onsite(){return onsite;}
    public boolean serves(WorkerStationBlockEntity station){return table.equals(station.getBlockPos())&&Objects.equals(owner,station.owner())&&Objects.equals(job,station.activeOrder());}
    public WorkerStationBlockEntity station(){return level().getBlockEntity(table) instanceof WorkerStationBlockEntity s&&serves(s)?s:null;}
    public boolean ownsWorker(BuilderGolem w){return w.getUUID().equals(operator)||destinations.containsKey(w.getUUID())||w.getVehicle()==this;}
    public void place(UUID owner,BlockPos anchor,Vec3 load){this.owner=owner;this.anchor=anchor.immutable();this.home=BlockPos.containing(load);anchorPoint(CraneMovement.anchorPoint((ServerLevel)level(),anchor));setPos(load);}
    public boolean link(WorkerStationBlockEntity s){
        if(!parked()||!destinations.isEmpty()||!batch.isEmpty()
                ||!Objects.equals(owner,s.owner())||s.activeOrder()==null||!builders().isEmpty())return false;
        table=s.getBlockPos().immutable();job=s.activeOrder();dispatch.clear();destinations.clear();batch.clear();status="Job linked; select a builder operator";return true;
    }
    public boolean assignOperator(BuilderGolem w){
        if(!Objects.equals(owner,w.owner())||!w.home().equals(table)||operator!=null||w.isPassenger())return false;
        operator=w.getUUID();var s=station();if(s!=null){s.releaseBuilderWorkOrder(operator);s.releaseBuilderClear(operator);}
        status="Operator approaching chassis";return true;
    }
    public boolean request(BuilderGolem w,BlockPos goal){
        if(!hasBox()||supplyMode()||operator==null||w.getUUID().equals(operator)||station()==null||!w.home().equals(table))return false;
        if(destinations.size()>=32&&!destinations.containsKey(w.getUUID()))return false;
        if(level().getGameTime()<retry.getOrDefault(w.getUUID(),0L))return false;
        if(!destinations.containsKey(w.getUUID())){
            WorkerNavigation.stop(w);
            destinations.put(w.getUUID(),goal.immutable());requestedAt.put(w.getUUID(),level().getGameTime());
            dispatch.request(w.getUUID(),w.blockPosition().getY(),goal.getY(),level().getGameTime());
        }
        return true;
    }
    public void requestSupplyStop(BlockPos p){if(supplyMode()&&operator!=null&&station()!=null){supplyStop=p.immutable();planAfter=0;}}
    public void recall(){recalling=true;dispatch.clear();batch.clear();destinations.clear();supplyStop=null;path.clear();railPath.clear();destination=null;status="Recalling to safe home landing";}
    private List<BuilderGolem> builders(){return getPassengers().stream().filter(e->e instanceof BuilderGolem&&!e.getUUID().equals(operator)).map(e->(BuilderGolem)e).toList();}
    @Override protected boolean canAddPassenger(Entity e){return e instanceof BuilderGolem&&(e.getUUID().equals(operator)||!supplyMode()&&builders().size()<4);}
    @Override protected void positionRider(Entity e,Entity.MoveFunction move){
        if(e.getUUID().equals(operator)){Vec3 a=anchorPoint();move.accept(e,a.x,a.y+1,a.z);}
        else {int i=Math.max(0,builders().indexOf(e));move.accept(e,getX()+(i%2==0?-.5:.5),getY()+.25,getZ()+(i<2?-.5:.5));}
    }
    @Override public boolean hurtServer(ServerLevel world,DamageSource source,float amount){return false;}
    @Override public boolean isPickable(){return true;}
    @Override public void tick(){
        super.tick();if(!(level() instanceof ServerLevel world))return;
        CraneService.track(world,this);
        setBoundingBox(CraneMovement.load(this,position()));
        if(operator==null){status="Needs a builder operator";return;}
        Entity driver=world.getEntity(operator);
        if(!(driver instanceof BuilderGolem builder)){status="Operator unloaded; waiting safely";return;}
        if(builder.getVehicle()!=this){
            Vec3 a=anchorPoint();
            if(builder.position().distanceTo(a)<2.2){WorkerNavigation.stop(builder);builder.startRiding(this);}
            else {CraneService.walk(world,builder,BlockPos.containing(a));return;}
        }
        if(station()==null&&!recalling){recall();status="Job ended or unavailable; returning passengers";}
        if(!path.isEmpty()||!railPath.isEmpty()){moveAlong(world);return;}
        if(destination!=null){
            if(position().distanceTo(destination)<.12){destination=null;status="Parked";updateOnsite();}
            else if(world.getGameTime()>=planAfter)plan(world,destination);
            return;
        }
        if(recalling){
            Vec3 end=Vec3.atBottomCenterOf(home);
            if(position().distanceTo(end)>.15){go(world,end);return;}
            boolean all=true;for(BuilderGolem rider:builders())all&=unload(world,rider,home);
            if(all){recalling=false;status="Home";}return;
        }
        if(supplyMode()){
            if(supplyStop!=null){Vec3 end=Vec3.atBottomCenterOf(supplyStop);
                if(position().distanceTo(end)>.15){go(world,end);return;}
                supplyStop=null;updateOnsite();status="Supply box parked";}
            return;
        }
        // Requests refresh through the builder loop; offline workers cannot hold a batch forever.
        for(UUID id:new ArrayList<>(destinations.keySet()))
            if(!(world.getEntity(id) instanceof BuilderGolem)&&world.getGameTime()-requestedAt.getOrDefault(id,0L)>1200)forget(id);
        if(!hasBox()){status="Attach a builder box or supply box";return;}
        if(batch.isEmpty()){
            batch.addAll(dispatch.batch());delivering=false;
            if(batch.isEmpty()){status="Ready";return;}
        }
        if(!delivering){
            UUID next=batch.stream().filter(id->world.getEntity(id) instanceof BuilderGolem w&&w.getVehicle()!=this).findFirst().orElse(null);
            if(next==null){delivering=true;pickup=null;return;}
            BuilderGolem w=(BuilderGolem)world.getEntity(next);
            if(!Objects.equals(next,pickup)){pickup=next;pickupStarted=world.getGameTime();}
            BlockPos dock=CraneMovement.landing(world,this,w,w.blockPosition());
            if(dock==null||world.getGameTime()-pickupStarted>400){defer(next);return;}
            Vec3 at=Vec3.atBottomCenterOf(dock);
            if(position().distanceTo(at)>.15){go(world,at);return;}
            if(w.distanceToSqr(getX(),getY(),getZ())>3.24){CraneService.walk(world,w,dock);return;}
            WorkerNavigation.stop(w);w.startRiding(this);status="Boarding builders";return;
        }
        // Finish the current direction before accepting another batch. One
        // unfinished/obstructed platform must not trap three other builders.
        List<BuilderGolem> current=builders().stream()
                .filter(w->batch.contains(w.getUUID())).toList();
        if(current.isEmpty()){batch.clear();return;}
        Map<UUID,BlockPos> docks=new LinkedHashMap<>();
        Map<UUID,Double> distances=new LinkedHashMap<>();
        for(BuilderGolem candidate:current){
            BlockPos target=destinations.getOrDefault(candidate.getUUID(),home);
            BlockPos safeDock=CraneMovement.landing(world,this,candidate,target);
            if(safeDock==null)continue;
            docks.put(candidate.getUUID(),safeDock);
            distances.put(candidate.getUUID(),safeDock.distSqr(blockPosition()));
        }
        UUID next=CraneDispatch.nearestReachable(distances);
        if(next==null){
            status="All passenger landings blocked; waiting for a safe platform or recall";
            return;
        }
        BuilderGolem rider=current.stream().filter(w->next.equals(w.getUUID())).findFirst().orElseThrow();
        BlockPos dock=docks.get(next);
        Vec3 at=Vec3.atBottomCenterOf(dock);
        if(position().distanceTo(at)>.15){go(world,at);return;}
        if(unload(world,rider,dock)){forget(rider.getUUID());status="Builder delivered";}
    }
    private void defer(UUID id){retry.put(id,level().getGameTime()+100);forget(id);status="Pickup unavailable; request deferred";}
    private void forget(UUID id){dispatch.remove(id);destinations.remove(id);requestedAt.remove(id);batch.remove(id);}
    private boolean unload(ServerLevel world,BuilderGolem w,BlockPos dock){
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
            BlockPos p=dock.offset(dx,0,dz);if(!WorkerNavigation.canStandOn(world,w,p))continue;
            AABB body=new AABB(p.getX()+.175,p.getY(),p.getZ()+.175,p.getX()+.825,p.getY()+1.62,p.getZ()+.825);
            if(!world.noCollision(w,body))continue;
            w.stopRiding();w.setPos(p.getX()+.5,p.getY(),p.getZ()+.5);w.setDeltaMovement(Vec3.ZERO);return true;
        }
        status="Landing occupied; waiting";return false;
    }
    private void go(ServerLevel world,Vec3 at){
        destination=at;
        // Crane operators belong to the planner crew; use its bounded,
        // temporary simulation tickets to load the destination ahead of
        // path checks rather than treating distant chunks as solid walls.
        if(world.getEntity(operator) instanceof BuilderGolem driver)
            BuilderChunkTickets.destination(world,driver,BlockPos.containing(at));
        if(world.getGameTime()>=planAfter)plan(world,at);
    }
    private void plan(ServerLevel world,Vec3 at){
        planAfter=world.getGameTime()+40;
        List<Vec3> route=CraneMovement.loadPath(world,this,at);
        if(route!=null){path=new ArrayList<>(route);status="Moving load";return;}
        // Try all four stow directions so a wall on the east side cannot
        // strand a crane whose north/south/west retract position is clear.
        // Once already stowed, do not repeatedly switch between directions.
        List<Vec3> stows=List.of(anchorPoint().add(3,0,0),
                anchorPoint().add(-3,0,0),anchorPoint().add(0,0,3),
                anchorPoint().add(0,0,-3));
        boolean stowed=stows.stream().anyMatch(p->position().distanceTo(p)<=.2);
        if(!stowed){
            var ordered=new ArrayList<>(stows);
            ordered.sort(Comparator.comparingDouble(p->p.distanceToSqr(position())));
            for(Vec3 stow:ordered){
                route=CraneMovement.loadPath(world,this,stow);
                if(route!=null){path=new ArrayList<>(route);status="Retracting for rail travel";return;}
            }
        }
        List<BlockPos> rails=CraneMovement.railPath(world,this,at);
        if(rails!=null&&!rails.isEmpty()){railPath=new ArrayList<>(rails);status="Travelling crane rails";return;}
        status="Blocked or out of reach: add clear connected crane rails";
    }
    private void moveAlong(ServerLevel world){
        Vec3 old=position(),a=anchorPoint(),nextA=a,next;
        if(!railPath.isEmpty()){
            BlockPos rail=railPath.get(0);
            if(!world.getBlockState(rail).is(CraneContent.RAIL)||!world.getBlockState(rail).canSurvive(world,rail)){
                railPath.clear();status="Rail missing or unsupported";return;}
            Vec3 end=CraneMovement.anchorPoint(world,rail),delta=end.subtract(a);nextA=a.add(delta.length()>.1?delta.normalize().scale(.1):delta);next=old.add(nextA.subtract(a));
        }else{Vec3 delta=path.get(0).subtract(old);next=old.add(delta.length()>.12?delta.normalize().scale(.12):delta);}
        if(!CraneMovement.swept(world,this,old,next,a,nextA)){
            path.clear();railPath.clear();planAfter=world.getGameTime()+40;status="Obstacle detected; stopped";return;}
        move(MoverType.SELF,next.subtract(old));setDeltaMovement(Vec3.ZERO);
        if(position().distanceTo(next)>.025){path.clear();railPath.clear();status="Load blocked by entity";return;}
        anchorPoint(nextA);
        if(!railPath.isEmpty()){
            if(nextA.distanceTo(CraneMovement.anchorPoint(world,railPath.get(0)))<.02)anchor=railPath.remove(0);
        }else if(position().distanceTo(path.get(0))<.02)path.remove(0);
    }
    private void updateOnsite(){var s=station();if(onsite&&supplyMode()&&parked()&&s!=null)s.setBuilderCraneStorage(owner,job,blockPosition());}
    public void setOnsite(boolean value){onsite=value;updateOnsite();}
    public void openPage(ServerPlayer player,int page){this.page=Math.floorMod(page,8);int start=this.page*54;
        Container view=new SimpleContainer(54){
            @Override public ItemStack getItem(int i){return BuildingCrane.this.getItem(start+i);}
            @Override public ItemStack removeItem(int i,int n){return BuildingCrane.this.removeItem(start+i,n);}
            @Override public ItemStack removeItemNoUpdate(int i){return BuildingCrane.this.removeItemNoUpdate(start+i);}
            @Override public void setItem(int i,ItemStack item){BuildingCrane.this.setItem(start+i,item);}
            @Override public boolean canPlaceItem(int i,ItemStack item){return BuildingCrane.this.canPlaceItem(start+i,item);}
            @Override public boolean stillValid(Player p){return BuildingCrane.this.stillValid(p);}
        };
        player.openMenu(new SimpleMenuProvider((id,inv,p)->ChestMenu.sixRows(id,inv,view),Component.literal("Crane supplies — page "+(this.page+1)+"/8")));
    }
    public InteractionResult interact(Player player,InteractionHand hand){
        if(!(player instanceof ServerPlayer p))return InteractionResult.SUCCESS;
        if(!p.getUUID().equals(owner))return InteractionResult.FAIL;
        ItemStack held=p.getItemInHand(hand);
        if(held.is(CraneContent.CONTROLLER)){CraneService.selectCrane(p,this);return InteractionResult.SUCCESS;}
        if(!parked()){CraneService.tell(p,"Crane must be parked for configuration or cargo access");return InteractionResult.SUCCESS;}
        if(held.is(CraneContent.BOOM)&&entityData.get(UPGRADES)<2){entityData.set(UPGRADES,entityData.get(UPGRADES)+1);held.shrink(1);}
        else if((held.is(CraneContent.SUPPLY_BOX)||held.is(CraneContent.BUILDER_BOX))&&builders().isEmpty()&&isEmpty()&&pallets.stream().allMatch(ItemStack::isEmpty)){
            boolean supply=held.is(CraneContent.SUPPLY_BOX);if(!hasBox()||supply!=supplyMode()){
                ItemStack old=hasBox()?new ItemStack(supplyMode()?CraneContent.SUPPLY_BOX:CraneContent.BUILDER_BOX):ItemStack.EMPTY;
                held.shrink(1);if(!old.isEmpty()&&!p.getInventory().add(old))p.drop(old,false);entityData.set(MODE,supply?1:0);
                onsite=false;destinations.clear();dispatch.clear();batch.clear();
            }
        }else if(supplyMode()&&isPallet(held)){
            for(int i=0;i<2;i++)if(pallets.get(i).isEmpty()){pallets.set(i,held.copyWithCount(1));held.shrink(1);readPallet(i);break;}
        }else if(supplyMode()&&held.is(Items.SHEARS)){
            for(int i=1;i>=0;i--)if(!pallets.get(i).isEmpty()){
                ItemStack pallet=packed(i);pallets.set(i,ItemStack.EMPTY);for(int j=0;j<216;j++)stock.set(i*216+j,ItemStack.EMPTY);
                if(!p.getInventory().add(pallet))p.drop(pallet,false);break;}
        }else if(supplyMode()&&held.isEmpty()){openPage(p,p.isShiftKeyDown()?page+1:page);}
        else CraneService.tell(p,status());
        return InteractionResult.SUCCESS;
    }
    static boolean isPallet(ItemStack item){return item.getItem() instanceof BlockItem b&&b.getBlock().getClass().getName().equals("com.caszgamermd.caszualadditions.pallet.PalletBlock");}
    private void readPallet(int slot){var items=NonNullList.withSize(216,ItemStack.EMPTY);pallets.get(slot).getOrDefault(DataComponents.CONTAINER,ItemContainerContents.EMPTY).copyInto(items);for(int i=0;i<216;i++)stock.set(slot*216+i,items.get(i));}
    private ItemStack packed(int slot){ItemStack result=pallets.get(slot).copy();if(!result.isEmpty())result.set(DataComponents.CONTAINER,ItemContainerContents.fromItems(stock.subList(slot*216,slot*216+216)));return result;}
    @Override public int getContainerSize(){return 432;}
    @Override public boolean isEmpty(){return stock.stream().allMatch(ItemStack::isEmpty);}
    @Override public ItemStack getItem(int i){return i>=0&&i<432?stock.get(i):ItemStack.EMPTY;}
    @Override public ItemStack removeItem(int i,int n){return i>=0&&i<432?ContainerHelper.removeItem(stock,i,n):ItemStack.EMPTY;}
    @Override public ItemStack removeItemNoUpdate(int i){return i>=0&&i<432?ContainerHelper.takeItem(stock,i):ItemStack.EMPTY;}
    @Override public void setItem(int i,ItemStack item){if(i>=0&&i<432&&!pallets.get(i/216).isEmpty())stock.set(i,item.copy());}
    @Override public boolean canPlaceItem(int i,ItemStack item){return i>=0&&i<432&&!pallets.get(i/216).isEmpty();}
    @Override public void setChanged(){}
    @Override public void clearContent(){for(int i=0;i<432;i++)stock.set(i,ItemStack.EMPTY);}
    @Override public boolean stillValid(Player p){return parked()&&p.getUUID().equals(owner)&&distanceToSqr(p)<64;}
    @Override protected void addAdditionalSaveData(ValueOutput out){
        if(owner!=null)out.putString("owner",owner.toString());if(job!=null)out.putString("job",job.toString());if(operator!=null)out.putString("operator",operator.toString());
        out.store("table",BlockPos.CODEC,table);out.store("anchor",BlockPos.CODEC,anchor);out.store("home",BlockPos.CODEC,home);
        out.putDouble("ax",anchorPoint().x);out.putDouble("ay",anchorPoint().y);out.putDouble("az",anchorPoint().z);
        out.putInt("mode",entityData.get(MODE));out.putInt("upgrades",entityData.get(UPGRADES));out.putBoolean("onsite",onsite);out.putBoolean("recall",recalling);
        for(int i=0;i<2;i++)out.store("pallet"+i,ItemStack.CODEC,packed(i));
        out.putInt("requests",destinations.size());int i=0;for(var row:destinations.entrySet()){
            out.putString("worker"+i,row.getKey().toString());out.store("goal"+i,BlockPos.CODEC,row.getValue());i++;}
        if(supplyStop!=null)out.store("supply_stop",BlockPos.CODEC,supplyStop);
    }
    @Override protected void readAdditionalSaveData(ValueInput in){
        owner=uuid(in.getStringOr("owner",""));job=uuid(in.getStringOr("job",""));operator=uuid(in.getStringOr("operator",""));
        table=in.read("table",BlockPos.CODEC).orElse(BlockPos.ZERO);anchor=in.read("anchor",BlockPos.CODEC).orElse(BlockPos.ZERO);home=in.read("home",BlockPos.CODEC).orElse(anchor);
        anchorPoint(new Vec3(in.getDoubleOr("ax",anchor.getX()+.5),in.getDoubleOr("ay",anchor.getY()),in.getDoubleOr("az",anchor.getZ()+.5)));
        entityData.set(MODE,Math.clamp(in.getIntOr("mode",-1),-1,1));entityData.set(UPGRADES,Math.clamp(in.getIntOr("upgrades",0),0,2));onsite=in.getBooleanOr("onsite",false);recalling=in.getBooleanOr("recall",false);
        for(int i=0;i<2;i++){pallets.set(i,in.read("pallet"+i,ItemStack.CODEC).orElse(ItemStack.EMPTY));readPallet(i);}
        for(int i=0;i<Math.clamp(in.getIntOr("requests",0),0,32);i++){
            UUID id=uuid(in.getStringOr("worker"+i,""));BlockPos goal=in.read("goal"+i,BlockPos.CODEC).orElse(home);
            if(id!=null){destinations.put(id,goal);requestedAt.put(id,level().getGameTime());dispatch.request(id,blockPosition().getY(),goal.getY(),level().getGameTime());}}
        supplyStop=in.read("supply_stop",BlockPos.CODEC).orElse(null);
    }
    private static UUID uuid(String text){try{return UUID.fromString(text);}catch(IllegalArgumentException e){return null;}}
}
