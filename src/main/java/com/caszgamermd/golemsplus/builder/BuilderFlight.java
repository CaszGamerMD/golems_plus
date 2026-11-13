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
        if (goal == null || !enabled(worker)) return false;
        return requestPosition(level,worker,goal,feet(goal));
    }
    /** Land at the actual collision-checked point inside a supported tile.
     * A nominal two-metre center-to-center leg may be 2.025m from the
     * physical walking endpoint. Trim the landing point, NOT the envelope. */
    static boolean requestSupportedLanding(ServerLevel level,BuilderGolem worker,BlockPos goal){
        if(goal==null||!enabled(worker)||!WorkerNavigation.canStandOn(level,worker,goal)
                ||Math.abs(WorkerNavigation.surveyFloorHeight(level,goal)-goal.getY())>.1)return false;
        Vec3 center=feet(goal),anchor=origin(worker);
        double dx=center.x-anchor.x,dz=center.z-anchor.z;
        double length=Math.hypot(dx,dz);
        Vec3 landing=center;
        if(length>HORIZONTAL_LIMIT){
            if(length>HORIZONTAL_LIMIT+.16)return false;
            double scale=(HORIZONTAL_LIMIT-.005)/length;
            landing=new Vec3(anchor.x+dx*scale,center.y,anchor.z+dz*scale);
            if(!BlockPos.containing(landing).equals(goal))return false;
            // Fractional landing is only legal on a full stable floor,
            // not on a narrow fence, stair lip or unsupported corner.
            var floor=goal.below();
            if(!level.getBlockState(floor).isFaceSturdy(level,floor,Direction.UP)
                    ||!clear(level,worker,landing))return false;
        }
        return requestPosition(level,worker,goal,landing);
    }
    private static boolean requestPosition(ServerLevel level,BuilderGolem worker,BlockPos goal,Vec3 destination){
        if(!inEnvelope(origin(worker),destination))return false;
        Flight flight = flights.get(worker);
        if (flight != null && goal.equals(flight.goal) && moving(worker)) return true;
        if (flight != null && goal.equals(flight.goal) && level.getGameTime() < flight.retryAt) return false;
        BuilderChunkTickets.destination(level, worker, goal);
        List<Vec3> path = path(level,worker,worker.position(),destination,origin(worker));
        if(flight!=null&&flight.lastFailedDestination!=null
                &&level.getGameTime()<flight.failedUntil
                &&destination.distanceToSqr(flight.lastFailedDestination)<.04)return false;
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
        // A successful, collision-checked powered takeoff owns movement.
        // Retain the real paid scaffold blocks, but never resume a stale
        // bridge route after landing somewhere else.
        BuilderScaffoldPlanA.cancel(worker);
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
        return launchPose(level,worker,target,true);
    }
    public static BlockPos launchPose(ServerLevel level, BuilderGolem worker, BlockPos target,boolean bodyBlocking) {
        if (!enabled(worker) || active(worker) || !worker.onGround()) return null;
        var candidates = new ArrayList<BlockPos>();
        int y = worker.blockPosition().getY();
        for (int dx = -4; dx <= 4; dx++) for (int dz = -4; dz <= 4; dz++) {
            BlockPos launch = new BlockPos(target.getX() + dx, y, target.getZ() + dz);
            if (!WorkerNavigation.canStandOn(level, worker, launch)) continue;
            for (int dy = -2; dy <= 1; dy++) {
                BlockPos pose = new BlockPos(launch.getX(), target.getY() + dy, launch.getZ());
                if (safeWorkPose(level, worker, target, feet(pose),bodyBlocking)
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
        return approachLaunch(level,worker,target,true);
    }
    public static boolean approachLaunch(ServerLevel level, BuilderGolem worker, BlockPos target,boolean bodyBlocking) {
        if(active(worker)||!worker.onGround())return false;
        long now=level.getGameTime();
        Launch saved=launches.get(worker);
        BlockPos launch=saved!=null&&saved.target().equals(target)&&now<saved.until()
                &&WorkerNavigation.canStandOn(level,worker,saved.position())?saved.position():null;
        if(launch==null){
            launch=launchPose(level,worker,target,bodyBlocking);
            if(launch==null){launches.remove(worker);return false;}
            launches.put(worker,new Launch(target.immutable(),launch.immutable(),now+100));
        }
        if(worker.position().distanceToSqr(feet(launch))<.04){
            launches.remove(worker);
            BlockPos pose=workPose(level,worker,target,bodyBlocking);
            return pose!=null&&request(level,worker,pose);
        }
        return WorkerNavigation.routeToExact(level,worker,launch,1.0);
    }
    /** Preflight a real walk-then-lift route, without starting movement.
     * A hover pose is not a walkable stance and must never be passed to A*. */
    public static boolean canAccessWork(ServerLevel level, BuilderGolem worker, BlockPos target) {
        return canAccessWork(level,worker,target,true);
    }
    public static boolean canAccessWork(ServerLevel level, BuilderGolem worker, BlockPos target,boolean bodyBlocking) {
        if (!enabled(worker) || BuilderGrappleAccess.active(worker)
                || BuilderScaffolding.isControllingMovement(worker)) return false;
        return workPose(level, worker, target,bodyBlocking) != null
                || launchPose(level, worker, target,bodyBlocking) != null;
    }
    private static final class RoofAccess {
        final BlockPos target,launch,landing;
        final long expires;
        long lastProgress;
        double bestDistance;
        RoofAccess(BlockPos target,BlockPos launch,BlockPos landing,long now,Vec3 start){
            this.target=target;this.launch=launch;this.landing=landing;
            this.expires=now+400;this.lastProgress=now;
            this.bestDistance=start.distanceToSqr(feet(launch));
        }
        BlockPos target(){return target;}
        BlockPos launch(){return launch;}
        BlockPos landing(){return landing;}
        long expires(){return expires;}
    }
    private static final Map<BuilderGolem,RoofAccess> roofAccess=new WeakHashMap<>();
    public static boolean roofAccessProgress(ServerLevel level,BuilderGolem worker,BlockPos target){
        RoofAccess r=roofAccess.get(worker);
        if(r==null||!r.target().equals(target)||level.getGameTime()>=r.expires())return false;
        double distance=worker.position().distanceToSqr(feet(r.launch()));
        if(distance<r.bestDistance-.04){r.bestDistance=distance;r.lastProgress=level.getGameTime();}
        return level.getGameTime()-r.lastProgress<=90;
    }
    private static final Map<BuilderGolem,Map<BlockPos,Long>> roofRetry=new WeakHashMap<>();
    private static final Map<BuilderGolem,Long> roofSearchBudget=new WeakHashMap<>();
    /** Find an actual walk-lift-walk route around a completed roof, not
     * a vertical flight through it. Both grounded legs are checked before
     * selecting a takeoff, and the airborne leg keeps the same 2m limit. */
    private static RoofAccess roofPlan(ServerLevel level,BuilderGolem worker,BlockPos target,boolean bodyBlocking){
        if(!enabled(worker)||active(worker)||!worker.onGround()
                ||target.getY()-worker.getY()<2)return null;
        long now=level.getGameTime();
        RoofAccess saved=roofAccess.get(worker);
        if(saved!=null&&saved.target().equals(target)&&now<saved.expires()
                &&roofAccessProgress(level,worker,target)
                &&WorkerNavigation.canStandOn(level,worker,saved.launch())
                &&WorkerNavigation.canStandOn(level,worker,saved.landing()))return saved;
        // A full perimeter search is a rare finishing-work fallback.
        // Distinct targets keep independent retry times, but a worker
        // cannot trigger dozens of expensive roof probes in one tick.
        var retries=roofRetry.computeIfAbsent(worker,k->new HashMap<>());
        retries.entrySet().removeIf(e->e.getValue()<=now);
        if(now<roofSearchBudget.getOrDefault(worker,0L)
                ||now<retries.getOrDefault(target,0L))return null;
        roofSearchBudget.put(worker,now+10);
        retries.put(target.immutable(),now+40);
        var work=new ArrayList<BlockPos>();
        for(int dy=-1;dy<=3;dy++)for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++){
            BlockPos at=target.offset(dx,dy,dz);
            if(WorkerNavigation.canStandOn(level,worker,at)
                    &&(bodyBlocking?safeWorkPose(level,worker,target,feet(at)):
                        feet(at).distanceToSqr(Vec3.atCenterOf(target))<=25
                        &&clear(level,worker,feet(at))))work.add(at);
        }
        if(work.isEmpty())return null;
        var launches=new ArrayList<BlockPos>();
        var byLaunch=new HashMap<BlockPos,BlockPos>();
        int baseY=worker.blockPosition().getY();
        // Sample the complete perimeter on small rings and intermediate
        // points on larger rings. Eight radial rays alone miss narrow
        // irregular roof edges (notably the Matrix water-channel wings).
        int probes=0;
        roofSearch: for(int radius=3;radius<=32;radius++){
            var ring=new ArrayList<int[]>();
            int stride=radius<=8?1:Math.max(2,radius/4);
            for(int k=-radius;k<=radius;k+=stride){
                ring.add(new int[]{radius,k});ring.add(new int[]{-radius,k});
                ring.add(new int[]{k,radius});ring.add(new int[]{k,-radius});
            }
            ring.sort(Comparator.comparingDouble(off->worker.position().distanceToSqr(
                    target.getX()+off[0]+.5,baseY,target.getZ()+off[1]+.5)));
            for(int[] off:ring){
            int[] axis={Integer.signum(off[0]),Integer.signum(off[1])};
            if(Math.abs(off[0])>Math.abs(off[1]))axis[1]=0;
            else if(Math.abs(off[1])>Math.abs(off[0]))axis[0]=0;
            for(int dy:new int[]{0,1,-1,2}){
                BlockPos landing=target.offset(off[0],dy,off[1]);
                if(!WorkerNavigation.canStandOn(level,worker,landing)
                        ||Math.abs(WorkerNavigation.surveyFloorHeight(level,landing)-landing.getY())>.1)continue;
                // Walk from a clear exterior column to the roof's lip.
                for(int distance:new int[]{1,2}){
                    BlockPos launch=new BlockPos(landing.getX()+distance*axis[0],
                            baseY,landing.getZ()+distance*axis[1]);
                    if(byLaunch.containsKey(launch)||!inEnvelope(feet(launch),feet(landing))
                            ||!WorkerNavigation.canStandOn(level,worker,launch)
                            ||Math.abs(WorkerNavigation.surveyFloorHeight(level,launch)-launch.getY())>.1
                            ||path(level,worker,feet(launch),feet(landing),feet(launch)).isEmpty())continue;
                    if(++probes>12)break roofSearch;
                    if(!WorkerNavigation.hasGroundRouteFrom(level,worker,landing,work))continue;
                    launches.add(launch);byLaunch.put(launch,landing);
                }
            }
            if(launches.size()>=8)break roofSearch;
            }
        }
        if(launches.isEmpty())return null;
        launches.sort(Comparator.comparingDouble(at->worker.position().distanceToSqr(feet(at))));
        BlockPos launch=WorkerNavigation.selectExecutableStand(level,worker,launches);
        if(launch==null)return null;
        var result=new RoofAccess(target.immutable(),launch,byLaunch.get(launch),now,worker.position());
        roofAccess.put(worker,result);return result;
    }
    public static boolean canAccessRoofWork(ServerLevel level,BuilderGolem worker,BlockPos target,boolean bodyBlocking){
        return roofPlan(level,worker,target,bodyBlocking)!=null;
    }
    public static boolean approachRoofWork(ServerLevel level,BuilderGolem worker,BlockPos target,boolean bodyBlocking){
        RoofAccess plan=roofPlan(level,worker,target,bodyBlocking);
        if(plan==null)return false;
        if(worker.position().distanceToSqr(feet(plan.launch()))<.04){
            if(requestSupportedLanding(level,worker,plan.landing())){
                roofAccess.remove(worker);return true;
            }
            // Keep a valid walking plan if takeoff was rejected. The
            // physical endpoint can still improve on the next tick.
            return WorkerNavigation.routeToExact(level,worker,plan.launch(),1.0);
        }
        return WorkerNavigation.routeToExact(level,worker,plan.launch(),1.0);
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
        int top = worker.blockPosition().getY() - (below && flight==null ? 1 : 0);
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
        if(below)landings.sort(Comparator.comparingInt((BlockPos p)->p.getY())
                .thenComparingDouble(p->worker.position().distanceToSqr(feet(p))));
        for(BlockPos floor:landings)if(request(level,worker,floor))return true;
        // A hovering worker can also land on a nearby raised lip and
        // walk to the edge, rather than hovering forever under a roof.
        if(flight!=null){
            for(int dy=1;dy<=4;dy++)for(BlockPos column:columns){
                BlockPos floor=new BlockPos(column.getX(),top+dy,column.getZ());
                if(floor.getY()>=level.getMaxY())continue;
                if(WorkerNavigation.canStandOn(level,worker,floor)&&request(level,worker,floor))
                    return true;
            }
        }
        return false;
    }

    /** Recover from an obsolete bridge without falling or destroying it.
     * The powered leg must finish on a genuine non-scaffold floor inside
     * the original horizontal envelope. A lower landing may be anywhere
     * in the world height, as with an ordinary propeller descent. */
    static boolean exitStalledScaffold(ServerLevel level,BuilderGolem worker){
        if(!enabled(worker)||active(worker)||BuilderGrappleAccess.active(worker)
                ||BuilderScaffolding.climbingNow(level,worker))return false;
        Vec3 anchor=worker.position();
        BlockPos here=worker.blockPosition();
        var landings=new ArrayList<BlockPos>();
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++){
            BlockPos column=here.offset(dx,0,dz);
            if(!inEnvelope(anchor,feet(column)))continue;
            for(int y=here.getY()+3;y>level.getMinY();y--){
                BlockPos at=column.atY(y);
                if(!level.hasChunkAt(at))break;
                if(!WorkerNavigation.canStandOn(level,worker,at))continue;
                BlockPos floor=at.below();
                if(level.getBlockState(floor).is(net.minecraft.world.level.block.Blocks.SCAFFOLDING)
                        ||level.getBlockState(at).is(net.minecraft.world.level.block.Blocks.SCAFFOLDING)
                        ||!level.getBlockState(floor).isFaceSturdy(level,floor,Direction.UP)
                        ||Math.abs(WorkerNavigation.surveyFloorHeight(level,at)-at.getY())>.1)continue;
                if(!path(level,worker,anchor,feet(at),anchor).isEmpty())landings.add(at);
                break; // no route through a real solid floor
            }
        }
        landings.sort(Comparator.comparingDouble(at->feet(at).distanceToSqr(anchor)));
        for(BlockPos landing:landings)if(request(level,worker,landing))return true;
        return false;
    }

    /** A rail/glass/wet-root pocket may have no horizontal route at all.
     * Lift in its open shaft, then land on a REAL nearby upper surface
     * with an onward ground path. No temporary block, anchor or teleport. */
    public static boolean escapePocket(ServerLevel level,BuilderGolem worker,BlockPos goal){
        if(!enabled(worker)||active(worker))return false;
        Vec3 anchor=worker.position();
        BlockPos here=worker.blockPosition();
        var candidates=new ArrayList<BlockPos>();
        for(int dy=1;dy<=5;dy++)for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++){
            BlockPos p=here.offset(dx,dy,dz);
            if(!inEnvelope(anchor,feet(p))||p.getY()<=anchor.y+.6
                    ||!WorkerNavigation.canStandOn(level,worker,p)
                    ||Math.abs(WorkerNavigation.surveyFloorHeight(level,p)-p.getY())>.1)continue;
            if(!path(level,worker,anchor,feet(p),anchor).isEmpty())candidates.add(p);
        }
        // Prefer a broad surface, not the same isolated one-block perch.
        candidates.sort(Comparator.comparingDouble(p->feet(p).distanceToSqr(anchor)));
        for(BlockPos p:candidates){
            int exits=0;
            for(Direction side:Direction.Plane.HORIZONTAL){
                BlockPos next=p.relative(side);
                if(WorkerNavigation.canStandOn(level,worker,next)
                        &&sweep(level,worker,feet(p),feet(next)))exits++;
            }
            if(exits>0&&request(level,worker,p))return true;
        }
        return false;
    }

    private static final class Descent {
        final BlockPos depot,launch,landing;
        final long until;
        long lastProgress;
        double bestDistance;
        Descent(BlockPos depot,BlockPos launch,BlockPos landing,long now,Vec3 start){
            this.depot=depot;this.launch=launch;this.landing=landing;
            this.until=now+400;this.lastProgress=now;
            this.bestDistance=start.distanceToSqr(feet(launch));
        }
        BlockPos depot(){return depot;}
        BlockPos launch(){return launch;}
        BlockPos landing(){return landing;}
        long until(){return until;}
    }
    private static final Map<BuilderGolem,Descent> descents=new WeakHashMap<>();

    /** Progress toward the planned edge, not straight-line distance to
     * the chest below. Protect only a genuinely advancing return leg. */
    public static boolean descentMakingProgress(ServerLevel level,BuilderGolem worker,BlockPos depot){
        Descent d=descents.get(worker);
        if(d==null||!d.depot().equals(depot)||level.getGameTime()>=d.until())return false;
        double distance=worker.position().distanceToSqr(feet(d.launch()));
        if(distance<d.bestDistance-.04){
            d.bestDistance=distance;
            d.lastProgress=level.getGameTime();
        }
        return level.getGameTime()-d.lastProgress<=65;
    }

    /** Walk to a real ledge, then use the local propeller to descend.
     * A two-block horizontal flight budget is not a two-block walking budget. */
    public static boolean hasDescentLaunch(ServerLevel level,BuilderGolem worker,BlockPos depot){
        Descent saved=descents.get(worker);
        return saved!=null&&saved.depot().equals(depot)&&level.getGameTime()<saved.until()
                &&descentMakingProgress(level,worker,depot);
    }

    public static boolean approachDescentLaunch(ServerLevel level,BuilderGolem worker,BlockPos depot){
        if(!enabled(worker)||active(worker)||!worker.onGround())return false;
        long now=level.getGameTime();
        Descent saved=descents.get(worker);
        if(saved!=null&&saved.depot().equals(depot)&&now<saved.until()
                &&descentMakingProgress(level,worker,depot)
                &&WorkerNavigation.canStandOn(level,worker,saved.launch())
                &&WorkerNavigation.canStandOn(level,worker,saved.landing())){
            if(worker.position().distanceToSqr(feet(saved.launch()))<.04){
                if(requestSupportedLanding(level,worker,saved.landing())){
                    descents.remove(worker);return true;
                }
                return WorkerNavigation.routeToExact(level,worker,saved.launch(),1.0);
            }
            return WorkerNavigation.routeToExact(level,worker,saved.launch(),1.0);
        }
        descents.remove(worker);
        var possible=new ArrayList<BlockPos>();
        var landingByLaunch=new HashMap<BlockPos,BlockPos>();
        BlockPos here=worker.blockPosition();
        int[][] offsets={{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
        // A 31x49 floor can leave a worker more than eight blocks
        // from an edge. Search the actual perimeter directions, not
        // just the small dome fixture's local eight-block radius.
        for(int radius=1;radius<=32;radius++)for(int[] off:offsets){
            BlockPos launch=here.offset(radius*off[0],0,radius*off[1]);
            if(!WorkerNavigation.canStandOn(level,worker,launch)
                    ||Math.abs(WorkerNavigation.surveyFloorHeight(level,launch)-launch.getY())>.1)continue;
            Vec3 anchor=feet(launch);
            BlockPos best=null;
            for(int[] side:offsets){
                BlockPos column=launch.offset(side[0],0,side[1]);
                if(!clear(level,worker,feet(column)))continue;
                for(int y=launch.getY()-2;y>level.getMinY()&&y>=depot.getY()-2;y--){
                    BlockPos floor=new BlockPos(column.getX(),y,column.getZ());
                    if(!level.hasChunkAt(floor))break;
                    if(!WorkerNavigation.canStandOn(level,worker,floor))continue;
                    if(!path(level,worker,anchor,feet(floor),anchor).isEmpty()
                            &&(best==null||floor.getY()<best.getY()))best=floor;
                    break;
                }
            }
            if(best!=null){possible.add(launch);landingByLaunch.put(launch,best);}
        }
        possible.sort(Comparator.comparingDouble(at->worker.position().distanceToSqr(feet(at))));
        BlockPos launch=WorkerNavigation.selectExecutableStand(level,worker,possible);
        if(launch==null)return false;
        descents.put(worker,new Descent(depot.immutable(),launch,landingByLaunch.get(launch),now,worker.position()));
        return WorkerNavigation.routeToExact(level,worker,launch,1.0);
    }
    public static boolean safeWorkPose(ServerLevel level, BuilderGolem worker,
            BlockPos target, Vec3 position) {
        return safeWorkPose(level,worker,target,position,true);
    }
    public static boolean safeWorkPose(ServerLevel level, BuilderGolem worker,
            BlockPos target, Vec3 position,boolean bodyBlocking) {
        if (position.distanceToSqr(Vec3.atCenterOf(target)) > 25 || !clear(level, worker, position))
            return false;
        // Waterlogging and collision-free circuit placement do not create
        // a solid cube around the worker. Still require a real clear body,
        // reach and an actual adjacent cell, never work from inside target.
        if(!bodyBlocking)return !BlockPos.containing(position).equals(target);
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
        return workPose(level,worker,target,true);
    }
    public static BlockPos workPose(ServerLevel level, BuilderGolem worker, BlockPos target,boolean bodyBlocking) {
        if (safeWorkPose(level, worker, target, worker.position(),bodyBlocking)
                && inEnvelope(origin(worker), feet(worker.blockPosition()))
                && safeWorkPose(level, worker, target, feet(worker.blockPosition()),bodyBlocking)
                && sweep(level, worker, worker.position(), feet(worker.blockPosition())))
            return worker.blockPosition();
        var candidates = new ArrayList<BlockPos>();
        for (int dy = -2; dy <= 1; dy++)
            for (int dx = -4; dx <= 4; dx++)
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos pose = target.offset(dx, dy, dz);
                    if (inEnvelope(origin(worker), feet(pose))
                            && safeWorkPose(level, worker, target, feet(pose),bodyBlocking)) candidates.add(pose);
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
        // At large world coordinates the final physical step can be a
        // few millimeters below an integer tile. Survey the actual goal
        // as well: flooring the near-integer feet can select the solid
        // block UNDER the landing and leave the propeller hovering.
        boolean atLanding=flight.goal!=null
                &&worker.position().distanceToSqr(feet(flight.goal))<.04
                &&WorkerNavigation.canStandOn(level,worker,flight.goal)
                &&Math.abs(worker.getY()-WorkerNavigation.surveyFloorHeight(level,flight.goal))<.15;
        if (!moving && (atLanding || worker.onGround() || (Double.isFinite(floor)
                && Math.abs(worker.getY() - floor) < .15))) {
            worker.setDeltaMovement(Vec3.ZERO);
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
