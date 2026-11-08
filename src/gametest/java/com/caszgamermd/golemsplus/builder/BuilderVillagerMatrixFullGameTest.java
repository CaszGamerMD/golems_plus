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
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Full VillagerMatrixClean_V2 (31x20x49, 15,376 non-air blocks).
 * Four REAL Builders start four air blocks below the elevated blueprint.
 * No instant placements, creative flying, or fake completion are allowed.
 * The current importer intentionally DOES NOT restore the 40 block-entity
 * NBT payloads; this test checks block-state construction, not inventories.
 */
public final class BuilderVillagerMatrixFullGameTest {
    private static final int EXPECTED_BLOCKS=15376;
    @GameTest(structure="golems_plus_gametest:matrix_full_arena",
            environment="golems_plus_gametest:matrix_full_e2e",
            maxTicks=200000,skyAccess=true)
    public void fourBuildersConstructEntireUpdatedMatrix(GameTestHelper helper){
        ServerLevel world=helper.getLevel();
        Path original;
        LitematicFileReader.Schematic schematic;
        BuilderConstructionPlan.Result plan;
        BlockPos origin=helper.absolutePos(new BlockPos(10,6,10));
        try{
            original=BuilderVillagerMatrixFullFixture.unpack();
            schematic=LitematicFileReader.read(original);
            if(!"VillagerMatrixClean_V2".equals(schematic.name())
                    ||schematic.blocks().size()!=EXPECTED_BLOCKS
                    ||schematic.airBlocks().size()!=
                            BuilderVillagerMatrixFullFixture.ORIGINAL_AIR)
                throw new IllegalStateException("Full Matrix count/name mismatch: "
                        +schematic.name()+" blocks="+schematic.blocks().size()
                        +" air="+schematic.airBlocks().size());
            int minX=schematic.blocks().stream().mapToInt(
                    LitematicFileReader.Block::x).min().orElseThrow();
            int maxX=schematic.blocks().stream().mapToInt(
                    LitematicFileReader.Block::x).max().orElseThrow();
            int minY=schematic.blocks().stream().mapToInt(
                    LitematicFileReader.Block::y).min().orElseThrow();
            int maxY=schematic.blocks().stream().mapToInt(
                    LitematicFileReader.Block::y).max().orElseThrow();
            int minZ=schematic.blocks().stream().mapToInt(
                    LitematicFileReader.Block::z).min().orElseThrow();
            int maxZ=schematic.blocks().stream().mapToInt(
                    LitematicFileReader.Block::z).max().orElseThrow();
            // The source REGION is 20 blocks high, but its highest
            // two Y slices are empty air. Non-air bounds stop at Y=17.
            // Verify BOTH the solid shape and the region's topmost AIR;
            // do not shift/alter the imported hologram to invent blocks.
            int topRegionY=schematic.airBlocks().stream()
                    .mapToInt(LitematicFileReader.Block::y).max()
                    .orElse(maxY);
            if(minX!=0||maxX!=30||minY!=0||maxY!=17
                    ||topRegionY!=19||minZ!=0||maxZ!=48)
                throw new IllegalStateException("Signed Matrix region changed bounds: "
                        +minX+","+minY+","+minZ+" to "+maxX+","+maxY+","+maxZ
                        +" lastRegionY="+topRegionY);
            plan=BuilderConstructionPlan.create(schematic,new BuildSite(
                    origin.getX(),origin.getY(),origin.getZ(),
                    BuildSite.Rotation.NONE,BuildSite.Mirror.NONE));
        }catch(Exception e){
            helper.fail("Cannot import exact full Villager Matrix "+e);
            return;
        }
        final var steps=plan.steps();
        System.out.println("[MATRIX FULL IMPORT] originalBlocks="+EXPECTED_BLOCKS
                +" air="+schematic.airBlocks().size()
                +" physicalSteps="+steps.size()
                +" unsupported="+plan.unsupported().size()
                +" sha256="+BuilderVillagerMatrixFullFixture.SHA256
                +" region=31x20x49 origin="+origin.toShortString()
                +" fourAirGap=true blockEntityPayloadsNotRestored=40");
        // This is a source-file geometry check, not a state repair.
        // An extended piston can only create its head in its facing
        // direction; a saved head BEHIND a base cannot be produced by
        // vanilla extension, even if a redstone circuit is energized.
        var pistonAudit=BuilderPistonAssemblyAudit.inspect(schematic);
        System.out.println("[MATRIX FULL PISTON AUDIT] extended="
                +pistonAudit.extended()+" forward="+pistonAudit.validForward()
                +" reverse="+pistonAudit.reversed()
                +" unmatched="+pistonAudit.unmatched()
                +" heads="+pistonAudit.totalHeads()
                +" examples="+pistonAudit.examples());
        // A real piston head/extended piston is a REDSTONE RESULT, not an
        // inventory item. Don't declare this full schematic buildable if
        // its states have not been taught to BuilderBlockResolver yet.
        if(!plan.unsupported().isEmpty()){
            // Report CELL counts, not just distinct palette strings.
            // A single "piston_head" variant may represent hundreds of
            // physical positions. Never classify these as finished work.
            java.util.Map<String,Integer> missingByBlock=new java.util.TreeMap<>();
            int affectedCells=0;
            java.util.List<String> examples=new java.util.ArrayList<>();
            for(var block:schematic.blocks()){
                if(!plan.unsupported().contains(block.state()))continue;
                affectedCells++;
                String id=block.state().split("\\[",2)[0];
                missingByBlock.merge(id,1,Integer::sum);
                if(examples.size()<12)
                    examples.add("("+block.x()+","+block.y()+","+block.z()
                            +") "+block.state());
            }
            System.out.println("[MATRIX FULL UNSUPPORTED] "+plan.unsupported());
            System.out.println("[MATRIX FULL UNSUPPORTED CELLS] affected="
                    +affectedCells+" categories="+missingByBlock
                    +" exampleXYZ="+examples);
            helper.fail("Full Matrix has "+plan.unsupported().size()
                    +" unsupported state variants across "+affectedCells
                    +" real cells; resolve power/water/ignition before "
                    +"claiming a complete physical build");
            return;
        }
        if(!pistonAudit.physicallyConsistent()){
            helper.fail("Full Matrix piston snapshot inconsistent: "
                    +pistonAudit.reversed()+" heads behind facing direction; "
                    +pistonAudit.unmatched()+" heads missing. "
                    +"Use the exact coordinates in [MATRIX FULL PISTON AUDIT]; "
                    +"do not flip block states, manufacture piston heads, "
                    +"or claim a physical completion");
            return;
        }
        // Isolate the entire 31x49 footprint and both sides of every
        // scaffold approach in a dedicated 72x48x72 GameTest arena.
        for(int y=2;y<43;y++)for(int x=0;x<72;x++)for(int z=0;z<72;z++){
            BlockPos p=helper.absolutePos(new BlockPos(x,y,z));
            if(!world.getBlockState(p).isAir())
                world.setBlock(p,Blocks.AIR.defaultBlockState(),3);
        }
        for(int x=1;x<71;x++)for(int z=1;z<71;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        helper.setBlock(4,2,6,WorksiteContent.BUILDER_TABLE);
        helper.setBlock(5,2,6,Blocks.CHEST);
        BlockPos plannerPos=helper.absolutePos(new BlockPos(4,2,6));
        BlockPos chestPos=helper.absolutePos(new BlockPos(5,2,6));
        if(!(world.getBlockEntity(plannerPos) instanceof WorkerStationBlockEntity table)){
            helper.fail("Full Matrix Planner block entity not initialized");return;
        }
        ServerPlayer owner=helper.makeMockServerPlayerInLevel();
        if(!table.claimIfUnowned(owner.getUUID())
                ||!table.setCrewTarget(owner.getUUID(),4)){
            helper.fail("Cannot claim full Matrix Planner / four-Builder crew");return;
        }
        String filename="placed-gametest-villager-matrix-clean-v2.litematic";
        try{
            Path folder=FabricLoader.getInstance().getConfigDir()
                    .resolve("golems_plus").resolve("schematics");
            Files.createDirectories(folder);
            Files.copy(original,folder.resolve(filename),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }catch(Exception e){
            helper.fail("Cannot stage exact full Matrix for production importer "+e);
            return;
        }
        if(!table.setPlacedBuilderPlan(owner.getUUID(),filename,
                    "VillagerMatrixClean_V2",origin,0,0,
                    steps.size(),plan.unsupported().size())
                ||!table.setBuilderMaterialStorage(owner.getUUID(),chestPos)){
            helper.fail("Planner rejected exact full Matrix placement");return;
        }
        table.setBuilderMaterialsForFile(filename,plan.required());
        List<BuilderGolem> crew=new ArrayList<>();
        for(int i=0;i<4;i++){
            BuilderGolem member=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
            BlockPos p=helper.absolutePos(new BlockPos(7+i,2,6));
            member.setPos(p.getX()+.5,p.getY(),p.getZ()+.5);
            member.assign(plannerPos,owner.getUUID());
            world.addFreshEntity(member);
            if(!table.registerBuilderWorker(member.getUUID())){
                helper.fail("Could not register full Matrix Builder "+i);return;
            }
            crew.add(member);
        }
        restock(world,table,chestPos,crew,steps);
        if(!table.startPlacedBuilder(owner.getUUID())){
            helper.fail("Planner refused to start the exact 15,376-block Matrix");
            return;
        }
        System.out.println("[MATRIX FULL START] actual non-air blocks="
                +EXPECTED_BLOCKS+" fourPhysicalBuilders=4");
        final long[] lastProgress={helper.getTick()};
        final int[] maxPlaced={0};
        helper.onEachTick(()->{
            long tick=helper.getTick();
            if(tick%20!=0)return;
            restock(world,table,chestPos,crew,steps);
            if(tick%200!=0)return;
            int correct=0;
            StringBuilder missing=new StringBuilder();
            int missingCount=0;
            for(var cell:schematic.blocks()){
                BlockPos at=origin.offset(cell.x(),cell.y(),cell.z());
                var expected=BuilderBlockResolver.resolve(cell.state(),
                        BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
                if(expected!=null&&BuilderPlacementState.matches(
                        world.getBlockState(at),expected.state()))correct++;
                else if(missingCount++<8){
                    missing.append(" [")
                            .append(at.subtract(origin).toShortString())
                            .append(" expected=").append(cell.state())
                            .append(" actual=").append(world.getBlockState(at))
                            .append("]");
                }
            }
            if(correct>maxPlaced[0]){
                maxPlaced[0]=correct;
                lastProgress[0]=tick;
            }
            System.out.println("[MATRIX FULL PROGRESS] tick="+tick
                    +" realBlocks="+correct+"/"+EXPECTED_BLOCKS
                    +" activeY="+table.builderActiveLayer(steps)
                    +" clear="+table.builderClearCursor()
                    +" claimCursor="+table.builderCursor()
                    +" fourAlive="+crew.stream().allMatch(BuilderGolem::isAlive)
                    +" problem="+table.problem());
            if(crew.stream().anyMatch(w->!w.isAlive())){
                helper.fail("Full Matrix Builder died; "+missing);return;
            }
            if(table.phase()==WorkerPhase.COMPLETE){
                if(correct!=EXPECTED_BLOCKS){
                    helper.fail("Full Matrix falsely completed at "+correct
                            +"/"+EXPECTED_BLOCKS+"; "+missing);return;
                }
                boolean grounded=crew.stream().allMatch(w->w.onGround()
                        &&!w.isNoGravity());
                boolean recovered=crew.stream().allMatch(w->
                        table.scaffoldPositions(w.getUUID()).isEmpty());
                if(grounded&&recovered){
                    System.out.println("[MATRIX FULL PASS] "+correct
                            +"/"+EXPECTED_BLOCKS
                            +" states physically correct, all crew grounded, "
                            +"all paid scaffolds recovered");
                    helper.succeed();
                }
            }else if(tick-lastProgress[0]>6000||tick>190000){
                StringBuilder report=new StringBuilder(
                        "[MATRIX FULL STALL] "+correct+"/"+EXPECTED_BLOCKS
                        +" tick="+tick+" firstMissing="+missing
                        +" problem="+table.problem());
                for(BuilderGolem worker:crew)
                    report.append(" | ").append(worker.getName().getString())
                            .append(" ").append(worker.blockPosition())
                            .append(" claim=").append(table.claimedStep(worker.getUUID()))
                            .append(" scaffold=").append(BuilderScaffoldPlanA.status(worker))
                            .append(" grapple=").append(BuilderGrappleAccess.debug(worker))
                            .append(" state=").append(
                                    BuilderBuildService.debugStatus(world,worker));
                System.out.println(report);
                helper.fail(report.toString());
            }
        });
    }
    /** GameTest-only supply. Never inject items into Builder inventories.
     * Active claimed materials go ahead of lookahead palette items so a
     * 27-slot REAL OnSite chest can serve hundreds of distinct materials.
     */
    private static void restock(ServerLevel world,WorkerStationBlockEntity table,
            BlockPos depot,List<BuilderGolem> crew,
            List<BuilderConstructionPlan.Step> steps){
        Set<Item> wanted=new LinkedHashSet<>();
        wanted.add(WorksiteContent.GRAPPLE_HOOK);
        wanted.add(Items.SCAFFOLDING);
        wanted.add(Items.BUCKET);
        wanted.add(Items.IRON_PICKAXE);
        wanted.add(Items.IRON_AXE);
        wanted.add(Items.IRON_SHOVEL);
        wanted.add(WorksiteContent.INFINITE_WATER_BUCKET);
        wanted.add(WorksiteContent.INFINITE_LAVA_BUCKET);
        String demand=table.problem();
        if(demand.startsWith("Builder needs ")){
            String key=demand.substring("Builder needs ".length()).split(" ",2)[0];
            var id=Identifier.tryParse(key);
            if(id!=null&&BuiltInRegistries.ITEM.containsKey(id))
                wanted.add(BuiltInRegistries.ITEM.getValue(id));
        }
        for(BuilderGolem worker:crew){
            int claim=table.claimedStep(worker.getUUID());
            if(claim>=0&&claim<steps.size())add(wanted,steps.get(claim));
        }
        for(BuilderGolem worker:crew)
            for(Integer step:table.builderWorkOrder(worker.getUUID())){
                if(wanted.size()>=25)break;
                if(step!=null&&step>=0&&step<steps.size())add(wanted,steps.get(step));
            }
        for(int i=Math.max(0,table.builderCursor());
                i<Math.min(steps.size(),table.builderCursor()+128)
                    &&wanted.size()<25;i++)
            add(wanted,steps.get(i));
        BuilderInfiniteOnsiteSupply.restock(world,depot,wanted);
    }
    private static void add(Set<Item> wanted,BuilderConstructionPlan.Step step){
        if(step.requiredItem()==null)return;
        var id=Identifier.tryParse(step.requiredItem());
        if(id!=null&&BuiltInRegistries.ITEM.containsKey(id))
            wanted.add(BuiltInRegistries.ITEM.getValue(id));
    }
}
