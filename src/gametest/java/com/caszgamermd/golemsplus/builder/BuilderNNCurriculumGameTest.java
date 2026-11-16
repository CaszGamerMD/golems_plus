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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Real separate-server courses; conventional controllers, observation only. */
public final class BuilderNNCurriculumGameTest {
    private record Expected(BlockPos pos,BlockState state){}
    @SuppressWarnings("removal")
    @GameTest(structure="golems_plus_gametest:matrix_arena",maxTicks=1_000_000_000,
            skyAccess=true,required=false)
    public void userBuildCourse(GameTestHelper h){
        try{
            run(h);
        }catch(Exception e){h.fail("Curriculum setup failed: "+e);e.printStackTrace();}
    }
    private void run(GameTestHelper h)throws Exception {
        var course=BuilderNNCurriculumFixture.selected();
        var schematic=BuilderNNCurriculumFixture.read(course);
        var all=new ArrayList<>(schematic.blocks());
        all.addAll(schematic.airBlocks());
        int minX=all.stream().mapToInt(LitematicFileReader.Block::x).min().orElseThrow();
        int minY=all.stream().mapToInt(LitematicFileReader.Block::y).min().orElseThrow();
        int minZ=all.stream().mapToInt(LitematicFileReader.Block::z).min().orElseThrow();
        int maxX=all.stream().mapToInt(LitematicFileReader.Block::x).max().orElseThrow();
        int maxY=all.stream().mapToInt(LitematicFileReader.Block::y).max().orElseThrow();
        int maxZ=all.stream().mapToInt(LitematicFileReader.Block::z).max().orElseThrow();
        System.out.println("[NN_CURRICULUM_IMPORT] course="+course.id()+" blocks="+course.blocks()
                +" air="+course.air()+" hash="+course.hash());
        if(!course.physical()){
            var plan=BuilderConstructionPlan.create(schematic,new BuildSite(0,0,0,
                    BuildSite.Rotation.NONE,BuildSite.Mirror.NONE),false);
            System.out.println("[NN_CURRICULUM_RESULT] course="+course.id()
                    +" outcome=IMPORT_ONLY total="+course.blocks()+" steps="+plan.steps().size()
                    +" unsupportedStates="+plan.unsupported());
            h.succeed();return;
        }
        ServerLevel level=h.getLevel();
        if(level.getSeed()!=0){h.fail("Curriculum seed is not zero");return;}
        final BlockPos arena=new BlockPos(1024,-57,1024);
        final BlockPos origin=arena.offset(10-minX,2-minY,10-minZ);
        int width=maxX-minX+1,height=maxY-minY+1,depth=maxZ-minZ+1;
        if(width>45||height>30||depth>50){h.fail("Course exceeds small arena");return;}
        BlockPos center=arena.offset(32,2,36);
        level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.FORCED,
                new net.minecraft.world.level.ChunkPos(center.getX()>>4,center.getZ()>>4),4);
        for(int cx=(arena.getX()>>4)-1;cx<=((arena.getX()+63)>>4)+1;cx++)
            for(int cz=(arena.getZ()>>4)-1;cz<=((arena.getZ()+71)>>4)+1;cz++)
                level.getChunk(cx,cz);
        for(int x=1;x<=62;x++)for(int z=1;z<=70;z++){
            level.setBlock(arena.offset(x,1,z),Blocks.STONE.defaultBlockState(),2);
            for(int y=2;y<=Math.max(27,height+4);y++){
                BlockPos p=arena.offset(x,y,z);
                if(!level.getBlockState(p).isAir())level.setBlock(p,Blocks.AIR.defaultBlockState(),2);
            }
        }
        int baseY=schematic.blocks().stream().mapToInt(LitematicFileReader.Block::y).min().orElseThrow();
        var first=schematic.blocks().stream().filter(b->b.y()==baseY)
                .min(java.util.Comparator.comparingInt(LitematicFileReader.Block::x)).orElseThrow();
        BlockPos supply=arena.offset(8,2,10+first.z()-minZ);
        BlockPos planner=supply.west();
        level.setBlock(planner,WorksiteContent.BUILDER_TABLE.defaultBlockState(),3);
        level.setBlock(supply,WorksiteContent.CREATIVE_CRATE.defaultBlockState(),3);
        if(!(level.getBlockEntity(planner) instanceof WorkerStationBlockEntity table)){
            h.fail("Missing curriculum Planner");return;
        }
        var owner=h.makeMockServerPlayerInLevel();
        owner.setPos(planner.getX()+.5,planner.getY(),planner.getZ()+2.5);
        UUID ownerId=owner.getUUID();
        var plan=BuilderConstructionPlan.create(schematic,new BuildSite(origin.getX(),
                origin.getY(),origin.getZ(),BuildSite.Rotation.NONE,BuildSite.Mirror.NONE),false);
        var expected=new ArrayList<Expected>();
        for(var b:schematic.blocks()){
            var state=BuilderBlockResolver.expectedState(b.state(),BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
            if(state!=null)expected.add(new Expected(origin.offset(b.x(),b.y(),b.z()),state));
        }
        int unsupported=course.blocks()-expected.size();
        var steps=plan.steps();
        if(!table.claimIfUnowned(ownerId)||!table.setCrewTarget(ownerId,4)){
            h.fail("Cannot configure curriculum crew");return;
        }
        Path folder=FabricLoader.getInstance().getConfigDir().resolve("golems_plus").resolve("schematics");
        Files.createDirectories(folder);
        String name="placed-nn-"+course.id()+".gpb";
        Files.write(folder.resolve(name),BuilderPlacementSnapshot.encode(schematic.name(),
                absolute(schematic.blocks(),origin),absolute(schematic.airBlocks(),origin)));
        if(!table.setPlacedBuilderPlan(ownerId,name,schematic.name(),origin,0,0,
                steps.size(),plan.unsupported().size())||!table.setBuilderMaterialStorage(ownerId,supply)){
            h.fail("Planner refused curriculum job");return;
        }
        table.setBuilderMaterialsForFile(name,plan.required());
        if(!(level.getBlockEntity(supply) instanceof CreativeCrateBlockEntity)
                ||!BuilderSupplyInventory.creativeSupply(table)){
            h.fail("Missing real Creative Crate");return;
        }
        var crew=new ArrayList<BuilderGolem>();
        for(int i=0;i<4;i++){
            var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
            BlockPos from=planner.offset(-2+i%2,0,-2+i/2);
            worker.setUUID(new UUID(0x4e4e434f55525345L,i+1));
            worker.setId(100000+i);
            worker.getRandom().setSeed(260215376L+i+1);
            worker.setPos(from.getX()+.5,from.getY(),from.getZ()+.5);
            worker.assign(planner,ownerId);
            level.addFreshEntity(worker);
            if(!table.registerBuilderWorker(worker.getUUID())){h.fail("Cannot register builder");return;}
            crew.add(worker);
        }
        level.getRandom().setSeed(260215376L);
        System.out.println("[NN_CURRICULUM_ENV] course="+course.id()+" seed="+level.getSeed()
                +" origin="+origin.toShortString().replace(" ","")+" workers=4 supply=creative_crate"
                +" scaffolding="+BuilderGolem.temporaryScaffoldingEnabled()
                +" unsupportedStates="+plan.unsupported());
        if(!table.startPlacedBuilder(ownerId)){h.fail("Cannot start curriculum job");return;}
        final long start=System.nanoTime();
        final long limit=Long.parseLong(System.getenv().getOrDefault("NN_COURSE_SECONDS","300"));
        if(limit<1||limit>1800){h.fail("Invalid course seconds");return;}
        var watchdog=new MatrixWorkWatchdog(h.getTick(),table.builderWorkDone(),table.builderClearCursor());
        final int[] best={0};
        h.startSequence().thenExecuteFor(1_000_000_000,()->{
            long tick=h.getTick();
            long elapsed=(System.nanoTime()-start)/1_000_000L;
            long noWork=watchdog.sample(tick,table.builderWorkDone(),table.builderClearCursor());
            boolean complete=table.phase()==WorkerPhase.COMPLETE;
            boolean timeout=elapsed>=limit*1000L;
            boolean stalled=noWork>=1200&&!BuilderCrewRecovery.assessmentGrace(level,table);
            if(tick%400!=0&&!complete&&!timeout&&!stalled)return;
            int correct=0;
            for(var b:expected)
                if(BuilderPistonWork.sourceMatches(level.getBlockState(b.pos()),b.state()))correct++;
            best[0]=Math.max(best[0],correct);
            int y=table.builderActiveLayer(steps);
            System.out.println("[NN_CURRICULUM_PROGRESS] course="+course.id()+" correct="+correct
                    +" total="+course.blocks()+" layer="+(y==Integer.MAX_VALUE?"complete":y-origin.getY())
                    +" tick="+tick+" problem="+table.problem());
            if(!complete&&!timeout&&!stalled)return;
            String outcome=complete?(correct==course.blocks()?"COMPLETE":"MISMATCH"):
                    stalled?"NO_WORK_PROGRESS":"TIME_LIMIT";
            BuilderTrace.dump(level,table,"curriculum "+outcome);
            System.out.println("[NN_CURRICULUM_RESULT] course="+course.id()+" outcome="+outcome
                    +" correct="+correct+" best="+best[0]+" total="+course.blocks()
                    +" elapsedMs="+elapsed+" tick="+tick+" unsupported="+unsupported);
            h.succeed(); // Assessment completed, not necessarily the build.
        });
    }
    private static List<LitematicFileReader.Block> absolute(List<LitematicFileReader.Block> cells,BlockPos origin){
        return cells.stream().map(b->new LitematicFileReader.Block(origin.getX()+b.x(),
                origin.getY()+b.y(),origin.getZ()+b.z(),b.state())).toList();
    }
}
