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
        BlockPos requested,approach,openedDoor,centering;
        Vec3 previous;
        int stalled,attempts,wait,doorAge,nextRoute;
        int unsafeChecks,avoidUntil;
        BlockPos lastBad;
        boolean narrowDoorway;
        // Per-door traffic reservation: one crossing at a time, but no
        // cross-door or cross-site global lock.
        BlockPos queuedDoor,doorStandby,doorExit;
        Direction doorOut;
        boolean waitingDoor,parkingAtDoor,finishingDoor;
        int doorLastProgressTick,doorReservationYields;
        double doorLastDistance=Double.POSITIVE_INFINITY;
        Vec3 doorLastPoint;
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
    private static double trainedTerrainCost(ServerLevel level,BlockPos feet){
        BlockState state=level.getBlockState(feet);
        // Reusable lessons from the real nine-Builder trials: door leaf
        // alignment/queueing costs time; half-slabs are fast real ground;
        // scaffold transit is a deliberate owned vertical manoeuvre.
        // These are penalties in block lengths, never stored routes.
        if(state.getBlock() instanceof DoorBlock
                ||state.getBlock() instanceof FenceGateBlock
                ||level.getBlockState(feet.above()).getBlock()
                        instanceof DoorBlock)return 1.5;
        if(state.is(Blocks.SCAFFOLDING)
                ||level.getBlockState(feet.below()).is(Blocks.SCAFFOLDING))
            return 1.0;
        if(state.getBlock() instanceof StairBlock
                ||level.getBlockState(feet.below()).getBlock() instanceof StairBlock)
            return .12;
        return 0;
    }
    private static List<Vec3> nearbyWorkers(ServerLevel level,PathfinderMob worker){
        List<Vec3> positions=new ArrayList<>();
        for(PathfinderMob other:level.getEntitiesOfClass(
                PathfinderMob.class,worker.getBoundingBox().inflate(12,5,12),
                e->e!=worker&&e.isAlive()&&(
                        e instanceof com.caszgamermd.golemsplus.builder.BuilderGolem
                        ||e instanceof com.caszgamermd.golemsplus.trader.TraderGolem
                        ||e instanceof com.caszgamermd.golemsplus.beekeeper.BeekeeperGolem
                        ||e instanceof com.caszgamermd.golemsplus.cooking.ChefGolem
                        ||e instanceof com.caszgamermd.golemsplus.fishing.FisherGolem)))
            positions.add(other.position());
        return positions;
    }
    /** Mutable teammates are SOFT costs rather than blocked terrain.
     * The A* can choose a parallel aisle when available, but may still
     * plan the only legal narrow corridor and use door reservations there.
     * Snapshot positions once per search, not once per expanded cell.
     */
    private static double crowdCost(BlockPos at,List<Vec3> neighbors){
        double price=0;
        for(Vec3 other:neighbors){
            if(Math.abs(at.getY()-other.y)>1.2)continue;
            double dx=at.getX()+.5-other.x,dz=at.getZ()+.5-other.z;
            double horizontal=dx*dx+dz*dz;
            if(horizontal<.90*.90)price+=7.0;
            else if(horizontal<1.55*1.55)price+=1.8;
        }
        return Math.min(18,price);
    }
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
        if(footing.isEmpty()){
            // Native ground paths round a fence/wall top UP to their
            // integer feet node. A 1.5-high fence two cells below still
            // physically supports feet at nodeY-0.5, despite AIR in the
            // intervening block. Full cubes/stairs never bridge this gap.
            BlockPos lower=feet.below(2);
            VoxelShape extended=level.getBlockState(lower)
                    .getCollisionShape(level,lower);
            if(extended.isEmpty()||extended.max(Direction.Axis.Y)<=1.0)
                return Double.NaN;
            double top=lower.getY()+extended.max(Direction.Axis.Y);
            return top>feet.getY()-1.0&&top<=feet.getY()?top:Double.NaN;
        }
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
        // Opening a wooden passage is an executable route action. Ignore
        // its movable leaf during preflight, but never ignore a neighboring
        // jamb or wall just because the sample center lies in a door tile.
        for(BlockPos at:BlockPos.betweenClosed(BlockPos.containing(body.minX,body.minY,body.minZ),
                BlockPos.containing(body.maxX,body.maxY,body.maxZ))){
            if(handDoor(world,at))continue;
            for(AABB box:world.getBlockState(at).getCollisionShape(world,at).toAabbs())
                if(box.move(at).intersects(body))return false;
        }
        return true;
    }
    private static boolean groundedNativeStart(PathfinderMob mob,BlockPos feet){
        BlockPos actual=mob.blockPosition();
        return mob.onGround()&&feet.getX()==actual.getX()
                &&feet.getZ()==actual.getZ()&&Math.abs(feet.getY()-mob.getY())<=1.05;
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
            if(!(i==0&&path.getNodeCount()>1&&groundedNativeStart(mob,feet))
                    &&!standingTile(level,mob,feet))return false;
            if(i==start)continue;
            Node previous=path.getNode(i-1);
            BlockPos previousFeet=new BlockPos(previous.x,previous.y,previous.z);
            boolean physicalStart=i==1&&groundedNativeStart(mob,previousFeet);
            double a=physicalStart?mob.getY():floorAt(level,previousFeet);
            double b=floorAt(level,feet);
            // One-block jumps and stair steps are legal for vanilla ground
            // mobs; reject only taller climbs or dangerous drops. Clearance
            // is checked at the raised position before entering the ledge.
            if(Double.isNaN(a)||Double.isNaN(b)
                    ||b-a>1.05||a-b>1.50)return false;
            double startX=physicalStart?mob.getX():previous.x+.5;
            double startZ=physicalStart?mob.getZ():previous.z+.5;
            double dx=node.x+.5-startX,dz=node.z+.5-startZ;
            int samples=Math.max(1,(int)Math.ceil(Math.hypot(dx,dz)*3));
            for(int k=1;k<samples;k++){
                double t=(double)k/samples;
                double x=startX+dx*t,z=startZ+dz*t;
                BlockPos beforeFeet=new BlockPos(previous.x,previous.y,previous.z);
                // Stair risers and half slabs are stepped over, not walked
                // through diagonally at intermediate interpolated heights.
                // Sweeping the worker through the step's collision shape
                // repeatedly rejected legal vanilla paths as blocked.
                boolean architecturalStep=partialFloor(level.getBlockState(beforeFeet))
                        ||partialFloor(level.getBlockState(feet))
                        ||partialFloor(level.getBlockState(beforeFeet.below()))
                        ||partialFloor(level.getBlockState(feet.below()));
                // Collision surfaces are not limited to stairs/slabs.
                // Fence tops stand 0.5 above neighboring full blocks;
                // interpolating through that riser rejects a valid step.
                // Sweep at the higher supported surface for every real
                // rise/drop, retaining head/body collision checks.
                boolean raisedSurface=Math.abs(a-b)>.05;
                double y=architecturalStep||raisedSurface
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

    /** One live navigation policy for every worker: collision-swept
     * eight-direction A* with trained door/slab/scaffold/turn costs.
     * Native Minecraft only moves along short physically validated legs.
     * There is no legacy native-only route selector or V1 fallback.
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
    private static boolean endsAtExactTile(ServerLevel level,Path path,BlockPos target){
        if(path==null||path.getNodeCount()<1)return false;
        Node last=path.getNode(path.getNodeCount()-1);
        // canReach() may accept a waypoint near the requested cell.
        // A Builder requiring actual interaction range cannot mistake
        // that approximate endpoint for an exact standing destination.
        if(last.x!=target.getX()||last.z!=target.getZ())return false;
        double expected=floorAt(level,target);
        double actual=floorAt(level,new BlockPos(last.x,last.y,last.z));
        // Integer aliases on fences/slabs are legal only when they mean
        // the SAME physical standing height, never an arbitrary Y +/-1.
        return Double.isFinite(expected)&&Double.isFinite(actual)
                &&Math.abs(expected-actual)<.05;
    }
    public static boolean canReachExact(ServerLevel level,PathfinderMob mob,
                                        BlockPos target){
        if(target==null||!level.hasChunkAt(target))return false;
        // Exact in-place standing must work even before Minecraft has
        // produced its first navigation path for this real entity.
        if(mob.blockPosition().equals(target)
                &&standingTile(level,mob,target))return true;
        Path path=mob.getNavigation().createPath(target,0);
        return endsAtExactTile(level,path,target)&&safePath(level,mob,path,false);
    }
    /**
     * Work-position preview uses the SAME terrain-weighted A* and the
     * SAME executable short native waypoint handoff as routeToExact().
     * A vanilla createPath(goal) alone is not proof that trained movement
     * can start. This method never calls moveTo, changes reservations,
     * or mutates navigation state.
     *
     * Multiple safe standing positions share a single bounded A* search
     * instead of rebuilding up to 98 separate paths per work block.
     * A partial route is not proof that the actual work pose is reachable.
     */
    public static BlockPos selectExecutableStand(ServerLevel level,
            PathfinderMob mob,List<BlockPos> candidates){
        if(candidates==null||candidates.isEmpty())return null;
        List<BlockPos> goals=new ArrayList<>();
        for(BlockPos p:candidates)
            if(p!=null&&standingTile(level,mob,p))goals.add(p);
        if(goals.isEmpty())return null;
        if(goals.contains(mob.blockPosition()))
            return mob.blockPosition().immutable();
        Progress progress=states.get(mob);
        // A geometrically valid cheapest route can have a first leg that
        // native movement cannot execute. Reject that destination only,
        // then let the same trained planner consider another work stance.
        // Bound retries so one blocked target cannot monopolize a tick.
        for(int attempt=0;attempt<4&&!goals.isEmpty();attempt++){
            WorkerRoutePlanner.Route route=planExactRoute(
                    level,mob,goals,progress==null?null:progress.lastBad);
            if(route==null||!route.complete()||route.steps().isEmpty())
                return null;
            BlockPos reached=route.steps().get(route.steps().size()-1);
            if(!goals.contains(reached))return null;
            if(canBeginTrainedLeg(level,mob,route))return reached.immutable();
            goals.removeIf(reached::equals);
        }
        return null;
    }

    private static WorkerRoutePlanner.Route planExactRoute(
            ServerLevel level,PathfinderMob mob,List<BlockPos> goals,
            BlockPos avoid){
        return planExactRoute(level,mob,goals,avoid,null);
    }
    private static WorkerRoutePlanner.Route planExactRoute(
            ServerLevel level,PathfinderMob mob,List<BlockPos> goals,BlockPos avoid,
            java.util.function.Consumer<WorkerRoutePlanner.SearchStats> observer){
        // Exact work probes must honor the same expiry as ordinary routing.
        // Otherwise a temporary congestion failure becomes a permanent wall
        // while a worker only asks for construction stances.
        Progress progress=states.get(mob);
        if(progress!=null&&mob.tickCount>=progress.avoidUntil){
            if(avoid!=null&&avoid.equals(progress.lastBad))avoid=null;
            progress.lastBad=null;
        }
        List<Vec3> crowds=nearbyWorkers(level,mob);
        BlockPos start=mob.blockPosition();
        // The occupied start may straddle a ledge or partial block. Its
        // actual grounded height is authoritative; other nodes must still
        // prove their support and body clearance from world geometry.
        var route=WorkerRoutePlanner.plan(start,goals,
                at->standingTile(level,mob,at),
                at->at.equals(start)&&mob.onGround()?mob.getY():floorAt(level,at),avoid,
                at->trainedTerrainCost(level,at)+crowdCost(at,crowds),observer);
        if(route!=null||!mob.onGround()||standingTile(level,mob,start))return route;
        // The real body can stand on the CORNER of a neighboring block
        // while its block-position center is over AIR. A*'s conservative
        // diagonal corner rule must not strand that already-grounded body.
        // Rejoin actual support by a swept, continuously supported segment;
        // all subsequent movement still uses the same trained planner.
        WorkerRoutePlanner.Route best=null;
        int attempts=0;
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
            if(dx==0&&dz==0)continue;
            BlockPos seed=start.offset(dx,0,dz);
            if(seed.equals(avoid)||!supportedRejoin(level,mob,seed))continue;
            if(++attempts>3)break;
            var onward=WorkerRoutePlanner.plan(seed,goals,
                    at->standingTile(level,mob,at),at->floorAt(level,at),avoid,
                    at->trainedTerrainCost(level,at)+crowdCost(at,crowds));
            if(onward==null)continue;
            List<BlockPos> steps=new ArrayList<>();
            steps.add(seed);steps.addAll(onward.steps());
            var joined=new WorkerRoutePlanner.Route(List.copyOf(steps),
                    onward.complete(),onward.cost()+14,onward.explored());
            if(best==null||joined.complete()&&!best.complete()
                    ||joined.complete()==best.complete()&&joined.cost()<best.cost())
                best=joined;
        }
        return best;
    }

    private static boolean supportedRejoin(ServerLevel level,PathfinderMob mob,
            BlockPos seed){
        if(!standingTile(level,mob,seed))return false;
        double y=floorAt(level,seed);
        if(Math.abs(y-mob.getY())>.05)return false; // no invented hop/fall
        double half=mob.getBbWidth()*.5;
        for(int sample=0;sample<=8;sample++){
            double t=sample/8.0;
            double x=mob.getX()+(seed.getX()+.5-mob.getX())*t;
            double z=mob.getZ()+(seed.getZ()+.5-mob.getZ())*t;
            AABB body=new AABB(x-half,y+.01,z-half,
                    x+half,y+mob.getBbHeight()-.025,z+half);
            if(!level.getFluidState(BlockPos.containing(x,y+.02,z)).isEmpty()
                    ||level.getBlockCollisions(mob,body).iterator().hasNext())return false;
            AABB contact=new AABB(x-half,y-.04,z-half,x+half,y+.001,z+half);
            if(!level.getBlockCollisions(mob,contact).iterator().hasNext())
                return false;
        }
        return true;
    }

    /** Geometry-only preview from a prospective grounded grapple landing.
     * Does not relocate the entity or invoke native movement from fake feet.
     * Execution still revalidates the actual first leg after dismounting.
     */
    public static boolean hasGroundRouteFrom(ServerLevel level,
            PathfinderMob mob,BlockPos start,List<BlockPos> goals){
        if(!standingTile(level,mob,start)||goals.isEmpty())return false;
        var route=WorkerRoutePlanner.plan(start,goals,
                at->standingTile(level,mob,at),at->floorAt(level,at),null,
                at->trainedTerrainCost(level,at));
        return route!=null&&route.complete();
    }

    /** Conservative set of grounded cells reached under the same trained
     * rules and node budget. A goal outside the window makes the search
     * expose its whole admitted component instead of stopping at one tile.
     * A budget-limited result can miss access, but cannot invent access.
     */
    public static java.util.Set<BlockPos> groundReachableFrom(ServerLevel level,
            PathfinderMob mob,BlockPos start){
        if(!standingTile(level,mob,start))return java.util.Set.of();
        WorkerRoutePlanner.SearchStats[] stats={null};
        WorkerRoutePlanner.plan(start,
                List.of(start.offset(WorkerRoutePlanner.RADIUS+1,0,0)),
                at->standingTile(level,mob,at),at->floorAt(level,at),null,
                at->trainedTerrainCost(level,at),s->stats[0]=s);
        return stats[0]==null?java.util.Set.of(start):stats[0].reachable();
    }

    /** Read-only reason why the trained work selector cannot start. */
    public static String diagnoseExecutableStand(ServerLevel level,
            PathfinderMob mob,List<BlockPos> candidates){
        if(candidates.isEmpty())return "no_candidates";
        if(candidates.contains(mob.blockPosition()))return "already_at_candidate";
        WorkerRoutePlanner.SearchStats[] stats={null};
        WorkerRoutePlanner.Route route=planExactRoute(
                level,mob,candidates,null,s->stats[0]=s);
        if(route==null){
            List<String> neighbors=new ArrayList<>();
            BlockPos start=mob.blockPosition();
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)
                for(int dy=-1;dy<=1;dy++){
                    if(dx==0&&dz==0)continue;
                    BlockPos at=start.offset(dx,dy,dz);
                    if(standingTile(level,mob,at))
                        neighbors.add(dx+","+dy+","+dz+" height="
                                +(floorAt(level,at)-mob.getY()));
                }
            return "search_no_route stats="+stats[0]
                    +" startCenter="+standingTile(level,mob,start)
                    +" floor="+floorAt(level,start)+" physicalY="+mob.getY()
                    +" neighbors="+neighbors
                    +" native="+diagnoseFirstLeg(level,mob,
                        new WorkerRoutePlanner.Route(List.of(candidates.get(0)),true,0,0));
        }
        if(!route.complete())return "search_partial explored="+route.explored();
        if(route.steps().isEmpty())return "search_empty";
        return canBeginTrainedLeg(level,mob,route)?"first_leg_valid":
                "first_leg_rejected explored="+route.explored()
                +" startFloor="+floorAt(level,mob.blockPosition())
                +" physicalY="+mob.getY()+" grounded="+mob.onGround()
                +" handoff="+diagnoseFirstLeg(level,mob,route);
    }

    private static String diagnoseFirstLeg(ServerLevel level,
            PathfinderMob mob,WorkerRoutePlanner.Route route){
        List<String> probes=new ArrayList<>();
        BlockPos origin=mob.blockPosition();
        for(int span:new int[]{3,2,1}){
            BlockPos waypoint=route.steps().get(Math.min(route.steps().size(),span)-1);
            Path path=mob.getNavigation().createPath(waypoint,0);
            if(path==null){probes.add(span+":null");continue;}
            List<String> nodes=new ArrayList<>();
            for(int j=0;j<Math.min(8,path.getNodeCount());j++){
                Node n=path.getNode(j);
                BlockPos at=new BlockPos(n.x,n.y,n.z);
                nodes.add(at.subtract(origin).toShortString()
                        +" floor="+(floorAt(level,at)-origin.getY())
                        +" stand="+standingTile(level,mob,at)
                        +" below="+level.getBlockState(at.below()));
            }
            probes.add(span+":end="+endsAtExactTile(level,path,waypoint)
                    +" reach="+path.canReach()+" safe="+safePath(level,mob,path,false)
                    +" nodes="+nodes+" sweep="+diagnoseSweep(level,mob,path));
        }
        return probes.toString();
    }

    private static String diagnoseSweep(ServerLevel level,PathfinderMob mob,Path path){
        BlockPos origin=mob.blockPosition();
        for(int i=1;i<path.getNodeCount();i++){
            Node prev=path.getNode(i-1),next=path.getNode(i);
            BlockPos a=new BlockPos(prev.x,prev.y,prev.z),b=new BlockPos(next.x,next.y,next.z);
            boolean actual=i==1&&groundedNativeStart(mob,a);
            double fromY=actual?mob.getY():floorAt(level,a),toY=floorAt(level,b);
            if(Math.abs(fromY-toY)>.60)continue;
            double sx=actual?mob.getX():prev.x+.5,sz=actual?mob.getZ():prev.z+.5;
            double dx=next.x+.5-sx,dz=next.z+.5-sz;
            int samples=Math.max(1,(int)Math.ceil(Math.hypot(dx,dz)*3));
            for(int k=1;k<samples;k++){
                double x=sx+dx*k/samples,z=sz+dz*k/samples,y=Math.max(fromY,toY);
                if(bodyClear(level,mob,x,y,z))continue;
                List<String> blocks=new ArrayList<>();
                double half=mob.getBbWidth()*.5;
                AABB body=new AABB(x-half,y+.01,z-half,x+half,y+mob.getBbHeight()-.025,z+half);
                for(BlockPos at:BlockPos.betweenClosed(BlockPos.containing(body.minX,body.minY,body.minZ),
                        BlockPos.containing(body.maxX,body.maxY,body.maxZ))){
                    for(AABB box:level.getBlockState(at).getCollisionShape(level,at).toAabbs())
                        if(box.move(at).intersects(body)){
                            blocks.add(at.subtract(origin).toShortString()+":"+level.getBlockState(at));break;
                        }
                }
                return "blocked@"+(x-origin.getX())+","+(y-origin.getY())+","+(z-origin.getZ())
                        +" by="+blocks+" body="+body;
            }
        }
        return "no_sample_collision";
    }

    private static boolean canBeginTrainedLeg(ServerLevel level,
            PathfinderMob mob,WorkerRoutePlanner.Route route){
        // Preflight and execution must use identical door capabilities.
        if(mob.getNavigation() instanceof GroundPathNavigation ground)
            ground.setCanOpenDoors(true);
        for(int span:new int[]{3,2,1}){
            BlockPos waypoint=route.steps().get(
                    Math.min(route.steps().size(),span)-1);
            Path path=mob.getNavigation().createPath(waypoint,0);
            if(endsAtExactTile(level,path,waypoint)
                    &&safePath(level,mob,path,false))return true;
        }
        return !route.steps().isEmpty()
                &&canWalkTrainedEdge(level,mob,route.steps().get(0));
    }

    /** Execute only the first same-height edge of the trained route when
     * native navigation rejects its handoff (e.g. an off-center roof rim).
     * Every sample must retain sole contact and full body clearance. This
     * cannot jump, cross a gap, skip a route corner, or create support.
     */
    private static boolean canWalkTrainedEdge(ServerLevel level,
            PathfinderMob mob,BlockPos waypoint){
        return mob instanceof com.caszgamermd.golemsplus.builder.BuilderGolem
                &&mob.onGround()&&!mob.isNoGravity()
                &&mob.distanceToSqr(waypoint.getX()+.5,mob.getY(),waypoint.getZ()+.5)>.025*.025
                &&mob.distanceToSqr(waypoint.getX()+.5,mob.getY(),waypoint.getZ()+.5)<=4
                &&supportedRejoin(level,mob,waypoint);
    }
    private static boolean walkTrainedEdge(ServerLevel level,PathfinderMob mob,
            Progress p,BlockPos waypoint){
        if(!canWalkTrainedEdge(level,mob,waypoint))return false;
        p.centering=waypoint.immutable();p.approach=waypoint.immutable();
        p.stalled=0;p.unsafeChecks=0;p.nextRoute=mob.tickCount+CHECK_TICKS;
        return tickExactCenter(level,mob);
    }

    private static void clearDoor(ServerLevel level,PathfinderMob mob,Progress p){
        if(p.queuedDoor!=null)WorkerDoorTraffic.release(level,p.queuedDoor,mob);
        p.queuedDoor=null;p.doorStandby=null;p.doorExit=null;p.doorOut=null;
        p.waitingDoor=false;p.parkingAtDoor=false;p.finishingDoor=false;
        p.doorLastDistance=Double.POSITIVE_INFINITY;
        p.doorLastPoint=null;
        p.doorLastProgressTick=mob.tickCount;
    }
    public static boolean routeToExact(ServerLevel level,PathfinderMob mob,
                                       BlockPos target,double speed){
        Progress progress=state(mob);
        if(progress.centering!=null){
            if(target!=null&&target.equals(progress.requested)
                    &&tickExactCenter(level,mob))return true;
            progress.centering=null;
        }
        if(progress.queuedDoor!=null&&target!=null
                &&progress.requested!=null&&!target.equals(progress.requested))
            clearDoor(level,mob,progress);
        if(progress.waitingDoor||progress.finishingDoor)return true;
        // Exact evac/stock routes must honor the SAME brief peer courtesy
        // pause as ordinary routes. Previously they restarted the native
        // path immediately, canceling the yield and causing doorway dances.
        if(progress.wait>0)return true;
        // Retain original work target while this worker clears the door.
        if(mob instanceof com.caszgamermd.golemsplus.builder.BuilderGolem)
            speed=1.0; // nominal 4 m/s from Builder movement attribute .40
        if(target==null||!level.hasChunkAt(target))return false;
        Progress p=state(mob);
        boolean same=target.equals(p.requested);
        if(same&&!mob.getNavigation().isDone()
                &&p.stalled<STALL_TICKS&&p.unsafeChecks==0)return true;
        if(mob.getNavigation() instanceof GroundPathNavigation ground)
            ground.setCanOpenDoors(true);
        // If the exact safe standing tile was reached on the previous
        // tick, do not reconstruct the same route and toggle navigation.
        double targetFloor=floorAt(level,target);
        boolean nearCenter=mob.onGround()
                &&mob.distanceToSqr(target.getX()+.5,targetFloor,target.getZ()+.5)<.81
                &&Math.abs(mob.getY()-targetFloor)<.05
                &&supportedRejoin(level,mob,target);
        if((mob.blockPosition().equals(target)||nearCenter)
                &&standingTile(level,mob,target)){
            p.requested=target.immutable();p.approach=target.immutable();
            // Normal MoveControl acceleration must not fight the final
            // fraction of a block. A per-entity-tick physical controller
            // centers this pose with current collision/support checks.
            p.centering=target.immutable();
            mob.getNavigation().stop();
            boolean moving=tickExactCenter(level,mob);
            return moving||mob.distanceToSqr(target.getX()+.5,
                    targetFloor,target.getZ()+.5)<.05*.05;
        }
        if(!standingTile(level,mob,target))return false;
        // Exact destinations also use the ONE trained planner. A
        // native navigation path is used solely to physically execute
        // a short already-scored waypoint, not as an alternate AI.
        WorkerRoutePlanner.Route plan=planExactRoute(
                level,mob,List.of(target),p.lastBad);
        if(plan==null||plan.steps().isEmpty())return false;
        p.requested=target.immutable();
        if(prepareTrainedDoor(level,mob,p,plan.steps()))return true;
        for(int span:new int[]{3,2,1}){
            BlockPos waypoint=plan.steps().get(Math.min(
                    plan.steps().size(),span)-1);
            Path path=mob.getNavigation().createPath(waypoint,0);
            if(!endsAtExactTile(level,path,waypoint)
                    ||!safePath(level,mob,path,false)
                    ||!mob.getNavigation().moveTo(path,speed))continue;
            p.requested=target.immutable();p.approach=waypoint.immutable();
            p.weightedRoute=plan.steps();
            p.routeCursor=Math.min(plan.steps().size(),span);
            p.stalled=0;p.unsafeChecks=0;p.wait=0;p.lastBad=null;
            p.nextRoute=mob.tickCount+CHECK_TICKS;
            return true;
        }
        if(walkTrainedEdge(level,mob,p,plan.steps().get(0))){
            p.weightedRoute=plan.steps();p.routeCursor=1;
            return true;
        }
        return false;
    }

    public static boolean routeTo(ServerLevel level,PathfinderMob mob,BlockPos target,
                                  double speed){
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
            p.centering=null;
            p.requested=target.immutable();p.approach=null;
            p.attempts=0;p.nextRoute=0;p.stalled=0;p.unsafeChecks=0;
            p.weightedRoute=List.of();p.routeCursor=0;p.lastBad=null;
        }
        if(p.centering!=null&&tickExactCenter(level,mob))return true;
        updateDoorClearance(level,mob,p);
        if(p.openedDoor==null)openApproachDoor(level,mob,p);
        if(p.waitingDoor)return true;
        if(p.wait>0)return true;
        if(!changed&&!mob.getNavigation().isDone()
                &&p.stalled<STALL_TICKS&&p.unsafeChecks==0)return true;
        if(!changed&&mob.tickCount<p.nextRoute)return false; // retry scheduled, not a hard no-path
        // A temporary body-block or a closed door must not permanently
        // blacklist the only valid aisle. Expire bad waypoints after
        // roughly three seconds, then try the new world state.
        if(p.lastBad!=null&&mob.tickCount>=p.avoidUntil)
            p.lastBad=null;

        // Every live route now uses ONE trained, terrain-weighted policy.
        // Vanilla may execute validated short legs, but it no longer
        // independently picks a competing native-only route.
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
            // Real-entity doorway regression: a requested walkable tile
            // MUST be the destination. Treating every nearby tile as a
            // goal lets A* stop on the near side of a door with no next
            // waypoint, even though the actual goal is clear.
            if(standingTile(level,mob,target)){
                goals.add(target.immutable());
            }else{
                // Solid workstation / future placement block: choose a
                // collision-valid approach near the structure rather than
                // trying to walk INTO solid blocks. This is never a
                // fallback to the old native path-selection algorithm.
                for(Candidate candidate:candidateGoals(level,mob,target,avoid)){
                    if(goals.size()>=16)break;
                    goals.add(candidate.feet());
                }
            }
            WorkerRoutePlanner.Route route=planExactRoute(
                    level,mob,goals,p.lastBad);
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
        if(prepareTrainedDoor(level,mob,p,p.weightedRoute.subList(p.routeCursor,count)))return true;
        for(int distance:new int[]{3,2,1}){
            int index=Math.min(count-1,p.routeCursor+distance-1);
            if(index<p.routeCursor)continue;
            BlockPos waypoint=p.weightedRoute.get(index);
            if(!standingTile(level,mob,waypoint))continue;
            Path path=mob.getNavigation().createPath(waypoint,0);
            if(!endsAtExactTile(level,path,waypoint)
                    ||!safePath(level,mob,path,false))continue;
            if(!mob.getNavigation().moveTo(path,speed))continue;
            p.approach=waypoint;
            p.routeCursor=index+1;
            p.stalled=0;p.unsafeChecks=0;p.lastBad=null;
            p.nextRoute=mob.tickCount+CHECK_TICKS;
            return true;
        }
        if(walkTrainedEdge(level,mob,p,p.weightedRoute.get(p.routeCursor))){
            p.routeCursor++;
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
        return "goal="+requested+" via="+destination
                +" retry="+remaining+"t attempts="+p.attempts
                +" stalled="+p.stalled+" unsafe="+p.unsafeChecks
                +" doorYields="+p.doorReservationYields
                +(p.queuedDoor==null?"":" door="+p.queuedDoor.toShortString()
                    +(p.waitingDoor?" WAIT":p.finishingDoor?" EXIT":" CROSS")
                    +" q#"+rank);
    }

    /** A retry timer alone is not evidence of a valid route. Equipment
     * handoffs may defer only while an actual trained plan is retained.
     */
    public static boolean hasPlannedGroundRoute(PathfinderMob mob,BlockPos goal){
        Progress p=states.get(mob);
        return p!=null&&goal.equals(p.requested)&&!p.weightedRoute.isEmpty();
    }

    public static boolean retryScheduled(PathfinderMob mob){
        Progress p=states.get(mob);
        return p!=null&&p.requested!=null
                &&(mob.tickCount<p.nextRoute||!mob.getNavigation().isDone());
    }

    public static void stop(PathfinderMob mob){
        Progress p=state(mob);
        if(mob.level() instanceof ServerLevel level)clearDoor(level,mob,p);
        p.requested=null;p.approach=null;p.centering=null;p.stalled=0;p.unsafeChecks=0;
        p.weightedRoute=List.of();p.routeCursor=0;p.lastBad=null;
        mob.getNavigation().stop();
        // Door clearance is reset during the next server navigation tick.
    }

    /** Exact final centering is ordinary supported horizontal motion.
     * Called before Builder physics so native acceleration cannot restart
     * each five-tick work update and oscillate around the requested center.
     */
    public static boolean tickExactCenter(ServerLevel level,PathfinderMob mob){
        Progress p=states.get(mob);
        if(p==null||p.centering==null)return false;
        BlockPos target=p.centering;
        if(!mob.onGround()||mob.isNoGravity()
                ||Math.abs(mob.getY()-floorAt(level,target))>.05
                ||!supportedRejoin(level,mob,target)){
            p.centering=null;return false;
        }
        mob.getNavigation().stop();
        mob.getMoveControl().setWantedPosition(mob.getX(),mob.getY(),mob.getZ(),0);
        double dx=target.getX()+.5-mob.getX(),dz=target.getZ()+.5-mob.getZ();
        Vec3 velocity=mob.getDeltaMovement();
        if(dx*dx+dz*dz<.025*.025){
            mob.setDeltaMovement(0,velocity.y,0);
            p.centering=null;return false;
        }
        double vx=Math.max(-.08,Math.min(.08,dx*.5));
        double vz=Math.max(-.08,Math.min(.08,dz*.5));
        if(!level.noCollision(mob,mob.getBoundingBox().move(vx,0,vz))){
            p.centering=null;return false;
        }
        mob.setDeltaMovement(vx,velocity.y,vz);
        return true;
    }

    /** Call every server tick, including when idle, to operate and close doors. */
    public static void tick(ServerLevel level,PathfinderMob mob){
        if(tickExactCenter(level,mob))return;
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
                if(blocked&&old!=null&&old.getNextNodeIndex()<old.getNodeCount()){
                    Node node=old.getNode(old.getNextNodeIndex());
                    p.lastBad=new BlockPos(node.x,node.y,node.z);
                    p.avoidUntil=mob.tickCount+60;
                }else if(!blocked){
                    // A teammate blocking an aisle is not an invalid
                    // floor tile: keep every legal movement available
                    // and use soft crowd cost for the next plan instead.
                    p.lastBad=null;
                }
                p.weightedRoute=List.of();p.routeCursor=0;
                p.nextRoute=mob.tickCount+CHECK_TICKS;
                mob.getNavigation().stop();
                return;
            }
        }
        if(p.requested!=null&&!mob.getNavigation().isDone()
                &&yieldToWorker(level,mob,p)){
            // Brief courtesy pause; preserve the trained plan. Traffic
            // is NOT a terrain failure, so there is no route mutation,
            // abandoned waypoint or new global search on every encounter.
            p.wait=5+Math.floorMod(mob.getId(),4);
            mob.getNavigation().stop();
            p.nextRoute=0;p.stalled=0;
            p.routeCursor=Math.max(0,p.routeCursor-3);
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
                if(!endsAtExactTile(level,path,tile)
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

    /** Reserve the actual imminent trained passage, including detours
     * that initially lead AWAY from the final work/depot destination. */
    private static boolean prepareTrainedDoor(ServerLevel level,PathfinderMob mob,
            Progress p,List<BlockPos> steps){
        for(int i=0;i<Math.min(6,steps.size());i++){
            BlockPos at=steps.get(i);
            BlockPos door=handDoor(level,at)?WorkerDoorTraffic.canonical(level,at):null;
            if(door==null||mob.distanceToSqr(door.getX()+.5,mob.getY(),door.getZ()+.5)>25)continue;
            if(p.queuedDoor!=null&&!p.queuedDoor.equals(door))clearDoor(level,mob,p);
            Direction normal=entranceFacing(level.getBlockState(door));
            double advance=0;
            for(int j=i+1;j<steps.size();j++){
                BlockPos after=steps.get(j);
                advance=(after.getX()-door.getX())*normal.getStepX()
                        +(after.getZ()-door.getZ())*normal.getStepZ();
                if(Math.abs(advance)>.5)break;
            }
            if(Math.abs(advance)<.5)
                advance=-((mob.getX()-door.getX()-.5)*normal.getStepX()
                        +(mob.getZ()-door.getZ()-.5)*normal.getStepZ());
            if(Math.abs(advance)<.1)continue;
            if(p.queuedDoor==null){
                p.queuedDoor=door;p.doorLastPoint=mob.position();
                p.doorLastDistance=Double.POSITIVE_INFINITY;p.doorLastProgressTick=mob.tickCount;
            }
            p.doorOut=advance>0?normal:normal.getOpposite();
            if(!WorkerDoorTraffic.request(level,door,mob)){
                p.waitingDoor=true;return true;
            }
            if(!isOpenPassage(level.getBlockState(door))){
                setPassageOpen(level,mob,door,true);p.openedDoor=door;p.doorAge=0;
            }
            return false;
        }
        return false;
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
                &&p.stalled<STALL_TICKS
                &&!WorkerDoorTraffic.inside(level,door,mob))return false;
        BlockState gate=level.getBlockState(door);
        if(!handDoor(level,door))return false;
        Direction facing=entranceFacing(gate);
        double advance=(p.requested.getX()-door.getX())*facing.getStepX()
                +(p.requested.getZ()-door.getZ())*facing.getStepZ();
        if(Math.abs(advance)<.5&&p.doorOut==null)return false;
        Direction out=p.doorOut!=null?p.doorOut:advance>0?facing:facing.getOpposite();
        // Only take the straight physical corridor when this worker is
        // aligned with its real door opening. Others continue using A*
        // to approach it, so we never steer through a wall at an angle.
        double side=out.getStepX()==0
                ?Math.abs(mob.getX()-door.getX()-.5)
                :Math.abs(mob.getZ()-door.getZ()-.5);
        // An actual mob can drift ~0.3 blocks sideways while queued
        // beside the open leaf. Abandoning the exit escort at .24
        // stranded the head just OUTSIDE the threshold and held the
        // other three workers forever. Continue steering toward the
        // centered exit; collision checks below still prevent phasing.
        if(side>.46){
            // A worker can stop diagonally across a gate corner with its
            // center just beyond the escort alignment tolerance. It is
            // already the physical occupant: recenter across the passage
            // before asking native A* to restart inside that same gate.
            if(!WorkerDoorTraffic.inside(level,door,mob))return false;
            if(!isOpenPassage(gate))setPassageOpen(level,mob,door,true);
            double dx=out.getStepX()==0?door.getX()+.5-mob.getX():0;
            double dz=out.getStepX()!=0?door.getZ()+.5-mob.getZ():0;
            double vx=Math.max(-.08,Math.min(.08,dx));
            double vz=Math.max(-.08,Math.min(.08,dz));
            if(!level.noCollision(mob,mob.getBoundingBox().move(vx,0,vz)))return false;
            mob.getNavigation().stop();
            mob.getMoveControl().setWantedPosition(mob.getX(),mob.getY(),mob.getZ(),0);
            mob.setDeltaMovement(vx,Math.min(0,mob.getDeltaMovement().y),vz);
            return true;
        }
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
            if(!handDoor(level,p.queuedDoor)
                    ||!p.finishingDoor
                        &&WorkerDoorTraffic.crossed(level,p.queuedDoor,mob)
                    ||mob.distanceToSqr(p.queuedDoor.getX()+.5,
                            p.queuedDoor.getY()+.5,p.queuedDoor.getZ()+.5)>64){
                WorkerDoorTraffic.release(level,p.queuedDoor,mob);
                p.queuedDoor=null;p.waitingDoor=false;p.doorOut=null;
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
                p.doorLastPoint=mob.position();
            }
        }
        if(p.queuedDoor==null)return false;
        double doorDistance=mob.distanceToSqr(
                p.queuedDoor.getX()+.5,p.queuedDoor.getY()+.5,
                p.queuedDoor.getZ()+.5);
        // Door ownership requires REAL MOTION. The previous timeout
        // applied only beyond four blocks; a stationary head right next
        // to the door could monopolize a whole crew indefinitely.
        if(WorkerDoorTraffic.owns(level,p.queuedDoor,mob)){
            boolean moved=p.doorLastPoint==null
                    ||mob.position().distanceToSqr(p.doorLastPoint)>.025;
            if(moved||doorDistance<p.doorLastDistance-.08){
                p.doorLastPoint=mob.position();
                p.doorLastDistance=doorDistance;
                p.doorLastProgressTick=mob.tickCount;
            }else if(mob.tickCount-p.doorLastProgressTick>=45
                    &&!WorkerDoorTraffic.inside(level,p.queuedDoor,mob)){
                WorkerDoorTraffic.yieldStalled(level,p.queuedDoor,mob);
                p.doorReservationYields++;
                p.waitingDoor=true;
                p.doorStandby=null;p.parkingAtDoor=false;
                p.doorLastProgressTick=mob.tickCount;
                p.nextRoute=0;
                p.weightedRoute=List.of();p.routeCursor=0;
                mob.getNavigation().stop();
                return true;
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
                if(endsAtExactTile(level,park,p.doorStandby)
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
            // Trial 3 discovery: slot/UUID priority alone makes a
            // FOLLOWER block a LEADER on narrow one-way corridors.
            // Only the rear worker yields in a same-direction convoy.
            // At head-on approaches, deterministic UUID priority prevents
            // reciprocal yielding (the failed 5/9 experiment).
            Path peer=other.getNavigation().getPath();
            Vec3 heading=other.getDeltaMovement();
            if(peer!=null&&peer.getNextNodeIndex()<peer.getNodeCount()){
                Node node=peer.getNode(peer.getNextNodeIndex());
                heading=new Vec3(node.x+.5-other.getX(),0,
                        node.z+.5-other.getZ());
            }
            Vec3 flatHeading=new Vec3(heading.x,0,heading.z);
            boolean sameDirection=flatHeading.lengthSqr()>.01
                    &&flatHeading.normalize().dot(forward)>.70;
            if(sameDirection){
                // Do not park a convoy for 15+ ticks when the golem in
                // front is moving away. Yield only on actual close contact.
                if(forwardDist<.85)return true;
                continue;
            }
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
