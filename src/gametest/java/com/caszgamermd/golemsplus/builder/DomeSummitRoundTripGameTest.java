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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Physical vertical navigation experiment on the exact original dome.
 * One actual Builder starts just outside the base, climbs to the highest
 * supported summit, then returns to the ground. The dome remains intact.
 * All walking, jumps, and hooks use the production movement implementations.
 * No creative flight, teleports, ghost paths, custom scaffolding or block edits
 * once the original test fixture has been placed.
 */
public final class DomeSummitRoundTripGameTest {
    private static final Path REPORT=Path.of("logs/dome_summit_roundtrip.csv");
    private static final Path SUMMARY=Path.of("logs/dome_summit_roundtrip_summary.txt");
    private static final int ROUTE_RETRY_TICKS=360;
    private static final int STAGE_TIMEOUT=9000;
    // Diagnostic endurance window extends past the old 4,761-tick
    // UNLOADED_TO_CHUNK removal to prove the refreshed lease persists.
    private static final int PROGRESS_TIMEOUT=5700;

    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=42000,skyAccess=true)
    public void oneBuilderScalesOriginalDomeUpThenDown(GameTestHelper h){
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

        // All summit cells must provide a physical, fully collision-safe
        // standing surface; a plant or floating block is not a summit.
        // Include every roof elevation so normal 1-block steps can be
        // preferred over equipment whenever a legal path exists.
        BuilderGolem golem=new BuilderGolem(
                WorksiteContent.BUILDER_GOLEM,world);
        // The south-west bounding-box corner is AIR on a circular dome.
        // Start beside an actual west-facing base block, with real ground
        // below the Builder, exactly as in the reverse demolition study.
        BlockPos western=remaining.keySet().stream()
                .filter(p->p.getY()==origin.getY())
                .min(Comparator.comparingInt((BlockPos p)->p.getX())
                    .thenComparingInt(p->Math.abs(
                            p.getZ()-origin.getZ()-19)))
                .orElseThrow();
        BlockPos start=western.west();
        if(!world.getBlockState(start).isAir()
                ||!WorkerNavigation.canStandOn(world,golem,start)){
            h.fail("No physical entry beside the original dome at "+start);
            return;
        }
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
        // Chunk boundaries depend on the random absolute GameTest origin.
        // Sampling only corners/center skips some interior 16x16 chunks,
        // freezing a real Builder when it walks into one of those gaps.
        // Sample EVERY 4 blocks across the entire schematic plus its
        // real exterior access lane, covering each actual ChunkPos.
        for(int dx=-4;dx<=40;dx+=4)for(int dz=-4;dz<=40;dz+=4){
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
        List<BlockPos> footings=new ArrayList<>();
        for(BlockPos block:remaining.keySet()){
            // A slab or stair can support feet IN its own block cell,
            // while a full block supplies footing at the cell above.
            for(BlockPos feet:List.of(block,block.above()))
                if(WorkerNavigation.canStandOn(world,golem,feet)
                        &&!footings.contains(feet))
                    footings.add(feet.immutable());
        }
        // The natural surrounding grass is a necessary approach surface.
        // Without these tiles, scouting at base Y has zero candidates:
        // the test can strand a golem before trying any real upper anchor.
        // This is discovery of existing footing, not construction.
        for(int dx=-4;dx<=42;dx++)for(int dz=-4;dz<=42;dz++){
            BlockPos feet=origin.offset(dx,0,dz);
            if(WorkerNavigation.canStandOn(world,golem,feet)
                    &&!footings.contains(feet))
                footings.add(feet.immutable());
        }
        if(footings.isEmpty()){
            releaseSimulation.run();
            h.fail("No supported roof footings on intact dome");return;
        }
        // The dome's walkable rooftop ends at local Y=35. Higher Y=36-38
        // tiles belong to decorative fence/wall/finial geometry and are
        // NOT an assignment to climb an ornament. Accept only a physically
        // grounded Builder on the supported Y=35 rooftop before descent.
        final int rooftopY=origin.getY()+35;
        final int highestFootingY=footings.stream()
                .mapToInt(BlockPos::getY).max().orElseThrow();
        if(footings.stream().noneMatch(p->p.getY()==rooftopY)){
            releaseSimulation.run();
            h.fail("No physically supported roof at expected Y=35");return;
        }
        int summitY=rooftopY;
        // Physical diagnostics reveal whether this dome has a continuous
        // network of standing ledges above the first reachable shelf.
        // Without such ledges, a one-anchor-at-a-time grapple will need
        // a real wall-facing rope relay instead of repeated return-to-base.
        StringBuilder layers=new StringBuilder();
        for(int y=origin.getY();y<=highestFootingY;y++){
            final int height=y;
            long count=footings.stream().filter(p->p.getY()==height).count();
            layers.append(y-origin.getY()).append(':').append(count).append(' ');
        }
        System.out.println("[DOME STANDING TOPOLOGY] "+layers);
        BlockPos firstShelf=origin.offset(1,5,20);
        for(int delta:new int[]{1,2,3,5,8,12,16,20}){
            int min=firstShelf.getY()+delta;
            long count=footings.stream().filter(p->p.getY()>=min
                    &&p.getY()<=firstShelf.getY()
                            +BuilderGrappleAccess.MAX_REACH
                    &&Math.abs(p.getX()-firstShelf.getX())<=19
                    &&Math.abs(p.getZ()-firstShelf.getZ())<=19).count();
            System.out.println("[DOME VERTICAL SIGHT] shelfY=5 above="
                    +delta+" standable="+count);
        }
        List<BlockPos> summitCells=footings.stream()
                .filter(p->p.getY()==summitY).toList();
        System.out.println("[DOME SUMMIT] original=3537 base="
                +start.toShortString()+" roofGoalY="
                +(summitY-origin.getY())+" roofCells="+summitCells.size()
                +" highestDecorationY="+(highestFootingY-origin.getY())
                +" footings="+footings.size()+" hook=real");
        Study study=new Study(world,golem,table,origin,start,remaining,
                footings,summitY);
        final int[] lastEntityTick={0};
        final long[] lastEntityAdvance={0};
        h.onEachTick(()->{
            long tick=h.getTick();
            // With concurrent GameTests, another fixture may release a
            // vanilla forceload on a shared 16x16 chunk. A one-shot
            // setChunkForced at test startup does NOT survive that release.
            // Reassert the full physical test lease at a fixed cadence,
            // before entity ticking and the removal guard below.
            if(tick%20==0){
                for(var chunk:simulationChunks){
                    if(world.setChunkForced(chunk.x(),chunk.z(),true)){
                        study.reforcedChunks++;
                        System.out.println("[DOME TEST CHUNK RECOVERED] tick="
                                +tick+" chunk="+chunk+" reassertions="
                                +study.reforcedChunks);
                    }
                    BuilderChunkTickets.holdPhysicalGameTestChunk(world,
                            new BlockPos(chunk.x() << 4,origin.getY(),
                                    chunk.z() << 4));
                }
            }
            if(golem.tickCount>lastEntityTick[0]){
                lastEntityTick[0]=golem.tickCount;
                lastEntityAdvance[0]=tick;
            }
            if(tick>300&&tick-lastEntityAdvance[0]>200){
                String failure="Entity ceased physically ticking";
                study.writeReports("FAIL "+failure);
                releaseSimulation.run();
                h.fail(failure+" "+study.status());return;
            }
            if(!golem.isAlive()){
                var inWorld=world.getEntity(golem.getUUID());
                String detail="Builder missing / not alive"
                        +" removed="+golem.isRemoved()
                        +" reason="+golem.getRemovalReason()
                        +" health="+golem.getHealth()
                        +" invulnerable="+golem.isInvulnerable()
                        +" oldEntityTick="+golem.tickCount
                        +" oldPos="+golem.position()
                        +" worldEntity="+(inWorld==null?"none":
                            inWorld.getClass().getSimpleName()+" id="+inWorld.getId()
                            +" same="+(inWorld==golem)
                            +" pos="+inWorld.position())
                        +" atChunk="+world.hasChunkAt(golem.blockPosition())
                        +" gameTime="+world.getGameTime();
                System.err.println("[DOME ENTITY REMOVAL DIAGNOSTIC] "+detail);
                study.writeReports("FAIL "+detail);
                releaseSimulation.run();
                h.fail(detail);return;
            }
            study.observe(tick);
            if(tick==5200){
                study.passedUnloadWindow=true;
                System.out.println("[DOME CHUNK SURVIVAL VERIFIED] tick="
                        +tick+" golemTick="+golem.tickCount
                        +" health="+golem.getHealth()
                        +" removed="+golem.isRemoved()
                        +" loaded="+world.hasChunkAt(golem.blockPosition())
                        +" renewals="+study.reforcedChunks);
                study.log(tick,"CHUNK_SURVIVAL_VERIFIED",golem.blockPosition(),
                        "healthy after previous 4761-tick removal");
            }
            if(tick%5!=0)return;
            String failure=study.tick(tick);
            if(tick%200==0)System.out.println("[DOME SUMMIT PROGRESS] "
                    +study.status());
            if(failure!=null){
                study.writeReports("FAIL "+failure);
                releaseSimulation.run();
                h.fail(failure+" | "+study.status());return;
            }
            if(study.done){
                // Summiting and returning do not grant permission to alter
                // the dome. The original prebuilt blueprint must stay put.
                for(var entry:remaining.entrySet())
                    if(world.getBlockState(entry.getKey()).getBlock()
                            !=entry.getValue().getBlock()){
                        study.writeReports("FAIL dome altered");
                        releaseSimulation.run();
                        h.fail("Ascent test modified dome at "+entry.getKey());
                        return;
                    }
                study.writeReports("PASS physically summited and returned");
                System.out.println("[DOME SUMMIT PASS] "+study.status());
                releaseSimulation.run();
                h.succeed();
            }
        });
    }

    private static final class Study {
        final ServerLevel level;
        final BuilderGolem mob;
        final WorkerStationBlockEntity table;
        final BlockPos origin,base;
        final Map<BlockPos,BlockState> dome;
        final List<BlockPos> footings;
        final int summitY;
        final Map<BlockPos,Long> excludedUntil=new HashMap<>();
        final Map<BlockPos,Long> frontierVisitedUntil=new HashMap<>();
        // Remember a roof sector after proving that its landing is isolated.
        // This is geometry knowledge (unusable ledge), NOT a memorized route.
        final Map<BlockPos,Long> isolatedShelvesUntil=new HashMap<>();
        int rejectedSectorTargets;
        long lastFrontierMove,finalRecoveryTick;
        int frontierRoutes,frontierNoPaths,descentRecoveries;
        final StringBuilder csv=new StringBuilder(
                "tick,action,phase,localX,localY,localZ,goalX,goalY,goalZ,"
                +"jumps,lowerLandings,grappleUps,grappleDowns,maxY,details\n");
        Vec3 lastPos;
        BlockPos goal;
        long goalStart,stageStart,lastHeightProgress, lastGoalAttempt;
        long scans,routeFailures,grappleProbes,anchorMisses,reforcedChunks;
        boolean passedUnloadWindow;
        int maxY,minDescentY,jumps,landings,grappleUps,grappleDowns;
        int levelsUp,levelsDown,lastGroundY;
        boolean hadGround,airborne,jumpedThisFlight,wasRope,ropeMoved,done;
        long ropeAttempts;
        double takeoffY,walked;
        String phase="ASCEND";
        String lastAction="SPAWN";
        Study(ServerLevel level,BuilderGolem mob,
                WorkerStationBlockEntity table,BlockPos origin,BlockPos base,
                Map<BlockPos,BlockState> dome,List<BlockPos> footings,int summitY){
            this.level=level;this.mob=mob;this.table=table;
            this.origin=origin;this.base=base;
            this.dome=dome;this.footings=footings;this.summitY=summitY;
            this.lastPos=mob.position();
            this.stageStart=0;
            this.maxY=base.getY();
            this.minDescentY=summitY;
        }
        void log(long tick,String action,BlockPos target,String details){
            BlockPos feet=mob.blockPosition();
            csv.append(tick).append(',').append(action).append(',')
                    .append(phase).append(',')
                    .append(feet.getX()-origin.getX()).append(',')
                    .append(feet.getY()-origin.getY()).append(',')
                    .append(feet.getZ()-origin.getZ()).append(',');
            if(target==null)csv.append(",,,");
            else csv.append(target.getX()-origin.getX()).append(',')
                    .append(target.getY()-origin.getY()).append(',')
                    .append(target.getZ()-origin.getZ()).append(',');
            csv.append(jumps).append(',').append(landings).append(',')
                    .append(grappleUps).append(',').append(grappleDowns)
                    .append(',').append(maxY-origin.getY()).append(',')
                    .append(details==null?"":details.replace(',',';')
                            .replace('\n',' ')).append('\n');
            lastAction=action;
        }
        void observe(long tick){
            Vec3 now=mob.position();
            boolean rope=BuilderGrappleAccess.active(mob);
            if(rope&&!wasRope){
                ropeAttempts++;
                ropeMoved=false;
                log(tick,"ROPE_ROUTE_STARTED",mob.blockPosition(),
                        BuilderGrappleAccess.debug(mob));
            }else if(!rope&&wasRope)
                log(tick,ropeMoved?"ROPE_RELEASED":"ROPE_ABORTED",
                        mob.blockPosition(),WorkerNavigation.debugStatus(mob));
            // The active rope state includes WALK_TO_LINE / WALK_TO_EDGE.
            // Count a grapple use ONLY after observed vertical movement
            // under its control, never just after a route was requested.
            double ropeDelta=now.y-lastPos.y;
            if(rope&&!ropeMoved
                    &&(phase.equals("ASCEND")&&ropeDelta>.055
                    ||phase.equals("DESCEND")&&ropeDelta<-.055)){
                ropeMoved=true;
                if(phase.equals("ASCEND"))grappleUps++;
                else grappleDowns++;
                log(tick,"ROPE_VERTICAL_MOVEMENT",mob.blockPosition(),
                        "deltaY="+String.format(Locale.ROOT,"%.3f",ropeDelta));
            }
            wasRope=rope;
            double horiz=Math.hypot(now.x-lastPos.x,now.z-lastPos.z);
            if(horiz<2)walked+=horiz;
            if(mob.onGround()){
                if(airborne&&!rope&&takeoffY-now.y>.65){
                    landings++;
                    log(tick,"LOWER_LANDING",mob.blockPosition(),
                            "takeoffY="+String.format(Locale.ROOT,"%.3f",takeoffY));
                }
                airborne=false;jumpedThisFlight=false;
                int y=mob.blockPosition().getY();
                if(hadGround&&y!=lastGroundY){
                    if(y>lastGroundY)levelsUp+=y-lastGroundY;
                    else levelsDown+=lastGroundY-y;
                    log(tick,y>lastGroundY?"FOOTING_UP":"FOOTING_DOWN",
                            mob.blockPosition(),"previousY="+
                                    (lastGroundY-origin.getY()));
                }
                hadGround=true;lastGroundY=y;
            }else if(hadGround&&!rope){
                if(!airborne){
                    airborne=true;takeoffY=lastPos.y;jumpedThisFlight=false;
                }
                if(!jumpedThisFlight&&now.y-lastPos.y>.06){
                    jumps++;jumpedThisFlight=true;
                    log(tick,"JUMP_ASCENT",mob.blockPosition(),"airborne rise");
                }
            }
            if(mob.onGround()){
                int y=mob.blockPosition().getY();
                if(phase.equals("ASCEND")&&y>maxY){
                    maxY=y;lastHeightProgress=tick;
                    log(tick,"NEW_MAX_HEIGHT",mob.blockPosition(),
                            "height="+(y-origin.getY()));
                }
                if(phase.equals("DESCEND")&&y<minDescentY){
                    minDescentY=y;lastHeightProgress=tick;
                    log(tick,"NEW_MIN_HEIGHT",mob.blockPosition(),
                            "height="+(y-origin.getY()));
                }
            }
            lastPos=now;
        }
        boolean revisitsIsolatedShelf(BlockPos p,long tick){
            for(var shelf:isolatedShelvesUntil.entrySet()){
                if(shelf.getValue()<=tick)continue;
                BlockPos bad=shelf.getKey();
                int dx=p.getX()-bad.getX(),dz=p.getZ()-bad.getZ();
                // Avoid that whole local face until an alternate approach
                // has been explored. The shelf can be reconsidered if the
                // surrounding structure changes during building/demolition.
                if(dx*dx+dz*dz<=36 && p.getY()>base.getY()
                        &&p.getY()<=bad.getY()+2)
                    return true;
            }
            return false;
        }
        List<BlockPos> nearFootings(int minY,int maxY,int radius){
            BlockPos here=mob.blockPosition();
            var out=new ArrayList<BlockPos>();
            int radius2=radius*radius;
            for(BlockPos p:footings){
                if(p.getY()<minY||p.getY()>maxY
                        ||excludedUntil.getOrDefault(p,0L)>mob.tickCount)
                    continue;
                int dx=p.getX()-here.getX(), dz=p.getZ()-here.getZ();
                if(dx*dx+dz*dz>radius2)continue;
                if(!WorkerNavigation.canStandOn(level,mob,p))continue;
                out.add(p);
            }
            out.sort(Comparator.comparingDouble((BlockPos p)->
                    p.distSqr(here)).thenComparingInt(
                    p->Math.abs(p.getY()-here.getY())));
            return out;
        }
        boolean routeTo(long tick,BlockPos next,String reason){
            if(next==null)return false;
            if(WorkerNavigation.routeToExact(level,mob,next,1.0)){
                goal=next.immutable();goalStart=tick;
                log(tick,reason,goal,WorkerNavigation.debugStatus(mob));
                return true;
            }
            return false;
        }
        boolean normalVertical(long tick,boolean ascending){
            int y=mob.blockPosition().getY();
            int low=ascending?y+1:Math.max(base.getY(),y-2);
            int high=ascending?Math.min(summitY,y+4):y-1;
            if(low>high)return false;
            List<BlockPos> candidates=nearFootings(low,high,16);
            if(ascending)candidates.removeIf(p->revisitsIsolatedShelf(p,tick));
            if(candidates.size()>80)candidates=candidates.subList(0,80);
            if(candidates.isEmpty())return false;
            BlockPos next=WorkerNavigation.selectExecutableStand(
                    level,mob,candidates);
            return next!=null&&routeTo(tick,next,
                    ascending?"WALK_ASCENT":"WALK_DESCENT");
        }
        boolean grappleUp(long tick){
            BlockPos here=mob.blockPosition();
            grappleProbes++;
            List<BlockPos> candidates=nearFootings(here.getY()+2,
                    Math.min(summitY,here.getY()+BuilderGrappleAccess.MAX_REACH),
                    19);
            int before=candidates.size();
            candidates.removeIf(p->revisitsIsolatedShelf(p,tick));
            rejectedSectorTargets+=before-candidates.size();
            // Close landings first: test physically plausible grapple
            // support rather than selecting an apex through a roof.
            candidates.sort(Comparator.comparingDouble((BlockPos p)->
                    p.distSqr(here)+Math.abs(p.getY()-here.getY()-5)*3));
            int attempts=0;
            for(BlockPos p:candidates){
                if(++attempts>120)break;
                if(!BuilderGrappleAccess.canPlanAscent(level,mob,p))continue;
                var result=BuilderGrappleAccess.reach(level,mob,table,p);
                if(result==BuilderGrappleAccess.Result.MOVING){
                    goal=null;
                    if(BuilderGrappleAccess.active(mob))
                        log(tick,"GRAPPLE_ASCENT_REQUEST",p,
                                BuilderGrappleAccess.debug(mob));
                    else log(tick,"NORMAL_ROUTE_FROM_HOOK_PROBE",p,
                                WorkerNavigation.debugStatus(mob));
                    lastGoalAttempt=tick;
                    return true;
                }
                excludedUntil.put(p,tick+200);
            }
            anchorMisses++;
            if(anchorMisses%10==1)log(tick,"NO_SAFE_UP_ANCHOR",here,
                    "probes="+grappleProbes+" checked="+attempts
                    +" elevatedCandidates="+candidates.size()
                    +" roofFootings="+nearFootings(here.getY()-2,
                            here.getY(),24).size());
            return false;
        }
        /**
         * A dead end at an elevated work ledge is NOT an instruction to
         * keep casting at the same unreachable wall. Examine physically
         * standable footholds at this height and up to two levels lower.
         * Score exposed positions that are close to a higher roof frontier,
         * then require the same trained A* to prove an executable route.
         * Alternate batches when a nearest waypoint is inaccessible.
         */
        boolean seekRoofFrontier(long tick){
            BlockPos here=mob.blockPosition();
            List<BlockPos> choices=nearFootings(
                    Math.max(base.getY(),here.getY()-2),here.getY(),30);
            choices.removeIf(p->p.distSqr(here)<9
                    ||frontierVisitedUntil.getOrDefault(p,0L)>tick);
            // Keep near options AND a geographically broad sampling of the
            // remaining roof. Otherwise evaluating every one of thousands
            // of footings against every higher ledge every 40 ticks is
            // quadratic and can starve Minecraft's simulation thread.
            if(choices.size()>192){
                List<BlockPos> sampled=new ArrayList<>(192);
                sampled.addAll(choices.subList(0,64));
                int remainingCount=choices.size()-64;
                for(int i=0;i<128;i++){
                    int at=64+(int)((long)i*remainingCount/128);
                    sampled.add(choices.get(Math.min(at,choices.size()-1)));
                }
                choices=sampled;
            }
            if(choices.isEmpty()){
                frontierNoPaths++;
                if(frontierNoPaths%10==1)log(tick,"NO_ROOF_FRONTIER",here,
                        "no unvisited supported roof/ledge near feet");
                return false;
            }
            // Look for genuine supported higher landings; this uses the
            // same physical world snapshot, never a stored dome route.
            Map<BlockPos,Double> scores=new HashMap<>();
            for(BlockPos p:choices){
                double higher=500;
                for(BlockPos upper:footings){
                    int rise=upper.getY()-p.getY();
                    if(rise<2||rise>BuilderGrappleAccess.MAX_REACH)continue;
                    int dx=upper.getX()-p.getX(),dz=upper.getZ()-p.getZ();
                    int lateral=dx*dx+dz*dz;
                    if(lateral>196)continue;
                    double value=lateral+Math.abs(rise-5)*4.0;
                    if(value<higher)higher=value;
                }
                // Skylight strongly favors the real exterior roof rather
                // than a cave floor below its own overhanging dome.
                double score=higher
                        +p.distSqr(here)*.3
                        +(here.getY()-p.getY())*14
                        -(level.canSeeSky(p.above())?35:0);
                scores.put(p,score);
            }
            choices.sort(Comparator.comparingDouble(scores::get));
            int checked=0;
            for(int offset=0;offset<choices.size()&&checked<144;offset+=12){
                List<BlockPos> batch=choices.subList(offset,
                        Math.min(choices.size(),offset+12));
                checked+=batch.size();
                BlockPos frontier=WorkerNavigation.selectExecutableStand(
                        level,mob,batch);
                if(frontier==null)continue;
                if(routeTo(tick,frontier,"REPOSITION_ROOF_FRONTIER")){
                    frontierRoutes++;lastFrontierMove=tick;
                    frontierVisitedUntil.put(frontier,tick+1000);
                    log(tick,"FRONTIER_SELECTED",frontier,
                            "score="+scores.get(frontier)
                            +" rank="+(offset+1)
                            +" candidates="+choices.size());
                    return true;
                }
                frontierVisitedUntil.put(frontier,tick+600);
            }
            frontierNoPaths++;
            if(frontierNoPaths%10==1)
                log(tick,"FRONTIER_UNREACHABLE",here,
                        "total="+choices.size()+" pathChecked="+checked
                        +" bestScore="+scores.get(choices.get(0))
                        +" nav="+WorkerNavigation.debugStatus(mob));
            return false;
        }
        /** If a roof shelf is isolated, descend by real rope instead of
         * polling the same impossible higher goals forever. Blacklist this
         * failed shelf while scouting a different approach from below. */
        boolean recoverIsolatedShelf(long tick){
            BlockPos here=mob.blockPosition();
            if(here.getY()<=base.getY()||tick-lastHeightProgress<1250
                    ||tick-finalRecoveryTick<300)return false;
            boolean started=BuilderGrappleAccess.rappelToward(
                    level,mob,table,base);
            if(!started)return false;
            excludedUntil.put(here,tick+10000);
            frontierVisitedUntil.put(here,tick+10000);
            isolatedShelvesUntil.put(here,tick+15000);
            finalRecoveryTick=tick;
            descentRecoveries++;
            log(tick,BuilderGrappleAccess.active(mob)
                            ?"ISOLATED_SHELF_RAPPEL":"ISOLATED_SHELF_WALK_DOWN",
                    here,"shelf excluded; try another physical route");
            // This is a real altitude-changing recovery. A new search
            // begins below, with the hard stage timeout still enforced.
            lastHeightProgress=tick;
            return true;
        }
        String tick(long tick){
            if(done)return null;
            if(mob.tickCount<5)return null;
            if(stageStart==0){stageStart=tick;lastHeightProgress=tick;}
            if(tick-stageStart>STAGE_TIMEOUT)
                return "Vertical phase timeout";
            if(tick-lastHeightProgress>PROGRESS_TIMEOUT)
                return "No vertical progress after "+PROGRESS_TIMEOUT
                        +" ticks; nav="+WorkerNavigation.debugStatus(mob)
                        +" rope="+BuilderGrappleAccess.debug(mob);
            if(BuilderGrappleAccess.active(mob))return null;
            BlockPos here=mob.blockPosition();
            if(phase.equals("ASCEND")
                    &&mob.onGround()&&here.getY()>=summitY
                    &&footings.contains(here)
                    &&WorkerNavigation.canStandOn(level,mob,here)){
                log(tick,"ROOF_REACHED",here,
                        "grounded supported roof Y=35; finial excluded");
                phase="DESCEND";goal=null;stageStart=tick;
                lastHeightProgress=tick;minDescentY=here.getY();
                excludedUntil.clear();
                WorkerNavigation.stop(mob);
                return null;
            }
            if(phase.equals("DESCEND")&&mob.onGround()
                    &&here.getY()<=base.getY()
                    &&level.getBlockState(here.below()).isFaceSturdy(
                            level,here.below(),net.minecraft.core.Direction.UP)){
                log(tick,"BASE_REACHED",here,"grounded and supported");
                done=true;return null;
            }
            if(goal!=null){
                if(mob.onGround()&&here.equals(goal)){
                    log(tick,"WAYPOINT_REACHED",goal,"reached physical tile");
                    goal=null;
                }else if(tick-goalStart<ROUTE_RETRY_TICKS){
                    WorkerNavigation.routeToExact(level,mob,goal,1.0);
                    return null;
                }else{
                    excludedUntil.put(goal,tick+640);
                    log(tick,"ROUTE_STALLED",goal,
                            WorkerNavigation.debugStatus(mob));
                    goal=null;routeFailures++;
                    WorkerNavigation.stop(mob);
                }
            }
            // Bound expensive multi-goal searches. The already issued
            // trained path continues to execute between controller ticks.
            if(tick-lastGoalAttempt<40)return null;
            lastGoalAttempt=tick;
            scans++;
            if(!mob.onGround())return null;
            if(phase.equals("ASCEND")){
                if(normalVertical(tick,true))return null;
                if(grappleUp(tick))return null;
                // Reposition across a reachable exterior roof/ledge,
                // including safe 1-2 level descents when necessary.
                if(seekRoofFrontier(tick))return null;
                if(recoverIsolatedShelf(tick))return null;
            }else{
                if(normalVertical(tick,false))return null;
                boolean accepted=BuilderGrappleAccess.rappelToward(
                        level,mob,table,base);
                if(accepted){
                    if(BuilderGrappleAccess.active(mob))
                        log(tick,"GRAPPLE_DESCENT_REQUEST",base,
                                BuilderGrappleAccess.debug(mob));
                    else log(tick,"NORMAL_DROP_ROUTE",base,
                                WorkerNavigation.debugStatus(mob));
                    return null;
                }
            }
            if(tick%400==0)
                log(tick,"NO_VERTICAL_PATH",here,
                        "nav="+WorkerNavigation.debugStatus(mob)
                        +" rope="+BuilderGrappleAccess.debug(mob));
            return null;
        }
        String status(){
            return "phase="+phase+" tick="+mob.tickCount
                    +" y="+(mob.blockPosition().getY()-origin.getY())
                    +" peak="+(maxY-origin.getY())
                    +" summit="+(summitY-origin.getY())
                    +" minDescent="+(minDescentY-origin.getY())
                    +" grounded="+mob.onGround()
                    +" health="+mob.getHealth()
                    +" removed="+mob.isRemoved()
                    +" forceReassertions="+reforcedChunks
                    +" survivedOldUnloadTick="+passedUnloadWindow
                    +" goal="+(goal==null?"none":
                        (goal.getX()-origin.getX())+"/"
                        +(goal.getY()-origin.getY())+"/"
                        +(goal.getZ()-origin.getZ()))
                    +" jumps="+jumps+" lowerLandings="+landings
                    +" footingUp="+levelsUp+" footingDown="+levelsDown
                    +" grappleUps="+grappleUps+" grappleDowns="+grappleDowns
                    +" ropeAttempts="+ropeAttempts+" probes="+grappleProbes+" noAnchors="+anchorMisses
                    +" frontierRoutes="+frontierRoutes
                    +" frontierNoPaths="+frontierNoPaths
                    +" shelfRecoveries="+descentRecoveries
                    +" isolatedSectors="+isolatedShelvesUntil.size()
                    +" rejectedTargets="+rejectedSectorTargets
                    +" position="+(mob.blockPosition().getX()-origin.getX())
                        +"/"+(mob.blockPosition().getZ()-origin.getZ())
                    +" routeFailures="+routeFailures
                    +" walked="+String.format(Locale.ROOT,"%.1f",walked)
                    +" nav="+WorkerNavigation.debugStatus(mob)
                    +" rope="+BuilderGrappleAccess.debug(mob)
                    +" last="+lastAction;
        }
        void writeReports(String outcome){
            try{
                Files.createDirectories(REPORT.getParent());
                Files.writeString(REPORT,csv.toString(),StandardCharsets.UTF_8);
                Files.writeString(SUMMARY,outcome+"\n"+status()+"\n"
                        +"Original 3537 physical blocks; one real golem; "
                        +"max ascent 1-step walking, rope reach 20; "
                        +"grounded summit and grounded return required\n",
                        StandardCharsets.UTF_8);
            }catch(Exception e){
                System.err.println("[DOME SUMMIT] report write failed "+e);
            }
        }
    }
}
