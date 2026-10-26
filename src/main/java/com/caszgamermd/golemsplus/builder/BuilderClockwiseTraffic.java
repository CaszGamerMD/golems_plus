package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Physical, clockwise right-of-way for Builder-to-Builder encounters.
 *
 * A traffic participant waits a per-encounter random 1..30 ticks BEFORE
 * it attempts a side step. Only one of two opposing moving workers yields
 * (stable UUID tie-break). This avoids the "both step aside, both step
 * back" dance that restarts the same A* detour forever.
 *
 * The yielding Builder tries RIGHT first, then the remaining positions
 * CLOCKWISE around its feet. Each destination must be a real, unloaded-
 * chunk-safe, supported same-height standing tile, without body collision.
 * No teleporting, creative scaffolds or disabled world collision.
 */
public final class BuilderClockwiseTraffic {
    public static final int MIN_WAIT_TICKS=1;
    public static final int MAX_WAIT_TICKS=30;
    private static final long MAX_MANEUVER_TICKS=110;
    private static final long POST_PASS_COOLDOWN=25;
    private static final Map<BuilderGolem,Maneuver> active=
            new WeakHashMap<>();
    private static final Map<BuilderGolem,Long> cooldowns=
            new WeakHashMap<>();
    private static final Map<BuilderGolem,Integer> completedYields=
            new WeakHashMap<>();
    private static final Map<BuilderGolem,Integer> lastDelays=
            new WeakHashMap<>();

    private enum Phase { STAGGER, MOVE_CLOCKWISE, GIVE_WAY }
    private static final class Maneuver {
        final UUID blocker;
        final Direction forward;
        final long started,after;
        final int delay;
        Phase phase=Phase.STAGGER;
        BlockPos destination;
        long parkedAt;
        Maneuver(UUID blocker,Direction forward,long now,int delay){
            this.blocker=blocker;
            this.forward=forward;
            this.started=now;
            this.after=now+delay;
            this.delay=delay;
        }
    }

    private BuilderClockwiseTraffic(){}

    public static boolean controlling(BuilderGolem worker){
        return active.containsKey(worker);
    }
    public static void cancel(BuilderGolem worker){
        active.remove(worker);
        cooldowns.remove(worker);
    }
    public static int completedYields(BuilderGolem worker){
        return completedYields.getOrDefault(worker,0);
    }
    public static int lastDelay(BuilderGolem worker){
        return lastDelays.getOrDefault(worker,0);
    }
    public static String debug(BuilderGolem worker){
        Maneuver m=active.get(worker);
        return m==null?"NONE":m.phase+" delay="+m.delay
                +" destination="+(m.destination==null?"pending":
                    m.destination.toShortString());
    }

    /** Pure cardinal rotation used by both the physics driver and tests.
     * Minecraft's horizontal clockwise order is N -> E -> S -> W.
     */
    public static Direction clockwise(Direction forward){
        if(forward.getAxis()==Direction.Axis.Y)
            throw new IllegalArgumentException("Only horizontal travel can yield");
        return forward.getClockWise();
    }
    public static int staggerTicks(net.minecraft.util.RandomSource random){
        return MIN_WAIT_TICKS+random.nextInt(MAX_WAIT_TICKS);
    }

