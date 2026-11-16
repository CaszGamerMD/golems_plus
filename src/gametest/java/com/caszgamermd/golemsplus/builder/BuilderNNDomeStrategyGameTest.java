package com.caszgamermd.golemsplus.builder;

import java.nio.file.*;
import java.util.*;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import com.caszgamermd.golemsplus.worker.WorkerPhase;

/** Same original Dome and supply geometry for every isolated trial.
 * Count source cells, not logical task claims or ignored vegetation. */
public final class BuilderNNDomeStrategyGameTest {
    private record Expected(BlockPos pos,BlockState state){}
    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=1000000000,skyAccess=true,required=false)
    public void domeStrategy(GameTestHelper h){
        try{run(h);}catch(Exception e){h.fail("Dome strategy setup: "+e);e.printStackTrace();}
    }
    private static List<LitematicFileReader.Block> absolute(List<LitematicFileReader.Block> cells,BlockPos origin){
        return cells.stream().map(b->new LitematicFileReader.Block(origin.getX()+b.x(),
                origin.getY()+b.y(),origin.getZ()+b.z(),b.state())).toList();
    }
    private void run(GameTestHelper h)throws Exception{
        String mode=System.getenv().getOrDefault("NN_DOME_MODE","AUTO4");
        if(!Set.of("AUTO4","AUTO8","Y1","X1","Z1").contains(mode))throw new IllegalArgumentException("Dome mode");
        int workers=mode.equals("AUTO8")?8:mode.equals("AUTO4")?4:1;
        String axis=mode.equals("X1")?"X":mode.equals("Z1")?"Z":"Y";
        Path original=BuilderDomeFixture.unpack();
        LitematicFileReader.Schematic source;
        try{source=LitematicFileReader.read(original);}finally{Files.deleteIfExists(original);}
        if(source.blocks().size()!=3537)throw new IllegalStateException("Wrong Dome source count");
        var all=new ArrayList<>(source.blocks());all.addAll(source.airBlocks());
        int minX=all.stream().mapToInt(LitematicFileReader.Block::x).min().orElseThrow(),
            minY=all.stream().mapToInt(LitematicFileReader.Block::y).min().orElseThrow(),
            minZ=all.stream().mapToInt(LitematicFileReader.Block::z).min().orElseThrow();
        int maxX=all.stream().mapToInt(LitematicFileReader.Block::x).max().orElseThrow(),
            maxY=all.stream().mapToInt(LitematicFileReader.Block::y).max().orElseThrow(),
            maxZ=all.stream().mapToInt(LitematicFileReader.Block::z).max().orElseThrow();
        var level=h.getLevel();
        if(level.getSeed()!=0)throw new IllegalStateException("Dome seed must be zero");
        var arena=new BlockPos(1024,-57,1024);
        var origin=arena.offset(10-minX,2-minY,10-minZ);
        int side=Math.max(70,Math.max(maxX-minX,maxZ-minZ)+21);
        int height=Math.max(36,maxY-minY+6);
        if(arena.getY()+height>=level.getMaxY())throw new IllegalStateException("Dome exceeds world height");
        var center=arena.offset(side/2,2,side/2);
        int radius=Math.max(4,(side+31)/32+1);
        level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.FORCED,
                new net.minecraft.world.level.ChunkPos(center.getX()>>4,center.getZ()>>4),radius);
        for(int cx=(arena.getX()>>4)-1;cx<=((arena.getX()+side+1)>>4)+1;cx++)
            for(int cz=(arena.getZ()>>4)-1;cz<=((arena.getZ()+side+1)>>4)+1;cz++)level.getChunk(cx,cz);
        for(int x=1;x<=side;x++)for(int z=1;z<=side;z++){
            level.setBlock(arena.offset(x,1,z),Blocks.STONE.defaultBlockState(),2);
            for(int y=2;y<=height;y++){
                var p=arena.offset(x,y,z);
                if(!level.getBlockState(p).isAir())level.setBlock(p,Blocks.AIR.defaultBlockState(),2);
            }
        }
        BlockPos depot=arena.offset(8,2,10),planner=depot.west();
        level.setBlock(planner,WorksiteContent.BUILDER_TABLE.defaultBlockState(),3);
        level.setBlock(depot,WorksiteContent.CREATIVE_CRATE.defaultBlockState(),3);
        if(!(level.getBlockEntity(planner) instanceof WorkerStationBlockEntity table))throw new IllegalStateException("Planner");
        var owner=h.makeMockServerPlayerInLevel();UUID id=owner.getUUID();
        owner.setPos(planner.getX()+.5,planner.getY(),planner.getZ()+2.5);
        var solids=absolute(source.blocks(),origin);var air=absolute(source.airBlocks(),origin);
        var frozen=new LitematicFileReader.Schematic(source.name(),solids,source.stateCounts(),air);
        var plan=BuilderConstructionPlan.create(frozen,BuilderPlacementSnapshot.worldSpaceSite(),true);
        var expected=new ArrayList<Expected>();int ignored=0;
        for(var b:solids){
            if(BuilderVegetation.ignoreSchematicState(b.state())){ignored++;continue;}
            var state=BuilderBlockResolver.expectedState(b.state(),BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
            if(state==null)throw new IllegalStateException("Unresolved source "+b.state());
            expected.add(new Expected(new BlockPos(b.x(),b.y(),b.z()),state));
        }
        if(expected.size()!=3493||ignored!=44||!plan.unsupported().isEmpty())
            throw new IllegalStateException("Dome target mismatch required="+expected.size()+" ignored="+ignored+" unsupported="+plan.unsupported());
        String file="placed-nn-dome.gpb";
        Path dir=FabricLoader.getInstance().getConfigDir().resolve("golems_plus").resolve("schematics");
        Files.createDirectories(dir);Files.write(dir.resolve(file),BuilderPlacementSnapshot.encode(source.name(),solids,air));
        if(!table.claimIfUnowned(id)||!table.setCrewTarget(id,workers)
                ||!table.setPlacedBuilderPlan(id,file,source.name(),origin,0,0,plan.steps().size(),0)
                ||!table.setBuilderMaterialStorage(id,depot)||!table.setBuilderIgnorePlants(id,true))
            throw new IllegalStateException("Dome plan rejected");
        table.setBuilderMaterialsForFile(file,plan.required());
        if(!BuilderSupplyInventory.creativeSupply(table))throw new IllegalStateException("No real Creative Crate");
        var crew=new ArrayList<BuilderGolem>();
        for(int i=0;i<workers;i++){
            var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
            var from=planner.offset(-2-i%2,0,1+i/2);
            worker.setUUID(new UUID(0x4e4e444f4d455354L,i+1));worker.setId(100000+i);
            worker.getRandom().setSeed(260215377L+i);
            worker.setPos(from.getX()+.5,from.getY(),from.getZ()+.5);
            worker.assign(planner,id);level.addFreshEntity(worker);
            if(!table.registerBuilderWorker(worker.getUUID()))throw new IllegalStateException("Register builder");
            crew.add(worker);
        }
        level.getRandom().setSeed(260215376L);
        if(!table.startPlacedBuilder(id))throw new IllegalStateException("Start Dome");
        var steps=plan.steps();
        int vertical=0;
        if(!axis.equals("Y")){
            var banded=BuilderNNDomeBandOrder.compile(steps,axis);
            vertical=banded.verticalTasks();
            if(!table.enableNNDomePilot(axis)||table.builderActiveStepIndex(steps)!=banded.order().getFirst())
                throw new IllegalStateException("Dome pilot rejected");
            if(vertical==0)System.out.println("[NN DOME NO VERTICAL] mode="+mode+" all bands protected; Y-only assessment");
        }
        final int eligible=vertical;
        System.out.println("[NN DOME START] mode="+mode+" axis="+axis+" workers="+workers
                +" source=3537 required=3493 ignored=44 verticalTasks="+eligible+" depot="+depot+" origin="+origin);
        final long start=h.getTick(),startNs=System.nanoTime();
        final int[] best={0};final double[] distance={0};
        var teamTrace=new BuilderNNTeamTrace("dome",mode,workers,3493);
        final var previous=crew.stream().map(BuilderGolem::position).toArray(net.minecraft.world.phys.Vec3[]::new);
        var watchdog=new MatrixWorkWatchdog(start,0,table.builderClearCursor());
        final boolean[] done={false};
        h.startSequence().thenExecuteFor(1000000000,()->{
            if(done[0])return;
            long tick=h.getTick(),elapsed=tick-start;
            for(int i=0;i<crew.size();i++){
                var pos=crew.get(i).position();distance[0]+=pos.distanceTo(previous[i]);previous[i]=pos;
            }
            boolean complete=table.phase()==WorkerPhase.COMPLETE;
            boolean timeout=elapsed>=180000||(System.nanoTime()-startNs)/1000000000L>=900;
            if(tick%200!=0&&!complete&&!timeout)return;
            int correct=0;for(var b:expected)if(BuilderPistonWork.sourceMatches(level.getBlockState(b.pos()),b.state()))correct++;
            best[0]=Math.max(best[0],correct);
            teamTrace.sample(level,table,crew,steps,elapsed,correct,distance[0],complete||timeout);
            long idle=watchdog.sample(tick,correct,table.builderClearCursor());
            boolean stall=idle>=2400&&!BuilderCrewRecovery.assessmentGrace(level,table);
            if(tick%1000==0||complete||timeout||stall){
                int activeY=table.builderActiveLayer(steps);
                System.out.println("[NN DOME PROGRESS] mode="+mode+" correct="+correct+" total=3493 tick="+elapsed
                        +" layer="+(activeY==Integer.MAX_VALUE?"complete":activeY-origin.getY())+" problem="+table.problem());
            }
            if(!complete&&!timeout&&!stall)return;
            long alive=crew.stream().filter(BuilderGolem::isAlive).count();
            String outcome=complete?(correct==3493&&alive==workers?"COMPLETE":"MISMATCH"):
                    stall?"NO_WORK_PROGRESS":elapsed>=180000?"TICK_LIMIT":"TIME_LIMIT";
            for(var w:crew)System.out.println("[NN DOME WORKER] mode="+mode+" feet="+w.position()
                    +" alive="+w.isAlive()+" claim="+table.claimedStep(w.getUUID())+" "+BuilderBuildService.debugStatus(level,w));
            // Diagnostic only: inspect the real remaining phase, batch and
            // pouch without changing claims or consuming any material.
            var pending=new ArrayList<Integer>();
            for(int i=0;i<steps.size()&&pending.size()<8;i++)
                if(table.builderStepInActivePhase(steps,i)&&!table.builderStepCompletedForMaterials(i))
                    pending.add(i);
            for(var w:crew){
                var stock=new ArrayList<String>();
                for(int i=0;i<w.getContainerSize();i++){
                    var stack=w.getItem(i);
                    if(!stack.isEmpty())stock.add(i+":"+stack);
                }
                System.out.println("[NN DOME MATERIAL] mode="+mode+" worker="+w.getUUID()
                        +" claim="+table.claimedStep(w.getUUID())+" prepared="+table.builderOrderPrepared(w.getUUID())
                        +" batch="+table.builderWorkOrder(w.getUUID())+" pouch="+stock
                        +" box="+w.hasSupplyBox()+" recovery="+BuilderCrewRecovery.traceStatus(table,w));
                for(int i:pending){
                    var step=steps.get(i);var item=BuilderMaterialNetwork.required(step);
                    System.out.println("[NN DOME PENDING] mode="+mode+" worker="+w.getUUID()+" step="+i
                            +" xyz="+new BlockPos(step.x(),step.y(),step.z()).subtract(origin)
                            +" item="+step.requiredItem()+" carried="+(item==null?0:w.carriedCount(item))
                            +" onsiteAvailable="+(item!=null&&BuilderSupplyInventory.containsInSources(table,item))
                            +" ready="+table.builderStepReadyForClaim(i));
                }
            }
            BuilderTrace.dump(level,table,"Dome "+mode+" "+outcome);
            System.out.println("[NN DOME RESULT] mode="+mode+" outcome="+outcome+" correct="+correct+" best="+best[0]
                    +" total=3493 ticks="+elapsed+" distance="+distance[0]+" workers="+workers+" alive="+alive
                    +" verticalTasks="+eligible+" selected="+table.builderNNStrategy()+" problem="+table.problem());
            done[0]=true;h.succeed(); // finished assessment, not proof of completion
        });
    }
}
