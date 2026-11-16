package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import com.caszgamermd.golemsplus.worker.WorkerPhase;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/** Actual assigned Builder, ordinary placement and supply, no synthetic completions. */
public final class BuilderNNStripPilotGameTest {
    @GameTest(structure="golems_plus_gametest:matrix_arena",maxTicks=12000,skyAccess=true)
    public void comparePhysicalStrip(GameTestHelper h){
        try{run(h);}catch(Exception e){h.fail("NN strip setup: "+e);}
    }
    private void run(GameTestHelper h)throws Exception{
        String axis=System.getenv().getOrDefault("NN_STRIP_AXIS","Y");
        if(!List.of("X","Y","Z").contains(axis)){h.fail("invalid axis");return;}
        var world=h.getLevel();
        if(world.getSeed()!=0){h.fail("expected fixed seed");return;}
        for(int x=1;x<=20;x++)for(int z=1;z<=20;z++){
            h.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=8;y++)h.setBlock(x,y,z,Blocks.AIR);
        }
        BlockPos planner=h.absolutePos(new BlockPos(2,2,2));
        BlockPos depot=h.absolutePos(new BlockPos(3,2,2));
        world.setBlock(planner,WorksiteContent.BUILDER_TABLE.defaultBlockState(),3);
        world.setBlock(depot,WorksiteContent.CREATIVE_CRATE.defaultBlockState(),3);
        var table=(WorkerStationBlockEntity)world.getBlockEntity(planner);
        var owner=h.makeMockServerPlayerInLevel();
        owner.setPos(planner.getX()+.5,planner.getY(),planner.getZ()+2.5);
        var id=owner.getUUID();
        var origin=h.absolutePos(new BlockPos(7,2,7));
        var blocks=new ArrayList<LitematicFileReader.Block>();
        var absolute=new ArrayList<LitematicFileReader.Block>();
        var targets=new ArrayList<BlockPos>();
        for(int y=0;y<3;y++)for(int x:new int[]{0,5})for(int z:new int[]{0,5}){
            blocks.add(new LitematicFileReader.Block(x,y,z,"minecraft:stone"));
            var at=origin.offset(x,y,z);targets.add(at);
            absolute.add(new LitematicFileReader.Block(at.getX(),at.getY(),at.getZ(),"minecraft:stone"));
        }
        var schematic=new LitematicFileReader.Schematic("NN strip pilot",blocks,Map.of("minecraft:stone",12L));
        var plan=BuilderConstructionPlan.create(schematic,new BuildSite(origin.getX(),origin.getY(),origin.getZ(),
                BuildSite.Rotation.NONE,BuildSite.Mirror.NONE));
        String name="placed-nn-strip-pilot-"+axis+".gpb";
        var dir=FabricLoader.getInstance().getConfigDir().resolve("golems_plus").resolve("schematics");
        Files.createDirectories(dir);
        Files.write(dir.resolve(name),BuilderPlacementSnapshot.encode(schematic.name(),absolute,List.of()));
        if(!table.claimIfUnowned(id)||!table.setCrewTarget(id,1)
                ||!table.setPlacedBuilderPlan(id,name,schematic.name(),origin,0,0,12,0)
                ||!table.setBuilderMaterialStorage(id,depot)){
            h.fail("planner refused fixture");return;
        }
        table.setBuilderMaterialsForFile(name,plan.required());
        var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        worker.setUUID(new UUID(0x4e4e53545249504cL,1));
        worker.setId(100001);
        worker.getRandom().setSeed(260215377L);
        world.getRandom().setSeed(260215376L);
        worker.setPos(planner.getX()+.5,planner.getY(),planner.getZ()+4.5);
        worker.assign(planner,id);
        world.addFreshEntity(worker);
        if(!table.registerBuilderWorker(worker.getUUID())||!table.startPlacedBuilder(id)
                ||(!axis.equals("Y")&&!table.enableNNStripPilot(axis))){
            h.fail("builder or pilot start refused");return;
        }
        // Assert the actual authoritative gate, not only preview ordering.
        int upper=-1,other=-1;
        for(int i=0;i<plan.steps().size();i++){
            var step=plan.steps().get(i);
            if(step.x()==origin.getX()&&step.z()==origin.getZ()&&step.y()==origin.getY()+1)upper=i;
            if(step.x()==origin.getX()+5&&step.z()==origin.getZ()+5&&step.y()==origin.getY())other=i;
        }
        if(upper<0||other<0||table.builderStepInActivePhase(plan.steps(),upper)
                ||table.builderStepInActivePhase(plan.steps(),other)!=axis.equals("Y")){
            h.fail("pilot gate not active");return;
        }
        final var previous=new net.minecraft.world.phys.Vec3[]{worker.position()};
        final double[] distance={0};
        final int[] best={0};
        final long[] progress={h.getTick()};
        final long start=h.getTick();
        h.onEachTick(()->{
            distance[0]+=worker.position().distanceTo(previous[0]);previous[0]=worker.position();
            int count=0;
            for(var at:targets)if(world.getBlockState(at).is(Blocks.STONE)){
                count++;
                if(at.getY()>origin.getY()&&!world.getBlockState(at.below()).is(Blocks.STONE)){
                    h.fail("unsupported placement "+at);return;
                }
                // Verify all lower levels in the active strip, or all earlier strips.
                for(var prior:targets){
                    int a=axis.equals("X")?at.getX():axis.equals("Z")?at.getZ():0;
                    int b=axis.equals("X")?prior.getX():axis.equals("Z")?prior.getZ():0;
                    if((b<a||b==a&&prior.getY()<at.getY())
                            &&!world.getBlockState(prior).is(Blocks.STONE)){
                        h.fail("physical ordering violation axis="+axis+" at="+at+" prior="+prior);return;
                    }
                }
            }
            if(count>best[0]){best[0]=count;progress[0]=h.getTick();}
            boolean done=table.phase()==WorkerPhase.COMPLETE;
            boolean stalled=h.getTick()-progress[0]>2400;
            if(done||stalled||!worker.isAlive()||h.getTick()-start>=11000){
                String result=done&&count==12&&worker.isAlive()?"COMPLETE":"FAILED";
                System.out.println("[NN STRIP RESULT] axis="+axis+" outcome="+result+" correct="+count
                        +" ticks="+(h.getTick()-start)+" distance="+distance[0]
                        +" problem="+table.problem());
                if(result.equals("COMPLETE"))h.succeed();
                else h.fail("physical strip failed: "+table.problem()+" "+BuilderBuildService.debugStatus(world,worker));
            }
        });
    }
}
