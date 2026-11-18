package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Repeatable navigation-only waypoint controller, never a block placement.
 * Selects local grounded stages with real collision-aware A* reachability.
 *
 * A distant high target is NOT a valid immediate grounded goal: a complete
 * native path to that position does not exist from the courtyard. Instead
 * walk through physically supported connected ground before attempting
 * bounded multi-leg flight. For a lower target, descend before taking a
 * long horizontal ground route. Never fly pointlessly across flat terrain.
 */
final class BuilderWaypointTravel {
    private static final int STAGE_RADIUS=18;
    private static final boolean EXPERIMENTAL_DIRECT_ASCENT=Boolean.parseBoolean(
            System.getenv().getOrDefault("GOLEMS_NN_WAYPOINT_DIRECT_ASCENT","false"));
    private static final boolean EXPERIMENTAL_GATEWAY=Boolean.parseBoolean(
            System.getenv().getOrDefault("GOLEMS_NN_WAYPOINT_CERTIFIED_GATEWAY","false"));
    private static final int STAGE_TTL=105;
    private static final int RETAIN=12;
    private static final Map<BuilderGolem,Plan> plans=new WeakHashMap<>();
    private static final class Plan {
        final BlockPos goal;
        final ArrayDeque<BlockPos> visited=new ArrayDeque<>();
        BlockPos stage;
        long stageTick, nextSurvey, nextDescentProbe, nextAscentProbe;
        BlockPos lastAscentLanding;
        double best=Double.POSITIVE_INFINITY;
        int failedStages,stagesCompleted;
        Plan(BlockPos goal){this.goal=goal.immutable();}
    }
    private BuilderWaypointTravel(){}
    static boolean needsElevation(BlockPos here,BlockPos goal){
        return Math.abs(here.getY()-goal.getY())>=3;
    }
    static boolean shouldApproachOnFoot(BlockPos here,BlockPos goal){
        int dx=here.getX()-goal.getX(),dz=here.getZ()-goal.getZ();
        return !needsElevation(here,goal)||dx*dx+dz*dz>14*14;
    }
    private static double flatDistance(BlockPos a,BlockPos b){
        return Math.hypot(a.getX()-b.getX(),a.getZ()-b.getZ());
    }
    static BlockPos chooseStage(BlockPos start,BlockPos target,Set<BlockPos> connected,
            List<BlockPos> recentlyUsed){
        // A connected standing tile is selected only as an INTERMEDIATE
        // position. It does not imply that the final target is reachable.
        // It may temporarily move sideways or backwards around a wall.
        double current=flatDistance(start,target);
        BlockPos chosen=null;
        double best=Double.POSITIVE_INFINITY;
        for(BlockPos at:connected){
            if(at.equals(start)||recentlyUsed.contains(at)
                    ||Math.abs(at.getY()-start.getY())>2)continue;
            double step=flatDistance(start,at);
            if(step<4||step>STAGE_RADIUS)continue;
            double remaining=flatDistance(at,target);
            double progress=current-remaining;
            // Prefer gaining proximity, but retain lateral approaches when
            // necessary to travel around a solid wall or a dome lip.
            if(progress< -2.5)continue;
            double cost=remaining+.10*step+.20*Math.abs(at.getY()-start.getY());
            if(cost<best){best=cost;chosen=at;}
        }
        return chosen;
    }
    /**
     * To ascend a solid roof, first reach SKY-EXPOSED connected ground
     * rather than standing directly beneath the objective and attempting
     * to fly through it. An exterior gateway is allowed to be FURTHER from
     * the goal in X/Z, but it must belong to the actually reachable ground
     * component and remain collision-safe.
     */
    static BlockPos chooseExposedGateway(BlockPos start,BlockPos target,
            Set<BlockPos> connected,List<BlockPos> recent,
            Predicate<BlockPos> skyExposed){
        BlockPos best=null;
        double bestCost=Double.POSITIVE_INFINITY;
        for(BlockPos at:connected){
            if(recent.contains(at)||Math.abs(start.getY()-at.getY())>2
                    ||at.equals(start))continue;
            double travel=flatDistance(start,at);
            if(travel<3||travel>STAGE_RADIUS+6||!skyExposed.test(at))continue;
            double residual=flatDistance(at,target);
            // Prefer a gateway near the roof's outer edge, but first
            // prioritize escaping the overhead obstruction.
            double cost=residual+.18*travel;
            if(cost<bestCost){bestCost=cost;best=at;}
        }
        return best;
    }
    /**
     * Find an exterior grounded launch which can execute a real collision-
     * swept upward flight to a FULL supported landing. A nearer x/z point
     * underneath the solid dome is NOT meaningful ascent progress.
     *
     * We use the actual reachable component and physical flight validator,
     * not a guessed skylight line or a virtual entity. The chosen launch is
     * reached by ordinary grounded A* before taking off.
     */
    static BlockPos chooseAscentGateway(ServerLevel level,BuilderGolem worker,
            BlockPos target,Set<BlockPos> connected,List<BlockPos> recent){
        BlockPos start=worker.blockPosition();
        List<BlockPos> options=new ArrayList<>();
        for(BlockPos candidate:connected){
            if(candidate.equals(start)||recent.contains(candidate)
                    ||Math.abs(candidate.getY()-start.getY())>2
                    ||flatDistance(start,candidate)>STAGE_RADIUS+9
                    ||!level.canSeeSky(candidate.above()))continue;
            options.add(candidate);
        }
        options.sort(Comparator.comparingDouble(p->flatDistance(p,target)));
        BlockPos best=null;
        double bestScore=Double.POSITIVE_INFINITY;
        int checked=0;
        for(BlockPos launch:options){
            if(++checked>40)break;
            // Candidate ascents must be to genuine supported full-foot
            // positions above this launch or within the 2m propeller
            // envelope. Never authorize arbitrary horizontal flight.
            int desired=Math.min(target.getY()+2,launch.getY()+28);
            boolean flightPossible=false;
            for(int dy=Math.max(3,desired-launch.getY());dy>=3&&!flightPossible;dy--){
                if(dy>28)continue;
                for(int dx=-2;dx<=2&&!flightPossible;dx++)
                    for(int dz=-2;dz<=2;dz++){
                        if(dx*dx+dz*dz>4)continue;
                        BlockPos landing=launch.offset(dx,dy,dz);
                        if(WorkerNavigation.canStandOn(level,worker,landing)
                                &&BuilderFlight.canFlyBetween(level,worker,launch,landing)){
                            flightPossible=true;break;
                        }
                    }
            }
            if(!flightPossible)continue;
            double score=flatDistance(launch,target)
                    +.23*flatDistance(start,launch);
            if(score<bestScore){bestScore=score;best=launch;}
        }
        if(best!=null)
            System.out.println("[NN WAYPOINT CERTIFIED ROOF ENTRY] worker="+worker.getUUID()
                    +" from="+start+" launch="+best+" target="+target
                    +" checked="+checked);
        return best;
    }
    private static void mark(Plan plan,BlockPos cell){
        if(cell==null)return;
        plan.visited.remove(cell);
        plan.visited.addLast(cell.immutable());
        while(plan.visited.size()>RETAIN)plan.visited.removeFirst();
    }
    /** Try a genuine swept, collision-certified downward flight from the
     * real stance. A whole roof often has no path to its far-side ground
     * objective; descending nearby first is a legitimate longer route. */
    private static boolean directDescent(ServerLevel level,BuilderGolem worker,
            BlockPos finalGoal){
        if(!worker.onGround()||BuilderFlight.active(worker))return false;
        BlockPos from=worker.blockPosition();
        if(from.getY()<=finalGoal.getY()+2)return false;
        BlockPos best=null;
        double bestScore=Double.NEGATIVE_INFINITY;
        int low=Math.max(level.getMinY()+2,finalGoal.getY()-2);
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++){
            if(dx*dx+dz*dz>4)continue;
            for(int y=from.getY()-3;y>=low;y--){
                BlockPos candidate=from.offset(dx,y-from.getY(),dz);
                if(!level.hasChunkAt(candidate))break;
                if(!WorkerNavigation.canStandOn(level,worker,candidate))continue;
                // If a higher floor blocks this column, a lower floor
                // cannot be accepted by guessing through that barrier.
                if(BuilderFlight.canFlyBetween(level,worker,from,candidate)){
                    double score=(from.getY()-candidate.getY())*2
                            -.03*flatDistance(candidate,finalGoal);
                    if(best==null||score>bestScore){
                        bestScore=score;best=candidate;
                    }
                }
                break;
            }
        }
        if(best!=null&&BuilderFlight.requestSupportedLanding(level,worker,best)){
            System.out.println("[NN WAYPOINT DIRECT DESCENT] worker="+worker.getUUID()
                    +" from="+from+" landing="+best+" final="+finalGoal);
            return true;
        }
        return false;
    }
    /**
     * Physically verified short, vertical-first roof climb from an existing
     * supported stance. Unlike a planned trip all the way to the target,
     * each hop is real and useful even if the full roof route is unknown.
     * Never allow unbounded horizontal propeller travel.
     */
    static BlockPos chooseDirectAscent(ServerLevel level,BuilderGolem worker,
            BlockPos target,BlockPos excluded){
        BlockPos start=worker.blockPosition();
        if(target.getY()<=start.getY()+2||!worker.onGround()
                ||BuilderFlight.active(worker))return null;
        BlockPos best=null;
        double score=Double.POSITIVE_INFINITY;
        int upper=Math.min(start.getY()+32,target.getY()+3);
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++){
            if(dx*dx+dz*dz>4)continue;
            for(int y=start.getY()+3;y<=upper;y++){
                BlockPos candidate=new BlockPos(start.getX()+dx,y,start.getZ()+dz);
                if(candidate.equals(excluded)
                        ||!level.hasChunkAt(candidate)
                        ||!WorkerNavigation.canStandOn(level,worker,candidate)
                        ||!BuilderFlight.canFlyBetween(level,worker,start,candidate))
                    continue;
                // A rise in Y is the primary objective; horizontal approach
                // is secondary. Allow overshooting the target height by at
                // most 3 blocks, but do not repeatedly land on one roof lip.
                double remaining=Math.abs(target.getY()-candidate.getY());
                double horizontal=flatDistance(candidate,target);
                double candidateScore=remaining*2+.18*horizontal
                        +Math.max(0,candidate.getY()-target.getY())*2;
                if(candidateScore<score){score=candidateScore;best=candidate;}
            }
        }
        return best;
    }
    static void clear(BuilderGolem worker){
        plans.remove(worker);
        BuilderMultiLegTravel.clear(worker);
    }
    static String debug(BuilderGolem worker){
        Plan p=plans.get(worker);
        return p==null?"none":"goal="+p.goal+" stage="+p.stage
                +" stageAge="+p.stageTick+" failed="+p.failedStages
                +" completed="+p.stagesCompleted+" seen="+p.visited.size();
    }
    static boolean tick(ServerLevel level,BuilderGolem worker,BlockPos destination){
        if(destination==null)return false;
        Plan p=plans.get(worker);
        if(p==null||!p.goal.equals(destination)){
            if(p!=null)BuilderMultiLegTravel.abortWaypoint(level,worker,"new_destination");
            p=new Plan(destination);
            plans.put(worker,p);
        }
        BlockPos here=worker.blockPosition();
        long now=level.getGameTime();
        if(BuilderFlight.moving(worker)){
            // Never overwrite or stop an active collision-certified flight.
            BuilderMultiLegTravel.stand(level,worker,destination);
            return true;
        }
        if(!worker.onGround())return BuilderFlight.active(worker);
        boolean high=destination.getY()>here.getY()+2;
        boolean lower=here.getY()>destination.getY()+2;
        double horizontal=flatDistance(here,destination);
        if(p.stage!=null){
            double dist=worker.position().distanceToSqr(
                    p.stage.getX()+.5,WorkerNavigation.surveyFloorHeight(level,p.stage),
                    p.stage.getZ()+.5);
            if(dist<.27*.27){
                mark(p,p.stage);
                p.stagesCompleted++;
                System.out.println("[NN WAYPOINT STAGE REACHED] worker="+worker.getUUID()
                        +" stage="+p.stage+" destination="+destination);
                p.stage=null;p.nextSurvey=now+10;p.best=Double.POSITIVE_INFINITY;
            }else if(now-p.stageTick>STAGE_TTL){
                System.out.println("[NN WAYPOINT STAGE FAILED] worker="+worker.getUUID()
                        +" stage="+p.stage+" target="+destination
                        +" traveled="+Math.sqrt(Math.max(0,p.best)));
                mark(p,p.stage);
                p.stage=null;p.failedStages++;
                p.nextSurvey=now+20;
                WorkerNavigation.stop(worker);
            }else{
                if(dist<p.best-.04){p.best=dist;p.stageTick=now;}
                WorkerNavigation.routeToExact(level,worker,p.stage,1.0);
                return true;
            }
        }
        if(lower){
            // Downward flights must start from a verified roof edge/launch.
            // Once grounded at courtyard Y, normal long-distance walking
            // resumes; do not use a roof-hop as a long horizontal shortcut.
            if(BuilderFlight.approachDescentLaunch(level,worker,destination))return true;
            if(now>=p.nextDescentProbe){
                p.nextDescentProbe=now+50;
                if(directDescent(level,worker,destination))return true;
            }
            if(horizontal<=12&&BuilderMultiLegTravel.stand(level,worker,destination))
                return true;
        }else if(high){
            // Short individually verified ascents are better than a single
            // six-flight commitment that fails after three legs. The actual
            // support and the complete flight sweep are checked afresh for
            // every intermediate landing, including awkward roof lips.
            if(EXPERIMENTAL_DIRECT_ASCENT&&now>=p.nextAscentProbe
                    &&(horizontal<=25||level.canSeeSky(here.above()))){
                p.nextAscentProbe=now+35;
                BlockPos landing=chooseDirectAscent(
                        level,worker,destination,p.lastAscentLanding);
                if(landing!=null&&BuilderFlight.requestSupportedLanding(
                        level,worker,landing)){
                    p.lastAscentLanding=landing.immutable();
                    BuilderMultiLegTravel.clear(worker);
                    System.out.println("[NN WAYPOINT INCREMENTAL ASCENT] worker="
                            +worker.getUUID()+" from="+here+" landing="+landing
                            +" final="+destination);
                    return true;
                }
            }
            // A rooftop waypoint often lies directly ABOVE a solid roof.
            // The takeoff must be from a physically clear exterior pose;
            // planning straight up through the ceiling is never legal.
            boolean openSky=level.canSeeSky(here.above());
            if(((horizontal<=18&&(openSky
                            ||destination.getY()-here.getY()<9))
                        ||(openSky&&horizontal<=24))
                    &&BuilderMultiLegTravel.stand(level,worker,destination))
                return true;
        }else{
            // A roof can have several unsupported seams at exactly the same
            // integer Y. Earlier multi-leg searches rejected ALL same-level
            // landings; a true short powered hop is safe when the swept
            // flight and landing are both physically certified.
            if(horizontal<=12){
                if(WorkerNavigation.canReachExact(level,worker,destination)){
                    WorkerNavigation.routeToExact(level,worker,destination,1.0);
                    return true;
                }
                if(BuilderMultiLegTravel.stand(level,worker,destination))
                    return true;
            }else if(horizontal<=26&&BuilderMultiLegTravel.stand(
                    level,worker,destination))return true;
            if(horizontal>12&&WorkerNavigation.routeTo(level,worker,destination,1.0))
                return true;
        }
        // When far from an elevated goal, walk on the CURRENT level
        // towards a legitimate approach point. A full vertical route is
        // not required until the entity reaches the Dome boundary.
        if(now>=p.nextSurvey){
            p.nextSurvey=now+75;
            Set<BlockPos> connected=WorkerNavigation.groundReachableFrom(level,worker,here);
            List<BlockPos> recent=new ArrayList<>(p.visited);
            // Never require horizontal proximity while trapped BELOW a
            // roof. Going outward to find unobstructed sky is real progress.
            BlockPos candidate=null;
            // On an upper-roof assignment a 1-block horizontal separation
            // can still mean 23 blocks of solid ceiling in between. Probe
            // the connected exterior, not a greedy near-target tile.
            if(EXPERIMENTAL_GATEWAY&&high&&destination.getY()-here.getY()>=6)
                candidate=chooseAscentGateway(
                        level,worker,destination,connected,recent);
            if(candidate==null&&high&&!level.canSeeSky(here.above()))
                candidate=chooseExposedGateway(here,destination,connected,recent,
                        at->level.canSeeSky(at.above()));
            if(candidate!=null)
                System.out.println("[NN WAYPOINT ROOF GATEWAY] worker="+worker.getUUID()
                        +" from="+here+" exterior="+candidate
                        +" target="+destination+" distanceDetour="
                        +flatDistance(candidate,destination));
            if(candidate==null)candidate=chooseStage(here,destination,connected,recent);
            if(candidate!=null){
                // Stage walking owns movement until the worker reaches this
                // collision-certified standing tile. An abandoned powered
                // roof trip must NOT wake up later and fight the stage route.
                // Censor it instead of claiming an unexecuted failure.
                BuilderMultiLegTravel.clear(worker);
                BuilderNNRoofManeuverPolicy.handedOff(worker.getUUID());
                p.stage=candidate.immutable();p.stageTick=now;
                p.best=Double.POSITIVE_INFINITY;
                System.out.println("[NN WAYPOINT STAGE PLAN] worker="+worker.getUUID()
                        +" stage="+candidate+" final="+destination+" from="+here
                        +" connected="+connected.size()
                        +" remainingXZ="+flatDistance(candidate,destination));
            }else{
                System.out.println("[NN WAYPOINT STAGE NO_ROUTE] worker="+worker.getUUID()
                        +" from="+here+" target="+destination
                        +" component="+connected.size()+" high="+high+" lower="+lower);
            }
        }
        if(p.stage!=null){
            WorkerNavigation.routeToExact(level,worker,p.stage,1.0);
            return true;
        }
        return false;
    }
}
