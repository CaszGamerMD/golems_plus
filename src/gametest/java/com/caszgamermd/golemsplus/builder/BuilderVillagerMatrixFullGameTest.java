package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.CreativeCrateBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import com.caszgamermd.golemsplus.worker.WorkerPhase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** The full, ORIGINAL user-uploaded VillagerMatrixClean_V2 (15,376 blocks).
 * No shortcut: the physical test sends four real Builder entities to an
 * empty, four-block-elevated job and counts only actual matching world blocks.
 * The actual Creative Crate supplies and accepts cargo at physical OnSite reach.
 * This long test is isolated from the quick mandatory GameTest suite.
 */
public final class BuilderVillagerMatrixFullGameTest {
    private static final long CONTROLLED_SEED=0L;
    private static final long CONTROLLED_RANDOM_SEED=260215376L;
    private static final BlockPos CONTROLLED_ARENA=new BlockPos(1024,-57,1024);
    private static final int SOURCE_BLOCKS=15376;
    private static final int SOURCE_AIR=15004;
    private static final int WORKERS=4;
    private static final int PROGRESS_PERIOD=400;
    private static final int STALL_TICKS=300;
    private static final int NO_WORK_LIMIT=1200;
    private static final int MOTION_SAMPLE_TICKS=5;
    private record Expected(BlockPos pos,BlockState state) {}

    @GameTest(structure="golems_plus_gametest:matrix_arena",maxTicks=20)
    public void acceptsFiveBlockWorkPoseThroughSolidWall(GameTestHelper h){
        var level=h.getLevel();
        for(int x=1;x<=9;x++)for(int z=1;z<=5;z++)
            h.setBlock(x,1,z,Blocks.STONE);
        for(int y=2;y<=4;y++)for(int z=1;z<=5;z++)
            h.setBlock(4,y,z,Blocks.STONE);
        var stance=h.absolutePos(new BlockPos(2,2,3));
        var target=h.absolutePos(new BlockPos(6,3,5));
        var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        worker.setPos(stance.getX()+.5,stance.getY(),stance.getZ()+.5);
        if(BuilderConstructionPlanner.lineOfSight(level,worker,stance,target)
                ||!BuilderConstructionPlanner.safeStand(level,worker,target,stance)
                ||BuilderConstructionPlanner.safeStand(
                        level,worker,target.east(),stance)){
            h.fail("Wall should block sight while only the in-range pose is accepted");
            return;
        }
        h.succeed();
    }

    @GameTest(maxTicks=20)
    public void detectsCompleteStallWindows(GameTestHelper h){
        var still=new MatrixMovementWindow(MOTION_SAMPLE_TICKS,STALL_TICKS);
        var repeating=new MatrixMovementWindow(MOTION_SAMPLE_TICKS,STALL_TICKS);
        var walking=new MatrixMovementWindow(MOTION_SAMPLE_TICKS,STALL_TICKS);
        for(int tick=0;tick<=STALL_TICKS;tick+=MOTION_SAMPLE_TICKS){
            still.sample(tick,Vec3.ZERO,true);
            int phase=tick%80;
            repeating.sample(tick,new Vec3(Math.min(phase,80-phase)*.1,0,0),true);
            walking.sample(tick,new Vec3(tick*.01,0,0),true);
            if(tick<STALL_TICKS&&still.stall()!=MatrixMovementWindow.Stall.MOVING){
                h.fail("Stationary movement was classified before 300 ticks");
                return;
            }
        }
        if(still.stall()!=MatrixMovementWindow.Stall.STATIONARY
                ||repeating.stall()!=MatrixMovementWindow.Stall.BACK_AND_FORTH
                ||walking.stall()!=MatrixMovementWindow.Stall.MOVING){
            h.fail("A complete window did not distinguish standing, repeating, and walking");
            return;
        }
        repeating.sample(STALL_TICKS+MOTION_SAMPLE_TICKS,new Vec3(0,0,3),true);
        still.sample(STALL_TICKS+MOTION_SAMPLE_TICKS,Vec3.ZERO,false);
        if(repeating.stall()!=MatrixMovementWindow.Stall.MOVING
                ||still.stall()!=MatrixMovementWindow.Stall.MOVING){
            h.fail("A new route or missing builder was counted as stalled");
            return;
        }
        h.succeed();
    }

