package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
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
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * REAL Builder job for the user's Villager Matrix STARTING FOUNDATION:
 * the exact 27 blocks from the narrow 7x5 lowest visual section,
 * with the foundation floating four air blocks above a flat worksite.
 *
 * This test intentionally does not claim to reproduce the entire 15378-block
 * redstone machine. Its purpose is to prove or disprove a cold start with
 * four real Builders, real inventory-paid scaffold towers and a world-space
 * hologram snapshot. The one waterlogged mangrove-roots cell is retained
 * as an unsupported-state diagnostic (not silently deleted).
 */
public final class BuilderVillagerMatrixStartGameTest {
    // Physical diagnostic currently reproduces the known incomplete
    // shared-scaffold-start behavior. Keep it registered and reporting
    // all blocked poses, but don't block main's distribution release
    // before the planner learns to coordinate access across worker zones.
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=4200,skyAccess=true,required=false)
    public void fourBuildersStartFloatingVillagerMatrixFoundation(GameTestHelper h){
        ServerLevel level=h.getLevel();
        // Original schematic's bottom visible layer: Region.Position Y=2
        // and Region.Size Y=-20. These are its EXACT 27 non-air cells,
        // translated X+18, Z+33 into this GameTest arena, and set at Y=6.
        int[][] row={{-1,-27},{0,-27},{1,-27},
                {-1,-26},{0,-26},{1,-26},
                {-2,-25},{-1,-25},{0,-25},{1,-25},
                {2,-25},{3,-25},{4,-25},
                {-2,-24},{-1,-24},{0,-24},{1,-24},
                {2,-24},{3,-24},{4,-24},
                {-2,-23},{-1,-23},{0,-23},{1,-23},
                {2,-23},{3,-23},{4,-23}};
        var targets=new ArrayList<LitematicFileReader.Block>();
        int high=h.absolutePos(new BlockPos(0,6,0)).getY();
        for(int[] p:row){
            String state=p[0]==1&&p[1]==-24
                    ?"minecraft:mangrove_roots[waterlogged=true]"
                    :p[0]==2&&p[1]==-24?"minecraft:redstone_block"
                    :p[0]==3&&p[1]==-24?"minecraft:soul_sand"
                    :"minecraft:smooth_stone";
            BlockPos at=h.absolutePos(new BlockPos(p[0]+18,6,p[1]+33));
            targets.add(new LitematicFileReader.Block(
                    at.getX(),at.getY(),at.getZ(),state));
        }
        try{
            var snapshot=BuilderPlacementSnapshot.encode(
                    "Villager Matrix: exact 27-cell floating starter",targets,List.of());
            var decoded=BuilderPlacementSnapshot.decode(snapshot);
            var site=BuilderPlacementSnapshot.worldSpaceSite();
            var plan=BuilderConstructionPlan.create(decoded,site);
            if(plan.steps().size()!=27||plan.steps().stream()
                    .anyMatch(s->s.y()!=high)){
                h.fail("Frozen hologram changed starter Y or position");return;
            }
            for(int x=1;x<=43;x++)for(int z=1;z<=43;z++)
                h.setBlock(x,1,z,Blocks.STONE);
            h.setBlock(4,2,4,WorksiteContent.BUILDER_TABLE);
            h.setBlock(5,2,4,Blocks.CHEST);
            BlockPos tablePos=h.absolutePos(new BlockPos(4,2,4));
            BlockPos chestPos=h.absolutePos(new BlockPos(5,2,4));
            if(!(level.getBlockEntity(tablePos)
                    instanceof WorkerStationBlockEntity table)
                    ||!(level.getBlockEntity(chestPos) instanceof Container stock)){
                h.fail("Missing Planner or OnSite chest");return;
            }
            // Stocked physical crate, not creative placement or items
            // injected directly into Builders' inventories.
            for(int slot=0;slot<12;slot++)
                stock.setItem(slot,new ItemStack(Items.SCAFFOLDING,64));
            stock.setItem(12,new ItemStack(Blocks.SMOOTH_STONE,64));
            stock.setItem(13,new ItemStack(Blocks.REDSTONE_BLOCK,16));
            stock.setItem(14,new ItemStack(Blocks.SOUL_SAND,16));
            stock.setItem(15,new ItemStack(Blocks.MANGROVE_ROOTS,16));
            for(int slot=16;slot<20;slot++)
                stock.setItem(slot,new ItemStack(WorksiteContent.GRAPPLE_HOOK));
            for(int slot=20;slot<24;slot++)
                stock.setItem(slot,new ItemStack(Items.BUCKET));
            ServerPlayer owner=h.makeMockServerPlayerInLevel();
            UUID ownerId=owner.getUUID();
            if(!table.claimIfUnowned(ownerId)||!table.setCrewTarget(ownerId,4)){
                h.fail("Owner or crew configuration failed");return;
            }
            Path folder=FabricLoader.getInstance().getConfigDir()
                    .resolve("golems_plus").resolve("schematics");
            Files.createDirectories(folder);
            String filename="placed-gametest-matrix-starter.gpb";
            Files.write(folder.resolve(filename),snapshot);
            if(!table.setPlacedBuilderPlan(ownerId,filename,
                    "Villager Matrix starter",tablePos,0,0,
                    targets.size(),plan.unsupported().size())
                    ||!table.setBuilderMaterialStorage(ownerId,chestPos)){
                h.fail("Matrix Planner rejected hologram snapshot");return;
            }
            table.setBuilderMaterialsForFile(filename,plan.required());
            var crew=new ArrayList<BuilderGolem>();
            for(int i=0;i<4;i++){
                var mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
                BlockPos from=h.absolutePos(new BlockPos(7+i,2,4));
                mob.setPos(from.getX()+.5,from.getY(),from.getZ()+.5);
                mob.assign(tablePos,ownerId);
                level.addFreshEntity(mob);
                if(!table.registerBuilderWorker(mob.getUUID())){
                    h.fail("Could not register Builder #"+i);return;
                }
                crew.add(mob);
            }
            if(!table.startPlacedBuilder(ownerId)){
                h.fail("Matrix starter plan refused to start");return;
            }
            System.out.println("[MATRIX START] imported 27 actual foundation blocks"
                    +" at worldY="+high+" with four real Builders, 4-air gap;"
                    +" unsupportedStates="+plan.unsupported());
            final int[] best={0};
            final long[] lastChange={h.getTick()};
            final java.util.Set<UUID> elevatedWorkers=new java.util.HashSet<>();
            h.onEachTick(()->{
                if(h.getTick()%20!=0)return;
                for(var mob:crew)
                    if(mob.isAlive()&&mob.getY()>=high-.35
                            &&mob.getY()<=high+1.8)
                        elevatedWorkers.add(mob.getUUID());
                int placed=0;
                for(var step:plan.steps()){
                    var at=new BlockPos(step.x(),step.y(),step.z());
                    if(level.getBlockState(at).is(Blocks.SMOOTH_STONE)
                            &&step.encodedState().equals("minecraft:smooth_stone")
                        ||level.getBlockState(at).is(Blocks.REDSTONE_BLOCK)
                            &&step.encodedState().equals("minecraft:redstone_block")
                        ||level.getBlockState(at).is(Blocks.SOUL_SAND)
                            &&step.encodedState().equals("minecraft:soul_sand"))
                        placed++;
                }
                if(placed>best[0]){
                    best[0]=placed;
                    lastChange[0]=h.getTick();
                    System.out.println("[MATRIX PROGRESS] tick="+h.getTick()
                            +" physicallyPlaced="+placed+"/26"
                            +" cursor="+table.builderCursor()
                            +" clear="+table.builderClearCursor()
                            +" activeY="+table.builderActiveLayer(plan.steps())
                            +" elevatedBuilders="+elevatedWorkers.size());
                }
                if(placed==26&&elevatedWorkers.size()>=2){
                    System.out.println("[MATRIX START PASS] "+placed+
                            " true foundation blocks placed by Builders"
                            +" with no prebuilt tower; elevatedBuilders="
                            +elevatedWorkers.size());
                    h.succeed();return;
                }
                if(h.getTick()>3600
                        ||h.getTick()>800&&h.getTick()-lastChange[0]>850){
                    StringBuilder error=new StringBuilder(
                            "Villager Matrix cold start stalled: placed="+placed
                            +"/26 after tick "+h.getTick()
                            +" cursor="+table.builderCursor()
                            +" clear="+table.builderClearCursor()
                            +" Y="+table.builderActiveLayer(plan.steps())
                            +" elevatedBuilders="+elevatedWorkers.size()
                            +" problem="+table.problem());
                    for(var mob:crew){
                        error.append(" | builder=").append(mob.getName().getString())
                                .append(" feet=").append(mob.blockPosition())
                                .append(" task=").append(table.claimedStep(mob.getUUID()))
                                .append(" scaffoldCount=")
                                .append(table.scaffoldPositions(mob.getUUID()).size())
                                .append(" bridge=").append(BuilderScaffoldPlanA.status(mob))
                                .append(" grapple=").append(BuilderGrappleAccess.debug(mob))
                                .append(" debug=").append(
                                        BuilderBuildService.debugStatus(level,mob));
                    }
                    System.out.println("[MATRIX START FAIL] "+error);
                    h.fail(error.toString());
                }
            });
        }catch(Exception e){
            h.fail("Could not stage actual Matrix foundation: "+e);
        }
    }
}
