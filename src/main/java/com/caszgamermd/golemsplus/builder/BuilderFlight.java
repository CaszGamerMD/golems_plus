package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Propeller-powered flight with bounded 3D path searches and swept body clearance. */
public final class BuilderFlight {
    private BuilderFlight() {}
    private static final double SPEED = .20;
    private static final int SEARCH_LIMIT = 4096;
    private static final Map<BuilderGolem, Flight> flights = new WeakHashMap<>();

    private static final class Flight {
        List<Vec3> path = List.of();
        int next;
        BlockPos goal;
        long retryAt;
        Vec3 lastPosition;
        int stuckTicks;
    }
    private record Node(BlockPos position, double cost, double score) {}

    public static boolean enabled(BuilderGolem worker) {
        return worker.propellerEnabled();
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
    private static double heuristic(BlockPos position, Set<BlockPos> goals) {
        double best = Double.POSITIVE_INFINITY;
        for (BlockPos goal : goals) best = Math.min(best, position.distSqr(goal));
        return Math.sqrt(best);
    }
    private static List<Vec3> search(ServerLevel level, BuilderGolem worker, List<BlockPos> candidates) {
        var goals = new LinkedHashSet<BlockPos>();
        candidates.stream().filter(Objects::nonNull)
                .filter(p -> clear(level, worker, feet(p)))
                .sorted(Comparator.comparingDouble(p -> worker.position().distanceToSqr(feet(p))))
                .limit(24).forEach(goals::add);
        for (BlockPos goal : goals)
            if (sweep(level, worker, worker.position(), feet(goal))) return List.of(feet(goal));
        if (goals.isEmpty()) return List.of();

        var open = new PriorityQueue<Node>(Comparator.comparingDouble(Node::score));
        var cost = new HashMap<BlockPos, Double>();
        var parent = new HashMap<BlockPos, BlockPos>();
        BlockPos origin = worker.blockPosition();
        // Fractional rail/slab starts connect to nearby free lattice nodes.
        for (int dy = -1; dy <= 1; dy++)
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos start = origin.offset(dx, dy, dz);
                    if (!sweep(level, worker, worker.position(), feet(start))) continue;
                    double distance = worker.position().distanceTo(feet(start));
                    cost.put(start, distance);
                    open.add(new Node(start, distance, distance + heuristic(start, goals)));
                }
        int examined = 0;
        while (!open.isEmpty() && examined++ < SEARCH_LIMIT) {
            Node node = open.remove();
            if (node.cost() > cost.getOrDefault(node.position(), Double.POSITIVE_INFINITY)) continue;
            if (goals.contains(node.position())) {
                var path = new ArrayList<Vec3>();
                for (BlockPos p = node.position(); p != null; p = parent.get(p)) path.add(feet(p));
                Collections.reverse(path);
                return path;
            }
            for (Direction direction : Direction.values()) {
                BlockPos next = node.position().relative(direction);
                double nextCost = node.cost() + 1;
                if (nextCost >= cost.getOrDefault(next, Double.POSITIVE_INFINITY)) continue;
                if (!sweep(level, worker, feet(node.position()), feet(next))) continue;
                cost.put(next, nextCost);
                parent.put(next, node.position());
                open.add(new Node(next, nextCost, nextCost + heuristic(next, goals)));
            }
        }
        return List.of();
    }
    public static BlockPos select(ServerLevel level, BuilderGolem worker, List<BlockPos> candidates) {
        List<Vec3> path = search(level, worker, candidates);
        return path.isEmpty() ? null : BlockPos.containing(path.getLast());
    }
    public static boolean request(ServerLevel level, BuilderGolem worker, BlockPos goal) {
        if (goal == null) return false;
        Flight flight = flights.computeIfAbsent(worker, unused -> new Flight());
        if (goal.equals(flight.goal) && moving(worker)) return true;
        if (goal.equals(flight.goal) && level.getGameTime() < flight.retryAt) return false;
        BuilderChunkTickets.destination(level, worker, goal);
        List<Vec3> path = search(level, worker, List.of(goal));
        flight.goal = goal.immutable();
        flight.retryAt = level.getGameTime() + 20;
        if (path.isEmpty()) return false;
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
        if (safeWorkPose(level, worker, target, worker.position())) return worker.blockPosition();
        var candidates = new ArrayList<BlockPos>();
        for (int dy = -2; dy <= 1; dy++)
            for (int dx = -4; dx <= 4; dx++)
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos pose = target.offset(dx, dy, dz);
                    if (safeWorkPose(level, worker, target, feet(pose))) candidates.add(pose);
                }
        return select(level, worker, candidates);
    }
    public static boolean tick(ServerLevel level, BuilderGolem worker) {
        if (!enabled(worker)) return false;
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
            stop(worker);
            flight.retryAt = level.getGameTime() + 10;
            return true;
        }
        while (flight.next < flight.path.size()
                && worker.position().distanceToSqr(flight.path.get(flight.next)) < .01) flight.next++;
        if (flight.next == flight.path.size()) return true;
        Vec3 delta = flight.path.get(flight.next).subtract(worker.position());
        Vec3 velocity = delta.scale(Math.min(1, SPEED / delta.length()));
        if (sweep(level, worker, worker.position(), worker.position().add(velocity)))
            worker.setDeltaMovement(velocity);
        else {
            stop(worker);
            flight.retryAt = level.getGameTime() + 10;
        }
        return true;
    }
}