    @GameTest(structure="golems_plus_gametest:matrix_arena",maxTicks=80,
            skyAccess=true)
    public void importsEntireUserSchematic(GameTestHelper h){
        try{
            var schematic=LitematicFileReader.read(
                    BuilderVillagerMatrixFullFixture.unpack());
            if(schematic.blocks().size()!=SOURCE_BLOCKS
                    ||schematic.airBlocks().size()!=SOURCE_AIR
                    ||!schematic.name().equals("VillagerMatrixClean_V2")){
                h.fail("Matrix fixture does not match upload: blocks="
                        +schematic.blocks().size()+" air="
                        +schematic.airBlocks().size()+" name="+schematic.name());
                return;
            }
            var bounds=schematic.blocks();
            int minX=bounds.stream().mapToInt(LitematicFileReader.Block::x).min().orElse(-1);
            int maxX=bounds.stream().mapToInt(LitematicFileReader.Block::x).max().orElse(-1);
            int minZ=bounds.stream().mapToInt(LitematicFileReader.Block::z).min().orElse(-1);
            int maxZ=bounds.stream().mapToInt(LitematicFileReader.Block::z).max().orElse(-1);
            if(minX!=0||maxX!=30||minZ!=0||maxZ!=48){
                h.fail("Matrix coordinates are wrong: x="+minX+".."+maxX
                        +" z="+minZ+".."+maxZ);return;
            }
            boolean eastHook=bounds.stream().anyMatch(b->
                    b.x()==14&&b.y()==3&&b.z()==24
                    &&b.state().startsWith("minecraft:tripwire_hook[")
                    &&b.state().contains("facing=east"));
            boolean westHook=bounds.stream().anyMatch(b->
                    b.x()==16&&b.y()==3&&b.z()==24
                    &&b.state().startsWith("minecraft:tripwire_hook[")
                    &&b.state().contains("facing=west"));
            if(!eastHook||!westHook){
                h.fail("Signed Matrix region put tripwire hooks on the wrong sides");
                return;
            }
            long dustBlocks=schematic.blocks().stream()
                    .filter(b->b.state().startsWith("minecraft:redstone_wire["))
                    .count();
            var paidDust=BuilderBlockResolver.resolve(
                    "minecraft:redstone_wire[east=side,north=none,power=12,south=side,west=none]",
                    BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
            if(dustBlocks!=700||paidDust==null||paidDust.item()!=Items.REDSTONE){
                h.fail("Redstone source/material regression: "+dustBlocks
                        +" cells, resolver="+paidDust);return;
            }
            System.out.println("[MATRIX REDSTONE IMPORT PASS] "+dustBlocks
                    +" redstone-wire cells use actual redstone dust");
            System.out.println("[MATRIX FULL IMPORT PASS] "+SOURCE_BLOCKS
                    +" source blocks, "+SOURCE_AIR
                    +" empty cells, full signed 31x20x49 region");
            h.succeed();
        }catch(Exception exception){
            h.fail("Matrix full-source import failed: "+exception);
        }
    }

    /** Real Minecraft block update check for a paid ascending rail whose
     * raised neighbor cannot hold a temporary rail. No block is created
     * by the slope correction; a real rail is staged on solid support. */
    @GameTest(structure="golems_plus_gametest:matrix_arena",
            maxTicks=80,skyAccess=true)
    public void retainsPaidRailSlopeWithoutPermanentJig(GameTestHelper h){
        ServerLevel level=h.getLevel();
        BlockPos pos=h.absolutePos(new BlockPos(12,3,12));
        h.setBlock(12,2,12,Blocks.SMOOTH_STONE);
        var desired=BuilderBlockResolver.resolve(
                "minecraft:powered_rail[powered=false,shape=ascending_west,waterlogged=false]",
                BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
        if(desired==null){h.fail("Could not resolve powered rail slope");return;}
        if(!level.setBlock(pos,Blocks.POWERED_RAIL.defaultBlockState(),3)
                ||!BuilderRailSlope.restorePaidSlope(level,pos,desired.state())){
            h.fail("Could not stabilize a physically present paid rail");return;
        }
        var energized=BuilderBlockResolver.resolve(
                "minecraft:powered_rail[powered=true,shape=ascending_west,waterlogged=false]",
                BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
        var waterlogged=BuilderBlockResolver.resolve(
                "minecraft:powered_rail[powered=true,shape=ascending_west,waterlogged=true]",
                BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
        if(energized==null||waterlogged==null
                ||!BuilderPlacementState.matches(level.getBlockState(pos),
                        energized.state())
                ||BuilderPlacementState.matches(level.getBlockState(pos),
                        waterlogged.state())
                ||BuilderPlacementState.matches(
                        Blocks.POWERED_RAIL.defaultBlockState(),desired.state())){
            h.fail("Rail matching must ignore power, not slope or waterlogging");
            return;
        }
        h.onEachTick(()->{
            if(h.getTick()!=20)return;
            if(!BuilderPlacementState.matches(level.getBlockState(pos),
                    desired.state())){
                h.fail("Paid ascending rail lost slope after world ticks: "
                        +level.getBlockState(pos));return;
            }
            System.out.println("[MATRIX RAIL PASS] west ascending rail "
                    +"retained without an unsupported raised jig");
            h.succeed();
        });
    }

    @GameTest(structure="golems_plus_gametest:matrix_arena",
            maxTicks=80,skyAccess=true)
    public void preventsPaidRailRebuildWhenRaisedEndHasNoSupport(GameTestHelper h){
        var world=h.getLevel();
        BlockPos pos=h.absolutePos(new BlockPos(12,3,12));
        h.setBlock(12,2,12,Blocks.SMOOTH_STONE);
        h.setBlock(11,2,12,Blocks.SMOOTH_STONE);
        h.setBlock(11,3,12,Blocks.POWERED_RAIL);
        var wanted=BuilderBlockResolver.resolve(
                "minecraft:powered_rail[powered=false,shape=ascending_west,waterlogged=true]",
                BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
        if(wanted==null){h.fail("Could not resolve wet ascending rail");return;}
        if(BuilderRailSlope.hasRaisedEndSupport(world,pos,wanted.state())){
            h.fail("A same-height rail falsely supports the raised connector");
            return;
        }
        // A REAL upper connector would survive only after this same
        // coordinate is physically supported. A paid replacement must not
        // be consumed until that condition is actually satisfied.
        h.setBlock(11,3,12,Blocks.SMOOTH_STONE);
        if(!BuilderRailSlope.hasRaisedEndSupport(world,pos,wanted.state())){
            h.fail("Solid west-end foundation was not recognized");return;
        }
        System.out.println("[MATRIX RAIL SUPPORT PASS] unsupported "
                +"same-Y neighbor blocked paid rail retry; solid support accepted");
        h.succeed();
    }

    @SuppressWarnings("removal")
    @GameTest(structure="golems_plus_gametest:matrix_arena",
            maxTicks=1_000_000_000,skyAccess=true,required=false)
    public void constructsMatrixUntilFourBuildersStall(GameTestHelper h){
        ServerLevel level=h.getLevel();
        final LitematicFileReader.Schematic schematic;
        final BuilderConstructionPlan.Result plan;
        final boolean controlled="controlled".equals(System.getenv("MATRIX_TEST_MODE"));
        if(controlled&&level.getSeed()!=CONTROLLED_SEED){
            h.fail("Controlled Matrix seed mismatch: "+level.getSeed());return;
        }
        // The harness may place its own test marker at a random coordinate.
        // The scored physical arena is independent of that marker.
        final BlockPos arena=controlled?CONTROLLED_ARENA:h.absolutePos(BlockPos.ZERO);
        final BlockPos origin=arena.offset(10,6,10);
        final BlockPos arenaCenter=arena.offset(32,2,36);
        level.getChunkSource().addTicketWithRadius(
                net.minecraft.server.level.TicketType.FORCED,
                new net.minecraft.world.level.ChunkPos(
                        arenaCenter.getX()>>4,arenaCenter.getZ()>>4),4);
        if(controlled){
            // Load the same full footprint before editing blocks, not
            // lazily as the workers arrive at different chunk boundaries.
            for(int cx=(arena.getX()>>4)-1;cx<=(arena.getX()+63>>4)+1;cx++)
                for(int cz=(arena.getZ()>>4)-1;cz<=(arena.getZ()+71>>4)+1;cz++)
                    level.getChunk(cx,cz);
        }
        try{
            schematic=LitematicFileReader.read(
                    BuilderVillagerMatrixFullFixture.unpack());
            if(schematic.blocks().size()!=SOURCE_BLOCKS
                    ||schematic.airBlocks().size()!=SOURCE_AIR
                    ||!schematic.name().equals("VillagerMatrixClean_V2"))
                throw new IllegalStateException("Unexpected schematic block count or name");
            System.out.println("[MATRIX FULL IMPORT PASS] "+SOURCE_BLOCKS
                    +" source blocks, "+SOURCE_AIR+" air cells; original fixture");
            plan=BuilderConstructionPlan.create(schematic,
                    new BuildSite(origin.getX(),origin.getY(),origin.getZ(),
                            BuildSite.Rotation.NONE,BuildSite.Mirror.NONE),false);
        }catch(Exception e){h.fail("Cannot plan full Villager Matrix: "+e);return;}
        final List<BuilderConstructionPlan.Step> steps=plan.steps();
        final List<Expected> expected=new ArrayList<>(SOURCE_BLOCKS);
        int unresolved=0;
        for(var cell:schematic.blocks()){
            var desired=BuilderBlockResolver.expectedState(cell.state(),
                    BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
            if(desired==null){unresolved++;continue;}
            expected.add(new Expected(origin.offset(cell.x(),cell.y(),cell.z()),
                    desired));
        }
        final int unavailableStates=unresolved;
        System.out.println("[MATRIX FULL TARGET] sourceBlocks="+SOURCE_BLOCKS
                +" dryPlusWaterSteps="+steps.size()+" unsupportedCells="
                +unavailableStates+" unsupportedStates="+plan.unsupported()
                +" "+WORKERS+" actual Builders; no scaffold or structure prebuilt");

        // The matrix_arena structure template contains no block records.
        // Vanilla's random test coordinates can therefore leave terrain
        // or a generated structure inside its nominally empty air volume.
        // Initialize the SAME empty flat test site each time, before
        // creating the job or spawning any Builder. Never preplace a
        // source cell or modify the world during the scored construction.
        int terrain=0;
        for(int x=1;x<=62;x++)for(int z=1;z<=70;z++){
            level.setBlock(arena.offset(x,1,z),Blocks.STONE.defaultBlockState(),2);
            for(int y=2;y<=27;y++){
                BlockPos p=arena.offset(x,y,z);
                if(!level.getBlockState(p).isAir()){
                    level.setBlock(p,Blocks.AIR.defaultBlockState(),2);
                    terrain++;
                }
            }
        }
        System.out.println("[MATRIX EMPTY ARENA] removed preexisting test terrain="+terrain);
        if(controlled){
            // Verify every floor and air cell after clearing; fail rather
            // than silently compare a contaminated fixture.
            for(int x=1;x<=62;x++)for(int z=1;z<=70;z++){
                if(!level.getBlockState(arena.offset(x,1,z)).is(Blocks.STONE)){
                    h.fail("Controlled Matrix floor mismatch");return;
                }
                for(int y=2;y<=27;y++)if(!level.getBlockState(arena.offset(x,y,z)).isAir()){
                    h.fail("Controlled Matrix air mismatch at "+arena.offset(x,y,z));return;
                }
            }
        }
        // The wider upper layers extend far beyond the starter foundation.
        // Measure depot proximity to occupied cells on the first build layer.
        BlockPos plannerPos=arena.offset(16,2,32);
        BlockPos supplyPos=arena.offset(17,2,32);
        int foundationY=schematic.blocks().stream()
                .mapToInt(LitematicFileReader.Block::y).min().orElseThrow();
        double distanceSquared=schematic.blocks().stream()
                .filter(cell->cell.y()==foundationY)
                .mapToDouble(cell->origin.offset(cell.x(),cell.y(),cell.z())
                        .distSqr(supplyPos))
                .min().orElseThrow();
        if(distanceSquared>100){
            h.fail("OnSite Storage must be within 10 blocks of the foundation");
            return;
        }
        System.out.println("[MATRIX FULL DEPOT] foundationDistance="
                +Math.sqrt(distanceSquared));
        level.setBlock(plannerPos,WorksiteContent.BUILDER_TABLE.defaultBlockState(),3);
        level.setBlock(supplyPos,WorksiteContent.CREATIVE_CRATE.defaultBlockState(),3);
        if(!(level.getBlockEntity(plannerPos) instanceof WorkerStationBlockEntity table)){
            h.fail("Missing full Matrix Planner");return;
        }
        ServerPlayer owner=h.makeMockServerPlayerInLevel();
        if(controlled)owner.setPos(plannerPos.getX()+.5,plannerPos.getY(),plannerPos.getZ()+2.5);
        UUID ownerId=owner.getUUID();
        if(!table.claimIfUnowned(ownerId)||!table.setCrewTarget(ownerId,WORKERS)){
            h.fail("Cannot configure four Matrix Builders");return;
        }
        Path folder=FabricLoader.getInstance().getConfigDir()
                .resolve("golems_plus").resolve("schematics");
        String name="placed-gametest-matrix-clean-v2.gpb";
        try{
            Files.createDirectories(folder);
            Files.write(folder.resolve(name),BuilderPlacementSnapshot.encode(
                    schematic.name(),absoluteCells(schematic.blocks(),origin),
                    absoluteCells(schematic.airBlocks(),origin)));
        }catch(Exception e){h.fail("Cannot stage exact Matrix file: "+e);return;}
        if(!table.setPlacedBuilderPlan(ownerId,name,schematic.name(),origin,
                    0,0,steps.size(),plan.unsupported().size())
                ||!table.setBuilderMaterialStorage(ownerId,supplyPos)){
            h.fail("Planner refused full Matrix job");return;
        }
        table.setBuilderMaterialsForFile(name,plan.required());
        // Use the production creative depot, not a refilled finite chest.
        // Workers still must physically reach the depot and carry materials.
        if(!(level.getBlockEntity(supplyPos) instanceof CreativeCrateBlockEntity)
                ||!BuilderSupplyInventory.creativeSupply(table)){
            h.fail("Matrix requires linked Creative Crate");return;
        }
        var crew=new ArrayList<BuilderGolem>();
        for(int i=0;i<WORKERS;i++){
            BuilderGolem worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
            BlockPos from=arena.offset(16+i%4,2,29+i/4);
            if(controlled){
                worker.setUUID(new UUID(0x4d41545249584cL,i+1));
                worker.setId(100000+i);
                worker.getRandom().setSeed(CONTROLLED_RANDOM_SEED+i+1);
                worker.setCustomName(net.minecraft.network.chat.Component.literal("Matrix Builder "+(i+1)));
            }
            worker.setPos(from.getX()+.5,from.getY(),from.getZ()+.5);
            worker.assign(plannerPos,ownerId);
            level.addFreshEntity(worker);
            if(!table.registerBuilderWorker(worker.getUUID())){
                h.fail("Cannot register Builder "+i);return;
            }
            crew.add(worker);
        }
        if(controlled){
            level.getRandom().setSeed(CONTROLLED_RANDOM_SEED);
            var roster=new StringBuilder();
            for(var worker:crew)roster.append(worker.getUUID()).append('@')
                    .append(worker.getId()).append('@').append(worker.blockPosition().toShortString().replace(" ",""))
                    .append(';');
            String manifest="seed="+level.getSeed()+" origin="+origin.toShortString().replace(" ","")
                    +" arena=62x26x70 floor=stone depot="+supplyPos.toShortString().replace(" ","")
                    +" crew="+roster+" supply=creative_crate cap=1500"
                    +" temporaryScaffolding="+BuilderGolem.temporaryScaffoldingEnabled();
            System.out.println("[MATRIX CONTROLLED ENV] "+manifest);
            System.out.println("[MATRIX CLOCK] worldTick="+level.getGameTime()+" testTick="+h.getTick());
        }
        if(!table.startPlacedBuilder(ownerId)){
            h.fail("Planner refused to start full Matrix");return;
        }
        final int[] best={0};
        final long assessmentStart=System.nanoTime();
        // The standard five-minute benchmark stays unchanged. Reliability
        // trials opt into a longer ceiling; elapsed time starts only when
        // the actual construction job starts, not Gradle/server startup.
        final long assessmentSeconds=Long.parseLong(
                System.getenv().getOrDefault("MATRIX_ASSESSMENT_SECONDS","300"));
        if(assessmentSeconds<1||assessmentSeconds>1800){
            h.fail("Invalid assessment time ceiling");return;
        }
        System.out.println("[MATRIX TRIAL START] limitSeconds="+assessmentSeconds
                +" origin="+origin.toShortString()+" workers="+WORKERS);
        var watchdog=new MatrixWorkWatchdog(h.getTick(),
                table.builderWorkDone(),table.builderClearCursor());
        var movement=crew.stream()
                .map(worker->new MatrixMovementWindow(MOTION_SAMPLE_TICKS,STALL_TICKS))
                .toList();
        // onEachTick eagerly allocates one callback for every maxTicks value.
        // A sequence keeps one repeating event for this open-ended assessment.
        h.startSequence().thenExecuteFor(1_000_000_000,()->{
            long tick=h.getTick();
            if(!BuilderGolem.temporaryScaffoldingEnabled()&&tick%100==0){
                for(var worker:crew){
                    if(!worker.getItem(BuilderGolem.SCAFFOLD_BELT_SLOT).isEmpty()
                            ||!table.scaffoldPositions(worker.getUUID()).isEmpty()){
                        h.fail("Scaffold-free Matrix violated tool/temporary scaffold restriction");
                        return;
                    }
                }
            }
            long noWorkTicks=watchdog.sample(tick,
                    table.builderWorkDone(),table.builderClearCursor());
            if(tick%MOTION_SAMPLE_TICKS==0){
                for(int i=0;i<crew.size();i++){
                    var worker=crew.get(i);
                    movement.get(i).sample(tick,worker.position(),
                            worker.isAlive()&&level.getEntity(worker.getUUID())==worker);
                }
            }
            long elapsedMillis=(System.nanoTime()-assessmentStart)/1_000_000L;
            boolean timeLimit=elapsedMillis>=assessmentSeconds*1000L;
            boolean recoveryGrace=BuilderCrewRecovery.assessmentGrace(level,table);
            boolean noWorkTimeout=!recoveryGrace&&noWorkTicks>=NO_WORK_LIMIT;
            boolean report=tick%PROGRESS_PERIOD==0;
            var stalls=tick%MOTION_SAMPLE_TICKS==0
                    &&(report||noWorkTicks>=STALL_TICKS)
                    ?movement.stream().map(MatrixMovementWindow::stall).toList()
                    :List.<MatrixMovementWindow.Stall>of();
            boolean allStalled=!recoveryGrace&&noWorkTicks>=STALL_TICKS
                    &&stalls.size()==WORKERS
                    &&stalls.stream().allMatch(stall->stall!=MatrixMovementWindow.Stall.MOVING);
            if(!report&&!allStalled&&!noWorkTimeout&&!timeLimit&&table.phase()!=WorkerPhase.COMPLETE)return;
            int correct=0;
            for(Expected candidate:expected)
                if(BuilderPistonWork.sourceMatches(
                        level.getBlockState(candidate.pos()),candidate.state()))
                    correct++;
            int cursor=table.builderCursor();
            best[0]=Math.max(best[0],correct);
            int y=table.builderActiveLayer(steps);
            System.out.println("[MATRIX FULL PROGRESS] tick="+tick
                    +" matchingSourceBlocks="+correct+"/"+SOURCE_BLOCKS
                    +" best="+best[0]+" cursor="+cursor+"/"+steps.size()
                    +" currentLayer="+(y==Integer.MAX_VALUE?"complete":y-origin.getY())
                    +" noWorkTicks="+(noWorkTicks)
                    +" crewAlive="+crew.stream().filter(BuilderGolem::isAlive).count()
                    +" movement="+stalls
                    +" problem="+table.problem());
            // A cursor alone cannot distinguish "rail was popped",
            // "water replaced the dry block", "support is missing" or a
            // genuine navigation failure. Observe all actual neighboring
            // states before proposing another construction shortcut.
            if((allStalled||noWorkTicks>=800)&&cursor>=0&&cursor<steps.size()){
                int activeIndex=table.builderActiveStepIndex(steps);
                var stalled=steps.get(activeIndex<0?cursor:activeIndex);
                BlockPos at=new BlockPos(stalled.x(),stalled.y(),stalled.z());
                var resolved=BuilderBlockResolver.resolve(
                        stalled.encodedState(),BuildSite.Rotation.NONE,
                        BuildSite.Mirror.NONE);
                System.out.println("[MATRIX BLOCK DIAGNOSTIC] tick="+tick
                        +" local="+at.subtract(origin)
                        +" desired="+stalled.encodedState()
                        +" actual="+level.getBlockState(at)
                        +" below="+level.getBlockState(at.below())
                        +" above="+level.getBlockState(at.above())
                        +" west="+level.getBlockState(at.west())
                        +" westUp="+level.getBlockState(at.west().above())
                        +" supported="+(resolved!=null
                            &&resolved.state().canSurvive(level,at))
                        +" fluid="+level.getFluidState(at));
            }
            if((allStalled||noWorkTimeout||timeLimit||table.phase()==WorkerPhase.COMPLETE)&&correct!=SOURCE_BLOCKS){
                var kinds=new java.util.TreeMap<String,Integer>();
                int samples=0;
                for(Expected candidate:expected){
                    var actual=level.getBlockState(candidate.pos());
                    if(BuilderPistonWork.sourceMatches(actual,candidate.state()))continue;
                    String key=candidate.state().getBlock()+" -> "+actual.getBlock();
                    kinds.merge(key,1,Integer::sum);
                    if(samples++<200)System.out.println("[MATRIX MISMATCH] local="+candidate.pos().subtract(origin)
                            +" expected="+candidate.state()+" actual="+actual
                            +" below="+level.getBlockState(candidate.pos().below())
                            +" above="+level.getBlockState(candidate.pos().above()));
                }
                System.out.println("[MATRIX MISMATCH TOTALS] "+kinds);
            }
            if(table.phase()==WorkerPhase.COMPLETE){
                BuilderTrace.dump(level,table,"matrix complete");
                System.out.println("[MATRIX TRIAL RESULT] outcome="+(correct==SOURCE_BLOCKS?"COMPLETE":"MISMATCH")
                        +" layer=complete elapsedMs="+elapsedMillis+" tick="+tick
                        +" correct="+correct+" total="+SOURCE_BLOCKS+" best="+best[0]
                        +" unsupported="+unavailableStates);
                if(correct!=SOURCE_BLOCKS){
                    h.fail("Full Matrix marked complete but "
                            +(SOURCE_BLOCKS-correct)+" source blocks differ; "
                            +unavailableStates+" unsupported cells");return;
                }
                System.out.println("[MATRIX FULL PASS] "+SOURCE_BLOCKS
                        +"/"+SOURCE_BLOCKS
                        +" physical source blocks verified with four Builders");
                h.succeed();return;
            }
            if(timeLimit&&!allStalled&&!noWorkTimeout){
                BuilderTrace.dump(level,table,"matrix time limit");
                System.out.println("[MATRIX TRIAL RESULT] outcome=TIME_LIMIT layer="+(y-origin.getY())
                        +" elapsedMs="+elapsedMillis+" tick="+tick+" correct="+correct
                        +" total="+SOURCE_BLOCKS+" best="+best[0]+" unsupported="+unavailableStates);
                System.out.println("[MATRIX FULL TIMED] seconds="+assessmentSeconds+" tick="+tick
                        +" matchingSourceBlocks="+correct+"/"+SOURCE_BLOCKS
                        +" best="+best[0]+" layer="+(y-origin.getY()));
                h.succeed();return;
            }
            if(allStalled||noWorkTimeout){
                BuilderTrace.dump(level,table,"matrix stall");
                System.out.println("[MATRIX TRIAL RESULT] outcome="+(allStalled?"ALL_BUILDERS_STALLED":"NO_WORK_PROGRESS")
                        +" layer="+(y-origin.getY())+" elapsedMs="+elapsedMillis+" tick="+tick
                        +" correct="+correct+" total="+SOURCE_BLOCKS+" best="+best[0]
                        +" unsupported="+unavailableStates);
                StringBuilder result=new StringBuilder(
                        "[MATRIX FULL STALL] tick="+tick
                                +" reason="+(allStalled?"ALL_BUILDERS_STALLED":"NO_WORK_PROGRESS")
                                +" noWorkTicks="+(noWorkTicks)
                                +" allBuilders="+WORKERS+" windowTicks="+STALL_TICKS
                                +" matchingSourceBlocks="+correct+"/"+SOURCE_BLOCKS
                                +" best="+best[0]+"/"+SOURCE_BLOCKS
                                +" cursor="+cursor+"/"+steps.size()
                                +" layer="+(y-origin.getY())
                                +" unsupported="+unavailableStates
                                +" problem="+table.problem());
                for(int i=0;i<crew.size();i++){
                    var mob=crew.get(i);
                    result.append(" | builder=").append(mob.getName().getString())
                            .append(" stall=").append(movement.get(i).stall())
                            .append(" feet=").append(mob.blockPosition())
                            .append(" step=").append(table.claimedStep(mob.getUUID()))
                            .append(" bridge=").append(BuilderScaffoldPlanA.status(mob))
                            .append(" grapple=").append(BuilderGrappleAccess.debug(mob))
                            .append(" ").append(BuilderBuildService.debugStatus(level,mob));
                }
                System.out.println(result);
                h.fail("Matrix assessment stopped: "+(allStalled
                        ?"all four builders stalled for 300 ticks"
                        :"no new work for "+NO_WORK_LIMIT+" ticks")
                        +"; best="+best[0]+"/"+SOURCE_BLOCKS);
            }
        });
    }

    private static List<LitematicFileReader.Block> absoluteCells(
            List<LitematicFileReader.Block> cells,BlockPos origin){
        return cells.stream().map(cell->new LitematicFileReader.Block(
                origin.getX()+cell.x(),origin.getY()+cell.y(),
                origin.getZ()+cell.z(),cell.state())).toList();
    }


}
