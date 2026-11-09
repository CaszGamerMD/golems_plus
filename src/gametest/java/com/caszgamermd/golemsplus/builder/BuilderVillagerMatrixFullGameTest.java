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

/** The full, ORIGINAL user-uploaded VillagerMatrixClean_V2 (15,376 blocks).
 * No shortcut: the physical test sends eight real Builder entities to an
 * empty, four-block-elevated job and counts only actual matching world blocks.
 * The test-only OnSite chest is replenished; no golem inventory is fabricated.
 * This long test is isolated from the quick mandatory GameTest suite.
 */
public final class BuilderVillagerMatrixFullGameTest {
    private static final int SOURCE_BLOCKS=15376;
    private static final int SOURCE_AIR=15004;
    private static final int WORKERS=8;
    private static final int PROGRESS_PERIOD=400;
    private record Expected(BlockPos pos,BlockState state) {}

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

    @SuppressWarnings("removal")
    @GameTest(structure="golems_plus_gametest:matrix_arena",
            maxTicks=140000,skyAccess=true,required=false)
    public void constructsEntireMatrixWithEightRealBuilders(GameTestHelper h){
        ServerLevel level=h.getLevel();
        final Path source;
        final LitematicFileReader.Schematic schematic;
        final BuilderConstructionPlan.Result plan;
        final BlockPos origin=h.absolutePos(new BlockPos(10,6,10));
        try{
            source=BuilderVillagerMatrixFullFixture.unpack();
            schematic=LitematicFileReader.read(source);
            if(schematic.blocks().size()!=SOURCE_BLOCKS
                    ||schematic.airBlocks().size()!=SOURCE_AIR)
                throw new IllegalStateException("Unexpected schematic block count");
            plan=BuilderConstructionPlan.create(schematic,
                    new BuildSite(origin.getX(),origin.getY(),origin.getZ(),
                            BuildSite.Rotation.NONE,BuildSite.Mirror.NONE),false);
        }catch(Exception e){h.fail("Cannot plan full Villager Matrix: "+e);return;}
        final List<BuilderConstructionPlan.Step> steps=plan.steps();
        final List<Expected> expected=new ArrayList<>(SOURCE_BLOCKS);
        int unresolved=0;
        for(var cell:schematic.blocks()){
            var resolved=BuilderBlockResolver.resolve(cell.state(),
                    BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
            if(resolved==null){unresolved++;continue;}
            expected.add(new Expected(origin.offset(cell.x(),cell.y(),cell.z()),
                    resolved.state()));
        }
        final int unavailableStates=unresolved;
        System.out.println("[MATRIX FULL TARGET] sourceBlocks="+SOURCE_BLOCKS
                +" dryPlusWaterSteps="+steps.size()+" unsupportedCells="
                +unavailableStates+" unsupportedStates="+plan.unsupported()
                +" 8 actual Builders; no scaffold or structure prebuilt");

        // Dedicated arena is larger than the 48x48 Dome room: the Matrix
        // is 49 blocks deep and requires external scaffold routes on all sides.
        for(int x=1;x<=62;x++)for(int z=1;z<=70;z++)
            h.setBlock(x,1,z,Blocks.STONE);
        h.setBlock(4,2,7,WorksiteContent.BUILDER_TABLE);
        h.setBlock(5,2,7,Blocks.CHEST);
        BlockPos plannerPos=h.absolutePos(new BlockPos(4,2,7));
        BlockPos supplyPos=h.absolutePos(new BlockPos(5,2,7));
        if(!(level.getBlockEntity(plannerPos) instanceof WorkerStationBlockEntity table)){
            h.fail("Missing full Matrix Planner");return;
        }
        ServerPlayer owner=h.makeMockServerPlayerInLevel();
        UUID ownerId=owner.getUUID();
        if(!table.claimIfUnowned(ownerId)||!table.setCrewTarget(ownerId,WORKERS)){
            h.fail("Cannot configure eight Matrix Builders");return;
        }
        Path folder=FabricLoader.getInstance().getConfigDir()
                .resolve("golems_plus").resolve("schematics");
        String name="placed-gametest-matrix-clean-v2.litematic";
        try{
            Files.createDirectories(folder);
            Files.copy(source,folder.resolve(name),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
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
        // One stack is not enough to kit eight workers at cold start.
        // Reserve eight actual OnSite chest slots for scaffolding.
        keepScaffoldingStock(level,supplyPos);
        BuilderInfiniteOnsiteSupply.restock(level,supplyPos,firstNeeds);
        var crew=new ArrayList<BuilderGolem>();
        for(int i=0;i<WORKERS;i++){
            BuilderGolem worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
            BlockPos from=h.absolutePos(new BlockPos(6+i%4,2,4+i/4));
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
        final int[] best={0},lastCursor={-1};
        final long[] lastProgress={h.getTick()};
        h.onEachTick(()->{
            long tick=h.getTick();
            if(tick%100!=0)return;
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
            for(int i=Math.max(0,table.builderCursor());
                    i<Math.min(steps.size(),table.builderCursor()+64)
                            &&needs.size()<19;i++)
                addItem(needs,steps.get(i));
            BuilderInfiniteOnsiteSupply.restock(level,supplyPos,needs);
            if(tick%PROGRESS_PERIOD!=0)return;
            int correct=0;
            for(Expected candidate:expected)
                if(BuilderPlacementState.matches(
                        level.getBlockState(candidate.pos()),candidate.state()))
                    correct++;
            int cursor=table.builderCursor();
            if(correct>best[0]||cursor>lastCursor[0]){
                best[0]=Math.max(best[0],correct);
                lastCursor[0]=Math.max(lastCursor[0],cursor);
                lastProgress[0]=tick;
            }
            int y=table.builderActiveLayer(steps);
            System.out.println("[MATRIX FULL PROGRESS] tick="+tick
                    +" matchingSourceBlocks="+correct+"/"+SOURCE_BLOCKS
                    +" best="+best[0]+" cursor="+cursor+"/"+steps.size()
                    +" currentLayer="+(y==Integer.MAX_VALUE?"complete":y-origin.getY())
                    +" idleTicks="+(tick-lastProgress[0])
                    +" crewAlive="+crew.stream().filter(BuilderGolem::isAlive).count()
                    +" problem="+table.problem());
            if(table.phase()==WorkerPhase.COMPLETE){
                if(correct!=SOURCE_BLOCKS){
                    h.fail("Full Matrix marked complete but "
                            +(SOURCE_BLOCKS-correct)+" source blocks differ; "
                            +unavailableStates+" unsupported cells");return;
                }
                System.out.println("[MATRIX FULL PASS] "+SOURCE_BLOCKS
                        +"/"+SOURCE_BLOCKS
                        +" physical source blocks verified with eight Builders");
                h.succeed();return;
            }
            if(tick-lastProgress[0]>3000){
                StringBuilder failure=new StringBuilder(
                        "[MATRIX FULL STALL] best="+best[0]+"/"+SOURCE_BLOCKS
                                +" cursor="+cursor+"/"+steps.size()
                                +" layer="+(y-origin.getY())
                                +" unsupported="+unavailableStates
                                +" problem="+table.problem());
                for(var mob:crew){
                    failure.append(" | builder=").append(mob.getName().getString())
                            .append(" feet=").append(mob.blockPosition())
                            .append(" step=").append(table.claimedStep(mob.getUUID()))
                            .append(" bridge=").append(BuilderScaffoldPlanA.status(mob))
                            .append(" grapple=").append(BuilderGrappleAccess.debug(mob))
                            .append(" ").append(BuilderBuildService.debugStatus(level,mob));
                }
                System.out.println(failure);
                h.fail(failure.toString());
            }
        });
    }

    /** Test-only restock: eight physically stored scaffold stacks, one per
     * concurrent Builder. The golems must still pick them up themselves.
     * Other material types are capped to the chest's remaining 19 slots. */
    private static void keepScaffoldingStock(ServerLevel level,BlockPos depot){
        if(!(level.getBlockEntity(depot) instanceof Container chest))return;
        for(int slot=0;slot<8;slot++)
            chest.setItem(slot,new ItemStack(Items.SCAFFOLDING,64));
        chest.setChanged();
    }

    private static Set<Item> baseStock(){
        Set<Item> needs=new LinkedHashSet<>();
        needs.add(Items.SCAFFOLDING);
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
    }
}
