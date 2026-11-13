package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Anchor-free vertical lift, with a small fixed horizontal envelope per takeoff. */
public final class BuilderFlight {
    private BuilderFlight() {}
    private static final double SPEED = .20;
    public static final double HORIZONTAL_LIMIT = 2.0;
    private static final double HORIZONTAL_LIMIT_SQUARED = HORIZONTAL_LIMIT * HORIZONTAL_LIMIT;
    private static final Map<BuilderGolem, Flight> flights = new WeakHashMap<>();

    private static final class Flight {
        List<Vec3> path = List.of();
        int next;
        BlockPos goal;
        long retryAt;
        Vec3 lastPosition;
        int stuckTicks;
        Vec3 origin;
        long landingRetryAt;
        Vec3 lastFailedDestination;
        long failedUntil;
    }

    public static boolean enabled(BuilderGolem worker) {
        return worker.propellerEnabled();
    }
    public static boolean active(BuilderGolem worker) {
        return flights.containsKey(worker);
    }
    public static boolean moving(BuilderGolem worker) {
        Flight flight = flights.get(worker);
        return flight != null && flight.next < flight.path.size();
    }
    public static void stop(BuilderGolem worker) {
        Flight flight = flights.get(worker);
        if (flight != null) {
            flight.path = List.of();
            flight.next = 0;
        }
        worker.setDeltaMovement(Vec3.ZERO);
    }
    private static Vec3 feet(BlockPos position) {
        return new Vec3(position.getX() + .5, position.getY(), position.getZ() + .5);
    }
    private static AABB body(BuilderGolem worker, Vec3 position) {
        return worker.getBoundingBox().move(position.subtract(worker.position()));
    }
    private static boolean loaded(ServerLevel level, AABB box) {
        if (box.minY < level.getMinY() || box.maxY > level.getMaxY()) return false;
        for (int x = ((int)Math.floor(box.minX)) >> 4; x <= ((int)Math.floor(box.maxX)) >> 4; x++)
            for (int z = ((int)Math.floor(box.minZ)) >> 4; z <= ((int)Math.floor(box.maxZ)) >> 4; z++)
                if (!level.hasChunk(x, z)) return false;
        return level.getWorldBorder().isWithinBounds(BlockPos.containing(box.getCenter()));
    }
    public static boolean clear(ServerLevel level, BuilderGolem worker, Vec3 position) {
        AABB box = body(worker, position);
        return loaded(level, box) && level.noCollision(worker, box);
    }
    private static boolean sweep(ServerLevel level, BuilderGolem worker, Vec3 from, Vec3 to) {
        // The swept AABB conservatively checks the whole segment, including
        // narrow corners. No velocity can cross an unchecked wall or chunk.
        AABB box = body(worker, from).minmax(body(worker, to));
        return loaded(level, box) && level.noCollision(worker, box);
    }
    private static Vec3 origin(BuilderGolem worker) {
        Flight flight = flights.get(worker);
        return flight == null ? worker.position() : flight.origin;
    }
    private static boolean inEnvelope(Vec3 origin, Vec3 point) {
        double dx = point.x - origin.x, dz = point.z - origin.z;
        return dx * dx + dz * dz <= HORIZONTAL_LIMIT_SQUARED + 1e-6;
    }
    // Vertical first, or a short sideways adjustment followed by vertical.
    // No unrestricted 3D search, repeated horizontal hops, or grapple anchor.
    private static List<Vec3> path(ServerLevel level, BuilderGolem worker,
            Vec3 from, Vec3 to, Vec3 anchor) {
        if (!inEnvelope(anchor, from) || !inEnvelope(anchor, to)
                || !clear(level, worker, to)) return List.of();
        Vec3 upper = new Vec3(from.x, to.y, from.z);
        if (sweep(level, worker, from, upper) && sweep(level, worker, upper, to))
            return upper.distanceToSqr(to) < .0001 ? List.of(to) : List.of(upper, to);
        Vec3 side = new Vec3(to.x, from.y, to.z);
        if (sweep(level, worker, from, side) && sweep(level, worker, side, to))
            return side.distanceToSqr(to) < .0001 ? List.of(to) : List.of(side, to);
        // A ledge can block sideways movement at both endpoint heights.
        // Try a short vertical over/under detour inside the takeoff envelope.
        for (int offset : new int[]{1,2,3,4,-1,-2,-3,-4}) {
            double y = offset > 0 ? Math.max(from.y,to.y)+offset : Math.min(from.y,to.y)+offset;
            Vec3 a = new Vec3(from.x,y,from.z);
            Vec3 b = new Vec3(to.x,y,to.z);
            if (sweep(level,worker,from,a) && sweep(level,worker,a,b)
                    && sweep(level,worker,b,to)) return List.of(a,b,to);
        }
        return List.of();
    }
    private static List<Vec3> search(ServerLevel level, BuilderGolem worker, List<BlockPos> candidates) {
        Vec3 anchor = origin(worker);
        return candidates.stream().filter(Objects::nonNull)
                .filter(p -> inEnvelope(anchor, feet(p)))
                .filter(p -> {
                    Flight flight=flights.get(worker);
                    return flight==null||flight.lastFailedDestination==null
                            ||level.getGameTime()>=flight.failedUntil
                            ||feet(p).distanceToSqr(flight.lastFailedDestination)>.04;
                })
                .sorted(Comparator.comparingDouble(p -> worker.position().distanceToSqr(feet(p))))
                .map(p -> path(level, worker, worker.position(), feet(p), anchor))
                .filter(p -> !p.isEmpty()).findFirst().orElse(List.of());
    }
    public static BlockPos select(ServerLevel level, BuilderGolem worker, List<BlockPos> candidates) {
        List<Vec3> path = search(level, worker, candidates);
        return path.isEmpty() ? null : BlockPos.containing(path.getLast());
    }
    public static boolean request(ServerLevel level, BuilderGolem worker, BlockPos goal) {
        if (goal == null || !enabled(worker) || !inEnvelope(origin(worker), feet(goal))) return false;
        Flight flight = flights.get(worker);
        if (flight != null && goal.equals(flight.goal) && moving(worker)) return true;
        if (flight != null && goal.equals(flight.goal) && level.getGameTime() < flight.retryAt) return false;
        BuilderChunkTickets.destination(level, worker, goal);
        List<Vec3> path = search(level, worker, List.of(goal));
        if (path.isEmpty()) {
            if (flight != null) {
                flight.goal = goal.immutable();
                flight.retryAt = level.getGameTime() + 20;
            }
            return false;
        }
        if (flight == null) {
            flight = new Flight();
            flight.origin = worker.position();
            flights.put(worker, flight);
        }
        flight.goal = goal.immutable();
        flight.retryAt = level.getGameTime() + 20;
        WorkerNavigation.stop(worker);
        flight.path = path;
        flight.next = 0;
        flight.lastPosition = worker.position();
        flight.stuckTicks = 0;
        worker.setNoGravity(true);
        worker.setPropellerSpinning(true);
        return true;
    }
    public static boolean toward(ServerLevel level, BuilderGolem worker, BlockPos target) {
        if (target == null) return false;
        if (clear(level, worker, feet(target))) return request(level, worker, target);
        var candidates = new ArrayList<BlockPos>();
        for (int dy = 0; dy <= 1; dy++)
            for (Direction direction : Direction.Plane.HORIZONTAL)
                candidates.add(target.relative(direction).above(dy));
        BlockPos goal = select(level, worker, candidates);
        return goal != null && request(level, worker, goal);
    }
    /** Walk to a grounded launch point near an elevated task; flight never
     * replaces long horizontal ground navigation. */
    public static BlockPos launchPose(ServerLevel level, BuilderGolem worker, BlockPos target) {
        if (!enabled(worker) || active(worker) || !worker.onGround()) return null;
        var candidates = new ArrayList<BlockPos>();
        int y = worker.blockPosition().getY();
        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
            BlockPos launch = new BlockPos(target.getX() + dx, y, target.getZ() + dz);
            if (!WorkerNavigation.canStandOn(level, worker, launch)) continue;
            for (int dy = -2; dy <= 1; dy++) {
                BlockPos pose = new BlockPos(launch.getX(), target.getY() + dy, launch.getZ());
                if (safeWorkPose(level, worker, target, feet(pose))
                        && !path(level, worker, feet(launch), feet(pose), feet(launch)).isEmpty()) {
                    candidates.add(launch);
                    break;
                }
            }
        }
        return WorkerNavigation.selectExecutableStand(level, worker, candidates);
    }
    private record Launch(BlockPos target,BlockPos position,long until) {}
    private static final Map<BuilderGolem,Launch> launches=new WeakHashMap<>();
    public static boolean approachLaunch(ServerLevel level, BuilderGolem worker, BlockPos target) {
        if(active(worker)||!worker.onGround())return false;
        long now=level.getGameTime();
        Launch saved=launches.get(worker);
        BlockPos launch=saved!=null&&saved.target().equals(target)&&now<saved.until()
                &&WorkerNavigation.canStandOn(level,worker,saved.position())?saved.position():null;
        if(launch==null){
            launch=launchPose(level,worker,target);
            if(launch==null){launches.remove(worker);return false;}
            launches.put(worker,new Launch(target.immutable(),launch.immutable(),now+100));
        }
        if(worker.position().distanceToSqr(feet(launch))<.04){
            launches.remove(worker);
            BlockPos pose=workPose(level,worker,target);
            return pose!=null&&request(level,worker,pose);
        }
        return WorkerNavigation.routeToExact(level,worker,launch,1.0);
    }
    /** Preflight a real walk-then-lift route, without starting movement.
     * A hover pose is not a walkable stance and must never be passed to A*. */
    public static boolean canAccessWork(ServerLevel level, BuilderGolem worker, BlockPos target) {
        if (!enabled(worker) || BuilderGrappleAccess.active(worker)
                || BuilderScaffolding.isControllingMovement(worker)) return false;
        return workPose(level, worker, target) != null
                || launchPose(level, worker, target) != null;
    }
    /** Find a real floor below this local column, or a nearby clear column.
     * A failed attempt never turns gravity on while hovering in open air. */
    public static boolean land(ServerLevel level, BuilderGolem worker, boolean below) {
        Flight flight = flights.get(worker);
        if (flight != null && moving(worker)) return true;
        if (flight != null && level.getGameTime() < flight.landingRetryAt) return false;
        if (flight != null) flight.landingRetryAt = level.getGameTime() + 20;
        Vec3 anchor = origin(worker);
        BlockPos center = BlockPos.containing(anchor);
        var columns = new ArrayList<BlockPos>();
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            BlockPos p = center.offset(dx, 0, dz);
            if (inEnvelope(anchor, feet(p))) columns.add(p);
        }
        columns.sort(Comparator.comparingDouble(p -> {
            double dx = p.getX() + .5 - worker.getX(), dz = p.getZ() + .5 - worker.getZ();
            return dx * dx + dz * dz;
        }));
        int top = worker.blockPosition().getY() - (below ? 1 : 0);
        var landings=new ArrayList<BlockPos>();
        for (BlockPos column : columns) {
            for (int y = top; y > level.getMinY(); y--) {
                BlockPos floor = new BlockPos(column.getX(), y, column.getZ());
                if (!level.hasChunkAt(floor)) break;
                if (!WorkerNavigation.canStandOn(level, worker, floor)) continue;
                landings.add(floor);
                // Lower floors cannot be reached through this solid floor.
                break;
            }
        }
        if(below)landings.sort(Comparator.comparingInt(BlockPos::getY)
                .thenComparingDouble(p->worker.position().distanceToSqr(feet(p))));
        for(BlockPos floor:landings)if(request(level,worker,floor))return true;
        return false;
    }
    public static boolean safeWorkPose(ServerLevel level, BuilderGolem worker,
            BlockPos target, Vec3 position) {
        if (position.distanceToSqr(Vec3.atCenterOf(target)) > 25 || !clear(level, worker, position))
            return false;
        double dx = Math.max(0, Math.max(target.getX() - position.x, position.x - target.getX() - 1));
        double dz = Math.max(0, Math.max(target.getZ() - position.z, position.z - target.getZ() - 1));
        if (dx * dx + dz * dz < .8 * .8) return false;
        AABB future = new AABB(target).inflate(.10);
        if (body(worker, position).intersects(future)) return false;
        for (Direction direction : Direction.values()) {
            Vec3 escape = position.add(direction.getStepX() * .3,
                    direction.getStepY() * .3, direction.getStepZ() * .3);
            if (!body(worker, escape).intersects(future) && sweep(level, worker, position, escape))
                return true;
        }
        return false;
    }
    public static BlockPos workPose(ServerLevel level, BuilderGolem worker, BlockPos target) {
        if (safeWorkPose(level, worker, target, worker.position())
                && inEnvelope(origin(worker), feet(worker.blockPosition()))
                && safeWorkPose(level, worker, target, feet(worker.blockPosition()))
                && sweep(level, worker, worker.position(), feet(worker.blockPosition())))
            return worker.blockPosition();
        var candidates = new ArrayList<BlockPos>();
        for (int dy = -2; dy <= 1; dy++)
            for (int dx = -4; dx <= 4; dx++)
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos pose = target.offset(dx, dy, dz);
                    if (inEnvelope(origin(worker), feet(pose))
                            && safeWorkPose(level, worker, target, feet(pose))) candidates.add(pose);
                }
        return select(level, worker, candidates);
    }
    public static boolean tick(ServerLevel level, BuilderGolem worker) {
        if (!enabled(worker)) { flights.remove(worker); worker.setPropellerSpinning(false); return false; }
        Flight flight = flights.get(worker);
        // Ordinary walking, falling, and furniture visits retain their controller
        // until an actual flight request has established powered hover.
        if (flight == null) {
            worker.setPropellerSpinning(false);
            return false;
        }
        boolean moving = moving(worker);
        worker.setPropellerSpinning(moving || !worker.onGround());
        double floor = WorkerNavigation.surveyFloorHeight(level, worker.blockPosition());
        if (!moving && (worker.onGround() || (Double.isFinite(floor)
                && Math.abs(worker.getY() - floor) < .15))) {
            worker.setNoGravity(false);
            worker.setPropellerSpinning(false);
            flights.remove(worker);
            return false;
        }
        worker.setNoGravity(true);
        worker.fallDistance = 0;
        worker.getNavigation().stop();
        worker.getMoveControl().setWantedPosition(worker.getX(), worker.getY(), worker.getZ(), 0);
        worker.setDeltaMovement(Vec3.ZERO);
        if (!moving) return true; // Powered hover persists while selecting the next task.
        if (worker.position().distanceToSqr(flight.lastPosition) > .01) {
            flight.lastPosition = worker.position();
            flight.stuckTicks = 0;
        } else if (++flight.stuckTicks > 40) {
            flight.lastFailedDestination=feet(flight.goal);
            flight.failedUntil=level.getGameTime()+100;
            stop(worker);
            flight.retryAt = level.getGameTime() + 10;
            return true;
        }
        while (flight.next < flight.path.size()
                && worker.position().distanceToSqr(flight.path.get(flight.next)) < .0004) flight.next++;
        if (flight.next == flight.path.size()) return true;
        Vec3 delta = flight.path.get(flight.next).subtract(worker.position());
        Vec3 velocity = delta.scale(Math.min(1, SPEED / delta.length()));
        if (inEnvelope(flight.origin, worker.position().add(velocity))
                && sweep(level, worker, worker.position(), worker.position().add(velocity)))
            worker.setDeltaMovement(velocity);
        else {
            flight.lastFailedDestination=feet(flight.goal);
            flight.failedUntil=level.getGameTime()+100;
            stop(worker);
            flight.retryAt = level.getGameTime() + 10;
        }
        return true;
    }
}
