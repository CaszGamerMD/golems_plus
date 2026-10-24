package com.caszgamermd.golemsplus.worker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * One shared, server-side ground-navigation controller for every worker.
 *
 * - Do not rebuild a path on every job tick.
 * - Open an actual wooden door before pushing against its collision shape.
 * - Close only doors the worker opened, after everyone clears the doorway.
 * - Route to standing tiles NEXT TO solid work blocks, not their centers.
 * - Detect lack of forward progress and retry another approach tile.
 * - Yield briefly to an approaching worker instead of body-pushing.
 *
 * Transient tracking does not belong in saves; only the actual job and cargo
 * are persisted. A fresh worker can resume navigating safely after reload.
 */
public final class WorkerNavigation {
    private static final Map<PathfinderMob,Progress> states=new WeakHashMap<>();
    private static final int STALL_TICKS=40;
    // Cheap 5-tick waypoint inspection; full A* only when the goal changes,
    // the path becomes obstructed, or progress genuinely stops.
    private static final int ROUTE_RETRY=10;
    private static final int CHECK_TICKS=5;
    private static final int LOOK_AHEAD=4;
    private static final int MAX_DESTINATION_PATHS=12;
    private WorkerNavigation(){}

    private static final class Progress {
        BlockPos requested,approach,openedDoor;
        Vec3 previous;
        int stalled,attempts,wait,doorAge,nextRoute;
        int unsafeChecks,avoidUntil;
        BlockPos lastBad;
        boolean narrowDoorway;
        // Cached weighted route is transient: the actual job and cargo
        // remain persisted on the worker and may safely resume after reload.
        List<BlockPos> weightedRoute=List.of();
        int routeCursor;
    }
    private static Progress state(PathfinderMob mob){
        return states.computeIfAbsent(mob,m->new Progress());
    }
    /** Open wooden doors leave only 13/16 of a block of width. A .85
     * worker is physically too wide, so while it is *crossing a doorway*
     * its real collision box briefly narrows to .70. Away from a doorway
     * it resumes its normal .85 width. The visible model never changes.
     */
    public static boolean inDoorway(PathfinderMob mob){
        Progress progress=states.get(mob);
        return progress!=null&&progress.narrowDoorway;
    }
    private static void updateDoorClearance(ServerLevel level,PathfinderMob mob,Progress progress){
        BlockPos pos=mob.blockPosition();
        boolean near=false;
        if(progress.requested!=null||progress.openedDoor!=null)
            for(int dx=-2;dx<=2&&!near;dx++)
                for(int dy=-1;dy<=1&&!near;dy++)
                    for(int dz=-2;dz<=2&&!near;dz++){
                        BlockPos probe=pos.offset(dx,dy,dz);
                        if(!handDoor(level,probe))continue;
                        double distance=mob.distanceToSqr(probe.getX()+.5,
                                probe.getY()+.4,probe.getZ()+.5);
                        if(distance<4.0)near=true;
                    }
        if(progress.narrowDoorway!=near){
            progress.narrowDoorway=near;
            if(!near)mob.refreshDimensions();
        }
        if(near){
            // LivingEntity.getDimensions(Pose) is FINAL in 26.2.
            // Use a transient collision AABB for the door passage,
            // while leaving the entity's registered .85 dimensions and
            // visible rendering completely unchanged. The normal box
            // returns immediately once the doorway has been cleared.
            double half=.35;
            mob.setBoundingBox(new AABB(mob.getX()-half,mob.getY(),mob.getZ()-half,
                    mob.getX()+half,mob.getY()+mob.getBbHeight(),mob.getZ()+half));
        }
    }

    private static boolean handDoor(ServerLevel world,BlockPos pos){
        if(!world.hasChunkAt(pos))return false;
        BlockState block=world.getBlockState(pos);
        return block.getBlock() instanceof DoorBlock door
                &&door.type().canOpenByHand();
    }

