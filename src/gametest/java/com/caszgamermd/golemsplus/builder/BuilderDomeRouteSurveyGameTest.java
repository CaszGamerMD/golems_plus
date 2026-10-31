package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.worker.WorkerRoutePlanner;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.StainedGlassBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Geometry-model survey, NOT a magical pre-recorded dome route.
 * Every block comes from the exact checksum-verified user litematic.
 * World footing/headroom come from Minecraft's actual VoxelShapes.
 * All coordinates in the output are the schematic's local XYZ.
 *
 * Two models are kept separate:
 * 1) The complete intended schematic: all 3,537 indexed block entries.
 * 2) Pre-roof constructed snapshot: all nonvegetation entries below Y30,
 *    but not the unfinished upper platform. This is NOT a claim of an
 *    exact tick-by-tick Y30 snapshot; the real dome GameTest separately
 *    logs live worker positions and remaining blocks.
 */
public final class BuilderDomeRouteSurveyGameTest {
    private record Pair(String name,BlockPos start,BlockPos target){}
    private static final Pair[] CASES={
        new Pair("NORTH",new BlockPos(8,26,11),new BlockPos(15,30,11)),
        new Pair("SOUTH",new BlockPos(9,26,24),new BlockPos(15,30,24)),
        new Pair("COBBLE_LEG",new BlockPos(8,26,12),new BlockPos(15,30,12)),
        new Pair("DIORITE_LEG",new BlockPos(22,29,23),new BlockPos(20,30,22))
    };
    private static BlockPos abs(BlockPos o,BlockPos rel){
        return o.offset(rel.getX(),rel.getY(),rel.getZ());
    }
    private static String rel(BlockPos o,BlockPos p){
        return p.subtract(o).toShortString();
    }
    private static char symbol(BlockState s){
        if(s.isAir())return '.';
        if(s.getBlock() instanceof WallBlock)return 'W';
        if(s.getBlock() instanceof FenceBlock)return 'F';
        if(s.getBlock() instanceof SlabBlock)return '=';
        if(s.getBlock() instanceof StairBlock)return '^';
        if(s.is(Blocks.GLASS)
                ||s.getBlock() instanceof StainedGlassBlock)return 'G';
        String n=s.getBlock().toString();
        if(n.contains("diorite"))return 'D';
        if(n.contains("cobblestone"))return 'C';
        if(n.contains("scaffolding"))return 'H';
        return '#';
    }
    private static String grid(ServerLevel world,BlockPos origin,
            int y,Pair focus,Set<BlockPos> reachable){
        StringBuilder map=new StringBuilder();
        map.append("[ROUTE SLICE] case=").append(focus.name())
                .append(" Y=").append(y).append(" (schematic local)\n");
        map.append("x  ");
        for(int x=5;x<=26;x++)map.append(x%10);
        map.append("\n");
        for(int z=6;z<=28;z++){
            map.append(String.format(java.util.Locale.ROOT,"%02d ",z));
            for(int x=5;x<=26;x++){
                BlockPos local=new BlockPos(x,y,z);
                char c=symbol(world.getBlockState(abs(origin,local)));
                if(reachable.contains(abs(origin,local)))c='o';
                if(local.equals(focus.start()))c='A';
                if(local.equals(focus.target()))c='T';
                map.append(c);
            }
            map.append('\n');
        }
        return map.toString();
    }
    private static String nodePath(BlockPos origin,WorkerRoutePlanner.Route route){
        if(route==null)return "none";
        if(route.steps().isEmpty())return "at_target";
        StringBuilder v=new StringBuilder("length=").append(route.steps().size())
                .append(" cost=").append(route.cost())
                .append(" complete=").append(route.complete()).append(" waypoints=");
        int k=0;
        for(int i=0;i<route.steps().size();i++){
            BlockPos here=route.steps().get(i);
            if(i>0&&i<route.steps().size()-1){
                BlockPos before=route.steps().get(i-1),next=route.steps().get(i+1);
                if(next.getX()-here.getX()==here.getX()-before.getX()
                        &&next.getZ()-here.getZ()==here.getZ()-before.getZ()
                        &&next.getY()-here.getY()==here.getY()-before.getY())
                    continue;
            }
            if(k++>26){v.append("...");break;}
            v.append(' ').append(rel(origin,here));
        }
        return v.toString();
    }

