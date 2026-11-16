package com.caszgamermd.golemsplus.builder;
import java.nio.file.Files;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import com.caszgamermd.golemsplus.worker.WorkerPhase;

/** Real job start, no manual axis override. Two rotated open-wall fixtures. */
public final class BuilderNNAutoStrategyGameTest {
    @GameTest(structure="golems_plus_gametest:matrix_arena",maxTicks=100000,skyAccess=true)
    public void autoWall(GameTestHelper h){
        try{run(h);}catch(Exception ex){h.fail("NN auto setup: "+ex);}
    }
    private void run(GameTestHelper h)throws Exception{
        String expected=System.getenv().getOrDefault("NN_AUTO_FIXTURE","X");
        if(!List.of("X","Z").contains(expected)){h.fail("invalid auto fixture");return;}
        boolean rotate=expected.equals("Z");
        var world=h.getLevel();
        BlockPos arena=new BlockPos(1024,-57,1024),origin=arena.offset(10,2,10);
        BlockPos depot=origin.offset(rotate?5:-1,0,rotate?-1:-5);
        BlockPos planner=depot.offset(rotate?1:0,0,rotate?0:-1);
        world.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.FORCED,
                new net.minecraft.world.level.ChunkPos((arena.getX()+25)>>4,(arena.getZ()+25)>>4),4);
        for(int x=0;x<52;x++)for(int z=0;z<52;z++){
            world.getChunk((arena.getX()+x)>>4,(arena.getZ()+z)>>4);
            world.setBlock(arena.offset(x,1,z),Blocks.STONE.defaultBlockState(),2);
            for(int y=2;y<35;y++){
                var p=arena.offset(x,y,z);
                if(!world.getBlockState(p).isAir())world.setBlock(p,Blocks.AIR.defaultBlockState(),2);
            }
        }
        world.setBlock(planner,WorksiteContent.BUILDER_TABLE.defaultBlockState(),3);
        world.setBlock(depot,WorksiteContent.CREATIVE_CRATE.defaultBlockState(),3);
        var table=(WorkerStationBlockEntity)world.getBlockEntity(planner);
        var owner=h.makeMockServerPlayerInLevel();var id=owner.getUUID();
        owner.setPos(planner.getX()+.5,planner.getY(),planner.getZ()+2.5);
        var cells=new ArrayList<LitematicFileReader.Block>();var targets=new ArrayList<BlockPos>();
        for(int y=0;y<30;y++)for(int x=0;x<32;x++)if(y<6||x==0||x==31){
            var at=origin.offset(rotate?0:x,y,rotate?x:0);targets.add(at);
            cells.add(new LitematicFileReader.Block(at.getX(),at.getY(),at.getZ(),"minecraft:stone"));
        }
        var schematic=new LitematicFileReader.Schematic("NN auto open wall",cells,Map.of("minecraft:stone",(long)cells.size()));
        var plan=BuilderConstructionPlan.create(schematic,BuilderPlacementSnapshot.worldSpaceSite());
        String file="placed-nn-auto-wall-"+expected+".gpb";
        var dir=FabricLoader.getInstance().getConfigDir().resolve("golems_plus").resolve("schematics");
        Files.createDirectories(dir);Files.write(dir.resolve(file),BuilderPlacementSnapshot.encode(schematic.name(),cells,List.of()));
        if(!table.claimIfUnowned(id)||!table.setCrewTarget(id,1)
                ||!table.setPlacedBuilderPlan(id,file,schematic.name(),origin,0,0,cells.size(),0)
                ||!table.setBuilderMaterialStorage(id,depot)){h.fail("planner refused");return;}
        table.setBuilderMaterialsForFile(file,plan.required());
        var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        worker.setUUID(new UUID(0x4e4e4155544f574cL,1));worker.setId(100001);
        worker.getRandom().setSeed(260215377L);world.getRandom().setSeed(260215376L);
        worker.setPos(planner.getX()+.5,planner.getY(),planner.getZ()+2.5);
        worker.assign(planner,id);world.addFreshEntity(worker);
        if(!table.registerBuilderWorker(worker.getUUID())||!table.startPlacedBuilder(id)){h.fail("start refused");return;}
        final long start=h.getTick();final int[] best={0};final long[] progress={start};final boolean[] chosen={false};
        h.onEachTick(()->{
            long tick=h.getTick();
            if(expected.equals(table.builderNNStrategy()))chosen[0]=true;
            if(tick-start>200&&!chosen[0]){h.fail("auto selector never chose "+expected);return;}
            if(tick%40!=0&&table.phase()!=WorkerPhase.COMPLETE)return;
            int correct=0;for(var at:targets)if(world.getBlockState(at).is(Blocks.STONE))correct++;
            if(correct>best[0]){best[0]=correct;progress[0]=tick;}
            if(table.phase()==WorkerPhase.COMPLETE||tick-start>=72000||tick-progress[0]>4000){
                String outcome=table.phase()==WorkerPhase.COMPLETE&&correct==targets.size()?"COMPLETE":"INCOMPLETE";
                System.out.println("[NN AUTO WALL RESULT] expected="+expected+" chosen="+chosen[0]+" final="+table.builderNNStrategy()
                        +" outcome="+outcome+" correct="+correct+" total="+targets.size()+" ticks="+(tick-start)+" problem="+table.problem());
                if(outcome.equals("COMPLETE")&&chosen[0])h.succeed();else h.fail("auto wall: "+BuilderBuildService.debugStatus(world,worker));
            }
        });
    }
}
