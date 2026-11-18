package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Navigation-only, PHYSICAL eight-builder benchmark on the finished original
 * Dome. No construction Planner is created. Targets are exact supported feet
 * poses above FULL CUBE blocks. Each worker independently traverses the
 * same reproducibly random 24-point course in a different order, including
 * distant ground goals that require descending and then climbing again.
 *
 * The test does not teleport workers to goals or accept nearby hovering.
 * Assessments succeed as GameTests even if individual routes fail: the
 * observed physical failures are the result, not a broken test fixture.
 */
public final class BuilderNNDomeWaypointGameTest {
    private static final Gson JSON=new Gson();
    private static final int REQUIRED=3493, SOURCE=3537;
    private static final int MAX_EPISODE_TICKS=750;
    private record Expected(BlockPos pos,BlockState state){}
    private record Target(BlockPos feet,BlockPos support,boolean roof,int id){}
    private record Visit(int worker,int attempt,int destination,boolean roof,boolean arrived,
            String outcome,int ticks,double distance,double straightStart,
            double highestAboveGoal,boolean passedX,boolean passedZ,
            String finalPosition){}
    private static final class Walker {
        final int id; final BuilderGolem entity;
        int attempt,successful,failed,roofReached,groundReached, rewarded;
        long started=-1,recoveringAt=-1;
        BlockPos destination;
        Vec3 departure,previous;
        double traveled,highest;
        boolean overshotX,overshotZ,retired;
        Walker(int id,BuilderGolem entity){this.id=id;this.entity=entity;}
    }