    /**
     * Authoritative snapshot from the live four-Builder dome test at the
     * instant it stalls. Unlike the static pre-roof survey, this records
     * the EXACT partially built and collision-resolved world state,
     * including completed Y30 placements and each actual golem/goal.
     * It never moves workers or changes any build block.
     */
    static void captureLive(ServerLevel world,
            WorkerStationBlockEntity table,
            List<BuilderGolem> workers,
            List<BuilderConstructionPlan.Step> steps){
        try{
            BlockPos origin=table.builderOrigin();
            Path out=Path.of("route-survey");
            Files.createDirectories(out);
            StringBuilder csv=new StringBuilder(
                    "x,y,z,intendedBlock,physicalBlock,collisionTop\n");
            for(var s:steps){
                BlockPos local=new BlockPos(s.x()-origin.getX(),
                        s.y()-origin.getY(),s.z()-origin.getZ());
                BlockPos pos=new BlockPos(s.x(),s.y(),s.z());
                BlockState now=world.getBlockState(pos);
                VoxelShape shape=now.getCollisionShape(world,pos);
                csv.append(local.getX()).append(',').append(local.getY())
                        .append(',').append(local.getZ()).append(',')
                        .append('"').append(s.encodedState()).append('"').append(',')
                        .append('"').append(now).append('"').append(',')
                        .append(shape.isEmpty()?0:shape.max(Direction.Axis.Y))
                        .append('\n');
            }
            Files.writeString(out.resolve("live_dome_last_stall.csv"),
                    csv,StandardCharsets.UTF_8);
            StringBuilder report=new StringBuilder(
                    "[LIVE DOME SURVEY] actual stalled 4-Builder construction\n"
                    +"Coordinates: ORIGINAL SCHEMATIC origin (0,0,0); "
                    +"not absolute random GameTest world positions.\n");
            int id=0;
            for(BuilderGolem mob:workers){
                int claimed=table.claimedStep(mob.getUUID());
                BlockPos start=mob.blockPosition();
                if(claimed<0||claimed>=steps.size()){
                    report.append("[WORKER] #").append(id++)
                        .append(" idle name=").append(mob.getName().getString())
                        .append(" at=").append(rel(origin,start)).append('\n');
                    continue;
                }
                var step=steps.get(claimed);
                BlockPos target=new BlockPos(step.x(),step.y(),step.z());
                report.append("[WORKER] #").append(id++)
                    .append(" name=").append(mob.getName().getString())
                    .append(" claimedIndex=").append(claimed)
                    .append(" feet=").append(rel(origin,start))
                    .append(" exactFeet=(")
                    .append(mob.getX()-origin.getX()).append(',')
                    .append(mob.getY()-origin.getY()).append(',')
                    .append(mob.getZ()-origin.getZ()).append(')')
                    .append(" target=").append(rel(origin,target))
                    .append(" block=").append(step.encodedState())
                    .append(" underneath=").append(world.getBlockState(target.below()))
                    .append(" grounded=").append(mob.onGround()).append('\n');
                report.append("[LIVE FOOTPRINT] collision=")
                    .append(!world.noCollision(mob,mob.getBoundingBox().deflate(.015)))
                    .append(" feetBelow=").append(world.getBlockState(start.below()))
                    .append(" actualHead=").append(world.getBlockState(start.above()))
                    .append(" centeredFeetStand=").append(
                            WorkerNavigation.canStandOn(world,mob,start))
                    .append(" cornerMask=").append(Integer.toBinaryString(
                            WorkerNavigation.supportedHitboxCorners(world,mob)))
                    .append('\n');
                report.append("[LIVE WORK STATE] ")
                    .append(BuilderBuildService.debugStatus(world,mob))
                    .append('\n');
                Set<BlockPos> component=WorkerNavigation.groundReachableFrom(
                        world,mob,start);
                List<BlockPos> safe=new ArrayList<>();
                int stand=0,sight=0,escape=0;
                for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++)
                    for(int dy=0;dy<=1;dy++){
                        BlockPos pose=target.offset(dx,dy,dz);
                        if(pose.equals(target)||pose.distSqr(target)>=14)continue;
                        if(!WorkerNavigation.canStandOn(world,mob,pose))continue;
                        stand++;
                        if(!BuilderConstructionPlanner.lineOfSight(
                                world,mob,pose,target))continue;
                        sight++;
                        if(!BuilderConstructionPlanner.safeStand(
                                world,mob,target,pose))continue;
                        escape++;
                        safe.add(pose);
                    }
                var direct=WorkerRoutePlanner.plan(start,safe,
                        p->WorkerNavigation.canStandOn(world,mob,p),
                        p->WorkerNavigation.surveyFloorHeight(world,p),null);
                long connected=safe.stream().filter(component::contains).count();
                report.append("[GROUND GRAPH] componentNodes=")
                    .append(component.size())
                    .append(" standablePoses=").append(stand)
                    .append(" lineOfSight=").append(sight)
                    .append(" safeEscape=").append(escape)
                    .append(" sameComponent=").append(connected)
                    .append(" bestRoute=").append(nodePath(origin,direct))
                    .append('\n');
                List<BlockPos> nearest=new ArrayList<>(component);
                nearest.sort(Comparator.comparingDouble(p->p.distSqr(target)));
                report.append("[NEAREST REAL FOOTHOLDS]");
                for(int i=0;i<Math.min(16,nearest.size());i++){
                    BlockPos p=nearest.get(i);
                    report.append(' ').append(rel(origin,p))
                          .append(" on=").append(world.getBlockState(p.below()));
                }
                report.append('\n');
                report.append("[PAID SCAFFOLD] ")
                        .append(BuilderScaffoldPlanA.diagnoseAccess(
                                world,mob,table,target,steps))
                        .append('\n');
                for(int y:new int[]{26,28,29,30,31}){
                    Pair focus=new Pair(mob.getName().getString(),
                            start.subtract(origin),target.subtract(origin));
                    report.append(grid(world,origin,y,focus,component));
                }
            }
            Files.writeString(out.resolve("live_worker_route_analysis.txt"),
                    report,StandardCharsets.UTF_8);
            System.out.println("[LIVE DOME SURVEY] captured actual world model "
                    +"and worker route report: route-survey/live_dome_last_stall.csv"
                    +" route-survey/live_worker_route_analysis.txt");
            for(String line:report.toString().split("\n"))
                if(line.startsWith("[WORKER]")||line.startsWith("[GROUND GRAPH]")
                        ||line.startsWith("[PAID SCAFFOLD]")
                        ||line.startsWith("[NEAREST REAL FOOTHOLDS]"))
                    System.out.println(line);
        }catch(Exception ex){
            System.err.println("[LIVE DOME SURVEY] diagnostic failed "+ex);
        }
    }

    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=180,skyAccess=true)
    public void surveyOriginalDomeRoofSurfaceAndWorkerRoutes(GameTestHelper h){
        ServerLevel world=h.getLevel();
        BlockPos origin=h.absolutePos(new BlockPos(8,2,8));
        world.getChunkSource().addTicketWithRadius(
                net.minecraft.server.level.TicketType.FORCED,
                new net.minecraft.world.level.ChunkPos(origin.getX()>>4,
                        origin.getZ()>>4),4);
        for(int x=4;x<=42;x++)for(int z=4;z<=42;z++)
            h.setBlock(x,1,z,Blocks.STONE);
        var original=new HashMap<BlockPos,BlockState>();
        try{
            var schematic=LitematicFileReader.read(BuilderDomeFixture.unpack());
            for(var cell:schematic.blocks()){
                var resolved=BuilderBlockResolver.resolve(cell.state(),
                        BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
                if(resolved==null)continue;
                BlockPos p=new BlockPos(cell.x(),cell.y(),cell.z());
                original.put(p,resolved.state());
                if(cell.y()<30&&!BuilderVegetation.ignoreSchematicState(cell.state()))
                    world.setBlock(abs(origin,p),resolved.state(),2);
            }
        }catch(Exception ex){h.fail("Original dome model failed "+ex);return;}
        if(original.size()!=3537){h.fail("Blueprint model missing blocks: "
                +original.size());return;}
        // These are LAST OBSERVED physical worker positions, not
        // manufactured locations reached by the golem during this test.
        List<BuilderGolem> workers=new ArrayList<>();
        for(Pair p:CASES){
            BuilderGolem mob=new BuilderGolem(
                    WorksiteContent.BUILDER_GOLEM,world);
            BlockPos spawn=abs(origin,p.start());
            mob.setPos(spawn.getX()+.5,spawn.getY(),spawn.getZ()+.5);
            world.addFreshEntity(mob);
            workers.add(mob);
        }
        h.onEachTick(()->{
            if(h.getTick()!=22)return;
            try{
                Path out=Path.of("route-survey");
                Files.createDirectories(out);
                StringBuilder report=new StringBuilder();
                report.append("# Exact dome collision/route survey\n");
                report.append("Original block entries: ").append(original.size())
                        .append("; expected required placements: 3493")
                        .append("; ignored vegetation: 44\n");
                report.append("World: real Minecraft voxel collision; schematic-local XYZ;")
                        .append(" snapshot contains constructed Y<30,")
                        .append(" and AIR for Y>=30. No prebuilt upper roof.\n");
                StringBuilder csv=new StringBuilder("x,y,z,intendedBlock,physicalBlock,collisionTop\n");
                var ordered=new ArrayList<>(original.entrySet());
                ordered.sort(Comparator.comparingInt((Map.Entry<BlockPos,BlockState> x)->x.getKey().getY())
                        .thenComparingInt(x->x.getKey().getX())
                        .thenComparingInt(x->x.getKey().getZ()));
                for(var cell:ordered){
                    BlockPos local=cell.getKey();
                    BlockPos pos=abs(origin,local);
                    BlockState now=world.getBlockState(pos);
                    VoxelShape actual=now.getCollisionShape(world,pos);
                    csv.append(local.getX()).append(',').append(local.getY()).append(',')
                            .append(local.getZ()).append(',')
                            .append('"').append(cell.getValue()).append('"').append(',')
                            .append('"').append(now).append('"').append(',')
                            .append(actual.isEmpty()?0:actual.max(Direction.Axis.Y)).append('\n');
                }
                Files.writeString(out.resolve("original_dome_exact_model.csv"),csv,
                        StandardCharsets.UTF_8);
                int id=0;
                for(Pair c:CASES){
                    var mob=workers.get(id++);
                    BlockPos start=abs(origin,c.start()),target=abs(origin,c.target());
                    BlockState targetState=original.get(c.target());
                    Set<BlockPos> reachable=WorkerNavigation.groundReachableFrom(
                            world,mob,mob.blockPosition());
                    report.append("\n[ROUTE CASE] ").append(c.name())
                            .append(" start=").append(c.start())
                            .append(" actualFeet=").append(rel(origin,mob.blockPosition()))
                            .append(" grounded=").append(mob.onGround())
                            .append(" goal=").append(c.target())
                            .append(" intended=").append(targetState)
                            .append(" current=").append(world.getBlockState(target))
                            .append(" below=").append(world.getBlockState(target.below()))
                            .append(" reachableNodes=").append(reachable.size())
                            .append(" startStanding=").append(
                                    WorkerNavigation.canStandOn(world,mob,mob.blockPosition()))
                            .append('\n');
                    List<BlockPos> poses=new ArrayList<>();
                    int stand=0,sight=0,escapes=0,walkable=0;
                    for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++)
                        for(int dy=0;dy<=1;dy++){
                            BlockPos at=target.offset(dx,dy,dz);
                            if(at.equals(target)||at.distSqr(target)>=14)continue;
                            double px=at.getX()+.5,pz=at.getZ()+.5;
                            double ex=Math.max(0,Math.max(target.getX()-px,
                                    px-target.getX()-1));
                            double ez=Math.max(0,Math.max(target.getZ()-pz,
                                    pz-target.getZ()-1));
                            if(Math.hypot(ex,ez)<.8
                                    ||!WorkerNavigation.canStandOn(world,mob,at))
                                continue;
                            stand++;
                            if(!BuilderConstructionPlanner.lineOfSight(
                                    world,mob,at,target))continue;
                            sight++;
                            if(!BuilderConstructionPlanner.safeStand(
                                    world,mob,target,at))continue;
                            escapes++;
                            poses.add(at);
                            if(reachable.contains(at))walkable++;
                        }
                    report.append("[WORK POSES] standable=").append(stand)
                            .append(" sight=").append(sight)
                            .append(" safeEscape=").append(escapes)
                            .append(" sameComponent=").append(walkable).append('\n');
                    var route=WorkerRoutePlanner.plan(mob.blockPosition(),poses,
                            p->WorkerNavigation.canStandOn(world,mob,p),
                            p->WorkerNavigation.surveyFloorHeight(world,p),null);
                    report.append("[GROUND ROUTE] ")
                            .append(nodePath(origin,route)).append('\n');
                    List<BlockPos> footholds=new ArrayList<>(reachable);
                    footholds.sort(Comparator.comparingDouble(p->
                            p.distSqr(target)));
                    report.append("[CLOSEST REACHABLE FOOTHOLDS]");
                    for(int i=0;i<Math.min(12,footholds.size());i++){
                        var at=footholds.get(i);
                        report.append(' ').append(rel(origin,at))
                            .append(':').append(
                                    world.getBlockState(at.below()).getBlock());
                    }
                    report.append('\n');
                    for(int y:new int[]{26,28,29,30,31})
                        report.append(grid(world,origin,y,c,reachable));
                    System.out.println("[DOME ROUTE SURVEY] "+c.name()
                            +" reachable="+reachable.size()
                            +" safePoses="+escapes+" connected="+walkable
                            +" groundRoute="+nodePath(origin,route));
                }
                Files.writeString(out.resolve("roof_route_survey.txt"),report,
                        StandardCharsets.UTF_8);
                System.out.println("[DOME MODEL] PASS model=3537 original blocks;"
                        +" output=route-survey/original_dome_exact_model.csv"
                        +" output=route-survey/roof_route_survey.txt");

                // ACTUAL last high-progress run 41065867271 logged these
                // five remaining Y30 blocks at tick 76800. Populate every
                // other original Y30 cell. The snapshot is therefore an
                // evidence-grounded model of the stalled upper roof, not a
                // guessed "completed dome" nor an incorrect all-AIR roof.
                Set<BlockPos> unfinished=Set.of(
                    new BlockPos(15,30,9),new BlockPos(15,30,10),
                    new BlockPos(15,30,11),new BlockPos(15,30,12),
                    new BlockPos(20,30,22));
                int builtY30=0;
                for(var cell:original.entrySet()){
                    BlockPos p=cell.getKey();
                    if(p.getY()!=30||unfinished.contains(p))continue;
                    world.setBlock(abs(origin,p),cell.getValue(),2);
                    builtY30++;
                }
                StringBuilder csvY30=new StringBuilder(
                    "x,y,z,intendedBlock,physicalBlock,collisionTop\n");
                for(var cell:ordered){
                    BlockPos local=cell.getKey();
                    BlockPos p=abs(origin,local);
                    BlockState actual=world.getBlockState(p);
                    VoxelShape shape=actual.getCollisionShape(world,p);
                    csvY30.append(local.getX()).append(',')
                        .append(local.getY()).append(',')
                        .append(local.getZ()).append(',')
                        .append('"').append(cell.getValue()).append('"')
                        .append(',').append('"').append(actual).append('"')
                        .append(',').append(shape.isEmpty()
                                ?0:shape.max(Direction.Axis.Y)).append('\n');
                }
                Files.writeString(out.resolve("reconstructed_y30_stall.csv"),
                        csvY30,StandardCharsets.UTF_8);
                // Assess every measured start/goal against THIS rebuilt
                // Y30 pocket, with actual server collision and A* edges.
                StringBuilder reportY30=new StringBuilder(
                    "Reconstruction of actual 3423/3493 Y30 stall;"
                    +" missing=(15,30,9),(15,30,10),(15,30,11),"
                    +"(15,30,12),(20,30,22)\n");
                int n=0;
                for(Pair focus:CASES){
                    BuilderGolem worker=workers.get(n++);
                    BlockPos target=abs(origin,focus.target());
                    Set<BlockPos> component=WorkerNavigation.groundReachableFrom(
                            world,worker,worker.blockPosition());
                    List<BlockPos> poses=new ArrayList<>();
                    int candidate=0,safe=0;
                    for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++)
                        for(int dy=0;dy<=1;dy++){
                            BlockPos p=target.offset(dx,dy,dz);
                            if(p.equals(target)||p.distSqr(target)>=14
                                    ||!WorkerNavigation.canStandOn(world,worker,p))
                                continue;
                            candidate++;
                            if(!BuilderConstructionPlanner.safeStand(
                                    world,worker,target,p))continue;
                            safe++;poses.add(p);
                        }
                    var path=WorkerRoutePlanner.plan(
                            worker.blockPosition(),poses,
                            p->WorkerNavigation.canStandOn(world,worker,p),
                            p->WorkerNavigation.surveyFloorHeight(world,p),null);
                    long inComponent=poses.stream()
                            .filter(component::contains).count();
                    String line="[Y30 WORKER] "+focus.name()
                        +" worker="+focus.start()+" actualFeet="
                        +rel(origin,worker.blockPosition())
                        +" target="+focus.target()
                        +" targetState="+original.get(focus.target())
                        +" underlying="+world.getBlockState(target.below())
                        +" candidates="+candidate+" safePoses="+safe
                        +" connectedPoses="+inComponent
                        +" componentNodes="+component.size()
                        +" route="+nodePath(origin,path);
                    reportY30.append(line).append('\n');
                    System.out.println(line);
                    for(int y:new int[]{26,28,29,30,31})
                        reportY30.append(grid(world,origin,y,focus,component));
                }
                Files.writeString(out.resolve("reconstructed_y30_routes.txt"),
                        reportY30,StandardCharsets.UTF_8);
                System.out.println("[Y30 RECONSTRUCTED MODEL] original=3537"
                        +" builtY30="+builtY30+" remainingY30="+unfinished.size());
                h.succeed();
            }catch(Exception e){h.fail("Dome survey failed: "+e);}
        });
    }
    /** No hand-coded physical waypoints: just the actual domed terrain,
     * the surveyed North start and a VALID safe work stance. Production
     * terrain A* decides which side to approach and whether to go up/down.
     */
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=950,skyAccess=true)
    public void northRoofPhysicalNavigationFollowsSurveyedTerrain(
            GameTestHelper h){
        ServerLevel world=h.getLevel();
        BlockPos origin=h.absolutePos(new BlockPos(8,2,8));
        world.getChunkSource().addTicketWithRadius(
                net.minecraft.server.level.TicketType.FORCED,
                new net.minecraft.world.level.ChunkPos(origin.getX()>>4,
                        origin.getZ()>>4),4);
        for(int x=4;x<=42;x++)for(int z=4;z<=42;z++)
            h.setBlock(x,1,z,Blocks.STONE);
        try{
            for(var b:LitematicFileReader.read(
                    BuilderDomeFixture.unpack()).blocks()){
                if(b.y()>=30||BuilderVegetation.ignoreSchematicState(
                        b.state()))continue;
                var r=BuilderBlockResolver.resolve(
                        b.state(),BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
                if(r!=null)world.setBlock(origin.offset(
                        b.x(),b.y(),b.z()),r.state(),2);
            }
        }catch(Exception ex){h.fail("North route model failed "+ex);return;}
        BlockPos start=origin.offset(8,26,11);
        BlockPos goal=origin.offset(14,30,14);
        var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        // Exact measured north roof handoff fractional footing. Center
        // of this block collides with architectural details and is NOT a
        // valid substitute for where the worker actually stood.
        worker.setPos(origin.getX()+8.342,origin.getY()+26,
                origin.getZ()+11.602);
        world.addFreshEntity(worker);
        boolean[] ascended={false};
        h.onEachTick(()->{
            if(!worker.isAlive()||worker.isNoGravity()
                    ||worker.getY()<origin.getY()+24){
                h.fail("Worker lost ground on real north roof");return;
            }
            if(h.getTick()==20){
                // The mob may have settled onto a true higher collision
                // surface by its first grounded tick; never veto movement
                // using the STATIC spawn Y in place of actual feet.
                System.out.println("[NORTH REAL ROUTE] initialFeet="
                        +rel(origin,worker.blockPosition())
                        +" grounded="+worker.onGround()
                        +" goalStand="+WorkerNavigation.canStandOn(
                                world,worker,goal)
                        +" fullRoute="+WorkerNavigation.hasGroundRouteFrom(
                                world,worker,worker.blockPosition(),List.of(goal)));
            }
            if(h.getTick()>=20&&h.getTick()%5==0)
                WorkerNavigation.routeToExact(world,worker,goal,1);
            if(worker.getY()>origin.getY()+28)ascended[0]=true;
            if(worker.onGround()&&worker.distanceToSqr(
                    goal.getX()+.5,goal.getY(),goal.getZ()+.5)<.14){
                if(!ascended[0]){
                    h.fail("Did not physically climb the correct upper route");
                    return;
                }
                System.out.println("[NORTH REAL ROUTE] PASS physically "
                        +"replanned original upper roof in "+h.getTick()
                        +" ticks; no fly, teleport, or predefined path");
                h.succeed();return;
            }
            if(h.getTick()>875)
                h.fail("Real North path stalled feet="
                        +rel(origin,worker.blockPosition())
                        +" "+WorkerNavigation.debugStatus(worker)
                        +" "+WorkerNavigation.diagnoseExecutableStand(
                                world,worker,List.of(goal))
                        +" "+WorkerNavigation.diagnoseGroundedEdge(
                                world,worker,goal));
        });
    }

    /** Replay the actual 3424/3493 worker's fractional roof footing and
     * physically follow each intended rise toward the missing Y30 cap.
     * Record the specific failed leg instead of declaring a coarse A*
     * route successful before Minecraft has executed it.
     */
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=1600,skyAccess=true)
    public void actualY30StalledFootingCanExecuteRoofAccessClimb(
            GameTestHelper h){
        ServerLevel world=h.getLevel();
        BlockPos origin=h.absolutePos(new BlockPos(8,2,8));
        world.getChunkSource().addTicketWithRadius(
                net.minecraft.server.level.TicketType.FORCED,
                new net.minecraft.world.level.ChunkPos(origin.getX()>>4,
                        origin.getZ()>>4),4);
        for(int x=4;x<=42;x++)for(int z=4;z<=42;z++)
            h.setBlock(x,1,z,Blocks.STONE);
        Set<BlockPos> unfinished=Set.of(
                new BlockPos(15,30,9),new BlockPos(15,30,10),
                new BlockPos(15,30,11),new BlockPos(15,30,12));
        try{
            for(var b:LitematicFileReader.read(
                    BuilderDomeFixture.unpack()).blocks()){
                if(b.y()>30||b.y()==30&&unfinished.contains(
                        new BlockPos(b.x(),b.y(),b.z()))
                        ||BuilderVegetation.ignoreSchematicState(
                                b.state()))continue;
                var resolved=BuilderBlockResolver.resolve(b.state(),
                        BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
                if(resolved!=null)world.setBlock(origin.offset(
                        b.x(),b.y(),b.z()),resolved.state(),2);
            }
        }catch(Exception e){h.fail("Exact Y30 roof fixture failed "+e);return;}
        // A prefilled roof is not the same as the live partially built
        // snapshot. In the captured 3,537-step live snapshot these two
        // cells are not scheduled roof solids; leaving glass in them
        // spawns the worker with its head inside a collision box and
        // cannot test navigation at all. Preserve actual free headroom.
        world.setBlock(origin.offset(8,27,10),Blocks.AIR.defaultBlockState(),2);
        world.setBlock(origin.offset(9,27,10),Blocks.AIR.defaultBlockState(),2);
        var mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        mob.setPos(origin.getX()+8.834945218637586,origin.getY()+26,
                origin.getZ()+10.432437775656581);
        world.addFreshEntity(mob);
        List<BlockPos> roofSteps=List.of(
                origin.offset(9,26,10),origin.offset(10,27,10),
                origin.offset(11,27,10),
                // The old coarse waypoint (12,28,10) is NOT a valid
                // centered landing. The native physical path correctly
                // detours north via supported (11,27,9), (12,28,9),
                // then rises onto the diorite at (12,29,10).
                origin.offset(11,27,9),origin.offset(12,28,9),
                origin.offset(12,29,10),origin.offset(15,30,10));
        BlockPos first=roofSteps.get(0);
        final int[] leg={0};
        h.onEachTick(()->{
            if(!mob.isAlive()||mob.isNoGravity()
                    ||mob.getY()<origin.getY()+24){
                h.fail("Exact roof worker lost safe physical gravity/footing");
                return;
            }
            if(h.getTick()==20){
                if(!world.noCollision(mob,mob.getBoundingBox().deflate(.015))){
                    h.fail("Fractional roof test began inside a solid block");
                    return;
                }
                for(int dz=-2;dz<=2;dz++){
                    StringBuilder row=new StringBuilder("[EXACT ROOF CELLS] dz="+dz);
                    for(int dx=-2;dx<=2;dx++){
                        BlockPos p=first.offset(dx,0,dz);
                        row.append(" {dx=").append(dx)
                            .append(" stand=").append(WorkerNavigation.canStandOn(world,mob,p))
                            .append(" feet=").append(world.getBlockState(p))
                            .append(" below=").append(world.getBlockState(p.below()))
                            .append(" head=").append(world.getBlockState(p.above()))
                            .append(" floor=").append(WorkerNavigation.surveyFloorHeight(world,p))
                            .append('}');
                    }
                    System.out.println(row);
                }
                System.out.println("[EXACT ROOF FIRST] start="+mob.position()
                        +" grounded="+mob.onGround()
                        +" centerStand="+WorkerNavigation.canStandOn(
                                world,mob,mob.blockPosition())
                        +" firstStand="+WorkerNavigation.canStandOn(
                                world,mob,first)
                        +" native="+WorkerNavigation.canReachExact(
                                world,mob,first)
                        +" survey="+WorkerNavigation.diagnoseGroundedEdge(
                                world,mob,first));
            }
            BlockPos next=roofSteps.get(leg[0]);
            if(h.getTick()>=20&&h.getTick()%5==0)
                WorkerNavigation.routeToExact(world,mob,next,1.0);
            double floor=WorkerNavigation.surveyFloorHeight(world,next);
            if(mob.onGround()&&Double.isFinite(floor)
                    &&mob.distanceToSqr(next.getX()+.5,
                            floor,next.getZ()+.5)<.15){
                System.out.println("[EXACT ROOF LEG] PASS "+leg[0]
                        +" target="+next.subtract(origin).toShortString()
                        +" ticks="+h.getTick());
                if(++leg[0]==roofSteps.size()){
                    System.out.println("[EXACT ROOF CLIMB] PASS physical "
                            +"access to Y30 from measured fractional feet");
                    h.succeed();
                }
                return;
            }
            if(h.getTick()>1500)
                h.fail("Actual fractional Y30 roof leg="+leg[0]
                        +" target="+next.subtract(origin).toShortString()
                        +" stalled "
                        +mob.position()+" "+WorkerNavigation.debugStatus(mob)
                        +" "+WorkerNavigation.diagnoseExecutableStand(
                                world,mob,List.of(next)));
        });
    }

    /** The seven grounded legs above prove the route exists. This paired
     * test asks the production navigator to discover it without any
     * hand-fed tile sequence, exposing precisely the planning gap.
     */
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=1600,skyAccess=true)
    public void actualY30FootingCanRouteDirectlyToRoof(GameTestHelper h){
        ServerLevel world=h.getLevel();
        BlockPos origin=h.absolutePos(new BlockPos(8,2,8));
        world.getChunkSource().addTicketWithRadius(
                net.minecraft.server.level.TicketType.FORCED,
                new net.minecraft.world.level.ChunkPos(origin.getX()>>4,
                        origin.getZ()>>4),4);
        for(int x=4;x<=42;x++)for(int z=4;z<=42;z++)
            h.setBlock(x,1,z,Blocks.STONE);
        Set<BlockPos> unfinished=Set.of(
                new BlockPos(15,30,9),new BlockPos(15,30,10),
                new BlockPos(15,30,11),new BlockPos(15,30,12));
        try{
            for(var b:LitematicFileReader.read(
                    BuilderDomeFixture.unpack()).blocks()){
                if(b.y()>30||b.y()==30&&unfinished.contains(
                        new BlockPos(b.x(),b.y(),b.z()))
                        ||BuilderVegetation.ignoreSchematicState(
                                b.state()))continue;
                var resolved=BuilderBlockResolver.resolve(b.state(),
                        BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
                if(resolved!=null)world.setBlock(origin.offset(
                        b.x(),b.y(),b.z()),resolved.state(),2);
            }
        }catch(Exception e){h.fail("Direct roof fixture failed "+e);return;}
        // Match the unoccupied first-leg headroom in the proven physical
        // route, so direct and incremental navigation face identical blocks.
        world.setBlock(origin.offset(8,27,10),Blocks.AIR.defaultBlockState(),2);
        world.setBlock(origin.offset(9,27,10),Blocks.AIR.defaultBlockState(),2);
        BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        // A second live 3424/3493 capture measured this Builder already
        // past the first narrow ledge, with valid centered footing.
        mob.setPos(origin.getX()+9.366045948117971,origin.getY()+26,
                origin.getZ()+10.483065175823867);
        world.addFreshEntity(mob);
        // This is the one safe same-Y placement stance chosen by the
        // real work-approach selector for BOTH missing north roof cells.
        // Reaching the future block itself is not enough to place it.
        BlockPos goal=origin.offset(12,30,11);
        final int[] accepted={0};
        h.onEachTick(()->{
            if(!mob.isAlive()||mob.isNoGravity()
                    ||mob.getY()<origin.getY()+24){
                h.fail("Direct roof worker lost safe physical footing");return;
            }
            if(h.getTick()==20){
                for(int z:new int[]{9,10}){
                    BlockPos work=origin.offset(15,30,z);
                    var rule=(java.util.function.Predicate<BlockPos>)at->
                            BuilderConstructionPlanner.safeStand(world,mob,work,at);
                    BlockPos approach=BuilderPlacementSpace.approachWithRules(
                            world,mob,work,rule);
                    System.out.println("[INITIAL ROOF WORK POSE] targetZ="+z
                            +" selected="+(approach==null?"none":
                                approach.subtract(origin).toShortString())
                            +" status="+BuilderPlacementSpace.diagnoseApproach(
                                    world,mob,work,rule));
                }
            }
            if(h.getTick()>=20&&h.getTick()%5==0
                    &&WorkerNavigation.routeToExact(world,mob,goal,1))
                accepted[0]++;
            if(mob.onGround()&&mob.distanceToSqr(goal.getX()+.5,
                    WorkerNavigation.surveyFloorHeight(world,goal),
                    goal.getZ()+.5)<.15){
                for(int z:new int[]{9,10}){
                    BlockPos work=origin.offset(15,30,z);
                    if(!BuilderConstructionPlanner.safeStand(
                            world,mob,work,mob.blockPosition())
                            ||!BuilderPlacementSpace.placementHeight(work,mob)
                            ||!BuilderPlacementSpace.horizontalClear(work,mob)){
                        h.fail("Reached roof but not a safe physical work "
                                +"stance for "+work.subtract(origin)
                                +" feet="+mob.position());return;
                    }
                }
                System.out.println("[DIRECT ROOF] PASS auto-planned work pose "
                        +"ticks="+h.getTick()+" accepted="+accepted[0]);
                for(int z:new int[]{9,10}){
                    BlockPos work=origin.offset(15,30,z);
                    var stanceRule=(java.util.function.Predicate<BlockPos>)at->
                            BuilderConstructionPlanner.safeStand(
                                    world,mob,work,at);
                    BlockPos normal=BuilderPlacementSpace.approachWithRules(
                            world,mob,work,stanceRule);
                    List<BlockPos> lower=new ArrayList<>();
                    for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++){
                        BlockPos at=work.offset(dx,-1,dz);
                        if(at.distSqr(work)>=14||!stanceRule.test(at)
                                ||!WorkerNavigation.canStandOn(world,mob,at))continue;
                        lower.add(at);
                    }
                    BlockPos lowerRoute=WorkerNavigation.selectExecutableStand(
                            world,mob,lower);
                    System.out.println("[ROOF WORK POSE] targetZ="+z
                            +" upper="+(normal==null?"none":
                                normal.subtract(origin).toShortString())
                            +" lowerCandidates="+lower.size()
                            +" lowerExecutable="+(lowerRoute==null?"none":
                                lowerRoute.subtract(origin).toShortString())
                            +" status="+BuilderPlacementSpace.diagnoseApproach(
                                    world,mob,work,stanceRule));
                }
                h.succeed();return;
            }
            if(h.getTick()>1500)
                h.fail("Automatic Y30 roof route stalled "
                        +mob.position()+" accepted="+accepted[0]
                        +" "+WorkerNavigation.debugStatus(mob)
                        +" "+WorkerNavigation.diagnoseExecutableStand(
                                world,mob,List.of(goal)));
        });
    }

}