    /** Check the floor and the full body clearance, not just whether the
     * center of an (x,z) tile is air. Vanilla's node-based paths can graze
     * corners or squeeze through gaps narrower than the actual worker.
     */
    private static boolean dangerous(BlockState state){
        return state.is(Blocks.MAGMA_BLOCK)||state.is(Blocks.CACTUS)
                ||state.is(Blocks.CAMPFIRE)||state.is(Blocks.SOUL_CAMPFIRE)
                ||state.is(Blocks.FIRE)||state.is(Blocks.SOUL_FIRE)
                ||state.is(Blocks.POWDER_SNOW)||state.is(Blocks.SWEET_BERRY_BUSH);
    }
    private static double floorAt(ServerLevel level,BlockPos feet){
        BlockPos below=feet.below();
        VoxelShape footing=level.getBlockState(below).getCollisionShape(level,below);
        if(footing.isEmpty())return Double.NaN;
        double y=below.getY()+footing.max(Direction.Axis.Y);
        VoxelShape inside=level.getBlockState(feet).getCollisionShape(level,feet);
        if(!inside.isEmpty()&&!handDoor(level,feet)){
            double height=inside.max(Direction.Axis.Y);
            if(height>.6001)return Double.NaN;
            y=Math.max(y,feet.getY()+height);
        }
        return y;
    }
    private static boolean standingTile(ServerLevel level,PathfinderMob mob,BlockPos feet){
        if(!level.hasChunkAt(feet)||!level.hasChunkAt(feet.above())
                ||!level.hasChunkAt(feet.below()))return false;
        BlockState state=level.getBlockState(feet);
        if(!level.getFluidState(feet).isEmpty()||dangerous(state)
                ||dangerous(level.getBlockState(feet.below())))return false;
        double floor=floorAt(level,feet);
        if(Double.isNaN(floor))return false;
        return bodyClear(level,mob,feet.getX()+.5,floor,feet.getZ()+.5);
    }
    private static boolean bodyClear(ServerLevel world,PathfinderMob mob,
                                     double x,double y,double z){
        BlockPos feet=BlockPos.containing(x,y+.02,z);
        boolean door=handDoor(world,feet)||handDoor(world,feet.above());
        // A hand-openable door temporarily narrows the worker from .85 to
        // .70 blocks only while passing through its doorway.
        double half=door?.35:mob.getBbWidth()*.5;
        AABB body=new AABB(x-half,y+.01,z-half,
                x+half,y+mob.getBbHeight()-.025,z+half);
        // Closed wooden doors along a route are opened on approach. They
        // are permitted as *nodes*, but other obstructed samples are not.
        return world.noCollision(mob,body)||door;
    }
    private static boolean safePath(ServerLevel level,PathfinderMob mob,
                                    Path path,boolean lookAheadOnly){
        if(path==null||!path.canReach()||path.getNodeCount()<1)return false;
        int start=lookAheadOnly?Math.max(0,path.getNextNodeIndex()):0;
        int end=lookAheadOnly?Math.min(path.getNodeCount(),start+LOOK_AHEAD)
                :path.getNodeCount();
        if(start>=end)return true;
        int checked=0;
        for(int i=start;i<end;i++){
            Node node=path.getNode(i);
            BlockPos feet=new BlockPos(node.x,node.y,node.z);
            if(!standingTile(level,mob,feet))return false;
            if(i==start)continue;
            Node previous=path.getNode(i-1);
            double a=floorAt(level,new BlockPos(previous.x,previous.y,previous.z));
            double b=floorAt(level,feet);
            // One-block jumps and stair steps are legal for vanilla ground
            // mobs; reject only taller climbs or dangerous drops. Clearance
            // is checked at the raised position before entering the ledge.
            if(Double.isNaN(a)||Double.isNaN(b)
                    ||b-a>1.05||a-b>1.50)return false;
            double dx=node.x-previous.x,dz=node.z-previous.z;
            int samples=Math.max(1,(int)Math.ceil(Math.hypot(dx,dz)*3));
            for(int k=1;k<samples;k++){
                double t=(double)k/samples;
                double x=previous.x+.5+dx*t,z=previous.z+.5+dz*t;
                double y=Math.abs(a-b)>.60?Math.max(a,b):a+(b-a)*t;
                if(!bodyClear(level,mob,x,y,z))return false;
            }
            if(++checked>120)break; // world chunks are never force-loaded
        }
        return true;
    }