    private static Direction directionTo(BuilderGolem worker,BlockPos goal){
        double dx=goal.getX()+.5-worker.getX();
        double dz=goal.getZ()+.5-worker.getZ();
        if(Math.abs(dx)+Math.abs(dz)<.35)return null;
        return Math.abs(dx)>=Math.abs(dz)
                ?dx>=0?Direction.EAST:Direction.WEST
                :dz>=0?Direction.SOUTH:Direction.NORTH;
    }
    private static Direction forward(BuilderGolem worker){
        BlockPos goal=WorkerNavigation.requestedTarget(worker);
        if(goal!=null){
            Direction d=directionTo(worker,goal);
            if(d!=null)return d;
        }
        var path=worker.getNavigation().getPath();
        if(path!=null&&!worker.getNavigation().isDone()
                &&path.getNextNodeIndex()<path.getNodeCount()){
            var node=path.getNode(path.getNextNodeIndex());
            Direction d=directionTo(worker,
                    new BlockPos(node.x,node.y,node.z));
            if(d!=null)return d;
        }
        Vec3 motion=worker.getDeltaMovement();
        if(motion.horizontalDistanceSqr()<.003)return null;
        return Math.abs(motion.x)>=Math.abs(motion.z)
                ?motion.x>=0?Direction.EAST:Direction.WEST
                :motion.z>=0?Direction.SOUTH:Direction.NORTH;
    }
    private static boolean canStand(ServerLevel world,BuilderGolem worker,
            BlockPos feet,BuilderGolem blocker){
        if(!world.hasChunkAt(feet)||!world.hasChunkAt(feet.above())
                ||!world.hasChunkAt(feet.below())
                ||feet.getY()!=worker.blockPosition().getY()
                ||!world.getFluidState(feet).isEmpty()
                ||!WorkerNavigation.canStandOn(world,worker,feet))
            return false;
        double x=feet.getX()+.5,z=feet.getZ()+.5;
        // Don't choose the tile occupied by the other Builder or a second
        // Builder's already-reserved passing lane.
        if(blocker!=null&&blocker.distanceToSqr(x,feet.getY(),z)<1.6)
            return false;
        for(var reservation:active.entrySet()){
            if(reservation.getKey()!=worker
                    &&feet.equals(reservation.getValue().destination))
                return false;
        }
        AABB destination=worker.getBoundingBox().move(
                x-worker.getX(),feet.getY()-worker.getY(),z-worker.getZ());
        if(!world.noCollision(worker,destination))return false;
        // The FIRST move must also be physically legal. An open target
        // on the other side of a wall is not a legal one-block sidestep.
        double dx=x-worker.getX(),dz=z-worker.getZ();
        double length=Math.hypot(dx,dz);
        return length>.01&&world.noCollision(worker,
                worker.getBoundingBox().move(dx/length*.10,0,dz/length*.10));
    }
    private static BlockPos clockwiseSpace(ServerLevel world,
            BuilderGolem worker,BuilderGolem blocker,Direction heading){
        BlockPos here=worker.blockPosition();
        // Always start with the right side. Only continue clockwise if
        // that physical lane is blocked; NEVER choose a random side.
        Direction side=clockwise(heading);
        for(int n=0;n<3;n++){
            BlockPos tile=here.relative(side);
            if(canStand(world,worker,tile,blocker))return tile.immutable();
            side=clockwise(side);
        }
        return null;
    }
    private static BuilderGolem obstructing(ServerLevel world,
            BuilderGolem worker,Direction heading){
        Vec3 f=new Vec3(heading.getStepX(),0,heading.getStepZ());
        BuilderGolem nearest=null;
        double best=Double.POSITIVE_INFINITY;
        for(BuilderGolem other:world.getEntitiesOfClass(BuilderGolem.class,
                worker.getBoundingBox().inflate(1.55,0.35,1.55),
                b->b!=worker&&b.isAlive()&&!b.isPassenger())){
            if(Math.abs(other.getY()-worker.getY())>1.15)continue;
            double dx=other.getX()-worker.getX();
            double dz=other.getZ()-worker.getZ();
            double ahead=f.x*dx+f.z*dz;
            double sideways=Math.abs(f.x*dz-f.z*dx);
            double separation=dx*dx+dz*dz;
            if(ahead<.15||ahead>1.7||sideways>1.0
                    ||separation>2.7)continue;
            // If BOTH have jobs in motion, ONE golem must keep moving:
            // the lower UUID retains right of way. An idle blocker can
            // still be walked around clockwise by an active worker.
            boolean otherMoving=WorkerNavigation.requestedTarget(other)!=null;
            if(otherMoving&&worker.getUUID().compareTo(other.getUUID())<0)
                continue;
            if(separation<best){best=separation;nearest=other;}
        }
        return nearest;
    }
    private static boolean finish(ServerLevel world,BuilderGolem worker){
        active.remove(worker);
        cooldowns.put(worker,world.getGameTime()+POST_PASS_COOLDOWN);
        worker.getNavigation().stop();
        worker.setDeltaMovement(0,worker.getDeltaMovement().y,0);
        return false;
    }
    private static boolean maneuver(ServerLevel world,BuilderGolem worker,
            Maneuver m){
        long now=world.getGameTime();
        if(now-m.started>MAX_MANEUVER_TICKS
                ||!worker.onGround()&&m.phase==Phase.STAGGER)
            return finish(world,worker);
        if(m.phase==Phase.STAGGER){
            // Do NOT re-roll this delay on successive tick/job updates.
            // Both Builders hold their old path while the encounter's
            // independent per-entity timer is counting down.
            worker.getNavigation().stop();
            worker.setDeltaMovement(0,worker.getDeltaMovement().y,0);
            if(now<m.after)return true;
            var entity=world.getEntity(m.blocker);
            BuilderGolem blocker=entity instanceof BuilderGolem b&&b.isAlive()
                    ?b:null;
            m.destination=clockwiseSpace(world,worker,blocker,m.forward);
            if(m.destination==null)return finish(world,worker);
            m.phase=Phase.MOVE_CLOCKWISE;
        }
        if(m.phase==Phase.MOVE_CLOCKWISE){
            BlockPos target=m.destination;
            var entity=world.getEntity(m.blocker);
            BuilderGolem blocker=entity instanceof BuilderGolem b?b:null;
            if(!canStand(world,worker,target,blocker))
                return finish(world,worker);
            double dx=target.getX()+.5-worker.getX();
            double dz=target.getZ()+.5-worker.getZ();
            double distance=Math.hypot(dx,dz);
            if(distance<.28){
                m.phase=Phase.GIVE_WAY;
                m.parkedAt=now;
                completedYields.merge(worker,1,Integer::sum);
                lastDelays.put(worker,m.delay);
            }else{
                double speed=Math.min(.12,distance);
                double vx=dx/distance*speed,vz=dz/distance*speed;
                if(!world.noCollision(worker,
                        worker.getBoundingBox().move(vx,0,vz)))
                    return finish(world,worker);
                worker.getNavigation().stop();
                worker.setNoGravity(false);
                worker.setDeltaMovement(vx,worker.getDeltaMovement().y,vz);
                return true;
            }
        }
        worker.getNavigation().stop();
        worker.setDeltaMovement(0,worker.getDeltaMovement().y,0);
        var other=world.getEntity(m.blocker);
        boolean past=!(other instanceof BuilderGolem b&&b.isAlive())
                ||other.distanceToSqr(worker)>7.0;
        // Give the other Builder at least 20 ticks to pass before
        // rebuilding this worker's ordinary A* route.
        if(now-m.parkedAt>=20&&(past||now-m.parkedAt>=65))
            return finish(world,worker);
        return true;
    }

    /** Runs each real entity tick after scaffold/door/placement controls.
     * The construction task remains suspended until the maneuver is done.
     */
    public static boolean tickMotion(ServerLevel world,BuilderGolem worker){
        Maneuver m=active.get(worker);
        if(m!=null)return maneuver(world,worker,m);
        if(world.getGameTime()<cooldowns.getOrDefault(worker,0L)
                ||worker.tickCount%4!=0
                ||!worker.onGround()||worker.isPassenger()
                ||worker.getNavigation().isDone()
                ||BuilderScaffolding.isControllingMovement(worker)
                ||BuilderTeamGroundTraffic.controlling(worker)
                ||BuilderPlacementSpace.controlling(worker)
                ||BuilderScaffoldBridge.moving(worker))
            return false;
        Direction heading=forward(worker);
        if(heading==null)return false;
        BuilderGolem blocker=obstructing(world,worker,heading);
        if(blocker==null)return false;
        int delay=staggerTicks(worker.getRandom());
        active.put(worker,new Maneuver(blocker.getUUID(),heading,
                world.getGameTime(),delay));
        WorkerNavigation.stop(worker);
        worker.setDeltaMovement(0,worker.getDeltaMovement().y,0);
        return true;
    }
}
