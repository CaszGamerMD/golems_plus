package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import com.caszgamermd.golemsplus.worker.WorkerPhase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.Container;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** The full, ORIGINAL user-uploaded VillagerMatrixClean_V2 (15,376 blocks).
 * No shortcut: the physical test sends four real Builder entities to an
 * empty, four-block-elevated job and counts only actual matching world blocks.
 * The test-only OnSite chest is replenished; no golem inventory is fabricated.
 * This long test is isolated from the quick mandatory GameTest suite.
 */
public final class BuilderVillagerMatrixFullGameTest {
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
        final BlockPos origin=h.absolutePos(new BlockPos(10,6,10));
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
            h.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=27;y++){
                BlockPos p=h.absolutePos(new BlockPos(x,y,z));
                if(!level.getBlockState(p).isAir()){
                    level.setBlock(p,Blocks.AIR.defaultBlockState(),2);
                    terrain++;
                }
            }
        }
        System.out.println("[MATRIX EMPTY ARENA] removed preexisting test terrain="+terrain);
        // The wider upper layers extend far beyond the starter foundation.
        // Measure depot proximity to occupied cells on the first build layer.
        BlockPos plannerPos=h.absolutePos(new BlockPos(16,2,32));
        BlockPos supplyPos=h.absolutePos(new BlockPos(17,2,32));
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
        // Keep the depot, crew and all build chunks ticking in this arena.
        BlockPos arenaCenter=h.absolutePos(new BlockPos(32,2,36));
        level.getChunkSource().addTicketWithRadius(
                net.minecraft.server.level.TicketType.FORCED,
                new net.minecraft.world.level.ChunkPos(
                        arenaCenter.getX()>>4,arenaCenter.getZ()>>4),4);
        h.setBlock(16,2,32,WorksiteContent.BUILDER_TABLE);
        h.setBlock(17,2,32,Blocks.CHEST);
        if(!(level.getBlockEntity(plannerPos) instanceof WorkerStationBlockEntity table)){
            h.fail("Missing full Matrix Planner");return;
        }
        ServerPlayer owner=h.makeMockServerPlayerInLevel();
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
        // Item availability is a test fixture, not a Creative-style Builder:
        // golems still take items from the chest, walk, scaffold, and place.
        var firstNeeds=baseStock();
        for(int i=0;i<Math.min(steps.size(),64);i++)
            if(firstNeeds.size()<26)addItem(firstNeeds,steps.get(i));
        // Stock one physical scaffold stack per Builder.
        keepScaffoldingStock(level,supplyPos);
        BuilderInfiniteOnsiteSupply.restock(level,supplyPos,firstNeeds);
        var crew=new ArrayList<BuilderGolem>();
        for(int i=0;i<WORKERS;i++){
            BuilderGolem worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
            BlockPos from=h.absolutePos(new BlockPos(16+i%4,2,29+i/4));
            worker.setPos(from.getX()+.5,from.getY(),from.getZ()+.5);
            worker.assign(plannerPos,ownerId);
            level.addFreshEntity(worker);
            if(!table.registerBuilderWorker(worker.getUUID())){
                h.fail("Cannot register Builder "+i);return;
            }
            crew.add(worker);
        }
        if(!table.startPlacedBuilder(ownerId)){
            h.fail("Planner refused to start full Matrix");return;
        }
        final int[] best={0};
        final long assessmentStart=System.nanoTime();
        var watchdog=new MatrixWorkWatchdog(h.getTick(),
                table.builderWorkDone(),table.builderClearCursor());
        var movement=crew.stream()
                .map(worker->new MatrixMovementWindow(MOTION_SAMPLE_TICKS,STALL_TICKS))
                .toList();
        // onEachTick eagerly allocates one callback for every maxTicks value.
        // A sequence keeps one repeating event for this open-ended assessment.
        h.startSequence().thenExecuteFor(1_000_000_000,()->{
            long tick=h.getTick();
            long noWorkTicks=watchdog.sample(tick,
                    table.builderWorkDone(),table.builderClearCursor());
            if(tick%MOTION_SAMPLE_TICKS==0){
                for(int i=0;i<crew.size();i++){
                    var worker=crew.get(i);
                    movement.get(i).sample(tick,worker.position(),
                            worker.isAlive()&&level.getEntity(worker.getUUID())==worker);
                }
            }
            boolean timeLimit=System.nanoTime()-assessmentStart>=300_000_000_000L;
            boolean noWorkTimeout=noWorkTicks>=NO_WORK_LIMIT;
            boolean report=tick%PROGRESS_PERIOD==0;
            var stalls=tick%MOTION_SAMPLE_TICKS==0
                    &&(report||noWorkTicks>=STALL_TICKS)
                    ?movement.stream().map(MatrixMovementWindow::stall).toList()
                    :List.<MatrixMovementWindow.Stall>of();
            boolean allStalled=noWorkTicks>=STALL_TICKS
                    &&stalls.size()==WORKERS
                    &&stalls.stream().allMatch(stall->stall!=MatrixMovementWindow.Stall.MOVING);
            if(tick%100==0){
                // 27 real chest slots cannot hold the entire machine palette.
                // Replenish relevant assignments only, without inserting items
                // directly into worker inventories or forcing any placements.
                keepScaffoldingStock(level,supplyPos);
                Set<Item> needs=baseStock();
                for(var worker:crew){
                    for(Integer n:table.builderWorkOrder(worker.getUUID())){
                        if(n!=null&&n>=0&&n<steps.size()&&needs.size()<19)
                            addItem(needs,steps.get(n));
                    }
                    int claim=table.claimedStep(worker.getUUID());
                    if(claim>=0&&claim<steps.size()&&needs.size()<19)
                        addItem(needs,steps.get(claim));
                }
                int activeStep=table.builderActiveStepIndex(steps);
                for(int i=Math.max(0,activeStep);
                        i<Math.min(steps.size(),activeStep+64)
                                &&needs.size()<19;i++)
                    addItem(needs,steps.get(i));
                BuilderInfiniteOnsiteSupply.restock(level,supplyPos,needs);
            }
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
            if(table.phase()==WorkerPhase.COMPLETE){
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
                System.out.println("[MATRIX FULL TIMED] seconds=300 tick="+tick
                        +" matchingSourceBlocks="+correct+"/"+SOURCE_BLOCKS
                        +" best="+best[0]+" layer="+(y-origin.getY()));
                h.succeed();return;
            }
            if(allStalled||noWorkTimeout){
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

    /** Test-only restock: four physically stored scaffold stacks, one per
     * concurrent Builder. The golems must still pick them up themselves.
     * Other material types are capped to the chest's remaining 23 slots. */
    private static void keepScaffoldingStock(ServerLevel level,BlockPos depot){
        if(!(level.getBlockEntity(depot) instanceof Container chest))return;
        for(int slot=0;slot<WORKERS;slot++)
            chest.setItem(slot,new ItemStack(Items.SCAFFOLDING,64));
        chest.setChanged();
    }

    private static Set<Item> baseStock(){
        Set<Item> needs=new LinkedHashSet<>();
        needs.add(Items.SCAFFOLDING);
        needs.add(Items.COBBLESTONE);
        needs.add(WorksiteContent.GRAPPLE_HOOK);
        needs.add(Items.BUCKET);
        needs.add(WorksiteContent.INFINITE_WATER_BUCKET);
        needs.add(Items.IRON_PICKAXE);
        needs.add(Items.IRON_AXE);
        needs.add(Items.IRON_SHOVEL);
        return needs;
    }
    private static void addItem(Set<Item> needs,BuilderConstructionPlan.Step step){
        if(step.requiredItem()==null)return;
        Identifier name=Identifier.tryParse(step.requiredItem());
        if(name==null)return;
        Item item=BuiltInRegistries.ITEM.getValue(name);
        if(item!=null&&item!=Items.AIR)needs.add(item);
        // Test-only stock for a VANILLA-POPPED rail on its later water pass.
        // Builders pay for and retrieve the replacement in-world; the
        // chest replenishment never silently inserts it in a worker pouch.
        if(BuilderWaterPass.isWaterlogPass(step.encodedState())){
            var dry=BuilderBlockResolver.resolve(
                    BuilderWaterPass.dryState(
                            BuilderWaterPass.originalState(step.encodedState())),
                    BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
            if(dry!=null&&BuilderRailSlope.raisedEnd(
                    BlockPos.ZERO,dry.state())!=null)
                needs.add(dry.item());
        }
    }
}
