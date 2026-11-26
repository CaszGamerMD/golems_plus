package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Collision-checked powered movement. Construction hats use unrestricted 3D flight; legacy propeller tests can opt out. */
public final class BuilderFlight {
    private BuilderFlight() {}
    private static final double SPEED = .20;
    private static final double CREATIVE_SPEED = .55; // nominal 11 blocks/sec
    public static boolean creative(BuilderGolem worker) {
        return enabled(worker) && worker.creativeFlightEnabled();
    }
    public static final double HORIZONTAL_LIMIT = 2.0;
    private static final double HORIZONTAL_LIMIT_SQUARED = HORIZONTAL_LIMIT * HORIZONTAL_LIMIT;
    private static final Map<BuilderGolem, Flight> flights = new WeakHashMap<>();
    private record Planned(BlockPos goal,Vec3 start,List<Vec3> route,long tick){}
    private static final Map<BuilderGolem,Planned> planned=new WeakHashMap<>();
    private static final class SupplyRoute {
        BlockPos depot;long retryAt,started;int failures;
        Vec3 lastExit;
    }
    private static final Map<BuilderGolem,SupplyRoute> supplyRoutes=new WeakHashMap<>();

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
        return loaded(level, box) && (creative(worker)?!level.getBlockCollisions(worker,box.deflate(1e-6)).iterator().hasNext():level.noCollision(worker, box));
    }
    private static boolean sweep(ServerLevel level, BuilderGolem worker, Vec3 from, Vec3 to) {
        // The swept AABB conservatively checks the whole segment, including
        // narrow corners. No velocity can cross an unchecked wall or chunk.
        AABB box = body(worker, from).minmax(body(worker, to));
        return loaded(level, box) && (creative(worker)?!level.getBlockCollisions(worker,box.deflate(1e-6)).iterator().hasNext():level.noCollision(worker, box));
    }
    private static Vec3 origin(BuilderGolem worker) {
        Flight flight = flights.get(worker);
        return flight == null ? worker.position() : flight.origin;
    }
    private static boolean inEnvelope(BuilderGolem worker, Vec3 origin, Vec3 point) {
        if (creative(worker)) return true;
        double dx = point.x - origin.x, dz = point.z - origin.z;
        return dx * dx + dz * dz <= HORIZONTAL_LIMIT_SQUARED + 1e-6;
    }
    /** A diagonal is checked in short swept segments rather than treating the
     * entire diagonal's enclosing rectangle as solid. */
    private static boolean airSegment(ServerLevel level, BuilderGolem worker, Vec3 a, Vec3 b) {
        int steps=Math.max(1,(int)Math.ceil(a.distanceTo(b)/.5));
        Vec3 prev=a;
        for(int i=1;i<=steps;i++) {
            Vec3 next=a.add(b.subtract(a).scale((double)i/steps));
            if(!sweep(level,worker,prev,next))return false;
            prev=next;
        }
        return true;
    }
    private static List<Vec3> simpleCreativePath(ServerLevel level,BuilderGolem worker,Vec3 from,Vec3 to) {
        if(airSegment(level,worker,from,to))return List.of(to);
        // Try direct vertical or horizontal bends, then bounded over/under
        // and side detours. Never teleport or fly through a solid block.
        for(Vec3 bend:List.of(new Vec3(from.x,to.y,from.z),new Vec3(to.x,from.y,to.z)))
            if(airSegment(level,worker,from,bend)&&airSegment(level,worker,bend,to))return List.of(bend,to);
        for(int offset:new int[]{1,2,4,8,16,32,-1,-2,-4,-8,-16,-32}) {
            double y=offset>0?Math.max(from.y,to.y)+offset:Math.min(from.y,to.y)+offset;
            Vec3 a=new Vec3(from.x,y,from.z),b=new Vec3(to.x,y,to.z);
            if(airSegment(level,worker,from,a)&&airSegment(level,worker,a,b)&&airSegment(level,worker,b,to))return List.of(a,b,to);
        }
        for(int offset:new int[]{1,-1,2,-2,4,-4,8,-8,16,-16,32,-32}) {
            for(boolean xAxis:new boolean[]{true,false}) {
                Vec3 shift=new Vec3(xAxis?offset:0,0,xAxis?0:offset);
                Vec3 a=from.add(shift),b=to.add(shift);
                if(airSegment(level,worker,from,a)&&airSegment(level,worker,a,b)&&airSegment(level,worker,b,to))return List.of(a,b,to);
            }
        }
        return List.of();
    }
    // Bounded 3D A* for winding roof and interior corridors.
    private record AirNode(BlockPos pos,double cost,double priority){}
    private static List<Vec3> gridPath(ServerLevel level,BuilderGolem worker,Vec3 from,Vec3 to){
        return gridPath(level,worker,from,to,1800);
    }
    private static List<Vec3> gridPath(ServerLevel level,BuilderGolem worker,Vec3 from,Vec3 to,int budget){
        return gridPath(level,worker,from,to,budget,Vec3.ZERO);
    }
    private static Vec3 gridFeet(BlockPos pos,Vec3 offset){return feet(pos).add(offset);}
    private static List<Vec3> gridPath(ServerLevel level,BuilderGolem worker,Vec3 from,Vec3 to,int budget,Vec3 offset){
        BlockPos start=BlockPos.containing(from.subtract(offset)),goal=BlockPos.containing(to.subtract(offset));
        int minX=Math.min(start.getX(),goal.getX())-8,maxX=Math.max(start.getX(),goal.getX())+8;
        int minY=Math.min(start.getY(),goal.getY())-8,maxY=Math.max(start.getY(),goal.getY())+12;
        int minZ=Math.min(start.getZ(),goal.getZ())-8,maxZ=Math.max(start.getZ(),goal.getZ())+8;
        var queue=new PriorityQueue<AirNode>(Comparator.comparingDouble(AirNode::priority));
        var cost=new HashMap<BlockPos,Double>();var parent=new HashMap<BlockPos,BlockPos>();
        var passable=new HashMap<BlockPos,Boolean>();
        for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
            BlockPos at=start.offset(dx,dy,dz);
            if(!airSegment(level,worker,from,gridFeet(at,offset)))continue;
            double g=from.distanceTo(gridFeet(at,offset));cost.put(at,g);
            queue.add(new AirNode(at,g,g+1.35*gridFeet(at,offset).distanceTo(to)));
        }
        int expanded=0;
        while(!queue.isEmpty()&&expanded<budget){
            AirNode node=queue.poll();BlockPos at=node.pos();
            if(node.cost()>cost.getOrDefault(at,Double.POSITIVE_INFINITY)+1e-6)continue;
            expanded++;
            if(gridFeet(at,offset).distanceToSqr(to)<=9&&airSegment(level,worker,gridFeet(at,offset),to)){
                var reversed=new ArrayList<Vec3>();reversed.add(to);
                for(BlockPos cur=at;cur!=null;cur=parent.get(cur))reversed.add(gridFeet(cur,offset));
                Collections.reverse(reversed);var result=new ArrayList<Vec3>();Vec3 anchor=from;
                for(int i=0;i<reversed.size();){int furthest=i;
                    for(int j=Math.min(reversed.size()-1,i+12);j>i;j--)
                        if(airSegment(level,worker,anchor,reversed.get(j))){furthest=j;break;}
                    anchor=reversed.get(furthest);result.add(anchor);i=furthest+1;
                }
                return result;
            }
            for(Direction direction:Direction.values()){
                BlockPos next=at.relative(direction);
                if(next.getX()<minX||next.getX()>maxX||next.getY()<minY||next.getY()>maxY||next.getZ()<minZ||next.getZ()>maxZ)continue;
                double g=node.cost()+1;
                if(g>=cost.getOrDefault(next,Double.POSITIVE_INFINITY))continue;
                if(!passable.computeIfAbsent(next,k->clear(level,worker,gridFeet(k,offset)))||!sweep(level,worker,gridFeet(at,offset),gridFeet(next,offset)))continue;
                cost.put(next,g);parent.put(next,at);queue.add(new AirNode(next,g,g+1.35*gridFeet(next,offset).distanceTo(to)));
            }
        }
        return List.of();
    }
    private static List<Vec3> creativePath(ServerLevel level,BuilderGolem worker,Vec3 from,Vec3 to){
        List<Vec3> simple=simpleCreativePath(level,worker,from,to);
        return simple.isEmpty()?gridPath(level,worker,from,to):simple;
    }
    // Vertical first, or a short sideways adjustment followed by vertical.
    // No unrestricted 3D search, repeated horizontal hops, or grapple anchor.
    private static List<Vec3> path(ServerLevel level, BuilderGolem worker,
            Vec3 from, Vec3 to, Vec3 anchor) {
        if (!inEnvelope(worker, anchor, from) || !inEnvelope(worker, anchor, to)
                || !clear(level, worker, to)) return List.of();
        if(creative(worker))return creativePath(level,worker,from,to);
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
        var ordered=candidates.stream().filter(Objects::nonNull)
                .filter(p -> inEnvelope(worker, anchor, feet(p)))
                .filter(p -> {
                    Flight flight=flights.get(worker);
                    return flight==null||flight.lastFailedDestination==null
                            ||level.getGameTime()>=flight.failedUntil
                            ||feet(p).distanceToSqr(flight.lastFailedDestination)>.04;
                })
                .sorted(Comparator.comparingDouble(p -> worker.position().distanceToSqr(feet(p))))
                .limit(creative(worker)?48:Long.MAX_VALUE).toList();
        if(creative(worker)){
            for(BlockPos p:ordered){
                var route=simpleCreativePath(level,worker,worker.position(),feet(p));
                if(!route.isEmpty())return route;
            }
            if(!ordered.isEmpty()){
                int offset=(int)Math.floorMod(level.getGameTime()/20+worker.getId(),ordered.size());
                for(int j=0;j<Math.min(2,ordered.size());j++){
                    var route=gridPath(level,worker,worker.position(),feet(ordered.get((offset+j)%ordered.size())));
                    if(!route.isEmpty())return route;
                }
            }
            return List.of();
        }
        return ordered.stream().map(p->path(level,worker,worker.position(),feet(p),anchor))
                .filter(p->!p.isEmpty()).findFirst().orElse(List.of());
    }
    public static BlockPos select(ServerLevel level, BuilderGolem worker, List<BlockPos> candidates) {
        List<Vec3> path = search(level, worker, candidates);
        if(path.isEmpty())return null;
        BlockPos result=BlockPos.containing(path.getLast());
        if(creative(worker))planned.put(worker,new Planned(result,worker.position(),path,level.getGameTime()));
        return result;
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
        Flight current=flights.get(worker);
        if(current!=null)advanceReached(worker,current);
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
        if (!enabled(worker)) return null;
        Vec3 from=worker.position();
        Vec3 best=null;
        double bestDistance=Double.POSITIVE_INFINITY;
        for (int dy=-2;dy<=1;dy++) {
            Vec3 to=new Vec3(from.x,target.getY()+dy,from.z);
            double distance=Math.abs(to.y-from.y);
            if (distance<.02 || distance>=bestDistance
                    || !inEnvelope(worker, origin(worker),to)
                    || !safeWorkPose(level,worker,target,to,bodyBlocking)
                    || !sweep(level,worker,from,to)) continue;
            best=to;bestDistance=distance;
        }
        return best;
    }

    static boolean adjustHoverForWork(ServerLevel level,BuilderGolem worker,
            BlockPos target,boolean bodyBlocking) {
        if (!active(worker)||moving(worker)) return false;
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
        if(creative(worker))return requestPosition(level,worker,goal,center);
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
    private static final double ARRIVAL_SQUARED=.02*.02;
    private static void advanceReached(BuilderGolem worker,Flight flight){
        while(flight.next<flight.path.size()
                &&worker.position().distanceToSqr(flight.path.get(flight.next))<ARRIVAL_SQUARED)
            flight.next++;
    }
    private static boolean actualSupport(ServerLevel level,BuilderGolem worker){
        // The actual footprint must contact a block, not just an entity
        // or a nominal center of a nearby tile. No position snap.
        return level.getBlockCollisions(worker,body(worker,worker.position()).move(0,-.05,0))
                .iterator().hasNext();
    }
    private static boolean releaseLanding(ServerLevel level,BuilderGolem worker,Flight flight){
        if(flight.next<flight.path.size()||!actualSupport(level,worker))return false;
        BlockPos cell=worker.blockPosition();
        BlockPos rounded=BlockPos.containing(worker.getX(),worker.getY()+.001,worker.getZ());
        boolean supported=false;
        for(BlockPos p:new BlockPos[]{flight.goal,cell,rounded}){
            if(p==null||!WorkerNavigation.canStandOn(level,worker,p))continue;
            Vec3 stand=supportedFeet(level,p);
            if(Double.isFinite(stand.y)&&Math.abs(stand.y-worker.getY())<.05
                    &&worker.position().distanceToSqr(stand)<.10){supported=true;break;}
        }
        if(!supported)return false;
        worker.setDeltaMovement(Vec3.ZERO);
        worker.setNoGravity(false);
        worker.setPropellerSpinning(false);
        BuilderTrace.decision(level,worker,"FLIGHT_LAND",flight.goal,"completed leg; actual block support verified");
        flights.remove(worker);
        return true;
    }
    private static boolean requestPosition(ServerLevel level,BuilderGolem worker,BlockPos goal,Vec3 destination){
        Flight existing=flights.get(worker);
        if(existing!=null){
            advanceReached(worker,existing);
            if(!moving(worker)){
                // Idempotent arrival: a repeated request at the endpoint
                // must not create a zero-length flight and reclaim control.
                boolean same=worker.position().distanceToSqr(destination)<ARRIVAL_SQUARED;
                if(!creative(worker))releaseLanding(level,worker,existing);
                if(same)return true;
            }
        }
        if(BuilderGrappleAccess.active(worker)||!inEnvelope(worker, origin(worker),destination)){
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
        Planned cached=planned.remove(worker);
        List<Vec3> path=creative(worker)&&cached!=null&&cached.goal().equals(goal)
                &&cached.start().distanceToSqr(worker.position())<1e-8
                &&cached.route().getLast().distanceToSqr(destination)<1e-8&&level.getGameTime()==cached.tick()
                ?cached.route():path(level,worker,worker.position(),destination,origin(worker));
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
        return commitRoute(level,worker,goal,destination,path);
    }
    private static boolean commitRoute(ServerLevel level,BuilderGolem worker,BlockPos goal,Vec3 destination,List<Vec3> path){
        if(path.isEmpty()||moving(worker))return false;
        Flight flight=flights.get(worker);
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
    /** Exact air coordinates are also valid work and transport destinations. */
    public static boolean canRouteExact(ServerLevel level,BuilderGolem worker,BlockPos target) {
        return target!=null&&!path(level,worker,worker.position(),feet(target),origin(worker)).isEmpty();
    }
    public static boolean routeExact(ServerLevel level,BuilderGolem worker,BlockPos target) {
        if(target==null)return false;
        if(WorkerNavigation.canStandOn(level,worker,target)) {
            Vec3 supported=supportedFeet(level,target);
            if(Double.isFinite(supported.y)&&clear(level,worker,supported))
                return requestPosition(level,worker,target,supported);
        }
        return request(level,worker,target);
    }
    /** Storage reach remains one block, with no requirement for a floor. */
    public static boolean towardStorage(ServerLevel level,BuilderGolem worker,BlockPos target) {
        if(target==null)return false;
        if(BuilderReturnService.withinStorageReach(worker,target)){supplyRoutes.remove(worker);return true;}
        if(moving(worker))return true;
        if(creative(worker))return supplyFlight(level,worker,target);
        BuilderChunkTickets.destination(level,worker,target);
        var candidates=new ArrayList<BlockPos>();
        for(int dy=-1;dy<=2;dy++)for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
            BlockPos p=target.offset(dx,dy,dz);
            Vec3 at=feet(p);
            if(BuilderReturnService.withinStorageReach(at.x,at.y,at.z,target)&&clear(level,worker,at))candidates.add(p);
        }
        BlockPos pose=select(level,worker,candidates);
        if(pose!=null&&request(level,worker,pose))return true;
        var fractional=new ArrayList<Vec3>();
        for(double dy:new double[]{.15,.6,1.2,1.65})for(double dx:new double[]{-1.0,-.55,0,.55,1.0})for(double dz:new double[]{-1.0,-.55,0,.55,1.0}){
            Vec3 at=new Vec3(target.getX()+.5+dx,target.getY()+dy,target.getZ()+.5+dz);
            if(BuilderReturnService.withinStorageReach(at.x,at.y,at.z,target)&&clear(level,worker,at))fractional.add(at);
        }
        fractional.sort(Comparator.comparingDouble(at->at.distanceToSqr(worker.position())));
        for(Vec3 at:fractional)if(!simpleCreativePath(level,worker,worker.position(),at).isEmpty()&&requestPosition(level,worker,BlockPos.containing(at),at))return true;
        return false;
    }
    /** An independent resupply route: actual air exit, exterior descent,
     * then a real one-block storage approach. No direct inventory access. */
    private static boolean supplyFlight(ServerLevel level,BuilderGolem worker,BlockPos depot){
        long now=level.getGameTime();
        SupplyRoute state=supplyRoutes.computeIfAbsent(worker,k->new SupplyRoute());
        if(!depot.equals(state.depot)){state.depot=depot.immutable();state.failures=0;state.retryAt=0;state.started=now;state.lastExit=null;}
        if(now<state.retryAt)return false;
        BuilderChunkTickets.destination(level,worker,depot);
        var destinations=new ArrayList<Vec3>();
        for(double dy:new double[]{0,1,1.5,.25,.65})for(double dx:new double[]{-1,-.5,0,.5,1})for(double dz:new double[]{-1,-.5,0,.5,1}){
            Vec3 at=new Vec3(depot.getX()+.5+dx,depot.getY()+dy,depot.getZ()+.5+dz);
            if(BuilderReturnService.withinStorageReach(at.x,at.y,at.z,depot)&&clear(level,worker,at))destinations.add(at);
        }
        // Prefer a point above the chest; vertical approach avoids its side walls.
        Vec3 from=worker.position();
        destinations.sort(Comparator.comparingDouble(at->from.distanceToSqr(at)));
        for(Vec3 at:destinations){
            if(recentlyFailed(worker,at,now))continue;
            var route=simpleCreativePath(level,worker,from,at);
            if(!route.isEmpty()&&commitRoute(level,worker,BlockPos.containing(at),at,route)){state.retryAt=0;return true;}
        }
        BuilderEscapeBox.Bounds box=BuilderBuildService.flightBounds(level,worker);
        if(box!=null&&!destinations.isEmpty()){
            int roof=Math.min(level.getMaxY()-3,box.maxY()+2);
            int cruise=Math.min(roof,Math.max(worker.blockPosition().getY()+3,BuilderBuildService.flightActiveY(level,worker)+3));
            var exits=new ArrayList<Vec3>();
            for(int y:new int[]{cruise,roof}){
                for(int r:new int[]{0,1,2,4,6,9})for(int dx=-r;dx<=r;dx+=Math.max(1,r))for(int dz=-r;dz<=r;dz+=Math.max(1,r)){
                    Vec3 at=new Vec3(Math.floor(from.x)+.5+dx,y,Math.floor(from.z)+.5+dz);
                    if(from.distanceToSqr(at)>1&&clear(level,worker,at)&&!exits.contains(at))exits.add(at);
                }
                for(Vec3 at:List.of(new Vec3(box.minX()-1.5,y,from.z),new Vec3(box.maxX()+1.5,y,from.z),new Vec3(from.x,y,box.minZ()-1.5),new Vec3(from.x,y,box.maxZ()+1.5)))
                    if(clear(level,worker,at)&&!exits.contains(at))exits.add(at);
            }
            if(state.lastExit!=null&&from.distanceToSqr(state.lastExit)>1&&clear(level,worker,state.lastExit))exits.addFirst(state.lastExit);
            exits.sort(Comparator.comparingDouble(at->from.distanceToSqr(at)+.15*at.distanceToSqr(destinations.getFirst())));
            // A usable exit must also have a proven onward route. Prefer
            // a straight descent over the chest from the open air plane.
            int probes=0;
            for(Vec3 exit:exits){
                if(++probes>40)break;
                if(recentlyFailed(worker,exit,now))continue;
                List<Vec3> escape=simpleCreativePath(level,worker,from,exit);
                if(escape.isEmpty())continue;
                for(Vec3 at:destinations.subList(0,Math.min(12,destinations.size()))){
                    if(recentlyFailed(worker,at,now))continue;
                    List<Vec3> onward=simpleCreativePath(level,worker,exit,at);
                    if(onward.isEmpty())continue;
                    var route=new ArrayList<Vec3>(escape);route.addAll(onward);
                    if(commitRoute(level,worker,BlockPos.containing(at),at,route)){
                        state.lastExit=exit;state.retryAt=0;
                        System.out.println("[HAT SUPPLY EXIT] worker="+worker.getUUID()+" via="+exit+" depot="+depot+" legs="+route.size());return true;
                    }
                }
            }
            // Complex enclosure: search toward rotating above-roof exits,
            // one bounded grid search per retry rather than per candidate.
            if(!exits.isEmpty()){
                Vec3 exit=exits.get(Math.floorMod(state.failures,Math.min(exits.size(),20)));
                if(!recentlyFailed(worker,exit,now)){
                    var escape=gridPath(level,worker,from,exit,3600);
                    if(!escape.isEmpty()&&commitRoute(level,worker,BlockPos.containing(exit),exit,escape)){
                        state.lastExit=exit;state.retryAt=0;state.failures++;
                        System.out.println("[HAT SUPPLY ASCENT] worker="+worker.getUUID()+" exit="+exit+" legs="+escape.size());return true;
                    }
                }
            }
        }
        if(!destinations.isEmpty()){
            Vec3 at=destinations.get(Math.floorMod(state.failures,destinations.size()));
            if(!recentlyFailed(worker,at,now)){
                var route=gridPath(level,worker,from,at,3600);
                if(!route.isEmpty()&&commitRoute(level,worker,BlockPos.containing(at),at,route)){state.retryAt=0;return true;}
            }
        }
        state.failures++;state.retryAt=now+20;
        if(state.failures%5==1)System.out.println("[HAT SUPPLY RETRY] worker="+worker.getUUID()+" failures="+state.failures+" feet="+from+" depot="+depot+" candidates="+destinations.size()+" flight="+traceStatus(worker));
        return false;
    }
    private static boolean recentlyFailed(BuilderGolem worker,Vec3 at,long now){
        Flight f=flights.get(worker);return f!=null&&f.lastFailedDestination!=null&&now<f.failedUntil&&at.distanceToSqr(f.lastFailedDestination)<.04;
    }
    public static boolean toward(ServerLevel level, BuilderGolem worker, BlockPos target) {
        if (target == null) return false;
        if(creative(worker)) {
            if(moving(worker))return true;
            if(clear(level,worker,feet(target))&&routeExact(level,worker,target))return true;
            return towardStorage(level,worker,target);
        }
        if (clear(level, worker, feet(target))) return request(level, worker, target);
        var candidates = new ArrayList<BlockPos>();
        for (int dy = 0; dy <= 1; dy++)
            for (Direction direction : Direction.Plane.HORIZONTAL)
                candidates.add(target.relative(direction).above(dy));
        BlockPos goal = select(level, worker, candidates);
        return goal != null && request(level, worker, goal);
    }
    /** An isolated lip, slab or narrow glass tile may not have an
     * integer-centered walking node. Lift directly from the actual feet
     * to nearby real support instead of requiring a grapple anchor.
     * Only a useful higher/closer landing is accepted; never reset the
     * two-block takeoff envelope while hovering. */
    static boolean escapeAwkwardFooting(ServerLevel level,BuilderGolem worker,BlockPos target){
        if(!enabled(worker)||active(worker)||BuilderGrappleAccess.active(worker)
                ||BuilderScaffolding.isControllingMovement(worker)
                ||!worker.onGround())return false;
        Vec3 from=worker.position();
        BlockPos here=worker.blockPosition();
        var candidates=new ArrayList<BlockPos>();
        for(int dy=-2;dy<=4;dy++)for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++){
            BlockPos p=here.offset(dx,dy,dz);
            if(!level.hasChunkAt(p)||!WorkerNavigation.canStandOn(level,worker,p))continue;
            Vec3 to=supportedFeet(level,p);
            if(!Double.isFinite(to.y)||!inEnvelope(worker, from,to)||to.distanceToSqr(from)<.25)continue;
            double old=from.distanceToSqr(Vec3.atBottomCenterOf(target));
            if(to.distanceToSqr(Vec3.atBottomCenterOf(target))>=old-.25)continue;
            if(path(level,worker,from,to,from).isEmpty())continue;
            candidates.add(p);
        }
        candidates.sort(Comparator.comparingDouble((BlockPos p)->p.distSqr(target))
                .thenComparingDouble(p->from.distanceToSqr(supportedFeet(level,p)))
                .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ));
        for(BlockPos p:candidates){
            if(requestSupportedLanding(level,worker,p)){
                BuilderTrace.decision(level,worker,"FLIGHT_FOOTING_ESCAPE",p,"actual feet="+from+" toward="+target);
                return true;
            }
        }
        return false;
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
        if(creative(worker))return safeWorkPose(level,worker,target,worker.position(),bodyBlocking)
                ||workPose(level,worker,target,bodyBlocking)!=null||fractionalWork(level,worker,target,bodyBlocking)!=null;
        return verticalWorkPose(level,worker,target,bodyBlocking)!=null
                || workPose(level, worker, target,bodyBlocking) != null
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
                    if(byLaunch.containsKey(launch)||!inEnvelope(worker, feet(launch),feet(landing))
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
            if (inEnvelope(worker, anchor, feet(p))) columns.add(p);
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
            if(!inEnvelope(worker, anchor,feet(column)))continue;
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
            if(rejected.contains(p)||!inEnvelope(worker, anchor,feet(p))||p.getY()<=anchor.y+.6
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
        if (!creative(worker) && dx * dx + dz * dz < .8 * .8) return false;
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
                && inEnvelope(worker, origin(worker), feet(worker.blockPosition()))
                && safeWorkPose(level, worker, target, feet(worker.blockPosition()),bodyBlocking)
                && sweep(level, worker, worker.position(), feet(worker.blockPosition())))
            return worker.blockPosition();
        var candidates = new ArrayList<BlockPos>();
        for (int dy = creative(worker)?-4:-2; dy <= (creative(worker)?4:1); dy++)
            for (int dx = -4; dx <= 4; dx++)
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos pose = target.offset(dx, dy, dz);
                    if (inEnvelope(worker, origin(worker), feet(pose))
                            && safeWorkPose(level, worker, target, feet(pose),bodyBlocking)) candidates.add(pose);
                }
        if(creative(worker)){
            var upper=candidates.stream().filter(p->p.getY()>=target.getY()).toList();
            BlockPos above=select(level,worker,upper);
            if(above!=null)return above;
        }
        return select(level, worker, candidates);
    }
    /** Fallback for half-slab floors and confined machine corridors.
     * Only used after integer work poses fail; same reach, clearance and
     * swept path checks apply. Bound expensive probes to two per attempt. */
    private static Vec3 fractionalWork(ServerLevel level,BuilderGolem worker,BlockPos target,boolean bodyBlocking){
        if(!creative(worker))return null;
        var candidates=new ArrayList<Vec3>();
        for(int dy=-2;dy<=3;dy++)for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++){
            BlockPos cell=target.offset(dx,dy,dz);
            for(double y:new double[]{.5,.25,.75})for(double[] lateral:new double[][]{{0,0},{.2,0},{-.2,0},{0,.2},{0,-.2}}){
                Vec3 at=feet(cell).add(lateral[0],y,lateral[1]);
                if(safeWorkPose(level,worker,target,at,bodyBlocking))candidates.add(at);
            }
        }
        candidates.sort(Comparator.comparingDouble(at->worker.position().distanceToSqr(at)));
        for(int i=0;i<Math.min(24,candidates.size());i++){
            Vec3 at=candidates.get(i);if(recentlyFailed(worker,at,level.getGameTime()))continue;
            var route=simpleCreativePath(level,worker,worker.position(),at);
            if(!route.isEmpty()){
                planned.put(worker,new Planned(BlockPos.containing(at),worker.position(),route,level.getGameTime()));
                return at;
            }
        }
        if(!candidates.isEmpty()){
            int offset=Math.floorMod((int)(level.getGameTime()/20)+worker.getId(),candidates.size());
            for(int j=0;j<Math.min(3,candidates.size());j++){
                Vec3 at=candidates.get((offset+j)%candidates.size());
                if(recentlyFailed(worker,at,level.getGameTime()))continue;
                var route=gridPath(level,worker,worker.position(),at,4800);
                if(!route.isEmpty()){
                    planned.put(worker,new Planned(BlockPos.containing(at),worker.position(),route,level.getGameTime()));
                    return at;
                }
            }
            // A full-block-center lattice cannot enter low slabs or
            // off-center corridors. Try one aligned lattice as a bounded
            // last resort; every edge still passes the collision sweep.
            Vec3 at=candidates.get(offset);
            Vec3 alignment=at.subtract(feet(BlockPos.containing(at)));
            if(!recentlyFailed(worker,at,level.getGameTime())){
                var route=gridPath(level,worker,worker.position(),at,3200,alignment);
                if(!route.isEmpty()){
                    planned.put(worker,new Planned(BlockPos.containing(at),worker.position(),route,level.getGameTime()));
                    return at;
                }
            }
        }
        return null;
    }
    static BlockPos railKitPose(ServerLevel level,BuilderGolem worker,BlockPos target,BlockPos footing,boolean bodyBlocking){
        var candidates=new ArrayList<BlockPos>();
        for(int dy=0;dy<=4;dy++)for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++){
            BlockPos at=target.offset(dx,dy,dz);Vec3 pose=feet(at);
            if(safeWorkPose(level,worker,target,pose,bodyBlocking)
                    &&safeWorkPose(level,worker,footing,pose,true))candidates.add(at);
        }
        BlockPos found=select(level,worker,candidates);if(found!=null)return found;
        candidates.clear();
        for(int dy=-3;dy<0;dy++)for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++){
            BlockPos at=target.offset(dx,dy,dz);Vec3 pose=feet(at);
            if(safeWorkPose(level,worker,target,pose,bodyBlocking)
                    &&safeWorkPose(level,worker,footing,pose,true))candidates.add(at);
        }
        return select(level,worker,candidates);
    }
    /** Rail recovery explores genuine off-center corridors and a longer
     * route budget only after ordinary integer flight fails. Reuse the
     * validated route instead of discarding it and searching again. */
    static boolean requestRailWork(ServerLevel level,BuilderGolem worker,BlockPos target,BlockPos footing,boolean bodyBlocking){
        BlockPos integer=footing==null?workPose(level,worker,target,bodyBlocking):railKitPose(level,worker,target,footing,bodyBlocking);
        if(integer!=null&&request(level,worker,integer))return true;
        var candidates=new ArrayList<Vec3>();
        for(int dy=-3;dy<=4;dy++)for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++){
            BlockPos cell=target.offset(dx,dy,dz);
            for(double fy:new double[]{.25,.5,.75})for(double[] lateral:new double[][]{{0,0},{.2,0},{-.2,0},{0,.2},{0,-.2}}){
                Vec3 at=feet(cell).add(lateral[0],fy,lateral[1]);
                if(safeWorkPose(level,worker,target,at,bodyBlocking)
                        &&(footing==null||safeWorkPose(level,worker,footing,at,true))
                        &&!recentlyFailed(worker,at,level.getGameTime()))candidates.add(at);
            }
        }
        candidates.sort(Comparator.comparingDouble(at->worker.position().distanceToSqr(at)));
        int size=candidates.size();if(size==0)return false;
        int offset=Math.floorMod((int)(level.getGameTime()/20)+worker.getId(),size);
        for(int i=0;i<Math.min(32,size);i++){
            Vec3 at=candidates.get(i<16?i:(offset+i-16)%size);
            var route=simpleCreativePath(level,worker,worker.position(),at);
            if(!route.isEmpty()&&commitRoute(level,worker,BlockPos.containing(at),at,route))return true;
        }
        for(int i=0;i<Math.min(3,size);i++){
            Vec3 at=candidates.get((offset+i)%size);
            var route=gridPath(level,worker,worker.position(),at,4800);
            if(!route.isEmpty()&&commitRoute(level,worker,BlockPos.containing(at),at,route))return true;
        }
        return false;
    }
    static boolean requestFractionalWork(ServerLevel level,BuilderGolem worker,BlockPos target,boolean bodyBlocking){
        Vec3 at=fractionalWork(level,worker,target,bodyBlocking);
        return at!=null&&requestPosition(level,worker,BlockPos.containing(at),at);
    }
    /** Traffic escape need not be a work pose: the worker is yielding,
     * not placing. Find a short collision-checked exit from the future
     * block and retain the ordinary flight controller. */
    static boolean requestVacate(ServerLevel level,BuilderGolem worker,BlockPos target){
        if(!creative(worker)||moving(worker))return false;
        Vec3 from=worker.position();
        AABB future=new AABB(target).inflate(.12);
        var candidates=new ArrayList<Vec3>();
        for(double distance:new double[]{.5,1,1.5,2,3,4}){
            for(Direction direction:Direction.values()){
                Vec3 at=from.add(direction.getStepX()*distance,direction.getStepY()*distance,direction.getStepZ()*distance);
                if(!body(worker,at).intersects(future)&&clear(level,worker,at))candidates.add(at);
            }
        }
        candidates.sort(Comparator.comparingDouble(from::distanceToSqr));
        for(Vec3 at:candidates)if(airSegment(level,worker,from,at)
                &&commitRoute(level,worker,BlockPos.containing(at),at,List.of(at)))return true;
        for(Vec3 at:candidates){
            var route=simpleCreativePath(level,worker,from,at);
            if(!route.isEmpty()&&commitRoute(level,worker,BlockPos.containing(at),at,route))return true;
        }
        return requestRailWork(level,worker,target,null,true);
    }
    static boolean reposition(ServerLevel level,BuilderGolem worker,BlockPos target){
        var candidates=new ArrayList<BlockPos>();
        for(int dy=-3;dy<=3;dy++)for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++){
            BlockPos p=target.offset(dx,dy,dz);
            if(worker.position().distanceToSqr(feet(p))>=2.25&&safeWorkPose(level,worker,target,feet(p),true))candidates.add(p);
        }
        BlockPos pose=select(level,worker,candidates);
        return pose!=null&&request(level,worker,pose);
    }
    public static boolean tick(ServerLevel level, BuilderGolem worker) {
        if (!enabled(worker)) { flights.remove(worker); worker.setPropellerSpinning(false); return false; }
        Flight flight = flights.get(worker);
        // Ordinary walking, falling, and furniture visits retain their controller
        // until an actual flight request has established powered hover.
        if (flight == null) {
            worker.setPropellerSpinning(creative(worker));
            if(creative(worker)){
                worker.setNoGravity(true);
                worker.fallDistance=0;
                worker.getNavigation().stop();
                worker.setDeltaMovement(Vec3.ZERO);
                return true;
            }
            return false;
        }
        // Resolve arrival BEFORE any caller can request another flight.
        // The previous tick physically moved the entity to its endpoint.
        advanceReached(worker,flight);
        if(!creative(worker)&&releaseLanding(level,worker,flight))return false;
        boolean moving = moving(worker);
        worker.setPropellerSpinning(true);
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
            flight.lastFailedDestination=flight.path.getLast();
            flight.failedUntil=level.getGameTime()+100;
            stop(worker);
            flight.retryAt = level.getGameTime() + 10;
            return true;
        }
        Vec3 delta = flight.path.get(flight.next).subtract(worker.position());
        Vec3 velocity = delta.scale(Math.min(1, (creative(worker)?CREATIVE_SPEED:SPEED) / delta.length()));
        if(creative(worker)&&!sweep(level,worker,worker.position(),worker.position().add(velocity))){
            // The sampled diagonal may graze a corner between ticks. A
            // shorter swept step may still be legal; never cross terrain.
            for(double fraction:new double[]{.5,.25}){
                Vec3 shorter=velocity.scale(fraction);
                if(sweep(level,worker,worker.position(),worker.position().add(shorter))){velocity=shorter;break;}
            }
        }
        if (inEnvelope(worker, flight.origin, worker.position().add(velocity))
                && sweep(level, worker, worker.position(), worker.position().add(velocity)))
            worker.setDeltaMovement(velocity);
        else {
            BuilderTrace.decision(level,worker,"FLIGHT_ABORT",flight.goal,"next swept body blocked or outside envelope");
            flight.lastFailedDestination=flight.path.getLast();
            flight.failedUntil=level.getGameTime()+100;
            stop(worker);
            flight.retryAt = level.getGameTime() + 10;
        }
        return true;
    }
}
