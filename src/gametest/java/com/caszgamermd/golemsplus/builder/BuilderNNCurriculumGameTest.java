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
        var raw=BuilderNNCurriculumFixture.read(course);
        boolean speed="true".equals(System.getenv("NN_SPEED_TEST"));
        boolean strip="true".equals(System.getenv("NN_DRAGON_STRATEGY"));
        String axis=System.getenv().getOrDefault("NN_STRIP_AXIS","Y");
        if(strip&&(!course.id().equals("dragon")||!List.of("Y","X","Z").contains(axis))){
            h.fail("Dragon strategy fixture/axis mismatch");return;
        }
        boolean rotationTest="true".equals(System.getenv("GOLEMS_NN_ROTATION_TEST"));
        int turn=Integer.parseInt(System.getenv().getOrDefault("NN_ROTATION_TURN","0"));
        int direction=Integer.parseInt(System.getenv().getOrDefault("NN_STRIP_DIRECTION","1"));
        String caseId=System.getenv().getOrDefault("NN_ROTATION_CASE","none");
        if(rotationTest&&(!strip||!List.of("X","Z").contains(axis)
                ||(turn!=0&&turn!=90)||(direction!=1&&direction!=-1)
                ||"true".equals(System.getenv("GOLEMS_NN_ADAPTIVE"))
                ||"true".equals(System.getenv("GOLEMS_NN_BANDED")))){
            h.fail("Rotation benchmark requires a pure signed strip");return;
        }
        int workers=strip?1:Integer.parseInt(System.getenv().getOrDefault("NN_TEAM_SIZE","4"));
        if(!List.of(1,2,4,8).contains(workers)){h.fail("Invalid crew size");return;}
        if(speed&&course.blocks()>2000){h.fail("Speed course exceeds 2000 source blocks");return;}
        var all=new ArrayList<>(raw.blocks());
        all.addAll(raw.airBlocks());
        int minX=all.stream().mapToInt(LitematicFileReader.Block::x).min().orElseThrow();
        int minY=all.stream().mapToInt(LitematicFileReader.Block::y).min().orElseThrow();
        int minZ=all.stream().mapToInt(LitematicFileReader.Block::z).min().orElseThrow();
        int maxX=all.stream().mapToInt(LitematicFileReader.Block::x).max().orElseThrow();
        int maxY=all.stream().mapToInt(LitematicFileReader.Block::y).max().orElseThrow();
        int maxZ=all.stream().mapToInt(LitematicFileReader.Block::z).max().orElseThrow();
        System.out.println("[NN_CURRICULUM_IMPORT] course="+course.id()+" blocks="+course.blocks()
                +" air="+course.air()+" hash="+course.hash());
        ServerLevel level=h.getLevel();
        if(level.getSeed()!=0){h.fail("Curriculum seed is not zero");return;}
        final BlockPos arena=new BlockPos(1024,-57,1024);
        final BlockPos originalOrigin=arena.offset(10-minX,2-minY,10-minZ);
        int width=maxX-minX+1,height=maxY-minY+1,depth=maxZ-minZ+1;
        // The fixture follows the schematic, never crops or divides a build.
        // Explicit budgets protect the CI server rather than imposing a
        // gameplay dimension restriction. A larger runner may raise them.
        int arenaWidth=Math.max(62,Math.addExact(width,20));
        int arenaDepth=Math.max(70,Math.addExact(depth,20));
        if(rotationTest){int side=Math.max(arenaWidth,arenaDepth);arenaWidth=side;arenaDepth=side;}
        final int squareSide=arenaWidth;
        final BlockPos origin=turn==90?rotateArena(originalOrigin,arena,squareSide):originalOrigin;
        var schematic=turn==90?rotateSchematic(raw):raw;
        int clearHeight=Math.max(27,Math.addExact(height,4));
        if((long)arena.getY()+clearHeight>=level.getMaxY()
                ||arena.getY()+1<level.getMinY()){
            h.fail("Course does not fit the test dimension's world height");return;
        }
        long volume=Math.multiplyExact(Math.multiplyExact((long)arenaWidth,arenaDepth),clearHeight);
        long budget=Long.parseLong(System.getenv().getOrDefault("NN_ARENA_CELL_BUDGET","8000000"));
        if(volume>budget){h.fail("Test runner cell budget exceeded: "+volume+" > "+budget);return;}
        int radius=Math.max(4,(Math.max(arenaWidth,arenaDepth)+31)/32+1);
        int chunkBudget=Integer.parseInt(System.getenv().getOrDefault("NN_ARENA_CHUNK_BUDGET","625"));
        if((2L*radius+1)*(2L*radius+1)>chunkBudget){
            h.fail("Test runner chunk budget exceeded; increase capacity, not crop the schematic");return;
        }
        BlockPos center=arena.offset((arenaWidth+2)/2,2,(arenaDepth+2)/2);
        level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.FORCED,
                new net.minecraft.world.level.ChunkPos(center.getX()>>4,center.getZ()>>4),radius);
        for(int cx=(arena.getX()>>4)-1;cx<=((arena.getX()+arenaWidth+1)>>4)+1;cx++)
            for(int cz=(arena.getZ()>>4)-1;cz<=((arena.getZ()+arenaDepth+1)>>4)+1;cz++)
                level.getChunk(cx,cz);
        for(int x=1;x<=arenaWidth;x++)for(int z=1;z<=arenaDepth;z++){
            level.setBlock(arena.offset(x,1,z),Blocks.STONE.defaultBlockState(),2);
            for(int y=2;y<=clearHeight;y++){
                BlockPos p=arena.offset(x,y,z);
                if(!level.getBlockState(p).isAir())level.setBlock(p,Blocks.AIR.defaultBlockState(),2);
            }
        }
        int baseY=raw.blocks().stream().mapToInt(LitematicFileReader.Block::y).min().orElseThrow();
        // Keep the depot outside the source bounding box and choose
        // the perimeter nearest a real foundation cell, not a fixed corner.
        // Small historical courses keep their original depot for comparison.
        var first=raw.blocks().stream().filter(b->b.y()==baseY)
                .min(java.util.Comparator.comparingInt(LitematicFileReader.Block::x)).orElseThrow();
        BlockPos supply=arena.offset(8,2,10+first.z()-minZ);
        BlockPos planner=supply.west();
        if(width>100||depth>100||height>60){
            var foundations=raw.blocks().stream().filter(b->b.y()==baseY).toList();
            int closest=Integer.MAX_VALUE;
            for(var cell:foundations){
                int x=cell.x()-minX,z=cell.z()-minZ;
                int[] distance={x,width-1-x,z,depth-1-z};
                for(int side=0;side<4;side++){
                    if(distance[side]>=closest)continue;
                    closest=distance[side];
                    supply=switch(side){
                        case 0 -> arena.offset(8,2,10+z);
                        case 1 -> arena.offset(10+width+1,2,10+z);
                        case 2 -> arena.offset(10+x,2,8);
                        default -> arena.offset(10+x,2,10+depth+1);
                    };
                    planner=switch(side){
                        case 0 -> supply.west();
                        case 1 -> supply.east();
                        case 2 -> supply.north();
                        default -> supply.south();
                    };
                }
            }
        }
        BlockPos ownerStart=planner.offset(0,0,2);
        var fixtureRotation=turn==90?net.minecraft.world.level.block.Rotation.CLOCKWISE_90:
                net.minecraft.world.level.block.Rotation.NONE;
        if(turn==90){
            planner=rotateArena(planner,arena,squareSide);
            supply=rotateArena(supply,arena,squareSide);
            ownerStart=rotateArena(ownerStart,arena,squareSide);
        }
        level.setBlock(planner,WorksiteContent.BUILDER_TABLE.defaultBlockState().rotate(fixtureRotation),3);
        level.setBlock(supply,WorksiteContent.CREATIVE_CRATE.defaultBlockState().rotate(fixtureRotation),3);
        if(!(level.getBlockEntity(planner) instanceof WorkerStationBlockEntity table)){
            h.fail("Missing curriculum Planner");return;
        }
        var owner=h.makeMockServerPlayerInLevel();
        owner.setPos(ownerStart.getX()+.5,ownerStart.getY(),ownerStart.getZ()+.5);
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
        if(rotationTest){
            if(unsupported!=0||!plan.unsupported().isEmpty()||expected.size()!=course.blocks()
                    ||schematic.airBlocks().size()!=raw.airBlocks().size()){
                h.fail("Rotated fixture lost source states");return;
            }
            System.out.println("[NN ROTATION SETUP] case="+caseId+" turn="+turn+" axis="+axis
                    +" direction="+direction+" square="+squareSide+" origin="+origin+" supply="+supply
                    +" planner="+planner+" owner="+ownerStart+" source="+expected.size());
        }
        if(!table.claimIfUnowned(ownerId)||!table.setCrewTarget(ownerId,workers)){
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
        for(int i=0;i<workers;i++){
            var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
            int dx=planner.getX()-supply.getX(),dz=planner.getZ()-supply.getZ();
            BlockPos from=planner.offset(dx*(2+i%2)-dz*(1+i/2),0,
                    dz*(2+i%2)+dx*(1+i/2));
            if(rotationTest)System.out.println("[NN ROTATION SPAWN] case="+caseId+" pos="+from);
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
                +" origin="+origin.toShortString().replace(" ","")+" arena="+arenaWidth+"x"+clearHeight+"x"+arenaDepth+" workers="+workers+" supply=creative_crate"
                +" scaffolding="+BuilderGolem.temporaryScaffoldingEnabled()
                +" unsupportedStates="+plan.unsupported());
        if(!table.startPlacedBuilder(ownerId)){h.fail("Cannot start curriculum job");return;}
        if(strip){
            if(!axis.equals("Y")){
                boolean banded="true".equals(System.getenv("GOLEMS_NN_BANDED"));
                int planBaseY=steps.stream().mapToInt(BuilderConstructionPlan.Step::y).min().orElseThrow();
                var order=banded?BuilderNNStripOrder.compileBanded(steps,axis,planBaseY)
                        :BuilderNNStripOrder.compile(steps,axis,true,rotationTest?direction:1);
                if(!table.enableNNStripPilot(axis)
                        ||table.builderActiveStepIndex(steps)!=order.getFirst()){
                    h.fail("Dragon strip policy refused");return;
                }
            }
            System.out.println("[NN DRAGON START] axis="+axis+" source="+course.blocks()
                    +" steps="+steps.size()+" workers="+workers);
        }
        final long start=System.nanoTime();
        final long startTick=h.getTick();
        final long tickLimit=Long.parseLong(System.getenv().getOrDefault("NN_SPEED_TICK_LIMIT","72000"));
        if(speed&&tickLimit<1){h.fail("Invalid speed tick limit");return;}
        if(speed)System.out.println("[NN_SPEED_START] course="+course.id()+" source="+course.blocks()
                +" startTick="+startTick+" tickLimit="+tickLimit+" crew="+workers);
        final long limit=Long.parseLong(System.getenv().getOrDefault("NN_COURSE_SECONDS","300"));
        if(limit<1||limit>1800){h.fail("Invalid course seconds");return;}
        var watchdog=new MatrixWorkWatchdog(h.getTick(),table.builderWorkDone(),table.builderClearCursor());
        final int[] best={0};
        final double[] distance={0};
        final var lastPosition=crew.stream().map(BuilderGolem::position).toArray(net.minecraft.world.phys.Vec3[]::new);
        var teamTrace=new BuilderNNTeamTrace(course.id(),"AUTO"+workers,workers,course.blocks());
        h.startSequence().thenExecuteFor(1_000_000_000,()->{
            long tick=h.getTick();
            if(strip||teamTrace.enabled())for(int i=0;i<crew.size();i++){
                var pos=crew.get(i).position();
                distance[0]+=pos.distanceTo(lastPosition[i]);lastPosition[i]=pos;
            }
            long elapsed=(System.nanoTime()-start)/1_000_000L;
            long noWork=watchdog.sample(tick,table.builderWorkDone(),table.builderClearCursor());
            boolean complete=table.phase()==WorkerPhase.COMPLETE;
            long elapsedTicks=tick-startTick;
            boolean tickTimeout=speed&&elapsedTicks>=tickLimit;
            boolean wallTimeout=elapsed>=limit*1000L;
            boolean timeout=tickTimeout||wallTimeout;
            boolean stalled=noWork>=1200&&!BuilderCrewRecovery.assessmentGrace(level,table);
            if(tick%400!=0&&!complete&&!timeout&&!stalled)return;
            int correct=0;
            for(var b:expected)
                if(BuilderPistonWork.sourceMatches(level.getBlockState(b.pos()),b.state()))correct++;
            best[0]=Math.max(best[0],correct);
            teamTrace.sample(level,table,crew,steps,elapsedTicks,correct,distance[0],complete||timeout||stalled);
            int y=table.builderActiveLayer(steps);
            System.out.println("[NN_CURRICULUM_PROGRESS] course="+course.id()+" correct="+correct
                    +" total="+course.blocks()+" layer="+(y==Integer.MAX_VALUE?"complete":y-origin.getY())
                    +" tick="+tick+" problem="+table.problem());
            if(!complete&&!timeout&&!stalled)return;
            String outcome=complete?(correct==course.blocks()?"COMPLETE":"MISMATCH"):
                    stalled?"NO_WORK_PROGRESS":tickTimeout?"TICK_LIMIT":"TIME_LIMIT";
            // Preserve the exact terminal claim, frontier and route evidence
            // even when the recovery state overwrote the table problem.
            for(var w:crew){
                System.out.println("[NN_CURRICULUM_WORKER] course="+course.id()
                        +" uuid="+w.getUUID()+" feet="+w.position()
                        +" alive="+w.isAlive()+" claim="+table.claimedStep(w.getUUID())
                        +" "+BuilderBuildService.debugStatus(level,w));
            }
            BuilderTrace.dump(level,table,"curriculum "+outcome);
            System.out.println("[NN_CURRICULUM_RESULT] course="+course.id()+" outcome="+outcome
                    +" correct="+correct+" best="+best[0]+" total="+course.blocks()
                    +" elapsedMs="+elapsed+" tick="+tick+" unsupported="+unsupported);
            if(speed)System.out.println("[NN_SPEED_RESULT] course="+course.id()+" outcome="+outcome
                    +" correct="+correct+" total="+course.blocks()+" elapsedTicks="+elapsedTicks
                    +" elapsedMs="+elapsed+" unsupported="+unsupported
                    +" crewAlive="+crew.stream().filter(BuilderGolem::isAlive).count()
                    +" crewUnclaimed="+crew.stream().filter(w->table.claimedStep(w.getUUID())<0).count());
            if(strip)System.out.println("[NN DRAGON RESULT] axis="+axis+" outcome="+outcome
                    +" correct="+correct+" best="+best[0]+" total="+course.blocks()
                    +" elapsedTicks="+elapsedTicks+" elapsedMs="+elapsed+" distance="+distance[0]
                    +" alive="+crew.getFirst().isAlive()+" unsupported="+unsupported
                    +" problem="+table.problem());
            if(rotationTest)System.out.println("[NN ROTATION RESULT] case="+caseId+" turn="+turn
                    +" axis="+axis+" direction="+direction+" outcome="+outcome+" correct="+correct
                    +" total="+course.blocks()+" elapsedTicks="+elapsedTicks+" distance="+distance[0]
                    +" problem="+table.problem());
            h.succeed(); // Assessment completed, not necessarily the build.
        });
    }
    // Rotate the whole square arena about its center, including cell centers.
    private static BlockPos rotateArena(BlockPos p,BlockPos arena,int side){
        return new BlockPos(arena.getX()+side+1-(p.getZ()-arena.getZ()),p.getY(),
                arena.getZ()+p.getX()-arena.getX());
    }
    private static <T extends Comparable<T>> String propertyValue(BlockState state,
            net.minecraft.world.level.block.state.properties.Property<T> property){
        return property.getName(state.getValue(property));
    }
    private static String encodeState(BlockState state){
        String id=net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        var properties=new ArrayList<String>();
        for(var property:state.getProperties())
            properties.add(property.getName()+"="+propertyValue(state,property));
        return properties.isEmpty()?id:id+"["+String.join(",",properties)+"]";
    }
    private static LitematicFileReader.Block rotateCell(LitematicFileReader.Block cell){
        var state=BuilderBlockResolver.expectedState(cell.state(),BuildSite.Rotation.CLOCKWISE_90,BuildSite.Mirror.NONE);
        // Air does not require a material resolver; its orientation is invariant.
        if(state==null&&cell.state().equals("minecraft:air"))return new LitematicFileReader.Block(-cell.z(),cell.y(),cell.x(),cell.state());
        if(state==null)throw new IllegalArgumentException("Cannot rotate source state "+cell.state());
        String encoded=encodeState(state);
        var inverse=BuilderBlockResolver.expectedState(encoded,BuildSite.Rotation.COUNTERCLOCKWISE_90,BuildSite.Mirror.NONE);
        var original=BuilderBlockResolver.expectedState(cell.state(),BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
        if(!java.util.Objects.equals(inverse,original))throw new IllegalArgumentException("State rotation not reversible: "+cell.state());
        return new LitematicFileReader.Block(-cell.z(),cell.y(),cell.x(),encoded);
    }
    private static LitematicFileReader.Schematic rotateSchematic(LitematicFileReader.Schematic raw){
        var blocks=raw.blocks().stream().map(BuilderNNCurriculumGameTest::rotateCell).toList();
        var air=raw.airBlocks().stream().map(BuilderNNCurriculumGameTest::rotateCell).toList();
        var counts=new java.util.HashMap<String,Long>();
        for(var cell:blocks)counts.merge(cell.state(),1L,Long::sum);
        return new LitematicFileReader.Schematic(raw.name(),blocks,counts,air);
    }
    private static List<LitematicFileReader.Block> absolute(List<LitematicFileReader.Block> cells,BlockPos origin){
        return cells.stream().map(b->new LitematicFileReader.Block(origin.getX()+b.x(),
                origin.getY()+b.y(),origin.getZ()+b.z(),b.state())).toList();
    }
}