    private static boolean neighborOccupied(ServerLevel world,PathfinderMob mover,BlockPos p){
        return !world.getEntitiesOfClass(PathfinderMob.class,
                new AABB(p).inflate(.10),
                other->other!=mover&&other.isAlive()).isEmpty();
    }
    private record Candidate(BlockPos feet,double score){}
    private static List<Candidate> candidateGoals(ServerLevel level,PathfinderMob mob,
                                                  BlockPos requested,int avoid){
        List<Candidate> candidates=new ArrayList<>();
        BlockPos origin=mob.blockPosition();
        // Direct goal first if it is genuinely standable. Never assume the
        // requested coordinate is a traversable tile (workstations aren't).
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)
            for(int dy=-1;dy<=1;dy++){
                BlockPos p=requested.offset(dx,dy,dz);
                if(!standingTile(level,mob,p)||neighborOccupied(level,mob,p))continue;
                double score=p.distSqr(requested)*2.5
                        +p.distSqr(origin)*.11+Math.abs(dy)*2;
                if(avoid!=0&&Math.floorMod(p.hashCode(),4)==avoid-1)
                    score+=10;
                if(p.equals(requested))score-=6;
                candidates.add(new Candidate(p.immutable(),score));
            }
        candidates.sort(Comparator.comparingDouble(Candidate::score));
        return candidates;
    }

    /** Job workers request goals about every 10-40 ticks. Maintain the
     * current validated vanilla A* route, except when five-tick inspection
     * detects a newly placed obstacle. All workers share this policy.
     */
    public static boolean routeTo(ServerLevel level,PathfinderMob mob,BlockPos target,
                                  double speed){
        if(mob instanceof com.caszgamermd.golemsplus.builder.BuilderGolem builder)
            com.caszgamermd.golemsplus.builder.BuilderChunkTickets
                    .destination(level,builder,target);
        if(target==null||!level.hasChunkAt(target))return false;
        Progress p=state(mob);
        if(mob.getNavigation() instanceof GroundPathNavigation ground)
            ground.setCanOpenDoors(true);
        boolean changed=!target.equals(p.requested);
        if(changed){
            p.requested=target.immutable();p.approach=null;
            p.attempts=0;p.nextRoute=0;p.stalled=0;p.unsafeChecks=0;
            p.weightedRoute=List.of();p.routeCursor=0;p.lastBad=null;
        }
        updateDoorClearance(level,mob,p);
        if(p.openedDoor==null)openApproachDoor(level,mob,p);
        if(p.wait>0)return true;
        if(!changed&&!mob.getNavigation().isDone()
                &&p.stalled<STALL_TICKS&&p.unsafeChecks==0)return true;
        if(!changed&&mob.tickCount<p.nextRoute)return false;
        // A temporary body-block or a closed door must not permanently
        // blacklist the only valid aisle. Expire bad waypoints after
        // roughly three seconds, then try the new world state.
        if(p.lastBad!=null&&mob.tickCount>=p.avoidUntil)
            p.lastBad=null;

        // Retain the same route across job updates. The old fan of 24/16/10
        // straight-line waypoints repeatedly chose the same dead end next
        // to a wall. Instead use one bounded lowest-cost search starting
        // from the actual Golem and validate every intermediate floor tile.
        if(p.routeCursor>=p.weightedRoute.size()){
            p.weightedRoute=List.of();
            p.routeCursor=0;
        }
        if(!p.weightedRoute.isEmpty()&&p.approach!=null
                &&mob.blockPosition().distSqr(p.approach)>9){
            // Navigation ended without arriving at the promised waypoint.
            // Invalidate this branch and search another physically valid
            // route after a short per-worker retry delay.
            p.lastBad=p.approach;p.avoidUntil=mob.tickCount+60;
            p.weightedRoute=List.of();p.routeCursor=0;
        }
        if(p.weightedRoute.isEmpty()){
            int avoid=p.attempts==0?0:(p.attempts%4)+1;
            List<BlockPos> goals=new ArrayList<>();
            for(Candidate candidate:candidateGoals(level,mob,target,avoid)){
                if(goals.size()>=16)break;
                goals.add(candidate.feet());
            }
            WorkerRoutePlanner.Route route=WorkerRoutePlanner.plan(
                    mob.blockPosition(),goals,
                    at->standingTile(level,mob,at),
                    at->floorAt(level,at),p.lastBad);
            if(route==null||route.steps().isEmpty()){
                p.attempts++;
                p.nextRoute=mob.tickCount+ROUTE_RETRY+Math.floorMod(mob.getId(),5);
                return false;
            }
            p.weightedRoute=route.steps();p.routeCursor=0;
        }
        // One native A* only for a SHORT portion of the already-validated
        // cheapest route. Trying shorter checkpoints prevents false "cannot
        // path" reports from the vanilla navigation radius.
        int count=p.weightedRoute.size();
        for(int distance:new int[]{10,6,3,1}){
            int index=Math.min(count-1,p.routeCursor+distance-1);
            if(index<p.routeCursor)continue;
            BlockPos waypoint=p.weightedRoute.get(index);
            if(!standingTile(level,mob,waypoint))continue;
            Path path=mob.getNavigation().createPath(waypoint,0);
            if(!safePath(level,mob,path,false))continue;
            if(!mob.getNavigation().moveTo(path,speed))continue;
            p.approach=waypoint;
            p.routeCursor=index+1;
            p.stalled=0;p.unsafeChecks=0;p.lastBad=null;
            p.nextRoute=mob.tickCount+CHECK_TICKS;
            return true;
        }
        p.lastBad=p.weightedRoute.get(p.routeCursor);
        p.avoidUntil=mob.tickCount+60;
        p.weightedRoute=List.of();p.routeCursor=0;
        // An impossible route must *not* fall back to walking directly at
        // the wall; try another approach as obstacles change or workers move.
        p.attempts++;
        p.nextRoute=mob.tickCount+ROUTE_RETRY+Math.floorMod(mob.getId(),5);
        return false;
    }

    public static void stop(PathfinderMob mob){
        Progress p=state(mob);
        p.requested=null;p.approach=null;p.stalled=0;p.unsafeChecks=0;
        p.weightedRoute=List.of();p.routeCursor=0;p.lastBad=null;
        mob.getNavigation().stop();
        // Door clearance is reset during the next server navigation tick.
    }

    /** Call every server tick, including when idle, to operate and close doors. */
    public static void tick(ServerLevel level,PathfinderMob mob){
        Progress p=state(mob);
        if(mob.getNavigation() instanceof GroundPathNavigation nav)
            nav.setCanOpenDoors(true);
        if(mob.tickCount%CHECK_TICKS!=0)return;
        updateDoorClearance(level,mob,p);
        if(p.previous==null)p.previous=mob.position();
        if(mob.position().distanceToSqr(p.previous)>.018){
            p.stalled=0;
        }else if(p.requested!=null&&!mob.getNavigation().isDone()){
            p.stalled+=CHECK_TICKS;
        }
        p.previous=mob.position();
        if(p.wait>0){p.wait=Math.max(0,p.wait-CHECK_TICKS);return;}
        // Check the next four waypoints every five ticks. When a player
        // places a block across the route, stop *before* walking into it;
        // the next job evaluation recalculates a path around that obstacle.
        if(p.requested!=null&&!mob.getNavigation().isDone()){
            Path path=mob.getNavigation().getPath();
            boolean blocked=!safePath(level,mob,path,true);
            if(blocked||p.stalled>=STALL_TICKS){
                p.unsafeChecks++;
                p.attempts++;
                Path old=mob.getNavigation().getPath();
                if(old!=null&&old.getNextNodeIndex()<old.getNodeCount()){
                    Node node=old.getNode(old.getNextNodeIndex());
                    p.lastBad=new BlockPos(node.x,node.y,node.z);
                    p.avoidUntil=mob.tickCount+60;
                }
                p.weightedRoute=List.of();p.routeCursor=0;
                p.nextRoute=mob.tickCount+CHECK_TICKS;
                mob.getNavigation().stop();
                return;
            }
        }
        if(p.requested!=null&&!mob.getNavigation().isDone()
                &&yieldToWorker(level,mob,p)){
            p.wait=15+(Math.floorMod(mob.getId(),4)*5);
            mob.getNavigation().stop();
            // Keep requested target so the next evaluation resumes.
            p.nextRoute=0;p.stalled=STALL_TICKS;
            p.weightedRoute=List.of();p.routeCursor=0;
            return;
        }
        // Open door based on actual next-step or desired route direction,
        // rather than a straight line toward the target villager.
        if(p.openedDoor==null&&p.requested!=null)
            openApproachDoor(level,mob,p);
        closeClearedDoor(level,mob,p);
    }

    private static boolean yieldToWorker(ServerLevel world,PathfinderMob mob,Progress p){
        if(p.requested==null)return false;
        Vec3 forward=new Vec3(p.requested.getX()+.5-mob.getX(),0,
                p.requested.getZ()+.5-mob.getZ());
        if(forward.lengthSqr()<.3)return false;
        forward=forward.normalize();
        for(PathfinderMob other:world.getEntitiesOfClass(PathfinderMob.class,
                mob.getBoundingBox().inflate(1.25),
                ent->ent!=mob&&ent.isAlive()
                    &&(ent instanceof com.caszgamermd.golemsplus.trader.TraderGolem
                        ||ent instanceof com.caszgamermd.golemsplus.builder.BuilderGolem
                        ||ent instanceof com.caszgamermd.golemsplus.beekeeper.BeekeeperGolem
                        ||ent instanceof com.caszgamermd.golemsplus.cooking.ChefGolem
                        ||ent instanceof com.caszgamermd.golemsplus.fishing.FisherGolem))){
            Vec3 delta=new Vec3(other.getX()-mob.getX(),0,other.getZ()-mob.getZ());
            double forwardDist=forward.dot(delta);
            if(forwardDist<.15||forwardDist>1.5||delta.lengthSqr()>2.0)
                continue;
            if(other.getNavigation().isDone())continue;
            // An asymmetric tie break ensures both workers don't yield.
            if(mob.getUUID().compareTo(other.getUUID())>0)
                return true;
        }
        return false;
    }
    private static void openApproachDoor(ServerLevel level,PathfinderMob mob,Progress p){
        // Follow the *next actual path node*, not a straight line drawn
        // through walls to the faraway workstation.
        Path path=mob.getNavigation().getPath();
        Vec3 goal;
        if(path!=null&&!mob.getNavigation().isDone()
                &&path.getNextNodeIndex()<path.getNodeCount()){
            Node next=path.getNode(path.getNextNodeIndex());
            goal=new Vec3(next.x+.5-mob.getX(),0,next.z+.5-mob.getZ());
        }else{
            BlockPos destination=p.approach==null?p.requested:p.approach;
            goal=new Vec3(destination.getX()+.5-mob.getX(),0,
                    destination.getZ()+.5-mob.getZ());
        }
        if(goal.lengthSqr()<.09)return;
        Vec3 ahead=goal.normalize();
        BlockPos origin=mob.blockPosition();
        BlockPos doorPos=null;
        double closest=Double.MAX_VALUE;
        for(int dx=-3;dx<=3;dx++)for(int dy=-1;dy<=1;dy++)
            for(int dz=-3;dz<=3;dz++){
                BlockPos probe=origin.offset(dx,dy,dz);
                if(!handDoor(level,probe))continue;
                BlockState state=level.getBlockState(probe);
                if(state.getValue(DoorBlock.OPEN))continue;
                Vec3 delta=new Vec3(probe.getX()+.5-mob.getX(),0,
                        probe.getZ()+.5-mob.getZ());
                double along=delta.dot(ahead);
                double side=Math.abs(delta.x*ahead.z-delta.z*ahead.x);
                if(along<-.3||along>3.5||side>1.20)continue;
                double score=delta.lengthSqr()+side*2;
                if(score<closest){doorPos=probe;closest=score;}
            }
        if(doorPos!=null){
            BlockState state=level.getBlockState(doorPos);
            ((DoorBlock)state.getBlock()).setOpen(mob,level,state,doorPos,true);
            p.openedDoor=doorPos.immutable();p.doorAge=0;
            updateDoorClearance(level,mob,p);
            // Path planned while the door was closed may be impossible.
            // Re-evaluate only once after opening it.
            p.stalled=0;p.nextRoute=0;
            mob.getNavigation().stop();
        }
    }
    private static void closeClearedDoor(ServerLevel level,PathfinderMob mob,Progress p){
        BlockPos door=p.openedDoor;
        if(door==null)return;
        p.doorAge+=CHECK_TICKS;
        if(p.doorAge<15||mob.distanceToSqr(door.getX()+.5,
                door.getY()+.5,door.getZ()+.5)<6.3)return;
        // Don't slam a door into any mob or player; leave it open until
        // everyone clears instead of trapping the next worker.
        if(!level.getEntitiesOfClass(Entity.class,new AABB(door).inflate(.75),
                ent->ent!=mob&&ent.isAlive()).isEmpty())return;
        if(level.hasChunkAt(door)&&handDoor(level,door)){
            BlockState state=level.getBlockState(door);
            if(state.getValue(DoorBlock.OPEN))
                ((DoorBlock)state.getBlock()).setOpen(mob,level,state,door,false);
        }
        p.openedDoor=null;
    }
}
