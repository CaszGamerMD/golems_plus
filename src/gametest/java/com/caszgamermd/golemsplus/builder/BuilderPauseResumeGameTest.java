package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorksiteContent;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.worker.WorkerPhase;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.server.level.TicketType;

/** A physical paid build interrupted after placements and with real cargo.
 * No simulated block placement, teleport or forced completion. */
public final class BuilderPauseResumeGameTest {
    private static int stockCount(Container stock){
        int count=0;
        for(int slot=0;slot<stock.getContainerSize();slot++)
            if(stock.getItem(slot).is(Items.SMOOTH_STONE))count+=stock.getItem(slot).getCount();
        return count;
    }
    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=3000,skyAccess=true)
    public void flyingBuilderPausesUnloadsAndResumesSamePaidJob(GameTestHelper h){
        var level=h.getLevel();
        for(int x=2;x<=27;x++)for(int z=2;z<=25;z++){
            h.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=8;y++)h.setBlock(x,y,z,Blocks.AIR);
        }
        h.setBlock(4,2,4,WorksiteContent.BUILDER_TABLE);
        h.setBlock(5,2,4,Blocks.CHEST);
        BlockPos station=h.absolutePos(new BlockPos(4,2,4));
        BlockPos depot=h.absolutePos(new BlockPos(5,2,4));
        BlockPos start=h.absolutePos(new BlockPos(7,2,4));
        level.getChunkSource().addTicketWithRadius(TicketType.FORCED,new ChunkPos(start.getX()>>4,start.getZ()>>4),3);
        if(!(level.getBlockEntity(station) instanceof WorkerStationBlockEntity table)
                ||!(level.getBlockEntity(depot) instanceof Container stock)){
            h.fail("Pause fixture missing Planner or OnSite");return;
        }
        stock.setItem(0,new ItemStack(Items.SMOOTH_STONE,64));
        stock.setItem(1,new ItemStack(Items.SCAFFOLDING,64));
        stock.setItem(2,new ItemStack(WorksiteContent.GRAPPLE_HOOK));
        stock.setItem(3,new ItemStack(Items.BUCKET));
        var cells=new ArrayList<LitematicFileReader.Block>();
        var targets=new ArrayList<BlockPos>();
        for(int x=12;x<20;x++)for(int z=12;z<18;z++){
            BlockPos p=h.absolutePos(new BlockPos(x,2,z));
            targets.add(p);
            cells.add(new LitematicFileReader.Block(p.getX(),p.getY(),p.getZ(),"minecraft:smooth_stone"));
        }
        var id=h.makeMockServerPlayerInLevel().getUUID();
        try{
            var snapshot=BuilderPlacementSnapshot.encode("Physical pause",cells,List.of());
            var plan=BuilderConstructionPlan.create(BuilderPlacementSnapshot.decode(snapshot),BuilderPlacementSnapshot.worldSpaceSite());
            var folder=net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("golems_plus").resolve("schematics");
            Files.createDirectories(folder);
            String file="placed-pause-"+UUID.randomUUID()+".gpb";
            Files.write(folder.resolve(file),snapshot);
            if(!table.claimIfUnowned(id)||!table.setCrewTarget(id,1)
                    ||!table.setPlacedBuilderPlan(id,file,"Physical pause",station,0,0,plan.steps().size(),plan.unsupported().size())
                    ||!table.setBuilderMaterialStorage(id,depot)){
                h.fail("Pause fixture refused plan");return;
            }
            table.setBuilderMaterialsForFile(file,plan.required());
            var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
            worker.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
            worker.assign(station,id);level.addFreshEntity(worker);
            if(!table.registerBuilderWorker(worker.getUUID())||!table.startPlacedBuilder(id)){
                h.fail("Pause fixture refused start");return;
            }
            UUID job=table.activeOrder();
            int[] stage={0},pausedBuilt={0},parkTick={-1},pausedCursor={0};
            h.onEachTick(()->{
                int built=(int)targets.stream().filter(p->level.getBlockState(p).is(Blocks.SMOOTH_STONE)).count();
                int carried=worker.carriedCount(Items.SMOOTH_STONE);
                if(stage[0]==0&&built>=8&&built<48&&carried>0){
                    pausedBuilt[0]=built;pausedCursor[0]=table.builderCursor();
                    table.setPhase(WorkerPhase.PAUSED);stage[0]=1;
                    if(BuilderBuildService.canResumePausedBuild(level,table)){
                        h.fail("Resume accepted away-from-depot builder with cargo");return;
                    }
                }else if(stage[0]==1){
                    if(!job.equals(table.activeOrder())||table.phase()!=WorkerPhase.PAUSED
                            ||table.builderCursor()!=pausedCursor[0]||built!=pausedBuilt[0]){
                        h.fail("Paused construction changed physical progress or job");return;
                    }
                    if(BuilderBuildService.canResumePausedBuild(level,table)){
                        if(!worker.isEmpty()||worker.hasSupplyBox()||stockCount(stock)+built!=64){
                            h.fail("Pause lost or duplicated real cargo");return;
                        }
                        if(parkTick[0]<0)parkTick[0]=h.getTick();
                        if(h.getTick()-parkTick[0]>=40){
                            table.setPhase(WorkerPhase.WORKING);stage[0]=2;
                            if(!job.equals(table.activeOrder())){
                                h.fail("Resume replaced active job UUID");return;
                            }
                        }
                    }else parkTick[0]=-1;
                }else if(stage[0]==2&&built==48&&table.phase()==WorkerPhase.COMPLETE){
                    if(stockCount(stock)+carried+built!=64){
                        h.fail("Resume lost or duplicated real materials");return;
                    }
                    System.out.println("[PHYSICAL PAUSE RESUME PASS] pausedAt="+pausedBuilt[0]
                            +" finished="+built+" remaining="+(stockCount(stock)+carried)+" tick="+h.getTick());
                    h.succeed();return;
                }
                if(h.getTick()>=2900)h.fail("Pause/resume stalled stage="+stage[0]+" built="+built
                        +" cursor="+table.builderCursor()+" cargo="+carried+" worker="+worker.position()
                        +" parked="+BuilderBuildService.pausedCrewParked(level,table)+" problem="+table.problem());
            });
        }catch(Exception e){h.fail("Pause fixture setup failed: "+e);}
    }
}
