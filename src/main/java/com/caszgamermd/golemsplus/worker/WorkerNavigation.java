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
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
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
    private static final int ROUTE_RETRY=20;
    private static final int CHECK_TICKS=5;
    private static final int LOOK_AHEAD=4;
    private static final int MAX_DESTINATION_PATHS=16;
    private WorkerNavigation(){}

    private static final class Progress {
        BlockPos requested,approach,openedDoor;
        Vec3 previous;
        int stalled,attempts,wait,doorAge,nextRoute;
        int unsafeChecks,avoidUntil;
        BlockPos lastBad;
        boolean narrowDoorway;
        // Per-door traffic reservation: one crossing at a time, but no
        // cross-door or cross-site global lock.
        BlockPos queuedDoor,doorStandby,doorExit;
        boolean waitingDoor,parkingAtDoor,finishingDoor;
        int doorLastProgressTick;
        double doorLastDistance=Double.POSITIVE_INFINITY;
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
        // Schematics commonly use a fence GATE as the entrance. Previous
        // logic recognized only vanilla DoorBlock, so every Builder kept
        // pathing toward an impassable closed gate forever.
        return block.getBlock() instanceof FenceGateBlock
                ||block.getBlock() instanceof DoorBlock door
                    &&door.type().canOpenByHand();
    }
    private static Direction entranceFacing(BlockState state){
        if(state.getBlock() instanceof FenceGateBlock)
            return state.getValue(FenceGateBlock.FACING);
        return state.getValue(DoorBlock.FACING);
    }
    private static boolean isOpenPassage(BlockState state){
        if(state.getBlock() instanceof FenceGateBlock)
            return state.getValue(FenceGateBlock.OPEN);
        return state.getValue(DoorBlock.OPEN);
    }
    private static void setPassageOpen(ServerLevel world,PathfinderMob mob,
                                       BlockPos door,boolean opened){
        BlockState state=world.getBlockState(door);
        if(state.getBlock() instanceof FenceGateBlock){
            if(state.getValue(FenceGateBlock.OPEN)!=opened)
                world.setBlock(door,state.setValue(FenceGateBlock.OPEN,opened),3);
        }else if(state.getBlock() instanceof DoorBlock block)
            block.setOpen(mob,world,state,door,opened);
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
    /** The integer navigation node is not always an air block. Vanilla
     * can put the feet node INSIDE a slab or a stair when walking on its
     * collision surface. A solid cube remains impassable, but partial
     * architectural blocks are legitimate floor. Avoid using only the
     * maximum Y of the block below as a fake full-block obstruction.
     */
    private static boolean partialFloor(BlockState state){
        return state.getBlock() instanceof StairBlock
                ||state.getBlock() instanceof SlabBlock
                    &&state.getValue(SlabBlock.TYPE)!=SlabType.DOUBLE;
    }
    private static double floorAt(ServerLevel level,BlockPos feet){
        BlockPos below=feet.below();
        VoxelShape footing=level.getBlockState(below).getCollisionShape(level,below);
        BlockState state=level.getBlockState(feet);
        VoxelShape inside=state.getCollisionShape(level,feet);
        // A stair or lower slab itself supplies the support surface;
        // vanilla can legitimately report its own Y as the path node.
        if(partialFloor(state)&&!inside.isEmpty()){
            double top=feet.getY()+inside.max(Direction.Axis.Y);
            return top;
        }
        if(footing.isEmpty())return Double.NaN;
        double y=below.getY()+footing.max(Direction.Axis.Y);
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
        // Closed wooden doors are opened on approach. Verify the actual
        // block collision separately from *other moving workers*. Calling
        // noCollision alone rejects a valid route while another golem is
        // standing at a waypoint and makes a flat site look impossible.
        // Dynamic entities still yield to each other during movement.
        if(world.noCollision(mob,body))return true;
        if(!door&&world.getBlockCollisions(mob,body).iterator().hasNext())
            return false;
        // Only OTHER moving worker golems may be treated as temporary
        // congestion. The earlier block-only fallback also ignored
        // physical vehicles/pallet transports: A* then repeatedly picked
        // a stance occupied by their large cargo entity. Keep those
        // objects as real obstacles, while ordinary worker queues still
        // yield to each other at doorways.
        return world.getEntitiesOfClass(Entity.class,body,
                entity->entity!=mob&&entity.isAlive()
                    &&entity.isPickable()
                    &&!(entity instanceof PathfinderMob other
                            &&worker(other))).isEmpty();
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
            // The first native path node can overlap the mover's current
            // body on a slab/ledge. The worker is already physically there
            // and needs to be able to step OUT, not fail all routes because
            // the first sample slightly clips the block under its feet.
            if(!(i==0&&path.getNodeCount()>1
                    &&mob.blockPosition().equals(feet))
                    &&!standingTile(level,mob,feet))return false;
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
                BlockPos beforeFeet=new BlockPos(previous.x,previous.y,previous.z);
                // Stair risers and half slabs are stepped over, not walked
                // through diagonally at intermediate interpolated heights.
                // Sweeping the worker through the step's collision shape
                // repeatedly rejected legal vanilla paths as blocked.
                boolean architecturalStep=partialFloor(level.getBlockState(beforeFeet))
                        ||partialFloor(level.getBlockState(feet))
                        ||partialFloor(level.getBlockState(beforeFeet.below()))
                        ||partialFloor(level.getBlockState(feet.below()));
                double y=architecturalStep||Math.abs(a-b)>.60
                        ?Math.max(a,b):a+(b-a)*t;
                // The game handles a one-block step as a hop, not a
                // horizontal sweep through the vertical face of the step.
                // Endpoints were checked for solid body/head collision;
                // trusting this short native step avoids false rejection
                // while retaining a <=1.05 block maximum climb.
                if(Math.abs(a-b)>.60)continue;
                if(!bodyClear(level,mob,x,y,z))return false;
            }
            if(++checked>120)break; // world chunks are never force-loaded
        }
        return true;
    }

    private static boolean worker(PathfinderMob candidate){
        return candidate instanceof com.caszgamermd.golemsplus.builder.BuilderGolem
            || candidate instanceof com.caszgamermd.golemsplus.trader.TraderGolem
            || candidate instanceof com.caszgamermd.golemsplus.beekeeper.BeekeeperGolem
            || candidate instanceof com.caszgamermd.golemsplus.cooking.ChefGolem
            || candidate instanceof com.caszgamermd.golemsplus.fishing.FisherGolem;
    }
    /** Workers prefer >=1.5 blocks of personal space where ground allows.
     * Do not invalidate an actual valid path just because a colleague will
     * move; score and avoid crowded destinations instead.
     */
    private static boolean neighborOccupied(ServerLevel world,PathfinderMob mover,BlockPos p){
        double x=p.getX()+.5,z=p.getZ()+.5;
        return !world.getEntitiesOfClass(PathfinderMob.class,
                new AABB(p).inflate(1.0),
                other->other!=mover&&other.isAlive()&&worker(other)
                    &&Math.pow(other.getX()-x,2)
                            +Math.pow(other.getZ()-z,2)<1.5*1.5
                    &&Math.abs(other.getY()-p.getY())<1.6).isEmpty();
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
                if(!standingTile(level,mob,p))continue;
                // Dynamic occupants must not invalidate every goal around
                // a crowded Planner or depot. Other Golems will move;
                // prefer free cells but retain the occupied ones as safe
                // fallback candidates rather than reporting "no route".
                double score=p.distSqr(requested)*2.5
                        +p.distSqr(origin)*.11+Math.abs(dy)*2
                        +(neighborOccupied(level,mob,p)?90:0);
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
    /** Only placement evacuation requires an exact destination. Ordinary
     * navigation may stop two blocks short of the goal; doing that while
     * vacating a future solid block would leave the Builder in danger.
     */
    /** Evaluate only the actual physical tile, not nearby transient
     * crowd occupancy. Used to retain a stable Builder approach target
     * without re-running pathfinding on every construction tick.
     */
    public static boolean canStandOn(ServerLevel level,PathfinderMob mob,
                                     BlockPos target){
        return target!=null&&standingTile(level,mob,target);
    }
    private static boolean endsAtExactTile(Path path,BlockPos target){
        if(path==null||path.getNodeCount()<1)return false;
        Node last=path.getNode(path.getNodeCount()-1);
        // canReach() may accept a waypoint near the requested cell.
        // A Builder requiring actual interaction range cannot mistake
        // that approximate endpoint for an exact standing destination.
        return last.x==target.getX()&&last.z==target.getZ()
                &&Math.abs(last.y-target.getY())<=1;
    }
    public static boolean canReachExact(ServerLevel level,PathfinderMob mob,
                                        BlockPos target){
        if(target==null||!level.hasChunkAt(target))return false;
        Path path=mob.getNavigation().createPath(target,0);
        return endsAtExactTile(path,target)&&safePath(level,mob,path,false);
    }
    private static void clearDoor(ServerLevel level,PathfinderMob mob,Progress p){
        if(p.queuedDoor!=null)WorkerDoorTraffic.release(level,p.queuedDoor,mob);
        p.queuedDoor=null;p.doorStandby=null;p.doorExit=null;
        p.waitingDoor=false;p.parkingAtDoor=false;p.finishingDoor=false;
        p.doorLastDistance=Double.POSITIVE_INFINITY;
        p.doorLastProgressTick=mob.tickCount;
    }
    public static boolean routeToExact(ServerLevel level,PathfinderMob mob,
                                       BlockPos target,double speed){
        // A booked crane must not cancel an active scaffold or temporary
        // right-of-way movement during a five-tick A* route retry.
        if(target!=null&&mob instanceof com.caszgamermd.golemsplus.builder.BuilderGolem builder
                &&!com.caszgamermd.golemsplus.builder.BuilderScaffolding
                        .isControllingMovement(builder)
                &&com.caszgamermd.golemsplus.crane.CraneService
                        .requestRide(level,builder,target))return true;
        Progress progress=state(mob);
        if(progress.queuedDoor!=null&&target!=null
                &&progress.requested!=null&&!target.equals(progress.requested))
            clearDoor(level,mob,progress);
        if(progress.waitingDoor||progress.finishingDoor)return true;
        // Retain original work target while this worker clears the door.
        if(mob instanceof com.caszgamermd.golemsplus.builder.BuilderGolem)
            speed=1.0; // nominal 4 m/s from Builder movement attribute .40
        if(target==null||!level.hasChunkAt(target))return false;
        Progress p=state(mob);
        boolean same=target.equals(p.requested)&&target.equals(p.approach);
        if(same&&!mob.getNavigation().isDone()
                &&p.stalled<STALL_TICKS&&p.unsafeChecks==0)return true;
        if(mob.getNavigation() instanceof GroundPathNavigation ground)
            ground.setCanOpenDoors(true);
        // If the exact safe standing tile was reached on the previous
        // tick, do not reconstruct the same route and toggle navigation.
        if(mob.blockPosition().equals(target)
                &&standingTile(level,mob,target)){
            p.requested=target.immutable();p.approach=target.immutable();
            return true;
        }
        Path path=mob.getNavigation().createPath(target,0);
        if(!endsAtExactTile(path,target)||!safePath(level,mob,path,false)
                ||!mob.getNavigation().moveTo(path,speed))return false;
        p.requested=target.immutable();p.approach=target.immutable();
        p.weightedRoute=List.of();p.routeCursor=0;
        p.stalled=0;p.unsafeChecks=0;p.wait=0;p.lastBad=null;
        p.nextRoute=mob.tickCount+CHECK_TICKS;
        return true;
    }

    public static boolean routeTo(ServerLevel level,PathfinderMob mob,BlockPos target,
                                  double speed){
        if(target!=null&&mob instanceof com.caszgamermd.golemsplus.builder.BuilderGolem builder
                &&!com.caszgamermd.golemsplus.builder.BuilderScaffolding
                        .isControllingMovement(builder)
                &&com.caszgamermd.golemsplus.crane.CraneService
                        .requestRide(level,builder,target))return true;
        if(mob instanceof com.caszgamermd.golemsplus.builder.BuilderGolem builder){
            com.caszgamermd.golemsplus.builder.BuilderChunkTickets
                    .destination(level,builder,target);
            speed=1.0; // Builder target: 4 blocks/second on level ground
        }
        if(target==null||!level.hasChunkAt(target))return false;
        Progress p=state(mob);
        if(p.queuedDoor!=null&&target!=null
                &&p.requested!=null&&!target.equals(p.requested))
            clearDoor(level,mob,p);
        if(p.waitingDoor||p.finishingDoor)return true;
        // Door crossing owns movement until the threshold is clear.
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
        if(p.waitingDoor)return true;
        if(p.wait>0)return true;
        if(!changed&&!mob.getNavigation().isDone()
                &&p.stalled<STALL_TICKS&&p.unsafeChecks==0)return true;
        if(!changed&&mob.tickCount<p.nextRoute)return false; // retry scheduled, not a hard no-path
        // A vanilla path can finish at its provisional "near enough"
        // endpoint while the REAL requested storage/work destination
        // remains four-plus blocks away. Repeated calls used to route
        // towards another nearby provisional tile, then back again,
        // visibly circling forever. Reject this exact endpoint for a
        // bounded window and require a genuinely different way through.
        if(!changed&&p.approach!=null&&mob.getNavigation().isDone()
                &&mob.blockPosition().distSqr(p.approach)<=2
                &&mob.distanceToSqr(target.getX()+.5,target.getY(),
                        target.getZ()+.5)>16.0){
            p.lastBad=p.approach;
            p.avoidUntil=mob.tickCount+150;
            p.approach=null;
            p.weightedRoute=List.of();p.routeCursor=0;
            p.attempts++;
            p.nextRoute=mob.tickCount+ROUTE_RETRY;
            return false;
        }
        // A temporary body-block or a closed door must not permanently
        // blacklist the only valid aisle. Expire bad waypoints after
        // roughly three seconds, then try the new world state.
        if(p.lastBad!=null&&mob.tickCount>=p.avoidUntil)
            p.lastBad=null;

        // Nearby destinations should NOT pay for a new 2,800-node
        // global search or blindly take the first roundabout candidate.
        // Compare a handful of validated native paths by actual path
        // length and choose the shortest legal one; the weighted search
        // remains the fallback for distant or blocked corridors.
        if(p.weightedRoute.isEmpty()
                &&mob.blockPosition().distSqr(target)<=20*20){
            Path best=null;BlockPos bestGoal=null;
            double bestCost=Double.MAX_VALUE;
            int checked=0;
            // Inspect more than the old five nearest cells. A wall at the
            // target can obstruct those five while a sixth (or tenth)
            // position on the same flat surface is easily reachable.
            for(Candidate option:candidateGoals(level,mob,target,
                    p.attempts==0?0:(p.attempts%4)+1)){
                if(++checked>MAX_DESTINATION_PATHS*2
                        ||best!=null&&checked>8)break;
                // A Builder within placement reach has NOT necessarily
                // arrived at a real movement target (door, depot, route
                // waypoint). Only the job itself may skip walking if its
                // placement is already within reach.
                if(option.feet().equals(mob.blockPosition())
                        ||p.lastBad!=null&&option.feet().distSqr(p.lastBad)<=2)
                    continue;
                Path candidate=mob.getNavigation().createPath(option.feet(),0);
                if(!safePath(level,mob,candidate,false))continue;
                // Distances along the actual path, then distance from its
                // reachable endpoint to the requested work location.
                double cost=candidate.getNodeCount()*10
                        +Math.sqrt(option.feet().distSqr(target))*4
                        +(neighborOccupied(level,mob,option.feet())?90:0);
                if(cost<bestCost){
                    best=candidate;bestGoal=option.feet();bestCost=cost;
                }
            }
            if(best!=null&&mob.getNavigation().moveTo(best,speed)){
                p.approach=bestGoal;
                p.stalled=0;p.unsafeChecks=0;p.lastBad=null;
                p.nextRoute=mob.tickCount+CHECK_TICKS;
                return true;
            }
        }
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
                if(p.lastBad!=null
                        &&candidate.feet().distSqr(p.lastBad)<=2)continue;
                goals.add(candidate.feet());
            }
            WorkerRoutePlanner.Route route=WorkerRoutePlanner.plan(
                    mob.blockPosition(),goals,
                    at->standingTile(level,mob,at),
                    at->floorAt(level,at),p.lastBad);
            // Never report an empty physical route as success solely
            // because the Builder is within placement range of target.
            // Storage, doorway and return-to-tower movements require
            // reaching a valid walking tile.
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

    /** A route may legitimately be throttled while it re-plans; that is
     * NOT proof that the visible terrain is unreachable. Used only for
     * meaningful Builder Planner diagnostics.
     */
    /** Read-only navigation diagnostics; required to distinguish no
     * candidate, cooldown, stalled path, and actual arrival in-game.
     */
    public static String debugStatus(PathfinderMob mob){
        Progress p=states.get(mob);
        if(p==null)return "uninitialized";
        String destination=p.approach==null?"none":p.approach.toShortString();
        String requested=p.requested==null?"none":p.requested.toShortString();
        int remaining=Math.max(0,p.nextRoute-mob.tickCount);
        int rank=p.queuedDoor!=null&&mob.level() instanceof ServerLevel world
                ?WorkerDoorTraffic.position(world,p.queuedDoor,mob):0;
        // Snapshot-only telemetry: do not create/rewrite a path while
        // collecting data from the four Dome Builder workers.
        Path path=mob.getNavigation().getPath();
        String nativeRoute="none";
        if(path!=null){
            int next=path.getNextNodeIndex();
            String waypoint=next<path.getNodeCount()
                    ?path.getNode(next).x+","+path.getNode(next).y+","
                            +path.getNode(next).z:"end";
            nativeRoute=next+"/"+path.getNodeCount()+" next="+waypoint
                    +" done="+mob.getNavigation().isDone();
        }
        return "goal="+requested+" via="+destination
                +" retry="+remaining+"t attempts="+p.attempts
                +" stalled="+p.stalled+" unsafe="+p.unsafeChecks
                +" native=["+nativeRoute+"]"
                +" weighted="+p.routeCursor+"/"+p.weightedRoute.size()
                +(p.lastBad==null?"":" avoid="+p.lastBad.toShortString()
                    +" until="+Math.max(0,p.avoidUntil-mob.tickCount)+"t")
                +(p.wait>0?" yield="+p.wait+"t":"")
                +(p.queuedDoor==null?"":" door="+p.queuedDoor.toShortString()
                    +(p.waitingDoor?" WAIT":p.finishingDoor?" EXIT":" CROSS")
                    +" q#"+rank);
    }

    /** A genuine repeated no-route failure, not a pause while another worker
     * crosses a door or while a reachable path is still in progress.
     * Builders use this to return down their OWN scaffold instead of retrying
     * an unreachable upper-platform approach forever. No motion is forced.
     */
    public static boolean repeatedlyUnreachable(PathfinderMob mob,int retries){
        Progress p=states.get(mob);
        // A* can finish a SHORT detour at a provisional waypoint but
        // remain many blocks short of the actual OnSite depot. Previously
        // approach!=null suppressed this recovery forever, even after
        // 125 failed routes on an isolated wall one block above ground.
        // Use actual destination distance and completed native navigation,
        // not the stale provisional waypoint, to trigger safe recovery.
        if(p==null||p.requested==null||p.attempts<retries
                ||!mob.getNavigation().isDone()
                ||p.waitingDoor||p.finishingDoor||p.parkingAtDoor)
            return false;
        double dx=mob.getX()-p.requested.getX()-.5;
        double dy=mob.getY()-p.requested.getY();
        double dz=mob.getZ()-p.requested.getZ()-.5;
        return dx*dx+dy*dy+dz*dz>16.0;
    }

    /** Real requested destination for the short, physical Builder crowd
     * passing maneuver. Not a provisional A* detour waypoint.
     */
    public static BlockPos requestedTarget(PathfinderMob mob){
        Progress p=states.get(mob);
        return p==null?p.requested:null;
    }
    public static boolean retryScheduled(PathfinderMob mob){
        Progress p=states.get(mob);
        return p!=null&&p.requested!=null
                &&(mob.tickCount<p.nextRoute||!mob.getNavigation().isDone());
    }

    public static void stop(PathfinderMob mob){
        Progress p=state(mob);
        if(mob.level() instanceof ServerLevel level)clearDoor(level,mob,p);
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
        if(doorTrafficTick(level,mob,p)){
            p.previous=mob.position();
            p.stalled=0;
            return; // waiting workers keep their exact queued order
        }
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

    /** Discover only doors on the imminent path or directly between the
     * worker and its requested destination. The candidate must lead to
     * the far side, so jobs ending beside a door do not queue unnecessarily.
     */
    /** Only reserve a door that divides the current worker from its
     * actual destination. A decorative doorway nearby, or a work target
     * on the SAME side of the door, must not lock unrelated builders.
     */
    private static boolean requiresDoorCrossing(ServerLevel level,
            PathfinderMob mob,Progress progress,BlockPos candidate){
        BlockPos door=WorkerDoorTraffic.canonical(level,candidate);
        if(!handDoor(level,door)||progress.requested==null)return false;
        // On the Dome, a worker walking on the second-floor platform
        // reserved a door two blocks BELOW its feet. It could never walk
        // through that lower doorway and held the FIFO lane indefinitely.
        // Only workers whose real collision box overlaps the doorway's
        // vertical span may take a reservation. A ground-floor worker
        // may still reserve its door from a few blocks horizontally away.
        if(mob.getBoundingBox().minY>=door.getY()+1.95
                ||mob.getBoundingBox().maxY<=door.getY())return false;
        BlockState state=level.getBlockState(door);
        Direction normal=entranceFacing(state);
        double ex=normal.getStepX(),ez=normal.getStepZ();
        double from=(mob.getX()-door.getX()-.5)*ex
                +(mob.getZ()-door.getZ()-.5)*ez;
        double to=(progress.requested.getX()-door.getX())*ex
                +(progress.requested.getZ()-door.getZ())*ez;
        return Math.abs(from)>.35&&Math.abs(to)>.35&&from*to<0;
    }
    private static BlockPos imminentDoor(ServerLevel world,PathfinderMob mob,Progress p){
        if(p.requested==null)return null;
        Path path=mob.getNavigation().getPath();
        if(path!=null&&!mob.getNavigation().isDone()){
            int end=Math.min(path.getNodeCount(),path.getNextNodeIndex()+9);
            for(int i=path.getNextNodeIndex();i<end;i++){
                Node node=path.getNode(i);
                BlockPos at=new BlockPos(node.x,node.y,node.z);
                for(int dy=-1;dy<=1;dy++){
                    BlockPos door=at.offset(0,dy,0);
                    if(handDoor(world,door)
                            &&requiresDoorCrossing(world,mob,p,door)
                            &&mob.distanceToSqr(door.getX()+.5,
                                    door.getY()+.5,door.getZ()+.5)<=25)
                        return WorkerDoorTraffic.canonical(world,door);
                }
            }
        }
        BlockPos closest=null;
        double best=Double.MAX_VALUE;
        Vec3 toGoal=new Vec3(p.requested.getX()+.5-mob.getX(),0,
                p.requested.getZ()+.5-mob.getZ());
        if(toGoal.lengthSqr()<.5)return null;
        Vec3 ahead=toGoal.normalize();
        BlockPos origin=mob.blockPosition();
        for(int dx=-5;dx<=5;dx++)for(int dz=-5;dz<=5;dz++)
            for(int dy=-1;dy<=1;dy++){
                BlockPos candidate=origin.offset(dx,dy,dz);
                if(!handDoor(world,candidate)
                        ||!requiresDoorCrossing(world,mob,p,candidate))
                    continue;
                Vec3 delta=new Vec3(candidate.getX()+.5-mob.getX(),0,
                        candidate.getZ()+.5-mob.getZ());
                double along=delta.dot(ahead);
                double side=Math.abs(delta.x*ahead.z-delta.z*ahead.x);
                double beyond=toGoal.dot(ahead)-along;
                if(along<.1||along>5||side>1.0||beyond<.75)continue;
                double score=delta.lengthSqr()+side*3;
                if(score<best){best=score;closest=candidate;}
            }
        return closest==null?null:WorkerDoorTraffic.canonical(world,closest);
    }

    /** Wait at a real, same-side standing tile 2-3 blocks from this door.
     * An unavailable parking tile does not justify teleporting or routing
     * through a closed wall: pause and retry on the next evaluation.
     */
    private static BlockPos doorStandby(ServerLevel level,PathfinderMob mob,BlockPos door){
        BlockPos best=null;double bestScore=Double.MAX_VALUE;
        double entranceX=mob.getX()-(door.getX()+.5);
        double entranceZ=mob.getZ()-(door.getZ()+.5);
        for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++)
            for(int dy=-1;dy<=1;dy++){
                double radius=Math.hypot(dx,dz);
                if(radius<2||radius>3.35)continue;
                if(dx*entranceX+dz*entranceZ<.2)continue;
                BlockPos tile=door.offset(dx,dy,dz);
                if(!standingTile(level,mob,tile)||neighborOccupied(level,mob,tile))
                    continue;
                Path path=mob.getNavigation().createPath(tile,0);
                if(!endsAtExactTile(path,tile)
                        ||!safePath(level,mob,path,false))continue;
                // A queue position is on the ENTRANCE side. Do not send
                // somebody through the busy door simply to get in line.
                boolean crosses=false;
                for(int i=0;i<path.getNodeCount();i++){
                    Node node=path.getNode(i);
                    BlockPos at=new BlockPos(node.x,node.y,node.z);
                    if(WorkerDoorTraffic.canonical(level,at).equals(door)
                            ||handDoor(level,at)){crosses=true;break;}
                }
                if(crosses)continue;
                double score=tile.distSqr(mob.blockPosition())
                        +Math.abs(dy)*2
                        +Math.abs(radius-2.5)*2;
                if(score<bestScore){best=tile;bestScore=score;}
            }
        return best;
    }

    /** The native path often ends INSIDE an open door rather than beyond
     * its threshold. Do not mark that path completed and leave the owner
     * blocking everyone behind it: escort it to safe ground on the far
     * side, holding only this particular door's FIFO reservation.
     */
    private static boolean finishDoorExit(ServerLevel level,PathfinderMob mob,
                                           Progress p){
        if(p.queuedDoor==null||p.requested==null
                ||!WorkerDoorTraffic.owns(level,p.queuedDoor,mob))return false;
        // Keep the same far-side landing until the entity is ACTUALLY
        // there. Previously the queue released at 1.25 blocks from the
        // door and vanilla A* could not restart from mid-crossing.
        if(p.finishingDoor&&p.doorExit!=null
                &&mob.distanceToSqr(p.doorExit.getX()+.5,
                        p.doorExit.getY(),p.doorExit.getZ()+.5)<.20
                &&mob.onGround()){
            clearDoor(level,mob,p);
            p.nextRoute=0;
            mob.getNavigation().stop();
            return true;
        }
        BlockPos door=p.queuedDoor;
        // Start escorting at the near-side approach (up to four blocks
        // away), not only once the Builder has already reached the
        // doorway. Vanilla A* can report no path while an OPEN wooden
        // door/fence gate is perfectly physically passable.
        double dist=mob.distanceToSqr(door.getX()+.5,
                mob.getY(),door.getZ()+.5);
        if(dist>16.0)return false;
        if(!p.finishingDoor&&!mob.getNavigation().isDone()
                &&p.stalled<STALL_TICKS)return false;
        BlockState gate=level.getBlockState(door);
        if(!handDoor(level,door))return false;
        Direction facing=entranceFacing(gate);
        double advance=(p.requested.getX()-door.getX())*facing.getStepX()
                +(p.requested.getZ()-door.getZ())*facing.getStepZ();
        if(Math.abs(advance)<.5)return false;
        Direction out=advance>0?facing:facing.getOpposite();
        // Only take the straight physical corridor when this worker is
        // aligned with its real door opening. Others continue using A*
        // to approach it, so we never steer through a wall at an angle.
        double side=out.getStepX()==0
                ?Math.abs(mob.getX()-door.getX()-.5)
                :Math.abs(mob.getZ()-door.getZ()-.5);
        if(side>.24)return false;
        double before=(mob.getX()-door.getX()-.5)*out.getStepX()
                +(mob.getZ()-door.getZ()-.5)*out.getStepZ();
        if(before>2.75||before<-4.0)return false;
        BlockPos exit=p.doorExit;
        if(exit==null){
            // Select once; don't swap exit targets as the worker crosses.
            // Two blocks beyond the threshold avoids blocking next user.
            for(int distance:new int[]{2,3,1}){
                BlockPos candidate=door.relative(out,distance);
                if(!standingTile(level,mob,candidate)
                        ||neighborOccupied(level,mob,candidate))continue;
                exit=candidate;break;
            }
        }
        if(exit==null)return false; // no safe place; never push into a wall
        p.doorExit=exit.immutable();
        if(!isOpenPassage(gate))setPassageOpen(level,mob,door,true);
        p.finishingDoor=true;
        p.waitingDoor=false;
        mob.getNavigation().stop();
        double x=exit.getX()+.5,z=exit.getZ()+.5;
        mob.getMoveControl().setWantedPosition(x,exit.getY(),z,1.0);
        double vx=Math.max(-.16,Math.min(.16,(x-mob.getX())*.25));
        double vz=Math.max(-.16,Math.min(.16,(z-mob.getZ())*.25));
        AABB current=mob.getBoundingBox();
        if(level.noCollision(mob,current.move(vx,0,vz))){
            var velocity=mob.getDeltaMovement();
            mob.setDeltaMovement(vx,Math.min(0,velocity.y),vz);
        }else{
            // An OPEN door still occupies a thin leaf of the 1-block
            // passage. A centered 0.65-wide golem can hit that leaf:
            // the free lane is offset towards the opposite jamb.
            // Test BOTH directions against real world collision. Slide
            // at most 0.15 sideways and continue forward once clear.
            double perpX=-out.getStepZ(),perpZ=out.getStepX();
            double deviation=(mob.getX()-door.getX()-.5)*perpX
                    +(mob.getZ()-door.getZ()-.5)*perpZ;
            double[] shifts={.13,-.13,.08,-.08};
            for(double offset:shifts){
                if(Math.abs(deviation+offset)>.17)continue;
                if(!level.noCollision(mob,current.move(perpX*offset,0,
                        perpZ*offset)))continue;
                if(!level.noCollision(mob,current.move(
                        vx+perpX*offset,0,vz+perpZ*offset)))continue;
                double nudge=Math.max(-.07,Math.min(.07,offset));
                mob.setDeltaMovement(perpX*nudge,0,perpZ*nudge);
                break;
            }
        }
        return true;
    }

    private static boolean doorTrafficTick(ServerLevel level,PathfinderMob mob,Progress p){
        if(p.queuedDoor!=null){
            // A Builder can climb onto a completed dome platform while
            // retaining a FIFO token for the lower door. It is physically
            // outside the doorway and must release the lane for others.
            boolean onDifferentFloor=mob.getBoundingBox().minY
                    >=p.queuedDoor.getY()+1.95
                    ||mob.getBoundingBox().maxY<=p.queuedDoor.getY();
            if(onDifferentFloor||!handDoor(level,p.queuedDoor)
                    ||!p.finishingDoor
                        &&WorkerDoorTraffic.crossed(level,p.queuedDoor,mob)
                    ||mob.distanceToSqr(p.queuedDoor.getX()+.5,
                            p.queuedDoor.getY()+.5,p.queuedDoor.getZ()+.5)>64){
                WorkerDoorTraffic.release(level,p.queuedDoor,mob);
                p.queuedDoor=null;p.waitingDoor=false;
                p.doorStandby=null;p.parkingAtDoor=false;p.finishingDoor=false;
                p.doorExit=null;p.nextRoute=0;
            }
        }
        if(p.queuedDoor==null&&p.requested!=null){
            // Rescue a worker whose path stopped with its feet already
            // INSIDE the gate. Dot-product crossing detection cannot
            // discover it because its starting offset is near zero.
            BlockPos inside=mob.blockPosition();
            BlockPos upcoming=handDoor(level,inside)?inside
                    :handDoor(level,inside.below())?inside.below()
                    :imminentDoor(level,mob,p);
            if(upcoming!=null){
                p.queuedDoor=upcoming;
                p.doorLastProgressTick=mob.tickCount;
                p.doorLastDistance=Double.POSITIVE_INFINITY;
            }
        }
        if(p.queuedDoor==null)return false;
        double doorDistance=mob.distanceToSqr(
                p.queuedDoor.getX()+.5,p.queuedDoor.getY()+.5,
                p.queuedDoor.getZ()+.5);
        // A door owner whose approach has not moved in five seconds must
        // not block the whole queue indefinitely. Yield its position,
        // apply a cooldown, and use the next path evaluation to recover.
        if(WorkerDoorTraffic.owns(level,p.queuedDoor,mob)){
            if(doorDistance<p.doorLastDistance-.20){
                p.doorLastDistance=doorDistance;
                p.doorLastProgressTick=mob.tickCount;
            }else if(mob.tickCount-p.doorLastProgressTick>120){
                // The owner may be right AT the gate with no valid floor
                // behind it (an incomplete dome ledge). Distance alone is
                // not progress. Release stalled reservations near the door
                // too, so a colleague can use another route or displace it.
                BlockPos blocked=p.queuedDoor;
                WorkerDoorTraffic.yieldStalled(level,blocked,mob);
                clearDoor(level,mob,p);
                p.lastBad=blocked;
                p.avoidUntil=mob.tickCount+120;
                p.weightedRoute=List.of();p.routeCursor=0;
                p.nextRoute=mob.tickCount+20;
                p.attempts++;
                mob.getNavigation().stop();
                return false;
            }
        }
        if(WorkerDoorTraffic.request(level,p.queuedDoor,mob)){
            // The head must open the passage BEFORE native A* can find
            // a route. This is essential for fence gates, which vanilla
            // ground navigation does not automatically open.
            BlockState entrance=level.getBlockState(p.queuedDoor);
            if(!isOpenPassage(entrance)){
                setPassageOpen(level,mob,p.queuedDoor,true);
                p.openedDoor=p.queuedDoor.immutable();p.doorAge=0;
                p.nextRoute=0;p.attempts=0;
                p.weightedRoute=List.of();p.routeCursor=0;
            }
            if(p.waitingDoor){
                p.waitingDoor=false;p.parkingAtDoor=false;p.doorStandby=null;
                p.nextRoute=0;p.stalled=0;p.unsafeChecks=0;
                mob.getNavigation().stop(); // job loop resumes its own route
            }
            if(finishDoorExit(level,mob,p))
                return true; // one worker finishes its crossing at a time
            return false;
        }
        p.finishingDoor=false;p.doorExit=null;
        if(!p.waitingDoor){
            p.waitingDoor=true;p.parkingAtDoor=false;p.doorStandby=null;
            mob.getNavigation().stop();
        }
        if(p.doorStandby==null||!standingTile(level,mob,p.doorStandby))
            p.doorStandby=doorStandby(level,mob,p.queuedDoor);
        if(p.doorStandby!=null){
            if(mob.blockPosition().distSqr(p.doorStandby)<=1){
                mob.getNavigation().stop();
                p.parkingAtDoor=false;
            }else if(!p.parkingAtDoor||mob.getNavigation().isDone()){
                Path park=mob.getNavigation().createPath(p.doorStandby,0);
                if(endsAtExactTile(park,p.doorStandby)
                        &&safePath(level,mob,park,false)
                        &&mob.getNavigation().moveTo(park,1.0))
                    p.parkingAtDoor=true;
            }
        }else mob.getNavigation().stop();
        return true;
    }

    private static boolean yieldToWorker(ServerLevel world,PathfinderMob mob,Progress p){
        if(p.requested==null)return false;
        Vec3 forward=new Vec3(p.requested.getX()+.5-mob.getX(),0,
                p.requested.getZ()+.5-mob.getZ());
        if(forward.lengthSqr()<.3)return false;
        forward=forward.normalize();
        for(PathfinderMob other:world.getEntitiesOfClass(PathfinderMob.class,
                mob.getBoundingBox().inflate(1.5),
                ent->ent!=mob&&ent.isAlive()
                    &&(ent instanceof com.caszgamermd.golemsplus.trader.TraderGolem
                        ||ent instanceof com.caszgamermd.golemsplus.builder.BuilderGolem
                        ||ent instanceof com.caszgamermd.golemsplus.beekeeper.BeekeeperGolem
                        ||ent instanceof com.caszgamermd.golemsplus.cooking.ChefGolem
                        ||ent instanceof com.caszgamermd.golemsplus.fishing.FisherGolem))){
            Vec3 delta=new Vec3(other.getX()-mob.getX(),0,other.getZ()-mob.getZ());
            double forwardDist=forward.dot(delta);
            if(forwardDist<.15||forwardDist>1.5||delta.lengthSqr()>1.5*1.5)
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
                if(isOpenPassage(state))continue;
                Vec3 delta=new Vec3(probe.getX()+.5-mob.getX(),0,
                        probe.getZ()+.5-mob.getZ());
                double along=delta.dot(ahead);
                double side=Math.abs(delta.x*ahead.z-delta.z*ahead.x);
                if(along<-.3||along>3.5||side>1.20)continue;
                double score=delta.lengthSqr()+side*2;
                if(score<closest){doorPos=probe;closest=score;}
            }
        if(doorPos!=null
                &&requiresDoorCrossing(level,mob,p,doorPos)){
            BlockPos local=WorkerDoorTraffic.canonical(level,doorPos);
            p.queuedDoor=local;
            if(!WorkerDoorTraffic.request(level,local,mob)){
                p.waitingDoor=true;
                return; // only the head of this door's queue can open it
            }
            setPassageOpen(level,mob,doorPos,true);
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
        if(WorkerDoorTraffic.count(level,door)>0)return;
        if(p.doorAge<15||mob.distanceToSqr(door.getX()+.5,
                door.getY()+.5,door.getZ()+.5)<6.3)return;
        // Don't slam a door into any mob or player; leave it open until
        // everyone clears instead of trapping the next worker.
        if(!level.getEntitiesOfClass(Entity.class,new AABB(door).inflate(.75),
                ent->ent!=mob&&ent.isAlive()).isEmpty())return;
        if(level.hasChunkAt(door)&&handDoor(level,door)){
            BlockState state=level.getBlockState(door);
            if(isOpenPassage(state))
                setPassageOpen(level,mob,door,false);
        }
        p.openedDoor=null;
    }
}