    @SuppressWarnings("removal")
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=1000000000,skyAccess=true,required=false)
    public void navigateTwentyFourFinishedDomeWaypoints(GameTestHelper h){
        try{run(h);}catch(Exception e){
            h.fail("Finished Dome waypoint setup failed: "+e);
            e.printStackTrace();
        }
    }
    private static int setting(String name,int fallback,int min,int max){
        int number=Integer.parseInt(System.getenv().getOrDefault(name,""+fallback));
        if(number<min||number>max)throw new IllegalArgumentException(name+"="+number);
        return number;
    }
    private static boolean fullSupport(ServerLevel level,BlockPos support){
        if(!level.hasChunkAt(support))return false;
        BlockState state=level.getBlockState(support);
        // A stair, slab, fence or diagonal shape never masquerades as a
        // full-faced roof block, even if a golem could stand on its bounds.
        return state.isFaceSturdy(level,support,Direction.UP)
                &&Block.isShapeFullBlock(state.getCollisionShape(level,support));
    }
    private static Vec3 standingPose(ServerLevel level,BlockPos feet){
        return new Vec3(feet.getX()+.5,WorkerNavigation.surveyFloorHeight(level,feet),
                feet.getZ()+.5);
    }
    private static boolean actualArrival(ServerLevel level,BuilderGolem worker,BlockPos feet){
        double floor=WorkerNavigation.surveyFloorHeight(level,feet);
        return Double.isFinite(floor)&&worker.onGround()
                &&!BuilderFlight.active(worker)
                &&WorkerNavigation.canStandOn(level,worker,feet)
                &&worker.position().distanceToSqr(
                        new Vec3(feet.getX()+.5,floor,feet.getZ()+.5))<.28*.28;
    }
    private static void record(Visit visit){
        System.out.println("[NN DOME WAYPOINT VISIT] "+JSON.toJson(visit));
    }
    private static void updateGeometry(Walker w,Target t){
        Vec3 p=w.entity.position();
        if(w.previous!=null){
            double travel=p.distanceTo(w.previous);
            if(travel<3&&Double.isFinite(travel))w.traveled+=travel;
        }
        w.previous=p;
        w.highest=Math.max(w.highest,p.y-(t.feet().getY()));
        if(w.departure!=null){
            double sx=w.departure.x,sz=w.departure.z,tx=t.feet().getX()+.5,
                   tz=t.feet().getZ()+.5;
            w.overshotX|=(sx<tx-.75&&p.x>tx+.75)||(sx>tx+.75&&p.x<tx-.75);
            w.overshotZ|=(sz<tz-.75&&p.z>tz+.75)||(sz>tz+.75&&p.z<tz-.75);
        }
    }
    private static void finishVisit(ServerLevel level,Walker w,Target target,
            long tick,boolean arrived,String reason){
        int elapsed=(int)(tick-w.started);
        double direct=w.departure==null?0:w.departure.distanceTo(standingPose(level,target.feet()));
        if(arrived){
            w.successful++;
            if(target.roof())w.roofReached++;else w.groundReached++;
            if(BuilderNNRoofManeuverPolicy.waypointReached(level,w.entity,target.feet()))
                w.rewarded++;
        }else{
            w.failed++;
            BuilderMultiLegTravel.abortWaypoint(level,w.entity,"waypoint_"+reason);
            BuilderNNRoofManeuverPolicy.handedOff(w.entity.getUUID());
        }
        record(new Visit(w.id,w.attempt,target.id(),target.roof(),arrived,
                reason,elapsed,w.traveled,direct,w.highest,w.overshotX,w.overshotZ,
                w.entity.position().toString()));
        w.attempt++;
        w.started=-1;w.destination=null;
        if(BuilderFlight.active(w.entity)||!w.entity.onGround())
            w.recoveringAt=tick;
        else{
            BuilderMultiLegTravel.clear(w.entity);
            WorkerNavigation.stop(w.entity);
        }
    }
    private static boolean tooClose(BlockPos feet,List<BlockPos> selected){
        for(BlockPos existing:selected)
            if(Math.abs(feet.getX()-existing.getX())<=2
                    &&Math.abs(feet.getZ()-existing.getZ())<=2
                    &&Math.abs(feet.getY()-existing.getY())<=3)return true;
        return false;
    }
    private static List<Target> course(ServerLevel level,BuilderGolem probe,
            BlockPos origin,BlockPos arena,int side,
            List<Expected> expected,int total,long seed){
        int roofCount=(total*3)/4;
        List<BlockPos> roofs=new ArrayList<>();
        for(Expected ex:expected){
            BlockPos at=ex.pos();
            if(at.getY()-origin.getY()<12||!fullSupport(level,at))continue;
            BlockPos feet=at.above();
            if(WorkerNavigation.canStandOn(level,probe,feet)
                    &&Double.isFinite(WorkerNavigation.surveyFloorHeight(level,feet)))
                roofs.add(feet);
        }
        if(roofs.size()<roofCount)
            throw new IllegalStateException("Not enough REAL upper full-block roof stances: "
                    +roofs.size()+" for "+roofCount+" targets");
        // Fixed random seed for exact A/B repeatability; use supported
        // positions from the BUILT world, not just the blueprint's intent.
        Collections.shuffle(roofs,new Random(seed));
        List<BlockPos> chosen=new ArrayList<>();
        for(BlockPos p:roofs){
            if(!tooClose(p,chosen))chosen.add(p);
            if(chosen.size()>=roofCount)break;
        }
        if(chosen.size()<roofCount)for(BlockPos p:roofs){
            if(!chosen.contains(p))chosen.add(p);
            if(chosen.size()>=roofCount)break;
        }
        Collections.shuffle(chosen,new Random(seed^0x6A1F232AL));
        List<BlockPos> grounds=new ArrayList<>();
        for(int i=4;i<side-3;i+=4){
            for(BlockPos support:List.of(
                    arena.offset(i,1,4),arena.offset(i,1,side-4),
                    arena.offset(4,1,i),arena.offset(side-4,1,i))){
                BlockPos feet=support.above();
                if(fullSupport(level,support)
                        &&WorkerNavigation.canStandOn(level,probe,feet)
                        &&Double.isFinite(WorkerNavigation.surveyFloorHeight(level,feet)))
                    grounds.add(feet);
            }
        }
        Collections.shuffle(grounds,new Random(seed^0x52A77BL));
        List<BlockPos> groundChosen=new ArrayList<>();
        for(BlockPos p:grounds){
            if(!tooClose(p,groundChosen))groundChosen.add(p);
            if(groundChosen.size()>=total-roofCount)break;
        }
        if(groundChosen.size()<total-roofCount)
            throw new IllegalStateException("Not enough full-faced exterior ground stances");
        List<Target> results=new ArrayList<>();
        int r=0,g=0;
        // Ground points every ~four destinations ensure we test descent
        // and re-ascent, not a single one-way climb.
        for(int i=0;i<total;i++){
            boolean ground=(i+1)%4==0&&g<groundChosen.size()
                    ||r>=roofCount;
            BlockPos feet=ground?groundChosen.get(g++):chosen.get(r++);
            results.add(new Target(feet,feet.below(),!ground,i));
        }
        return List.copyOf(results);
    }
    private void run(GameTestHelper h)throws Exception{
        ServerLevel level=h.getLevel();
        int targetCount=setting("NN_WAYPOINT_COUNT",24,10,30);
        int crewCount=setting("NN_WAYPOINT_CREW",8,1,8);
        long seed=setting("NN_WAYPOINT_SEED",260218,1,1000000);
        int episodeTicks=setting("NN_WAYPOINT_EPISODE_TICKS",
                MAX_EPISODE_TICKS,200,2400);
        int maxTicks=setting("NN_WAYPOINT_TOTAL_TICKS",24000,1000,75000);
        int maxSeconds=setting("NN_WAYPOINT_WALL_SECONDS",480,30,900);
        var original=BuilderDomeFixture.unpack();
        LitematicFileReader.Schematic schematic;
        try{schematic=LitematicFileReader.read(original);}
        finally{Files.deleteIfExists(original);}
        if(schematic.blocks().size()!=SOURCE)throw new IllegalStateException("Wrong Dome source");
        var cells=new ArrayList<>(schematic.blocks());
        cells.addAll(schematic.airBlocks());
        int minX=cells.stream().mapToInt(LitematicFileReader.Block::x).min().orElseThrow();
        int minY=cells.stream().mapToInt(LitematicFileReader.Block::y).min().orElseThrow();
        int minZ=cells.stream().mapToInt(LitematicFileReader.Block::z).min().orElseThrow();
        BlockPos arena=new BlockPos(1024,-57,1024);
        BlockPos origin=arena.offset(10-minX,2-minY,10-minZ);
        int side=72;
        var center=arena.offset(36,2,36);
        level.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.FORCED,
                new net.minecraft.world.level.ChunkPos(center.getX()>>4,center.getZ()>>4),5);
        for(int cx=(arena.getX()>>4)-1;cx<=((arena.getX()+side+1)>>4)+1;cx++)
            for(int cz=(arena.getZ()>>4)-1;cz<=((arena.getZ()+side+1)>>4)+1;cz++)
                level.getChunk(cx,cz);
        for(int x=1;x<=side;x++)for(int z=1;z<=side;z++){
            level.setBlock(arena.offset(x,1,z),Blocks.STONE.defaultBlockState(),2);
            for(int y=2;y<=53;y++){
                var at=arena.offset(x,y,z);
                if(!level.getBlockState(at).isAir())
                    level.setBlock(at,Blocks.AIR.defaultBlockState(),2);
            }
        }
        List<Expected> expected=new ArrayList<>();
        int ignored=0,vegetationPlaced=0;
        // Build the FULL intended structure immediately, in bottom-to-top
        // order, using the original real source state resolver. This is
        // fixture preparation, NOT credited golem construction.
        cells=new ArrayList<>(schematic.blocks());
        cells.sort(Comparator.comparingInt(LitematicFileReader.Block::y));
        for(var b:cells){
            var support=origin.offset(b.x(),b.y(),b.z());
            var resolved=BuilderBlockResolver.resolve(b.state(),
                    BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
            if(BuilderVegetation.ignoreSchematicState(b.state())){
                ignored++;
                if(resolved!=null){
                    level.setBlock(support,resolved.state(),2);
                    vegetationPlaced++;
                }
                continue;
            }
            var targetState=BuilderBlockResolver.expectedState(b.state(),
                    BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
            if(targetState==null||resolved==null)
                throw new IllegalStateException("Unresolved source state "+b.state());
            level.setBlock(support,resolved.state(),2);
            expected.add(new Expected(support,targetState));
        }
        if(expected.size()!=REQUIRED||ignored!=44)
            throw new IllegalStateException("Dome fixture mismatch required="+expected.size()+" ignored="+ignored);
        long correct=expected.stream().filter(b->BuilderPistonWork.sourceMatches(
                level.getBlockState(b.pos()),b.state())).count();
        if(correct!=REQUIRED)
            throw new IllegalStateException("FULL Dome fixture contains "+
                    correct+"/"+REQUIRED+" verified states, cannot assess navigation");
        List<Walker> workers=new ArrayList<>();
        for(int i=0;i<crewCount;i++){
            BuilderGolem golem=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
            BlockPos spawn=arena.offset(6,2,10+i*6);
            golem.setUUID(new UUID(0x4E4E574159504F49L,100+i));
            golem.getRandom().setSeed(seed+111+i);
            golem.setPos(spawn.getX()+.5,spawn.getY(),spawn.getZ()+.5);
            level.addFreshEntity(golem);
            workers.add(new Walker(i,golem));
        }
        List<Target> route=course(level,workers.getFirst().entity,
                origin,arena,side,expected,targetCount,seed);
        int roofGoals=(int)route.stream().filter(Target::roof).count();
        System.out.println("[NN DOME WAYPOINT START] mode="+
                System.getenv().getOrDefault("NN_WAYPOINT_MODE","unknown")
                +" original=3537 verified="+correct+" vegetation="+vegetationPlaced
                +" workers="+crewCount+" targets="+targetCount+" roof="+roofGoals
                +" ground="+(targetCount-roofGoals)+" seed="+seed+" arena="+arena
                +" targetSelection=full_cube_supported_face_and_standable_feet");
        for(Target target:route)
            System.out.println("[NN DOME WAYPOINT TARGET] id="+target.id()
                    +" zone="+(target.roof()?"ROOF":"GROUND")
                    +" local="+target.feet().subtract(origin)
                    +" support="+target.support().subtract(origin)
                    +" supported="+WorkerNavigation.canStandOn(
                            level,workers.getFirst().entity,target.feet()));
        final long start=h.getTick(),started=System.nanoTime();
        final boolean[] finished={false};
        h.onEachTick(()->{
            if(finished[0])return;
            long now=h.getTick();
            for(Walker w:workers){
                if(w.retired||w.attempt>=route.size())continue;
                BuilderGolem worker=w.entity;
                if(!worker.isAlive()){
                    w.retired=true;
                    System.out.println("[NN DOME WAYPOINT RETIRE] worker="+w.id+" reason=dead");
                    continue;
                }
                if(w.recoveringAt>=0){
                    if(worker.onGround()&&!BuilderFlight.active(worker)){
                        WorkerNavigation.stop(worker);
                        w.recoveringAt=-1;
                    }else if(now-w.recoveringAt>450){
                        w.retired=true;
                        System.out.println("[NN DOME WAYPOINT RETIRE] worker="+w.id
                                +" reason=unable_to_land pose="+worker.position());
                    }else if(now%15==0)BuilderFlight.land(level,worker,false);
                    continue;
                }
                int goalIndex=Math.floorMod(w.id*3-w.attempt,route.size());
                Target target=route.get(goalIndex);
                if(w.started<0){
                    w.started=now;w.destination=target.feet();
                    w.departure=worker.position();w.previous=worker.position();
                    w.traveled=0;w.highest=Double.NEGATIVE_INFINITY;
                    w.overshotX=false;w.overshotZ=false;
                }
                updateGeometry(w,target);
                if(actualArrival(level,worker,target.feet())){
                    finishVisit(level,w,target,now,true,"ARRIVED_VERIFIED_SUPPORT");
                    continue;
                }
                long elapsed=now-w.started;
                if(elapsed>=episodeTicks){
                    System.out.println("[NN DOME WAYPOINT STALL] worker="+w.id
                            +" goal="+goalIndex+" feet="+worker.position()
                            +" target="+target.feet()+" elapsed="+elapsed
                            +" multileg="+BuilderMultiLegTravel.traceStatus(worker)
                            +" flight="+BuilderFlight.traceStatus(worker)
                            +" nav="+WorkerNavigation.debugStatus(worker));
                    finishVisit(level,w,target,now,false,"PER_GOAL_TIMEOUT");
                    continue;
                }
                if(now%7==0){
                    boolean multiLeg=BuilderMultiLegTravel.stand(level,worker,target.feet());
                    if(!multiLeg&&!BuilderFlight.active(worker)
                            &&!BuilderGrappleAccess.active(worker))
                        WorkerNavigation.routeToExact(level,worker,target.feet(),1.0);
                }
            }
            boolean complete=workers.stream().allMatch(w->w.retired||w.attempt>=route.size());
            boolean timeout=now-start>=maxTicks
                    ||(System.nanoTime()-started)/1_000_000_000L>=maxSeconds;
            if(now%1000==0||complete||timeout){
                int succeeded=workers.stream().mapToInt(w->w.successful).sum();
                int failed=workers.stream().mapToInt(w->w.failed).sum();
                System.out.println("[NN DOME WAYPOINT PROGRESS] tick="+(now-start)
                        +" successes="+succeeded+" failures="+failed
                        +" planned="+(crewCount*targetCount)
                        +" active="+workers.stream().filter(w->!w.retired
                                &&w.attempt<route.size()).count());
            }
            if(!complete&&!timeout)return;
            for(Walker w:workers)
                if(!w.retired&&w.started>=0&&w.attempt<route.size()){
                    Target target=route.get(Math.floorMod(w.id*3-w.attempt,route.size()));
                    finishVisit(level,w,target,now,false,"TEST_WALL_OR_TICK_LIMIT");
                }
            int successes=workers.stream().mapToInt(w->w.successful).sum();
            int failures=workers.stream().mapToInt(w->w.failed).sum();
            int roofs=workers.stream().mapToInt(w->w.roofReached).sum();
            int grounds=workers.stream().mapToInt(w->w.groundReached).sum();
            int rewarded=workers.stream().mapToInt(w->w.rewarded).sum();
            int retired=(int)workers.stream().filter(w->w.retired).count();
            long intact=expected.stream().filter(b->BuilderPistonWork.sourceMatches(
                    level.getBlockState(b.pos()),b.state())).count();
            BuilderNNRoofManeuverPolicy.flush();
            String outcome=successes==targetCount*crewCount
                    ?"ALL_WAYPOINTS_REACHED":timeout?"TIME_LIMIT":"PARTIAL_ROUTE_FAILURES";
            System.out.println("[NN DOME WAYPOINT RESULT] outcome="+outcome
                    +" expected="+(targetCount*crewCount)
                    +" success="+successes+" failed="+failures
                    +" roofSuccess="+roofs+" groundSuccess="+grounds
                    +" rewardedNeuralManeuvers="+rewarded+" retired="+retired
                    +" verifiedDomeIntact="+intact+"/"+REQUIRED
                    +" ticks="+(now-start)+" mode="+
                    System.getenv().getOrDefault("NN_WAYPOINT_MODE","unknown"));
            finished[0]=true;
            h.succeed(); // Assessment: a failed navigation route is DATA.
        });
    }
}
