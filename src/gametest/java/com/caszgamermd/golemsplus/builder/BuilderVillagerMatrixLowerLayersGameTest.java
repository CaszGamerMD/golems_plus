package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * PHYSICAL diagnostic, NOT a substitute for the required 15,376-cell test.
 * Load the original full, checksum-verified user schematic through its
 * production importer; apply the existing player-accessible height rule to
 * construct only the 107 real blocks in source Y=0..3, four blocks above
 * ground. No synthetic steps and no creative block placement.
 *
 * This proves a meaningful real Matrix worksite can proceed while the
 * separate full test correctly rejects invalid piston-head geometry.
 */
public final class BuilderVillagerMatrixLowerLayersGameTest {
    @GameTest(structure="golems_plus_gametest:matrix_full_arena",
            environment="golems_plus_gametest:matrix_full_e2e",
            maxTicks=70000,skyAccess=true)
    public void fourBuildersConstructOriginalMatrixLowerFourLayers(GameTestHelper h){
        ServerLevel level=h.getLevel();
        Path source;
        LitematicFileReader.Schematic schematic;
        BuilderConstructionPlan.Result fullPlan;
        final BlockPos origin=h.absolutePos(new BlockPos(10,6,10));
        try{
            source=BuilderVillagerMatrixFullFixture.unpack();
            schematic=LitematicFileReader.read(source);
            fullPlan=BuilderConstructionPlan.create(schematic,new BuildSite(
                    origin.getX(),origin.getY(),origin.getZ(),
                    BuildSite.Rotation.NONE,BuildSite.Mirror.NONE));
            int lower=0;
            for(var block:schematic.blocks())if(block.y()>=0&&block.y()<=3){
                lower++;
                if(BuilderBlockResolver.resolve(block.state(),
                        BuildSite.Rotation.NONE,BuildSite.Mirror.NONE)==null)
                    throw new IllegalStateException("Unbuildable lower Matrix cell "
                            +block.x()+","+block.y()+","+block.z()+" "+block.state());
            }
            if(lower!=107)
                throw new IllegalStateException("Expected 107 actual lower cells, got "+lower);
        }catch(Exception exception){
            h.fail("Could not import original full Matrix lower layers: "+exception);
            return;
        }
        // Reset ONLY this disposable empty GameTest world.
        for(int y=2;y<43;y++)for(int x=0;x<72;x++)for(int z=0;z<72;z++){
            BlockPos pos=h.absolutePos(new BlockPos(x,y,z));
            if(!level.getBlockState(pos).isAir())
                level.setBlock(pos,Blocks.AIR.defaultBlockState(),3);
        }
        for(int x=1;x<71;x++)for(int z=1;z<71;z++)
            h.setBlock(x,1,z,Blocks.STONE);
        h.setBlock(4,2,6,WorksiteContent.BUILDER_TABLE);
        h.setBlock(5,2,6,Blocks.CHEST);
        BlockPos planner=h.absolutePos(new BlockPos(4,2,6));
        BlockPos depot=h.absolutePos(new BlockPos(5,2,6));
        if(!(level.getBlockEntity(planner) instanceof WorkerStationBlockEntity table)){
            h.fail("Lower Matrix Planner failed initialization");
            return;
        }
        ServerPlayer owner=h.makeMockServerPlayerInLevel();
        if(!table.claimIfUnowned(owner.getUUID())
                ||!table.setCrewTarget(owner.getUUID(),4)){
            h.fail("Cannot claim four-Builder Matrix worksite");
            return;
        }
        String name="placed-gametest-villager-matrix-low-four-v2.litematic";
        try{
            Path folder=FabricLoader.getInstance().getConfigDir()
                    .resolve("golems_plus").resolve("schematics");
            Files.createDirectories(folder);
            Files.copy(source,folder.resolve(name),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }catch(Exception exception){
            h.fail("Cannot stage full original Matrix for lower-layer test "+exception);
            return;
        }
        if(!table.setPlacedBuilderPlan(owner.getUUID(),name,
                    "VillagerMatrixClean_V2 lower-four physical diagnostic",
                    origin,0,0,fullPlan.steps().size(),
                    fullPlan.unsupported().size())
                ||!table.setBuilderMaterialStorage(owner.getUUID(),depot)
                ||!table.setBuilderHeightMode(owner.getUUID(),
                    BuilderMaterialRules.FROM_Y_DOWN)
                ||!table.setBuilderStartY(owner.getUUID(),origin.getY()+3)){
            h.fail("Could not configure original Matrix lower-four Y range");
            return;
        }
        table.setBuilderMaterialsForFile(name,fullPlan.required());
        List<BuilderGolem> crew=new ArrayList<>();
        for(int i=0;i<4;i++){
            var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
            BlockPos start=h.absolutePos(new BlockPos(7+i,2,6));
            worker.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
            worker.assign(planner,owner.getUUID());
            level.addFreshEntity(worker);
            if(!table.registerBuilderWorker(worker.getUUID())){
                h.fail("Could not register lower Matrix worker "+i);return;
            }
            crew.add(worker);
        }
        // The lower four Y layers require fewer than 27 distinct items.
        // Supply ONLY a REAL chest: workers must collect, carry and spend.
        Set<Item> material=new LinkedHashSet<>();
        material.add(WorksiteContent.GRAPPLE_HOOK);
        material.add(Items.SCAFFOLDING);
        material.add(Items.BUCKET);
        material.add(Items.IRON_PICKAXE);
        material.add(Items.IRON_AXE);
        material.add(Items.IRON_SHOVEL);
        material.add(WorksiteContent.INFINITE_WATER_BUCKET);
        for(var cell:schematic.blocks()){
            if(cell.y()>3)continue;
            var resolved=BuilderBlockResolver.resolve(cell.state(),
                    BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
            if(resolved!=null)material.add(resolved.item());
        }
        BuilderInfiniteOnsiteSupply.restock(level,depot,material);
        if(!table.startPlacedBuilder(owner.getUUID())){
            h.fail("Could not start physical lower-layer Matrix job");return;
        }
        System.out.println("[MATRIX LOWER START] 107 original non-air cells"
                +" Y=0..3, source=full VillagerMatrixClean_V2, four real Builders");
        final int[] lastCount={0},lastClear={0};
        final long[] lastProgress={h.getTick()};
        h.onEachTick(()->{
            long tick=h.getTick();
            if(tick%20!=0)return;
            BuilderInfiniteOnsiteSupply.restock(level,depot,material);
            if(tick%200!=0)return;
            int correct=0;
            StringBuilder firstMissing=new StringBuilder();
            for(var cell:schematic.blocks()){
                if(cell.y()>3)continue;
                BlockPos pos=origin.offset(cell.x(),cell.y(),cell.z());
                var state=BuilderBlockResolver.resolve(cell.state(),
                        BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
                if(state!=null&&BuilderPlacementState.matches(
                        level.getBlockState(pos),state.state()))correct++;
                else if(firstMissing.length()<340)
                    firstMissing.append(" ").append(
                            pos.subtract(origin).toShortString())
                            .append(" ").append(cell.state());
            }
            if(correct>lastCount[0]
                    ||table.builderClearCursor()!=lastClear[0]){
                lastCount[0]=Math.max(correct,lastCount[0]);
                lastClear[0]=table.builderClearCursor();
                lastProgress[0]=tick;
            }
            if(tick%400==0)
                System.out.println("[MATRIX LOWER PROGRESS] tick="+tick
                        +" correctlyPlaced="+correct+"/107"
                        +" clear="+table.builderClearCursor()
                        +" activeY="+table.builderActiveLayer(fullPlan.steps())
                        +" fourAlive="+crew.stream().allMatch(BuilderGolem::isAlive)
                        +" problem="+table.problem());
            if(correct==107&&crew.stream().allMatch(BuilderGolem::isAlive)){
                System.out.println("[MATRIX LOWER PASS] 107/107 original Matrix"
                        +" source cells physically correct, four real Builders alive");
                h.succeed();return;
            }
            if(crew.stream().anyMatch(g->!g.isAlive())){
                h.fail("Lower Matrix worker died; firstMissing="+firstMissing);return;
            }
            if(tick-lastProgress[0]>4200||tick>64000){
                StringBuilder failed=new StringBuilder(
                        "[MATRIX LOWER STALL] tick="+tick
                        +" correctlyPlaced="+correct+"/107"
                        +" clear="+table.builderClearCursor()
                        +" firstMissing="+firstMissing
                        +" problem="+table.problem());
                for(var worker:crew)
                    failed.append(" | ").append(worker.getName().getString())
                            .append(" ").append(worker.blockPosition())
                            .append(" claimed=").append(
                                    table.claimedStep(worker.getUUID()))
                            .append(" scaffold=").append(
                                    BuilderScaffoldPlanA.status(worker))
                            .append(" movement=").append(
                                    BuilderBuildService.debugStatus(level,worker));
                System.out.println(failed);
                h.fail(failed.toString());
            }
        });
    }
}
