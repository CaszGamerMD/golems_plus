package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Test-only reverse building laboratory, not a secret production demolition
 * ability. One actual BuilderGolem physically walks, jumps, drops, grapples,
 * dismantles ONE reachable block per mining action, and carries at most
 * 192 collected real items in its existing nine-slot pouch.
 *
 * The dome is explicitly PREBUILT by the fixture, never by the golem.
 * Inventory is unloaded only at the nearby physical collection point.
 * An external experiment tally records items when that depot receives them.
 * Every selected target, movement and removal is logged for study.
 */
public final class DomeReverseDemolitionGameTest {
    private static final int CAPACITY=192;
    private static final int MATERIAL_SLOTS=8; // ninth slot holds a real hook
    private static final int MINING_TICKS=8;
    private static final int BLOCKS_PER_SCAN=8;
    // Gradle runGameTest's working directory is build/run/gameTest.
    // Use the SAME logs directory already included by CI's artifact
    // uploader; previous CSVs accidentally went into a nested build/.
    private static final Path REPORT=Path.of("logs/reverse_dome_study.csv");
    private static final Path SUMMARY=Path.of("logs/reverse_dome_summary.txt");

    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=90000,skyAccess=true)
    public void oneGolemReverseMinesOriginalDome(GameTestHelper h){
        ServerLevel world=h.getLevel();
        BlockPos origin=h.absolutePos(new BlockPos(8,2,8));
        // The same full 39x39 ground and ORIGINAL checksum-verified Dome
        // used in the real construction challenge.
        // The original dome anchors at local (8,2,8) and spans
        // 39 x 39: its far edge is x/z=46, not 42. The old physical
        // dome fixture omitted four ground-support strips, which can
        // cause schematic plants and wall approaches to fail.
        // The Litematic includes ground-level short grass and azaleas.
        // Those plants cannot survive on the old artificial STONE base.
        // Give the PREBUILT fixture natural supported grassy terrain;
        // this is surrounding world ground, not another Dome block.
        for(int x=4;x<=47;x++)for(int z=4;z<=47;z++)
            h.setBlock(x,1,z,Blocks.GRASS_BLOCK);
        // Depot sits beside the ACTUAL western rim near schematic Z=19.
        // The square 39x39 bounding box has empty corners; positioning
        // the depot near Z=0 would be twenty blocks from the real dome.
        h.setBlock(4,2,27,WorksiteContent.BUILDER_TABLE);
        h.setBlock(5,2,27,Blocks.CHEST);
        BlockPos depot=h.absolutePos(new BlockPos(5,2,27));
        var table=(WorkerStationBlockEntity)world.getBlockEntity(
                h.absolutePos(new BlockPos(4,2,27)));
        if(table==null){h.fail("No real demo workstation for grappling");return;}
        LinkedHashMap<BlockPos,BlockState> remaining=new LinkedHashMap<>();
        try{
            var schematic=LitematicFileReader.read(BuilderDomeFixture.unpack());
            var site=new BuildSite(origin.getX(),origin.getY(),origin.getZ(),
                    BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
            var plan=BuilderConstructionPlan.create(schematic,site,false);
            if(plan.steps().size()!=3537)
                throw new IllegalStateException("Not the original 3537-block dome");
            for(var step:plan.steps()){
                var result=BuilderBlockResolver.resolve(step.encodedState(),
                        BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
                if(result==null)
                    throw new IllegalStateException(
                            "Unsupported prebuilt block "+step.encodedState());
                BlockPos p=new BlockPos(step.x(),step.y(),step.z());
                if(remaining.putIfAbsent(p,result.state())!=null)
                    throw new IllegalStateException("Duplicate blueprint cell "+p);
            }
            // Survival-sensitive plants must be added AFTER their stone
            // supports and neighboring structural blocks, or vanilla
            // neighbor updates legitimately pop them before the test
            // begins. This is only TEST fixture prebuilding, not a
            // Builder Golem placement or a demolition shortcut.
            for(var entry:remaining.entrySet()){
                BlockPos p=entry.getKey();
                BlockState state=entry.getValue();
                if(BuilderVegetation.ignoreSchematicState(
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK
                        .getKey(state.getBlock()).toString()))
                    continue;
                if(!world.setBlock(p,state,2))
                    throw new IllegalStateException("Could not prebuild "+p);
            }
            for(var entry:remaining.entrySet()){
                BlockPos p=entry.getKey();
                BlockState state=entry.getValue();
                if(!BuilderVegetation.ignoreSchematicState(
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK
                        .getKey(state.getBlock()).toString()))
                    continue;
                if(!state.canSurvive(world,p)
                        &&state.hasProperty(
                            net.minecraft.world.level.block.state.properties
                            .BlockStateProperties.DOUBLE_BLOCK_HALF)
                        &&state.getValue(
                            net.minecraft.world.level.block.state.properties
                            .BlockStateProperties.DOUBLE_BLOCK_HALF)
                            ==net.minecraft.world.level.block.state.properties
                                .DoubleBlockHalf.UPPER
                        &&!remaining.containsKey(p.below())){
                    // The original source is clipped at Y=0 and includes
                    // upper halves of two-high flowers with the corresponding
                    // lower halves OUTSIDE its recorded region. No ground
                    // substrate can support an upper-half peony directly.
                    // Restore only the non-schematic lower flower and its
                    // real soil, keeping all 3537 original cells unchanged.
                    BlockPos lower=p.below();
                    world.setBlock(lower.below(),
                            Blocks.GRASS_BLOCK.defaultBlockState(),2);
                    BlockState lowerHalf=state.setValue(
                            net.minecraft.world.level.block.state.properties
                            .BlockStateProperties.DOUBLE_BLOCK_HALF,
                            net.minecraft.world.level.block.state.properties
                            .DoubleBlockHalf.LOWER);
                    world.setBlock(lower,lowerHalf,2);
                    System.out.println("[REVERSE TERRAIN] restored clipped "
                            +"outside-schematic lower flower "+lowerHalf
                            +" for upper "+p);
                }
                if(!state.canSurvive(world,p)){
                    // The schematic does not encode the SURROUNDING
                    // natural terrain at Y=-1. Preserve all original
                    // dome blocks; only vary the test's OUTSIDE ground
                    // directly under a plant whose substrate requires
                    // a different real material (e.g. dead bush/sand).
                    if(remaining.containsKey(p.below()))
                        throw new IllegalStateException(
                                "Schematic plant is unsupported by an "
                                +"original dome block: "+state+" at "+p);
                    boolean supported=false;
                    for(BlockState ground:List.of(
                            Blocks.DIRT.defaultBlockState(),
                            Blocks.SAND.defaultBlockState(),
                            Blocks.RED_SAND.defaultBlockState(),
                            Blocks.SOUL_SAND.defaultBlockState(),
                            Blocks.FARMLAND.defaultBlockState(),
                            Blocks.CLAY.defaultBlockState(),
                            Blocks.MOSS_BLOCK.defaultBlockState(),
                            Blocks.NETHERRACK.defaultBlockState(),
                            Blocks.MUD.defaultBlockState())){
                        world.setBlock(p.below(),ground,2);
                        if(state.canSurvive(world,p)){
                            supported=true;
                            System.out.println("[REVERSE TERRAIN] "
                                    +state+" at "+p
                                    +" requires "+ground+" ground");
                            break;
                        }
                    }
                    if(!supported)
                        throw new IllegalStateException(
                                "No naturally valid terrain for "+state
                                +" at "+p+" below="+world.getBlockState(p.below()));
                }
                if(!world.setBlock(p,state,2))
                    throw new IllegalStateException("Could not prebuild vegetation "+p);
            }
            for(var entry:remaining.entrySet())
                if(!world.getBlockState(entry.getKey()).equals(entry.getValue()))
                    throw new IllegalStateException("Prebuilt state differs at "
                            +entry.getKey()+" got "+world.getBlockState(entry.getKey())
                            +" expected "+entry.getValue());
        }catch(Exception error){
            h.fail("Exact PREBUILT dome setup failed: "+error);return;
        }
        BuilderGolem golem=new BuilderGolem(
                WorksiteContent.BUILDER_GOLEM,world);
        // A dome is circular! The southwest corner of its 39x39
        // bounding box is AIR, so a (7,2,7) spawn had no legal block
        // within 3 blocks and produced zero movements. Find the real
        // westernmost BLOCK of the bottom layer and stand one tile
        // OUTSIDE its exposed face instead.
        BlockPos western=remaining.keySet().stream()
                .filter(p->p.getY()==origin.getY())
                .min(Comparator.comparingInt((BlockPos p)->p.getX())
                    .thenComparingInt(p->Math.abs(
                            p.getZ()-origin.getZ()-19)))
                .orElseThrow();
        BlockPos start=western.west();
        if(!world.getBlockState(start).isAir()){
            h.fail("No physical entry outside dome at "+start);return;
        }
        System.out.println("[REVERSE ENTRY] starting outside "
                +western+" at "+start+" local="+
                (start.getX()-origin.getX())+"/"+
                (start.getY()-origin.getY())+"/"+
                (start.getZ()-origin.getZ()));
        // A GameTest fixture advances its own clock even when no real entity
        // simulation ticket covers a spawned mob. This independent demolition
        // Golem has no live construction order, so the production job ticket
        // manager intentionally does not keep it ticking. Prime every dome
        // chunk and the nearby depot with explicit scoped test tickets.
        var simulationChunks=new java.util.LinkedHashSet<
                net.minecraft.world.level.ChunkPos>();
        simulationChunks.add(new net.minecraft.world.level.ChunkPos(
                start.getX() >> 4,start.getZ() >> 4));
        simulationChunks.add(new net.minecraft.world.level.ChunkPos(
                depot.getX() >> 4,depot.getZ() >> 4));
        for(int dx=0;dx<=38;dx+=19)for(int dz=0;dz<=38;dz+=19){
            BlockPos p=origin.offset(dx,0,dz);
            simulationChunks.add(new net.minecraft.world.level.ChunkPos(
                    p.getX() >> 4,p.getZ() >> 4));
        }
        // The GameTest harness itself can advance its clock while an
        // otherwise loaded chunk is not yet ENTITY-SIMULATED. Explicit
        // vanilla forceload is test-only and scoped; it complements the
        // independent simulation tickets rather than altering pathfinding.
        for(var chunk:simulationChunks){
            world.setChunkForced(chunk.x(),chunk.z(),true);
            BuilderChunkTickets.holdPhysicalGameTestChunk(world,new BlockPos(
                    chunk.x() << 4,origin.getY(),chunk.z() << 4));
        }
        Runnable releaseSimulation=()->{
            for(var chunk:simulationChunks){
                BuilderChunkTickets.releasePhysicalGameTestChunk(world,
                        new BlockPos(chunk.x() << 4,origin.getY(),chunk.z() << 4));
                world.setChunkForced(chunk.x(),chunk.z(),false);
            }
        };
        System.out.println("[REVERSE SIMULATION] primed "+
                simulationChunks.size()+" real entity-ticking chunks");
        golem.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        golem.setItem(8,new ItemStack(WorksiteContent.GRAPPLE_HOOK));
        world.addFreshEntity(golem);
        WorkerNavigation.setReverseDemolitionTraining(golem,true);
        Study study=new Study(world,golem,table,depot,origin,remaining);
        System.out.println("[REVERSE DOME] PREBUILT 3537 original physical blocks;"
                +" ONE actual Builder; reach=3; jump=1; drop=2; "
                +"hook for taller elevations; pouch=192; base=(0,0,0)");
        final int[] lastEntityTick={0};
        final long[] lastEntityAdvance={0};
        h.onEachTick(()->{
            long tick=h.getTick();
            // Periodically renew the dedicated GameTest simulation ticket.
            // Ticket ownership is independent of active Builder jobs.
            if(tick%40==0)
                for(var chunk:simulationChunks)
                    BuilderChunkTickets.holdPhysicalGameTestChunk(world,
                            new BlockPos(chunk.x() << 4,origin.getY(),
                                    chunk.z() << 4));
            if(golem.tickCount>lastEntityTick[0]){
                lastEntityTick[0]=golem.tickCount;
                lastEntityAdvance[0]=tick;
            }
            if(tick>300&&tick-lastEntityAdvance[0]>200){
                String stalled="Entity simulation stopped GameTest="+tick
                        +" mobTicks="+golem.tickCount
                        +" inWorld="+(world.getEntity(golem.getUUID())==golem)
                        +" removed="+golem.isRemoved()
                        +" chunk="+(golem.blockPosition().getX()>>4)+"/"
                                    +(golem.blockPosition().getZ()>>4)
                        +" "+study.status();
                study.writeReports("FAIL "+stalled);
                releaseSimulation.run();
                h.fail(stalled);return;
            }
            if(!golem.isAlive()){
                study.writeReports("FAIL lost real golem at tick "+tick);
                releaseSimulation.run();
                h.fail("Reverse demolition golem died");return;
            }
            study.observe(tick);
            if(tick==300 && golem.tickCount<10){
                study.writeReports("FAIL entity never received simulation ticks");
                releaseSimulation.run();
                h.fail("Study entity did not tick: GameTest="+tick
                        +" mob="+golem.tickCount);
                return;
            }
            if(tick%5!=0)return;
            String failure=study.tick(tick);
            if(tick%400==0)
                System.out.println("[REVERSE PROGRESS] tick="+tick
                        +" mined="+study.mined+"/3537"
                        +" physics="+study.physicsCleared
                        +" cleared="+(study.mined+study.physicsCleared)+"/3537"
                        +" carried="+study.carried()
                        +" deposited="+study.deposited
                        +" unloads="+study.unloads
                        +" jump="+study.jumps+" drop="+study.drops
                        +" grapples="+study.grapples
                        +" currentY="+(golem.blockPosition().getY()-origin.getY())
                        +" mobTicks="+golem.tickCount
                        +" grounded="+golem.onGround());
            if(failure!=null){
                study.writeReports("FAIL "+failure);
                releaseSimulation.run();
                h.fail(failure+" | "+study.status());return;
            }
            if(study.remaining.isEmpty()){
                if(study.carried()>0&&!study.finishedUnloading())return;
                if(study.mined+study.physicsCleared!=3537
                        ||study.deposited!=study.itemsCollected){
                    study.writeReports("FAIL accounting");
                    releaseSimulation.run();
                    h.fail("Block/item accounting mismatched "+study.status());
                    return;
                }
                study.writeReports("SUCCESS original dome fully dismantled");
                releaseSimulation.run();
                System.out.println("[REVERSE DOME] PASS original 3537 cells cleared; "
                        +"manual BREAKs="+study.mined
                        +" natural physics pops="+study.physicsCleared
                        +" real carried items delivered="+study.deposited+"; "
                        +study.status());
                h.succeed();
            }
        });
    }

    private static final class Study {
        final ServerLevel level;
        final BuilderGolem mob;
        final WorkerStationBlockEntity table;
        final BlockPos depot,origin;
        final LinkedHashMap<BlockPos,BlockState> remaining;
        final Map<BlockPos,Long> excludedUntil=new HashMap<>();
        final Map<Item,Long> depotTally=new LinkedHashMap<>();
        final StringBuilder csv=new StringBuilder(
                "tick,action,localX,localY,localZ,feetX,feetY,feetZ,"
                +"route,carried,mined,deposited,meters,jumps,drops,grapples\n");
        BlockPos target,stance,lastBroken;
        // Adaptive physical return route; never a recorded path or teleport.
        final List<BlockPos> outerRing=new ArrayList<>();
        int ringExit=-1,ringAt=-1,ringDirection=0,returnDetours;
        BlockPos returnWaypoint;
        long waypointProgressTick;
        double bestWaypointDistance=Double.POSITIVE_INFINITY;
        Vec3 previous;
        long lastProgress,lastRouteProgress,lastScanTick;
        int mined,physicsCleared,itemlessBlocks,itemsCollected,unloads,jumps,drops,grapples,scanOffset,routeFailures;
        long deposited;
        boolean unloading;
        int miningAge;
        double walkMeters,closestDistance=Double.POSITIVE_INFINITY;
        String lastAction="prebuilt";
        Study(ServerLevel level,BuilderGolem mob,
                WorkerStationBlockEntity table,BlockPos depot,BlockPos origin,
                LinkedHashMap<BlockPos,BlockState> remaining){
            this.level=level;this.mob=mob;this.table=table;
            this.depot=depot;this.origin=origin;this.remaining=remaining;
            this.previous=mob.position();
            // Derive an open outside walking corridor from the ORIGINAL
            // blueprint bounds, before dismantling changes the silhouette.
            int minX=remaining.keySet().stream().mapToInt((BlockPos p)->p.getX()).min().orElse(origin.getX());
            int maxX=remaining.keySet().stream().mapToInt((BlockPos p)->p.getX()).max().orElse(origin.getX());
            int minZ=remaining.keySet().stream().mapToInt((BlockPos p)->p.getZ()).min().orElse(origin.getZ());
            int maxZ=remaining.keySet().stream().mapToInt((BlockPos p)->p.getZ()).max().orElse(origin.getZ());
            int left=minX-1,right=maxX+1,top=minZ-1,bottom=maxZ+1;
            for(int x=left;x<=right;x++)outerRing.add(new BlockPos(x,depot.getY(),top));
            for(int z=top+1;z<=bottom;z++)outerRing.add(new BlockPos(right,depot.getY(),z));
            for(int x=right-1;x>=left;x--)outerRing.add(new BlockPos(x,depot.getY(),bottom));
            for(int z=bottom-1;z>top;z--)outerRing.add(new BlockPos(left,depot.getY(),z));
            double best=Double.POSITIVE_INFINITY;
            for(int i=0;i<outerRing.size();i++){
                BlockPos p=outerRing.get(i);
                if(!WorkerNavigation.canStandOn(level,mob,p))continue;
                double d=p.distSqr(depot);
                if(d<best){best=d;ringExit=i;}
            }
            System.out.println("[REVERSE RETURN] ring length="+outerRing.size()
                    +" exit="+(ringExit<0?"NONE":relative(outerRing.get(ringExit)))
                    +" depot="+relative(depot));
        }
        int carried(){
            int total=0;
            for(int i=0;i<MATERIAL_SLOTS;i++)
                total+=mob.getItem(i).getCount();
            return total;
        }
        boolean roomFor(Item item){
            if(carried()>=CAPACITY)return false;
            for(int i=0;i<MATERIAL_SLOTS;i++){
                ItemStack stack=mob.getItem(i);
                if(stack.isEmpty()||stack.is(item)
                        &&stack.getCount()<stack.getMaxStackSize())
                    return true;
            }
            return false;
        }
        void collect(Item item){
            if(!roomFor(item))throw new IllegalStateException("pouch over capacity");
            for(int i=0;i<MATERIAL_SLOTS;i++){
                ItemStack stack=mob.getItem(i);
                if(stack.is(item)&&stack.getCount()<stack.getMaxStackSize()){
                    stack.grow(1);
                    mob.setItem(i,stack);
                    return;
                }
            }
            for(int i=0;i<MATERIAL_SLOTS;i++)if(mob.getItem(i).isEmpty()){
                mob.setItem(i,new ItemStack(item));
                return;
            }
            throw new IllegalStateException("pouch has no stack slot");
        }
        void trace(long tick,String action,BlockPos p,String detail){
            BlockPos c=mob.blockPosition();
            BlockPos b=p==null?origin:p;
            String d=detail==null?"":detail.replace(',',';').replace('\n',' ');
            csv.append(tick).append(',').append(action).append(',')
                    .append(b.getX()-origin.getX()).append(',')
                    .append(b.getY()-origin.getY()).append(',')
                    .append(b.getZ()-origin.getZ()).append(',')
                    .append(c.getX()-origin.getX()).append(',')
                    .append(c.getY()-origin.getY()).append(',')
                    .append(c.getZ()-origin.getZ()).append(',')
                    .append(d).append(',').append(carried()).append(',')
                    .append(mined).append(',').append(deposited).append(',')
                    .append(String.format(Locale.ROOT,"%.3f",walkMeters))
                    .append(',').append(jumps).append(',')
                    .append(drops).append(',').append(grapples).append('\n');
            lastAction=action+" "+d;
        }
        void observe(long tick){
            Vec3 now=mob.position();
            double horizontal=Math.hypot(now.x-previous.x,now.z-previous.z);
            if(horizontal<2)walkMeters+=horizontal;
            double change=now.y-previous.y;
            if(change>.55 &&!BuilderGrappleAccess.active(mob))
                jumps++;
            if(change<-.55 &&!BuilderGrappleAccess.active(mob))
                drops++;
            previous=now;
        }
        boolean visible(BlockPos from,BlockPos p){
            Vec3 eye=new Vec3(from.getX()+.5,
                    from.getY()+mob.getEyeHeight(),from.getZ()+.5);
            return visibleEye(eye,p);
        }
        boolean visibleEye(Vec3 eye,BlockPos p){
            // The center of a dome block can be hidden behind a curved
            // neighboring wall while a genuine exposed face is in view.
            // Trace several points ON THIS block, always enforcing
            // the same <=3 block Euclidean mining reach.
            double[][] aims={
                {.5,.5,.5},{.5,.90,.5},
                {.10,.5,.5},{.90,.5,.5},
                {.5,.5,.10},{.5,.5,.90}
            };
            for(double[] at:aims){
                Vec3 aim=new Vec3(p.getX()+at[0],p.getY()+at[1],
                        p.getZ()+at[2]);
                if(eye.distanceToSqr(aim)>9.0)continue;
                BlockHitResult result=level.clip(new ClipContext(
                        eye,aim,ClipContext.Block.OUTLINE,
                        ClipContext.Fluid.NONE,mob));
                if(result.getBlockPos().equals(p))return true;
            }
            return false;
        }
        List<BlockPos> stances(BlockPos p){
            var out=new ArrayList<BlockPos>();
            for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++)
                for(int dy=-3;dy<=3;dy++){
                    BlockPos feet=p.offset(dx,dy,dz);
                    if(feet.equals(p)||!WorkerNavigation.canStandOn(
                            level,mob,feet)||!visible(feet,p))continue;
                    out.add(feet);
                }
            out.sort(Comparator
                    .comparingDouble((BlockPos feet)->
                            feet.distSqr(mob.blockPosition()))
                    .thenComparingInt(BlockPos::getY));
            return out;
        }
        void reset(){
            target=null;stance=null;miningAge=0;
            closestDistance=Double.POSITIVE_INFINITY;
        }
        String tick(long now){
            if(carried()>CAPACITY)return "Exceeded 192-block pouch";
            if(mob.getItem(8).isEmpty()
                    ||!mob.getItem(8).is(WorksiteContent.GRAPPLE_HOOK))
                return "Missing physically carried Grapple Hook";
            if(BuilderGrappleAccess.active(mob))return null;
            if(unloading || carried()>=CAPACITY
                    ||remaining.isEmpty()&&carried()>0){
                unloading=true;return unload(now);
            }
            if(target!=null && !remaining.containsKey(target))reset();
            if(target!=null){
                // A mining action requires grounded REAL reach and an
                // unobstructed line; never harvest remotely or behind walls.
                if(mob.onGround()&&visibleEye(mob.getEyePosition(),target)){
                    miningAge+=5;
                    if(miningAge>=MINING_TICKS){
                        BlockState expected=remaining.get(target);
                        if(expected==null){reset();return null;}
                        Item item=expected.getBlock().asItem();
                        // A world block may have no corresponding item
                        // (e.g. an attached plant). Demolition is still
                        // legitimate, but inventory must not conjure one.
                        if(item!=Items.AIR&&!roomFor(item)){
                            unloading=true;return null;
                        }
                        if(level.getBlockState(target).getBlock()!=expected.getBlock())
                            return "Unexpectedly changed target "+target+
                                    " expected="+expected+" actual="+level.getBlockState(target)
                                    +" after last mined="+lastBroken;
                        mob.swing(InteractionHand.MAIN_HAND);
                        if(!level.destroyBlock(target,false,mob))
                            return "Physical block removal failed at "+target;
                        if(item!=Items.AIR){
                            collect(item);
                            itemsCollected++;
                        }else itemlessBlocks++;
                        remaining.remove(target);
                        lastBroken=target.immutable();
                        mined++;lastProgress=now;
                        trace(now,"BREAK",target,expected.getBlock().getName().getString());
                        if(mined%32==0)writeReports("IN PROGRESS");
                        reset();
                    }
                    return null;
                }
                miningAge=0;
                // A complete trained A* may have intermediate short legs.
                // Do not regard a single retry cooldown as no valid route.
                double d=mob.distanceToSqr(
                        stance.getX()+.5,stance.getY(),stance.getZ()+.5);
                if(d<closestDistance-.10){
                    closestDistance=d;lastRouteProgress=now;
                }
                if(now-lastRouteProgress>150){
                    excludedUntil.put(target,now+240);
                    trace(now,"ROUTE_TIMEOUT",target,
                            WorkerNavigation.debugStatus(mob));
                    reset();
                    routeFailures++;
                }else{
                    boolean moving=WorkerNavigation.routeToExact(
                            level,mob,stance,1.0);
                    if(now%100==0)trace(now,"APPROACH",target,
                            "moving="+moving+" stance="+relative(stance)
                            +" nav="+WorkerNavigation.debugStatus(mob));
                    return null;
                }
            }
            if(now-lastProgress>(mined==0?1000:1800))
                return "No real demolition progress for "
                        +(now-lastProgress)+" ticks";
            if(now-lastScanTick<20)return null;
            lastScanTick=now;
            // Explore the actual remaining world, not a recorded route.
            // Prefer physical proximity, but keep rotating search pages so
            // an inaccessible corner never monopolizes the study.
            List<BlockPos> candidates=new ArrayList<>(remaining.keySet());
            candidates.sort(Comparator
                    .comparingDouble((BlockPos p)->
                        p.distSqr(mob.blockPosition())-
                        Math.max(0,p.getY()-origin.getY())*2.0)
                    .thenComparingInt(BlockPos::getY));
            int scans=0;
            int count=candidates.size();
            int start=count==0?0:scanOffset%count;
            for(int k=0;k<count&&scans<BLOCKS_PER_SCAN;k++){
                BlockPos p=candidates.get((start+k)%count);
                if(excludedUntil.getOrDefault(p,0L)>now)continue;
                scans++;
                BlockState state=remaining.get(p);
                if(state==null)continue;
                // Walls, panes, fences and other connected blocks may update
                // their legal connection properties after neighboring blocks
                // are physically removed. Only a DIFFERENT BLOCK or AIR is
                // evidence of an untracked break; property changes are normal.
                BlockState current=level.getBlockState(p);
                if(current.getBlock()!=state.getBlock()){
                    if(current.isAir()){
                        // Double-height doors, flowers and attached blocks
                        // may vanish via *real Minecraft neighbor updates*
                        // when another block is mined. Record this separately
                        // from the one-at-a-time BREAKs. No phantom mining
                        // action and no invented item enter the golem's pouch.
                        remaining.remove(p);
                        physicsCleared++;
                        lastProgress=now;
                        trace(now,"PHYSICS_POP",p,"expected="+state
                                +" after="+lastBroken);
                        continue;
                    }
                    return "Unexpected replacement block at "+p
                            +" expected="+state+" actual="+current
                            +" lastBroken="+lastBroken;
                }
                Item item=state.getBlock().asItem();
                if(item!=Items.AIR&&!roomFor(item)){
                    unloading=true;return null;
                }
                BlockPos choice;
                if(mob.onGround()&&visibleEye(mob.getEyePosition(),p))
                    choice=mob.blockPosition();
                else choice=WorkerNavigation.selectExecutableStand(
                        level,mob,stances(p));
                if(choice==null)continue;
                target=p;stance=choice;lastRouteProgress=now;
                closestDistance=Double.POSITIVE_INFINITY;
                trace(now,"SELECT",target,"stance="
                        +relative(stance)+" mobTicks="+mob.tickCount
                        +" grounded="+mob.onGround()
                        +" eyeVisible="+visibleEye(mob.getEyePosition(),target));
                scanOffset=(start+k+1)%count;
                return null;
            }
            scanOffset=(start+Math.max(1,scans))%Math.max(1,count);
            // No ground route is available: find a sturdy physical
            // upper ledge for a carried Grapple Hook. Normal 1-block
            // jumps and 2-block drops were already considered by A*.
            if(now%80==0 && mob.onGround()){
                int trials=0;
                for(BlockPos block:candidates){
                    BlockPos ledge=block.above();
                    int rise=ledge.getY()-mob.blockPosition().getY();
                    if(rise<2||rise>BuilderGrappleAccess.MAX_REACH
                            ||block.distSqr(mob.blockPosition())>320)
                        continue;
                    if(++trials>24)break;
                    if(!WorkerNavigation.canStandOn(level,mob,ledge)
                            ||!BuilderGrappleAccess.canPlanAscent(
                                level,mob,ledge))continue;
                    var result=BuilderGrappleAccess.reach(
                            level,mob,table,ledge);
                    if(result==BuilderGrappleAccess.Result.MOVING){
                        grapples++;
                        trace(now,"GRAPPLE_UP",ledge,
                                BuilderGrappleAccess.debug(mob));
                        return null;
                    }
                }
            }
            return null;
        }
        String relative(BlockPos p){
            return (p.getX()-origin.getX())+"/"
                    +(p.getY()-origin.getY())+"/"
                    +(p.getZ()-origin.getZ());
        }
        boolean finishedUnloading(){
            return !unloading&&carried()==0;
        }
        /** A waypoint is reached only by physical walking to its center. */
        boolean reached(BlockPos p){
            return p!=null&&mob.onGround()
                    &&mob.blockPosition().equals(p)
                    &&mob.distanceToSqr(p.getX()+.5,p.getY(),p.getZ()+.5)<.27;
        }
        void setReturnWaypoint(long now,BlockPos p,String stage){
            if(p==null)return;
            returnWaypoint=p.immutable();
            waypointProgressTick=now;
            bestWaypointDistance=Double.POSITIVE_INFINITY;
            trace(now,"RETURN_"+stage,p,"ring="+ringAt
                    +" direction="+ringDirection+" detours="+returnDetours);
        }
        boolean returnStalled(long now){
            if(returnWaypoint==null)return false;
            double d=mob.distanceToSqr(returnWaypoint.getX()+.5,
                    returnWaypoint.getY(),returnWaypoint.getZ()+.5);
            if(d<bestWaypointDistance-.12){
                bestWaypointDistance=d;waypointProgressTick=now;
            }
            return now-waypointProgressTick>160;
        }
        int ringDistance(int start,int goal,int direction){
            int n=outerRing.size();
            return Math.floorMod((goal-start)*direction,n);
        }
        void chooseRingDirection(){
            int positive=ringDistance(ringAt,ringExit,1);
            int negative=ringDistance(ringAt,ringExit,-1);
            ringDirection=positive<=negative?1:-1;
        }
        /** Try a direct, physically verified last-mile approach to the chest. */
        boolean depotApproach(long now){
            if(mob.blockPosition().getY()!=depot.getY()
                    ||mob.blockPosition().distSqr(depot)>100)return false;
            var landings=new ArrayList<BlockPos>();
            for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++){
                BlockPos foot=depot.offset(dx,0,dz);
                if(foot.distSqr(depot)>9
                        ||!WorkerNavigation.canStandOn(level,mob,foot))continue;
                landings.add(foot);
            }
            BlockPos chosen=WorkerNavigation.selectExecutableStand(level,mob,landings);
            if(chosen==null)return false;
            if(!chosen.equals(returnWaypoint))setReturnWaypoint(now,chosen,"FINAL");
            return WorkerNavigation.routeToExact(level,mob,chosen,1.0);
        }
        /** Plan short executable legs on an exterior route derived from the
         * schematic's bounds. Long inside-the-wall native routes are not
         * treated as proof of reachability; every leg uses trained A*.
         */
        boolean returnViaOutside(long now){
            if(ringExit<0 || outerRing.isEmpty())return false;
            if(returnWaypoint!=null && !reached(returnWaypoint)){
                if(!returnStalled(now)){
                    WorkerNavigation.routeToExact(level,mob,returnWaypoint,1.0);
                    return true;
                }
                trace(now,"RETURN_REPLAN",returnWaypoint,
                        "cannot physically finish leg "+WorkerNavigation.debugStatus(mob));
                returnDetours++;
                if(ringAt>=0)ringDirection=-ringDirection;
                else ringDirection=0;
                WorkerNavigation.stop(mob);
                returnWaypoint=null;
            }
            if(returnWaypoint!=null&&reached(returnWaypoint)){
                int index=outerRing.indexOf(returnWaypoint);
                if(index>=0){
                    ringAt=index;
                    if(ringDirection==0)chooseRingDirection();
                }
                returnWaypoint=null;
            }
            if(ringAt<0){
                // One multi-goal A* chooses an attainable OUTSIDE entry.
                // Look around the whole ring instead of targeting an
                // inaccessible near-side chest cell through the dome.
                var candidate=new ArrayList<>(outerRing);
                candidate.removeIf(p->!WorkerNavigation.canStandOn(level,mob,p));
                candidate.sort(Comparator.comparingDouble(
                        p->p.distSqr(mob.blockPosition())));
                BlockPos entry=WorkerNavigation.selectExecutableStand(level,mob,
                        candidate.subList(0,Math.min(48,candidate.size())));
                if(entry==null)return false;
                setReturnWaypoint(now,entry,"RING_ENTRY");
                WorkerNavigation.routeToExact(level,mob,entry,1.0);
                return true;
            }
            if(ringAt==ringExit){
                // Once the outer exit is physically reached, transition
                // through the short, open depot approach tiles.
                if(depotApproach(now))return true;
                if(returnStalled(now)){ringAt=-1;returnWaypoint=null;}
                return false;
            }
            int distance=ringDistance(ringAt,ringExit,ringDirection);
            int n=outerRing.size();
            // Preview progressively shorter ground segments. These are
            // waypoints, not saved paths; legality is recomputed each trip.
            for(int hop:new int[]{5,3,1}){
                int next=Math.floorMod(ringAt+
                        ringDirection*Math.min(hop,distance),n);
                BlockPos leg=outerRing.get(next);
                if(!WorkerNavigation.canStandOn(level,mob,leg))continue;
                if(WorkerNavigation.routeToExact(level,mob,leg,1.0)){
                    setReturnWaypoint(now,leg,"PERIMETER");
                    return true;
                }
            }
            returnDetours++;
            ringDirection=-ringDirection;
            trace(now,"RETURN_REVERSE",outerRing.get(ringAt),
                    "blocked physical perimeter leg; reverse direction");
            return false;
        }
        /** Hook is real equipment with real support and rope clearance;
         * only attempt it when walking cannot access the exterior.
         */
        boolean grappleToExit(long now){
            if(!mob.onGround()||now%100!=0
                    ||mob.blockPosition().getY()!=depot.getY())return false;
            int examined=0;
            for(BlockPos block:remaining.keySet()){
                BlockPos ledge=block.above();
                int rise=ledge.getY()-mob.blockPosition().getY();
                if(rise<2||rise>BuilderGrappleAccess.MAX_REACH
                        ||block.distSqr(mob.blockPosition())>260)continue;
                if(++examined>64)break;
                if(!WorkerNavigation.canStandOn(level,mob,ledge)
                        ||!BuilderGrappleAccess.canPlanAscent(level,mob,ledge))
                    continue;
                var result=BuilderGrappleAccess.reach(level,mob,table,ledge);
                if(result==BuilderGrappleAccess.Result.MOVING
                        &&BuilderGrappleAccess.active(mob)){
                    grapples++;
                    trace(now,"GRAPPLE_EXIT",ledge,BuilderGrappleAccess.debug(mob));
                    return true;
                }
            }
            return false;
        }
        String unload(long now){
            // A FULL pouch must return to the real collection point.
            // Test-owned ledger is an infinite downstream warehouse,
            // but items are deleted ONLY while the actual golem stands
            // by the collection chest. Each transfer is accounted.
            if(mob.blockPosition().getY()==depot.getY()
                    &&mob.distanceToSqr(depot.getX()+.5,
                            depot.getY()+.5,depot.getZ()+.5)<=12){
                int dropped=0;
                for(int slot=0;slot<MATERIAL_SLOTS;slot++){
                    ItemStack stack=mob.getItem(slot);
                    if(stack.isEmpty())continue;
                    dropped+=stack.getCount();
                    depotTally.merge(stack.getItem(),
                            (long)stack.getCount(),Long::sum);
                    mob.setItem(slot,ItemStack.EMPTY);
                }
                deposited+=dropped;
                if(dropped>0)unloads++;
                unloading=false;
                WorkerNavigation.stop(mob);
                trace(now,"UNLOAD",depot,"depot received="+dropped);
                reset();lastProgress=now;
                ringAt=-1;ringDirection=0;returnWaypoint=null;
                return null;
            }
            if(BuilderGrappleAccess.active(mob))return null;
            if(mob.blockPosition().getY()>depot.getY()+2){
                if(BuilderGrappleAccess.rappelToward(
                        level,mob,table,depot)){
                    grapples++;
                    trace(now,"GRAPPLE_DOWN",depot,
                            BuilderGrappleAccess.debug(mob));
                    return null;
                }
            }
            // Terrain changes as dismantling progresses. A natural
            // 2-block drop should win over grappling whenever feasible.
            if(mob.blockPosition().getY()>depot.getY()){
                var choices=new ArrayList<BlockPos>();
                BlockPos at=mob.blockPosition();
                for(int dx=-10;dx<=10;dx++)for(int dz=-10;dz<=10;dz++)
                    for(int dy=-2;dy<=-1;dy++){
                        BlockPos p=at.offset(dx,dy,dz);
                        if(p.getY()<depot.getY()
                                ||!WorkerNavigation.canStandOn(level,mob,p))
                            continue;
                        choices.add(p);
                    }
                choices.sort(Comparator.comparingDouble(
                        p->p.distSqr(depot)));
                BlockPos descent=WorkerNavigation.selectExecutableStand(
                        level,mob,choices.subList(0,Math.min(80,choices.size())));
                if(descent!=null){
                    WorkerNavigation.routeToExact(level,mob,descent,1.0);
                    return null;
                }
                if(BuilderGrappleAccess.rappelToward(
                        level,mob,table,depot))return null;
            }
            // Real depot needs a path to an adjacent STANDING cell, not
            // a repeatedly regenerated A* toward the solid chest block.
            if(depotApproach(now))return null;
            if(returnViaOutside(now))return null;
            if(grappleToExit(now))return null;
            if(now-lastProgress>4200)
                return "Cannot reach exterior route / depot with carried load"
                    +" ring="+ringAt+" exit="+ringExit
                    +" waypoint="+returnWaypoint+" detours="+returnDetours;
            return null;
        }
        String status(){
            return "mined="+mined+" physics="+physicsCleared
                    +" cleared="+(mined+physicsCleared)+"/3537"
                    +" itemless="+itemlessBlocks
                    +" collected="+itemsCollected+" left="+remaining.size()
                    +" carried="+carried()+" deposited="+deposited
                    +" returns="+unloads+" walked="
                    +String.format(Locale.ROOT,"%.2f",walkMeters)
                    +" jumps="+jumps+" drops="+drops+" grapples="+grapples
                    +" failedRoutes="+routeFailures
                    +" last="+lastAction
                    +" feet="+relative(mob.blockPosition())
                    +" mobTicks="+mob.tickCount
                    +" grounded="+mob.onGround()
                    +" target="+(target==null?"none":relative(target))
                    +" stance="+(stance==null?"none":relative(stance))
                    +" eyeVisible="+(target!=null&&visibleEye(mob.getEyePosition(),target));
        }
        void writeReports(String outcome){
            try{
                Files.createDirectories(REPORT.getParent());
                Files.writeString(REPORT,csv.toString(),StandardCharsets.UTF_8);
                Files.writeString(SUMMARY,outcome+"\n"+status()+"\n"
                        +"reach=3 jump=1 drop=2 hook=>2 bundle=192\n"
                        +"manual_blocks="+mined+" natural_physics_pops="+physicsCleared
                        +" collectible_items="+itemsCollected
                        +" itemless_blocks="+itemlessBlocks+"\n"
                        +"depot distinct items="+depotTally.size()+"\n",
                        StandardCharsets.UTF_8);
            }catch(Exception e){
                System.err.println("[REVERSE DOME] report write failed "+e);
            }
        }
    }
}
