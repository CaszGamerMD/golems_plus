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
    // Experimental roof-gap crossing: 3.5m is a short, bounded powered
    // adjustment, NOT sustained horizontal flight. Every proposed maneuver
    // still requires a real supported landing and continuous swept-AABB
    // clearance. In ordinary builds the established two-block limit remains.
    private static final boolean WIDE_ROOF_HOPS=Boolean.parseBoolean(
            System.getenv().getOrDefault("GOLEMS_NN_ROOF_WIDE_HOPS","false"));
    public static final double HORIZONTAL_LIMIT = WIDE_ROOF_HOPS ? 3.5 : 2.0;
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

    /** Read-only destination for experimental observations. */
    static BlockPos observedDestination(BuilderGolem worker){
        Flight f=flights.get(worker);
        return f==null?null:f.goal;
    }

    /** Read-only, bounded execution state for schema-3 offline observations. */
    static double[] observedExecution(BuilderGolem worker, long now) {
        Flight f = flights.get(worker);
        double[] x = new double[7];
        if (f == null) return x;
        boolean moving = f.next < f.path.size();
        x[0] = moving ? 1 : 0;
        x[1] = Math.clamp((f.path.size() - f.next) / 3.0, 0, 1);
        if (moving) {
            Vec3 at = worker.position();
            double remaining = at.distanceTo(f.path.get(f.next));
            x[2] = Math.min(1, remaining / 32);
            for (int i = f.next + 1; i < f.path.size(); i++)
                remaining += f.path.get(i - 1).distanceTo(f.path.get(i));
            x[3] = Math.min(1, remaining / 32);
        }
        x[4] = Math.clamp(f.stuckTicks / 40.0, 0, 1);
        x[5] = now < f.retryAt ? 1 : 0;
        x[6] = f.lastFailedDestination != null && now < f.failedUntil ? 1 : 0;
        return x;
    }

    static String traceStatus(BuilderGolem worker){
        Flight f=flights.get(worker);
        if(f==null)return "none";
        return "goal="+f.goal+" leg="+f.next+"/"+f.path.size()
                +" origin="+f.origin+" next="+(f.next<f.path.size()?f.path.get(f.next):"none")
                +" stuckTicks="+f.stuckTicks+" failed="+f.lastFailedDestination
                +" retryAt="+f.retryAt;
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
            if (worker.level() instanceof ServerLevel level)
                BuilderNeuralRecorder.finishFlight(level, worker, "CANCELLED");
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
    /** Experimental route context, sampled only for accepted requests.
     * No route or controller state is mutated. Candidate flags describe
     * current geometry, not future success or permission to execute. */
    static double[] observedKnowledge(ServerLevel level, BuilderGolem worker,
            List<Vec3> chosen, Vec3 destination) {
        Vec3 from = worker.position(), anchor = origin(worker);
        double[] x = new double[17];
        int[][] offsets = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
        for (int i=0;i<offsets.length;i++) {
            int[] o=offsets[i];
            x[i]=clear(level,worker,from.add(o[0]*.5,o[1]*.5,o[2]*.5))?1:0;
            x[6+i]=clear(level,worker,destination.add(o[0]*.5,o[1]*.5,o[2]*.5))?1:0;
        }
        Vec3 vertical = new Vec3(from.x,destination.y,from.z);
        Vec3 side = new Vec3(destination.x,from.y,destination.z);
        x[12]=sweep(level,worker,from,vertical)&&sweep(level,worker,vertical,destination)?1:0;
        x[13]=sweep(level,worker,from,side)&&sweep(level,worker,side,destination)?1:0;
        // Two bounded over/under probes, within the same takeoff envelope.
        for (int i=0;i<2;i++) {
            double y=i==0?Math.max(from.y,destination.y)+2:Math.min(from.y,destination.y)-2;
            Vec3 a=new Vec3(from.x,y,from.z),b=new Vec3(destination.x,y,destination.z);
            x[14+i]=inEnvelope(anchor,a)&&inEnvelope(anchor,b)
                    &&sweep(level,worker,from,a)&&sweep(level,worker,a,b)
                    &&sweep(level,worker,b,destination)?1:0;
        }
        // Nearby builders are a crowding signal, not proof of collision.
        AABB corridor=body(worker,from);
        for(Vec3 at:chosen) corridor=corridor.minmax(body(worker,at));
        x[16]=Math.min(1,level.getEntitiesOfClass(BuilderGolem.class,
                corridor.inflate(1),other->other!=worker).size()/4.0);
        return x;
    }

    private static String observedBlockage(ServerLevel level, BuilderGolem worker, Vec3 next, Vec3 anchor) {
        if (!inEnvelope(anchor,next)) return "ENVELOPE";
        AABB box=body(worker,worker.position()).minmax(body(worker,next));
        if (!loaded(level,box)) return "UNLOADED_OR_BORDER";
        if (level.getBlockCollisions(worker,box).iterator().hasNext()) return "BLOCK";
        return "OTHER_COLLISION";
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
        // A roof ridge or short parapet may need more than four blocks
        // of clearance. The propeller already has vertical range: keep the
        // SAME two-block horizontal takeoff envelope, and verify every
        // vertical/sideways/descent swept AABB before accepting any route.
        // Checking additional heights is never permission to phase through
        // a ledge. Short, cheap legal maneuvers are still preferred first.
        for (int offset : new int[]{1,2,3,4,6,8,12,16,-1,-2,-3,-4,-6,-8}) {
            double y = offset > 0 ? Math.max(from.y,to.y)+offset : Math.min(from.y,to.y)+offset;
            if(y+2>level.getMaxY()||y<level.getMinY())continue;
            Vec3 a = new Vec3(from.x,y,from.z);
            Vec3 b = new Vec3(to.x,y,to.z);
            if (sweep(level,worker,from,a) && sweep(level,worker,a,b)
                    && sweep(level,worker,b,to)) return List.of(a,b,to);
        }
        return List.of();
    }
    /** Final immutable physical certificate for every AI-selected powered
     * route. Model predictions never grant permission to cross blocks,
     * escape the 2m takeoff envelope, or land in an unloaded chunk. */
    static boolean verifiedFlightRoute(ServerLevel level,BuilderGolem worker,
            List<Vec3> proposed,Vec3 destination){
        if(proposed==null||proposed.isEmpty()||destination==null
                ||proposed.getLast().distanceToSqr(destination)>.000001
                ||!clear(level,worker,destination))return false;
        Vec3 anchor=origin(worker),previous=worker.position();
        if(!inEnvelope(anchor,previous))return false;
        for(Vec3 next:proposed){
            if(next==null||!Double.isFinite(next.x)||!Double.isFinite(next.y)
                    ||!Double.isFinite(next.z)||!inEnvelope(anchor,next)
                    ||!sweep(level,worker,previous,next))return false;
            previous=next;
        }
        return true;
    }
    /** Shadow only: enumerate legal same-destination alternatives to the
     * already accepted path. No motion, destination or claim writes. */
    static List<List<Vec3>> shadowAlternatives(ServerLevel level, BuilderGolem worker,
            List<Vec3> accepted, Vec3 to) {
        Vec3 from=worker.position(),anchor=origin(worker);
        var result=new ArrayList<List<Vec3>>();
        result.add(accepted);
        if(!inEnvelope(anchor,from)||!inEnvelope(anchor,to)||!clear(level,worker,to)
                ||Math.hypot(to.x-from.x,to.z-from.z)<.02)return result;
        Vec3 vertical=new Vec3(from.x,to.y,from.z);
        Vec3 side=new Vec3(to.x,from.y,to.z);
        if(sweep(level,worker,from,vertical)&&sweep(level,worker,vertical,to)){
            var p=vertical.distanceToSqr(to)<.0001?List.of(to):List.of(vertical,to);
            if(!result.contains(p))result.add(p);
        }
        if(sweep(level,worker,from,side)&&sweep(level,worker,side,to)){
            var p=side.distanceToSqr(to)<.0001?List.of(to):List.of(side,to);
            if(!result.contains(p))result.add(p);
        }
        for(int offset:new int[]{1,2,3,4,-1,-2,-3,-4}){
            double y=offset>0?Math.max(from.y,to.y)+offset:Math.min(from.y,to.y)+offset;
            Vec3 a=new Vec3(from.x,y,from.z),b=new Vec3(to.x,y,to.z);
            if(inEnvelope(anchor,a)&&inEnvelope(anchor,b)&&sweep(level,worker,from,a)
                    &&sweep(level,worker,a,b)&&sweep(level,worker,b,to)){
                var p=List.of(a,b,to);
                if(!result.contains(p))result.add(p);
            }
        }
        return result;
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
        if (goal == null || !enabled(worker) || BuilderGrappleAccess.active(worker)) return false;
        return requestPosition(level,worker,goal,feet(goal));
    }
    /** Move in the current vertical column, including from a powered hover.
     * Keep the original takeoff envelope and the exact physical X/Z; a
     * fractional hover must not be forced to recenter or find a floor. */
    public static boolean requestVertical(ServerLevel level, BuilderGolem worker, double y) {
        if (!Double.isFinite(y)) return false;
        Vec3 from=worker.position();
        Vec3 to=new Vec3(from.x,y,from.z);
        BlockPos goal=BlockPos.containing(to);
        if (!enabled(worker) || BuilderGrappleAccess.active(worker)
                || BuilderScaffolding.isControllingMovement(worker)) {
            BuilderTrace.decision(level,worker,"VERTICAL_REJECT",goal,"disabled or competing motion owner");
            return false;
        }
        // onGround is a contact flag from the preceding physics tick,
        // not proof of support. Like request(), allow a clear powered
        // launch during the short falling/settling transition.
        if (moving(worker)) return false;
        if (Math.abs(y-from.y)<.02 || !sweep(level,worker,from,to)) {
            BuilderTrace.decision(level,worker,"VERTICAL_REJECT",goal,
                    "zero length or blocked vertical sweep; feet="+from+" destination="+to);
            return false;
        }
        return requestPosition(level,worker,goal,to);
    }

    private static Vec3 verticalWorkPose(ServerLevel level,BuilderGolem worker,
            BlockPos target,boolean bodyBlocking) {
        if (!active(worker)) return null;
        Vec3 from=worker.position();
        Vec3 best=null;
        double bestDistance=Double.POSITIVE_INFINITY;
        for (int dy=-2;dy<=1;dy++) {
            Vec3 to=new Vec3(from.x,target.getY()+dy,from.z);
            double distance=Math.abs(to.y-from.y);
            if (distance<.02 || distance>=bestDistance
                    || !inEnvelope(origin(worker),to)
                    || !safeWorkPose(level,worker,target,to,bodyBlocking)
                    || !sweep(level,worker,from,to)) continue;
            best=to;bestDistance=distance;
        }
        return best;
    }

    static boolean adjustHoverForWork(ServerLevel level,BuilderGolem worker,
            BlockPos target,boolean bodyBlocking) {
        if (moving(worker)) return false;
        Vec3 pose=verticalWorkPose(level,worker,target,bodyBlocking);
        return pose!=null&&requestVertical(level,worker,pose.y);
    }

    /** Land at the actual collision-checked point inside a supported tile.
     * A nominal two-metre center-to-center leg may be 2.025m from the
     * physical walking endpoint. Trim the landing point, NOT the envelope. */
    static boolean requestSupportedLanding(ServerLevel level,BuilderGolem worker,BlockPos goal){
        if(goal==null||!enabled(worker)||BuilderGrappleAccess.active(worker)
                ||!WorkerNavigation.canStandOn(level,worker,goal))return false;
        // A legal stand may be a bottom slab, stair, soul sand or
        // farmland: its physical top is not necessarily integer Y.
        // Use that exact collision surface for the entire powered leg.
        Vec3 center=supportedFeet(level,goal),anchor=origin(worker);
        if(!Double.isFinite(center.y)||!clear(level,worker,center))return false;
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
    static boolean canFlyBetween(ServerLevel level,BuilderGolem worker,BlockPos from,BlockPos to){
        if(!WorkerNavigation.canStandOn(level,worker,from)||!WorkerNavigation.canStandOn(level,worker,to))return false;
        Vec3 a=supportedFeet(level,from),b=supportedFeet(level,to);
        return Double.isFinite(a.y)&&Double.isFinite(b.y)&&!path(level,worker,a,b,a).isEmpty();
    }
    private static Vec3 supportedFeet(ServerLevel level,BlockPos stand){
        return new Vec3(stand.getX()+.5,WorkerNavigation.surveyFloorHeight(level,stand),stand.getZ()+.5);
    }
    private static boolean requestPosition(ServerLevel level,BuilderGolem worker,BlockPos goal,Vec3 destination){
        if(BuilderGrappleAccess.active(worker)||!inEnvelope(origin(worker),destination)){
            BuilderTrace.decision(level,worker,"FLIGHT_REJECT",goal,
                    BuilderGrappleAccess.active(worker)?"grapple owns motion":"outside horizontal envelope");
            return false;
        }
        Flight flight = flights.get(worker);
        // A committed powered leg has exactly one owner. A new work or
        // recovery request cannot overwrite its destination mid-flight.
        if (flight != null && moving(worker)) {
            return destination.distanceToSqr(flight.path.getLast())<.0004;
        }
        if (flight != null && goal.equals(flight.goal) && level.getGameTime() < flight.retryAt
                && flight.lastFailedDestination!=null) return false;
        BuilderChunkTickets.destination(level, worker, goal);
        List<Vec3> path = path(level,worker,worker.position(),destination,origin(worker));
        if(flight!=null&&flight.lastFailedDestination!=null
                &&level.getGameTime()<flight.failedUntil
                &&destination.distanceToSqr(flight.lastFailedDestination)<.04)return false;
        if (path.isEmpty()) {
            BuilderTrace.decision(level,worker,"FLIGHT_REJECT",goal,"no collision-free powered path");
            if (flight != null) {
                flight.goal = goal.immutable();
                flight.retryAt = level.getGameTime() + 20;
            }
            return false;
        }
        // The neural pilot may choose a DIFFERENT, collision-checked
        // sequence to this exact target. Safety remains fully deterministic:
        // failed inference or revalidation always uses the old route.
        List<Vec3> aiPath=BuilderNeuralAdvisor.decideLive(level,worker,path,destination);
        if(verifiedFlightRoute(level,worker,aiPath,destination))path=aiPath;
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
        BuilderTrace.decision(level,worker,"FLIGHT_ACCEPT",goal,"legs="+path.size()+" destination="+destination);
        BuilderNeuralRecorder.beginFlight(level, worker, path, destination);
        flight.path = path;
        flight.next = 0;
        flight.lastPosition = worker.position();
        flight.stuckTicks = 0;
        worker.getMoveControl().setWantedPosition(worker.getX(),worker.getY(),worker.getZ(),0);
        worker.setDeltaMovement(Vec3.ZERO);
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
        return verticalWorkPose(level,worker,target,bodyBlocking)!=null
                || workPose(level, worker, target,bodyBlocking) != null
                || launchPose(level, worker, target,bodyBlocking) != null;
    }
    private static final class RoofAccess {
        final BlockPos target;
        BlockPos launch,landing;
        final long expires;
        final List<BuilderNNRoofManeuverPolicy.Candidate> alternatives;
        BuilderNNRoofManeuverPolicy.Selection neural;
        long lastProgress;
        double bestDistance;
        RoofAccess(BlockPos target,BlockPos launch,BlockPos landing,long now,Vec3 start,
                List<BuilderNNRoofManeuverPolicy.Candidate> alternatives){
            this.target=target;this.launch=launch;this.landing=landing;
            this.alternatives=alternatives;
            this.expires=now+400;this.lastProgress=now;
            this.bestDistance=start.distanceToSqr(feet(launch));
        }
        BlockPos target(){return target;}
        BlockPos launch(){return launch;}
        BlockPos landing(){return landing;}
        long expires(){return expires;}
    }
    private static final Map<BuilderGolem,RoofAccess> roofAccess=new WeakHashMap<>();
    /** Chosen roof entry is trained ONLY after its physical flight ends. */
    private static final Map<BuilderGolem,RoofAccess> executingRoofEntries=new WeakHashMap<>();
    public static boolean roofAccessProgress(ServerLevel level,BuilderGolem worker,BlockPos target){
        RoofAccess r=roofAccess.get(worker);
        if(r==null||!r.target().equals(target)||level.getGameTime()>=r.expires())return false;
        double distance=worker.position().distanceToSqr(supportedFeet(level,r.launch()));
        if(distance<r.bestDistance-.04){r.bestDistance=distance;r.lastProgress=level.getGameTime();}
        return level.getGameTime()-r.lastProgress<=90;
    }
    private static final Map<BuilderGolem,Map<BlockPos,Long>> roofRetry=new WeakHashMap<>();
    private static final Map<BuilderGolem,Long> roofSearchBudget=new WeakHashMap<>();
    private static final Map<BuilderGolem,Long> roofReasonAt=new WeakHashMap<>();
    private static void roofReject(ServerLevel level,BuilderGolem worker,BlockPos target,
            String stage,int workCount,int supports,int flightLegs,int groundRoutes){
        if(!BuilderNNRoofFrontier.enabled())return;
        long now=level.getGameTime();
        if(now<roofReasonAt.getOrDefault(worker,0L))return;
        roofReasonAt.put(worker,now+160);
        System.out.println("[NN ROOF ROUTE REJECT] stage="+stage
                +" worker="+worker.getUUID()+" target="+target
                +" realWorkStances="+workCount+" standableLandings="+supports
                +" physicallyClearFlights="+flightLegs
                +" connectedGroundRoutes="+groundRoutes);
    }
    /** Find an actual walk-lift-walk route around a completed roof, not
     * a vertical flight through it. Both grounded legs are checked before
     * selecting a takeoff, and the airborne leg keeps the same 2m limit. */
    private static RoofAccess roofPlan(ServerLevel level,BuilderGolem worker,BlockPos target,boolean bodyBlocking){
        if(!enabled(worker)||active(worker)||!worker.onGround()
                ||(target.getY()-worker.getY()<2
                    &&(!BuilderNNRoofManeuverPolicy.ENABLED
                        ||worker.getY()-target.getY()<2)))return null;
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
            // The dome has slabs/stairs/fences with real fractional-height
            // foot surfaces. Never test an invented integer-Y body pose.
            Vec3 pose=supportedFeet(level,at);
            if(WorkerNavigation.canStandOn(level,worker,at)
                    &&Double.isFinite(pose.y)
                    &&(bodyBlocking?safeWorkPose(level,worker,target,pose):
                        pose.distanceToSqr(Vec3.atCenterOf(target))<=25
                        &&clear(level,worker,pose)))work.add(at);
        }
        if(work.isEmpty()){
            roofReject(level,worker,target,"no_collision_safe_work_pose",0,0,0,0);
            return null;
        }
        int physicalSupports=0,physicalLegs=0,connectedRoutes=0;
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
                // A half slab or low stair is legitimate real terrain.
                // The worker navigation and flight-sweep checks decide
                // safety using the exact loaded-world voxel collision.
                if(!WorkerNavigation.canStandOn(level,worker,landing))continue;
                Vec3 landingPose=supportedFeet(level,landing);
                if(!Double.isFinite(landingPose.y)
                        ||!clear(level,worker,landingPose))continue;
                physicalSupports++;
                // Walk from a clear exterior column to the roof's lip.
                for(int distance:new int[]{1,2}){
                    BlockPos launch=new BlockPos(landing.getX()+distance*axis[0],
                            baseY,landing.getZ()+distance*axis[1]);
                    if(byLaunch.containsKey(launch)
                            ||!WorkerNavigation.canStandOn(level,worker,launch))continue;
                    Vec3 launchPose=supportedFeet(level,launch);
                    if(!Double.isFinite(launchPose.y)
                            ||!clear(level,worker,launchPose)
                            ||!inEnvelope(launchPose,landingPose)
                            ||path(level,worker,launchPose,landingPose,launchPose).isEmpty())continue;
                    physicalLegs++;
                    if(++probes>24)break roofSearch;
                    if(!WorkerNavigation.hasGroundRouteFrom(level,worker,landing,work))continue;
                    connectedRoutes++;
                    launches.add(launch);byLaunch.put(launch,landing);
                }
            }
            if(launches.size()>=8)break roofSearch;
            }
        }
        if(launches.isEmpty()){
            roofReject(level,worker,target,"no_executable_roof_entry",
                    work.size(),physicalSupports,physicalLegs,connectedRoutes);
            return null;
        }
        launches.sort(Comparator.comparingDouble(at->worker.position().distanceToSqr(supportedFeet(level,at))));
        BlockPos launch=WorkerNavigation.selectExecutableStand(level,worker,launches);
        if(launch==null){
            roofReject(level,worker,target,"no_reachable_launch_from_worker",
                    work.size(),physicalSupports,physicalLegs,connectedRoutes);
            return null;
        }
        var alternatives=new ArrayList<BuilderNNRoofManeuverPolicy.Candidate>();
        if(BuilderNNRoofManeuverPolicy.ENABLED){
            // Every alternative must be a REAL accessible launch, collision-
            // checked flight, and a landing with a confirmed grounded route
            // to a physically safe work stance. Baseline passed the normal
            // selectExecutableStand validator above.
            alternatives.add(new BuilderNNRoofManeuverPolicy.Candidate(
                    List.of(new BuilderMultiLegTravel.Leg(launch,byLaunch.get(launch))),false));
            for(BlockPos other:launches){
                if(other.equals(launch)||alternatives.size()>=8)continue;
                BlockPos landing=byLaunch.get(other);
                if(landing==null
                        ||!WorkerNavigation.canStandOn(level,worker,other)
                        ||!WorkerNavigation.canStandOn(level,worker,landing)
                        ||!WorkerNavigation.hasGroundRouteFrom(
                                level,worker,worker.blockPosition(),List.of(other)))continue;
                alternatives.add(new BuilderNNRoofManeuverPolicy.Candidate(
                        List.of(new BuilderMultiLegTravel.Leg(other,landing)),false));
            }
        }
        var result=new RoofAccess(target.immutable(),launch,byLaunch.get(launch),
                now,worker.position(),List.copyOf(alternatives));
        roofAccess.put(worker,result);return result;
    }
    public static boolean canAccessRoofWork(ServerLevel level,BuilderGolem worker,BlockPos target,boolean bodyBlocking){
        return roofPlan(level,worker,target,bodyBlocking)!=null;
    }
    public static boolean approachRoofWork(ServerLevel level,BuilderGolem worker,BlockPos target,boolean bodyBlocking){
        RoofAccess plan=roofPlan(level,worker,target,bodyBlocking);
        if(plan==null)return false;
        if(BuilderNNRoofManeuverPolicy.ENABLED&&plan.neural==null
                &&!plan.alternatives.isEmpty()){
            List<BuilderMultiLegTravel.Leg> selected=BuilderNNRoofManeuverPolicy.choose(
                    level,worker,target,plan.alternatives,0);
            var token=BuilderNNRoofManeuverPolicy.committed(worker,target,selected);
            if(token!=null){
                plan.neural=token;
                plan.launch=selected.getFirst().launch();
                plan.landing=selected.getFirst().landing();
                plan.bestDistance=worker.position().distanceToSqr(
                        supportedFeet(level,plan.launch()));
                plan.lastProgress=level.getGameTime();
            }
        }
        if(worker.position().distanceToSqr(supportedFeet(level,plan.launch()))<.04){
            if(requestSupportedLanding(level,worker,plan.landing())){
                if(plan.neural!=null)executingRoofEntries.put(worker,plan);
                roofAccess.remove(worker);return true;
            }
            // Keep a valid walking plan if takeoff was rejected. The
            // physical endpoint can still improve on the next tick.
            return WorkerNavigation.routeToExact(level,worker,plan.launch(),1.0);
        }
        return WorkerNavigation.routeToExact(level,worker,plan.launch(),1.0);
    }
    /** A shell exit may be air. Survey the entire clear vertical column
     * before committing to it. Never treat a virtual box as support. */
    static BlockPos escapeColumnGround(ServerLevel level,BuilderGolem worker,BlockPos air){
        Vec3 top=feet(air);
        if(!clear(level,worker,top))return null;
        for(int y=air.getY()-1;y>level.getMinY();y--){
            BlockPos p=air.atY(y);
            if(!level.hasChunkAt(p))return null;
            if(!WorkerNavigation.canStandOn(level,worker,p))continue;
            Vec3 floor=supportedFeet(level,p);
            // First physical floor only: do not fly through a roof.
            return sweep(level,worker,top,floor)?p:null;
        }
        return null;
    }
    static boolean canReachEscapeAir(ServerLevel level,BuilderGolem worker,BlockPos launch,BlockPos air){
        Vec3 from=supportedFeet(level,launch);
        return Double.isFinite(from.y)&&!path(level,worker,from,feet(air),from).isEmpty();
    }
    static boolean atEscapeAir(BuilderGolem worker,BlockPos air){
        return worker.position().distanceToSqr(feet(air))<.04;
    }
    /** Find a real floor below this local column, or a nearby clear column.
     * A failed attempt never turns gravity on while hovering in open air. */
    public static boolean land(ServerLevel level, BuilderGolem worker, boolean below) {
        return land(level,worker,below,Set.of());
    }
    static boolean land(ServerLevel level, BuilderGolem worker, boolean below, Set<BlockPos> rejected) {
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
        for(BlockPos floor:landings)if(!rejected.contains(floor)&&requestSupportedLanding(level,worker,floor))return true;
        // A hovering worker can also land on a nearby raised lip and
        // walk to the edge, rather than hovering forever under a roof.
        if(flight!=null){
            for(int dy=1;dy<=4;dy++)for(BlockPos column:columns){
                BlockPos floor=new BlockPos(column.getX(),top+dy,column.getZ());
                if(floor.getY()>=level.getMaxY())continue;
                if(!rejected.contains(floor)&&WorkerNavigation.canStandOn(level,worker,floor)&&requestSupportedLanding(level,worker,floor))
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
        return escapePocket(level,worker,goal,Set.of());
    }
    static boolean escapePocket(ServerLevel level,BuilderGolem worker,BlockPos goal,Set<BlockPos> rejected){
        if(!enabled(worker)||active(worker))return false;
        Vec3 anchor=worker.position();
        BlockPos here=worker.blockPosition();
        var candidates=new ArrayList<BlockPos>();
        for(int dy=1;dy<=5;dy++)for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++){
            BlockPos p=here.offset(dx,dy,dz);
            if(rejected.contains(p)||!inEnvelope(anchor,feet(p))||p.getY()<=anchor.y+.6
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
        return approachDescentLaunch(level,worker,depot,Set.of());
    }
    static boolean approachDescentLaunch(ServerLevel level,BuilderGolem worker,BlockPos depot,Set<BlockPos> rejected){
        if(!enabled(worker)||active(worker)||!worker.onGround())return false;
        long now=level.getGameTime();
        Descent saved=descents.get(worker);
        if(saved!=null&&saved.depot().equals(depot)&&now<saved.until()
                &&!rejected.contains(saved.landing())
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
                    if(!rejected.contains(floor)&&!path(level,worker,anchor,feet(floor),anchor).isEmpty()
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
        if (!enabled(worker)) {
            BuilderNeuralRecorder.finishFlight(level, worker, "DISABLED");
            executingRoofEntries.remove(worker);
            flights.remove(worker); worker.setPropellerSpinning(false); return false; }
        Flight flight = flights.get(worker);
        // Ordinary walking, falling, and furniture visits retain their controller
        // until an actual flight request has established powered hover.
        if (flight == null) {
            worker.setPropellerSpinning(false);
            return false;
        }
        BuilderNeuralRecorder.observeFlightTick(level, worker);
        boolean moving = moving(worker);
        worker.setPropellerSpinning(moving || !worker.onGround());
        double floor = WorkerNavigation.surveyFloorHeight(level, worker.blockPosition());
        // At large world coordinates the final physical step can be a
        // few millimeters below an integer tile. Survey the actual goal
        // as well: flooring the near-integer feet can select the solid
        // block UNDER the landing and leave the propeller hovering.
        boolean atLanding=flight.goal!=null
                &&WorkerNavigation.canStandOn(level,worker,flight.goal)
                &&worker.position().distanceToSqr(supportedFeet(level,flight.goal))<.04;
        // A stale onGround flag alone must never end a powered hover.
        // Require the actual support surface at the intended landing or
        // the current physical tile (including integer rounding aliases).
        BlockPos rounded=BlockPos.containing(worker.getX(),worker.getY()+.001,worker.getZ());
        double roundedFloor=WorkerNavigation.surveyFloorHeight(level,rounded);
        boolean supportedHere=(Double.isFinite(floor)&&Math.abs(worker.getY()-floor)<.05
                &&WorkerNavigation.canStandOn(level,worker,worker.blockPosition()))
                ||(Double.isFinite(roundedFloor)&&Math.abs(worker.getY()-roundedFloor)<.05
                &&WorkerNavigation.canStandOn(level,worker,rounded));
        if (!moving && (atLanding || supportedHere)) {
            worker.setDeltaMovement(Vec3.ZERO);
            worker.setNoGravity(false);
            worker.setPropellerSpinning(false);
            BuilderTrace.decision(level,worker,"FLIGHT_LAND",flight.goal,"support/contact verified");
            RoofAccess learned=executingRoofEntries.remove(worker);
            if(learned!=null){
                if(atLanding&&flight.goal!=null&&flight.goal.equals(learned.landing()))
                    BuilderNNRoofManeuverPolicy.finished(level,worker,learned.neural,false);
                else
                    BuilderNNRoofManeuverPolicy.failed(
                            level,worker,learned.neural,"diverted_landing");
            }
            flights.remove(worker);
            return false;
        }
        worker.setNoGravity(true);
        worker.fallDistance = 0;
        worker.getNavigation().stop();
        worker.getMoveControl().setWantedPosition(worker.getX(), worker.getY(), worker.getZ(), 0);
        worker.setDeltaMovement(Vec3.ZERO);
        if (!moving) {
            worker.setPropellerSpinning(true);
            return true; // Powered hover persists while selecting the next task.
        }
        if (worker.position().distanceToSqr(flight.lastPosition) > .01) {
            flight.lastPosition = worker.position();
            flight.stuckTicks = 0;
        } else if (++flight.stuckTicks > 40) {
            BuilderTrace.decision(level,worker,"FLIGHT_ABORT",flight.goal,"no physical displacement for 40 ticks");
            BuilderNeuralRecorder.finishFlight(level, worker, "NO_DISPLACEMENT");
            RoofAccess failedRoof=executingRoofEntries.remove(worker);
            if(failedRoof!=null)BuilderNNRoofManeuverPolicy.failed(
                    level,worker,failedRoof.neural,"flight_no_displacement");
            flight.lastFailedDestination=flight.path.getLast();
            flight.failedUntil=level.getGameTime()+100;
            stop(worker);
            flight.retryAt = level.getGameTime() + 10;
            return true;
        }
        while (flight.next < flight.path.size()
                && worker.position().distanceToSqr(flight.path.get(flight.next)) < .0004) flight.next++;
        if (flight.next == flight.path.size()) {
            BuilderNeuralRecorder.finishFlight(level, worker, "ARRIVED");
            return true;
        }
        Vec3 delta = flight.path.get(flight.next).subtract(worker.position());
        Vec3 velocity = delta.scale(Math.min(1, SPEED / delta.length()));
        if (inEnvelope(flight.origin, worker.position().add(velocity))
                && sweep(level, worker, worker.position(), worker.position().add(velocity)))
            worker.setDeltaMovement(velocity);
        else {
            BuilderTrace.decision(level,worker,"FLIGHT_ABORT",flight.goal,"next swept body blocked or outside envelope");
            if (BuilderNeuralRecorder.ENABLED)
                BuilderNeuralRecorder.finishFlight(level, worker, "BLOCKED",
                        observedBlockage(level,worker,worker.position().add(velocity),flight.origin));
            RoofAccess blockedRoof=executingRoofEntries.remove(worker);
            if(blockedRoof!=null)BuilderNNRoofManeuverPolicy.failed(
                    level,worker,blockedRoof.neural,"flight_collision_blocked");
            flight.lastFailedDestination=flight.path.getLast();
            flight.failedUntil=level.getGameTime()+100;
            stop(worker);
            flight.retryAt = level.getGameTime() + 10;
        }
        return true;
    }
}
