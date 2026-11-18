package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Bounded look-ahead through real grounded poses. Each flight starts a
 * fresh two-block horizontal envelope only after a supported landing.
 * The destination and entire sequence stay committed through intermediate
 * walks and flights; no route is replaced solely because of distance. */
final class BuilderMultiLegTravel {
    private BuilderMultiLegTravel(){}
    private enum Kind {WORK,EXIT,LEVEL,STAND}
    private static final boolean LONG_DETOUR=Boolean.parseBoolean(System.getProperty(
            "golems.nn.longDetour",
            System.getenv().getOrDefault("GOLEMS_NN_LONG_DETOUR","false")));
    // Dome roof walking can change Y at every tile. Allow collision-verified
    // grounded approaches to sloped takeoffs rather than assuming that only
    // launch stances at the current integer elevation are walkable.
    private static final boolean SLOPED_LAUNCHES=Boolean.parseBoolean(
            System.getenv().getOrDefault("GOLEMS_NN_SLOPED_ROOF_LAUNCHES","false"));
    private record Goal(Kind kind,BlockPos target,boolean bodyBlocking,BuilderEscapeBox.Bounds box){}
    record Leg(BlockPos launch,BlockPos landing){}
    private record Node(BlockPos at,List<Leg> legs,double score){}
    private static final class Trip {
        final Goal goal;
        final List<Leg> legs;
        final BuilderNNRoofManeuverPolicy.Selection neural;
        int index;
        int rejectedLaunches;
        boolean flying;
        long progress;
        double best=Double.POSITIVE_INFINITY;
        Trip(Goal goal,List<Leg> legs,long now,
                BuilderNNRoofManeuverPolicy.Selection neural){
            this.goal=goal;this.legs=legs;this.neural=neural;progress=now;
        }
    }
    private static final Map<BuilderGolem,Trip> trips=new WeakHashMap<>();
    private static final Map<BuilderGolem,Long> searchAfter=new WeakHashMap<>();
    // A physically rejected takeoff must not be selected again immediately.
    // This is per-worker, temporary, and never bypasses collision checks.
    private static final Map<BuilderGolem,Map<Leg,Long>> rejectedLegs=new WeakHashMap<>();
    private static final long FAILED_LEG_RETRY_TICKS=240;
    private static final boolean STICKY_PHYSICAL_REJECTIONS=Boolean.parseBoolean(
            System.getenv().getOrDefault("GOLEMS_NN_STICKY_ROOF_REJECTIONS","false"));
    static long rejectionWindow(String reason,boolean sticky){
        // Repeated launch geometry failures and abandoned powered hovers
        // cannot become passable merely by checking the same blocks again
        // 240 ticks later. Within one waypoint trial retain the failure,
        // but never apply this experimental exclusion to ordinary builds.
        if(sticky&&("repeated_centered_launch_reject".equals(reason)
                ||"stopped_hover_landing_recovery".equals(reason)
                ||"flight_ended_short".equals(reason)))return 850;
        return FAILED_LEG_RETRY_TICKS;
    }
    static boolean staleFlightLeg(boolean moving,long elapsed){
        return !moving&&elapsed>=25;
    }
    private static boolean rejected(BuilderGolem worker,Leg leg,long now){
        Map<Leg,Long> bad=rejectedLegs.get(worker);
        return bad!=null&&bad.getOrDefault(leg,-1L)>now;
    }
    private static void reject(BuilderGolem worker,Leg leg,long now,String reason){
        long expiry=now+rejectionWindow(reason,STICKY_PHYSICAL_REJECTIONS);
        rejectedLegs.computeIfAbsent(worker,k->new HashMap<>())
                .put(leg,expiry);
        System.out.println("[NN WAYPOINT BAD LEG] worker="+worker.getUUID()
                +" launch="+leg.launch()+" landing="+leg.landing()
                +" reason="+reason+" retryAfter="+expiry);
    }
    static void clear(BuilderGolem worker){trips.remove(worker);searchAfter.remove(worker);}
    static void resetRejections(BuilderGolem worker){rejectedLegs.remove(worker);}
    static void abortWaypoint(ServerLevel level,BuilderGolem worker,String reason){
        Trip t=trips.remove(worker);
        searchAfter.remove(worker);
        // A flight that ended in unsupported hover demonstrably did NOT
        // complete its chosen leg. Do not propose that same physically
        // fragile maneuver again immediately after rescue; the next search
        // must try another supported route instead of looping in place.
        if(t!=null&&"stopped_hover_landing_recovery".equals(reason)
                &&t.flying&&t.index<t.legs.size())
            reject(worker,t.legs.get(t.index),level.getGameTime(),reason);
        if(t!=null&&t.goal.kind()==Kind.STAND)
            BuilderNNRoofManeuverPolicy.failed(level,worker,t.neural,reason);
    }
    static boolean working(BuilderGolem worker,BlockPos target){
        Trip t=trips.get(worker);return t!=null&&t.goal.kind()==Kind.WORK&&t.goal.target().equals(target);
    }
    private record WorkProbe(Goal goal,BlockPos start,long expires,boolean feasible){}
    private static final Map<BuilderGolem,WorkProbe> workProbes=new WeakHashMap<>();
    /** Rare finishing-cell admission: a complete grounded flight chain,
     * not merely a partial improvement, must reach a safe work pose. */
    static boolean canAccessWork(ServerLevel level,BuilderGolem worker,BlockPos target,boolean blocking){
        if(!BuilderFlight.enabled(worker)||BuilderFlight.active(worker)||!worker.onGround()
                ||BuilderGrappleAccess.active(worker)||BuilderScaffolding.isControllingMovement(worker))return false;
        var goal=new Goal(Kind.WORK,target.immutable(),blocking,null);
        long now=level.getGameTime();
        var old=workProbes.get(worker);
        if(old!=null&&old.goal().equals(goal)&&old.start().equals(worker.blockPosition())&&now<old.expires())
            return old.feasible();
        var legs=plan(level,worker,goal,false);
        boolean feasible=!legs.isEmpty()&&good(level,worker,goal,legs.getLast().landing());
        workProbes.put(worker,new WorkProbe(goal,worker.blockPosition(),now+60,feasible));
        if(feasible)System.out.println("[NN MULTILEG ACCESS] target="+target+" legs="+legs);
        return feasible;
    }
    static boolean work(ServerLevel level,BuilderGolem worker,BlockPos target,boolean blocking){
        return tick(level,worker,new Goal(Kind.WORK,target.immutable(),blocking,null));
    }
    static boolean exit(ServerLevel level,BuilderGolem worker,BlockPos target,BuilderEscapeBox.Bounds box){
        return tick(level,worker,new Goal(Kind.EXIT,target.immutable(),false,box));
    }
    static boolean elevation(ServerLevel level,BuilderGolem worker,BlockPos target,BuilderEscapeBox.Bounds box){
        return tick(level,worker,new Goal(Kind.LEVEL,target.immutable(),false,box));
    }
    static boolean stand(ServerLevel level,BuilderGolem worker,BlockPos target){
        return tick(level,worker,new Goal(Kind.STAND,target.immutable(),false,null));
    }
    private static Vec3 feet(ServerLevel level,BlockPos p){
        return new Vec3(p.getX()+.5,WorkerNavigation.surveyFloorHeight(level,p),p.getZ()+.5);
    }
    private static boolean arrived(ServerLevel level,BuilderGolem worker,BlockPos p){
        return worker.onGround()&&!BuilderFlight.active(worker)
                &&worker.position().distanceToSqr(feet(level,p))<.10;
    }
    private static boolean centeredLaunch(ServerLevel level,BuilderGolem worker,BlockPos p){
        return arrived(level,worker,p)
                &&worker.position().distanceToSqr(feet(level,p))<.035*.035;
    }
    static String traceStatus(BuilderGolem worker){
        Trip t=trips.get(worker);
        if(t==null)return "none";
        return "goal="+t.goal+" leg="+t.index+"/"+t.legs.size()
                +" flying="+t.flying+" legs="+t.legs+" progress="+t.progress;
    }
    private static boolean good(ServerLevel level,BuilderGolem worker,Goal goal,BlockPos p){
        if(goal.kind()==Kind.STAND)return p.equals(goal.target());
        if(goal.kind()==Kind.EXIT)return goal.box().sideOutside(p)||p.getY()<=goal.box().minY();
        if(goal.kind()==Kind.LEVEL)return goal.box().outside(p)
                &&Math.abs(WorkerNavigation.surveyFloorHeight(level,p)-goal.target().getY())<=.6;
        return BuilderFlight.safeWorkPose(level,worker,goal.target(),feet(level,p),goal.bodyBlocking());
    }
    /** Going above / beyond the target can be the only valid path to its
     * other side. An incomplete detour must still be finite and bounded. */
    static boolean usefulPartial(double startingCost,double landingCost,
            BlockPos start,BlockPos target,BlockPos landing,boolean longDetour){
        if(landingCost+4<startingCost)return true;
        if(!longDetour||!Double.isFinite(startingCost)||!Double.isFinite(landingCost)
                ||landingCost>startingCost+84)return false;
        boolean above=landing.getY()>=target.getY()+2;
        boolean pastX=(start.getX()<target.getX()&&landing.getX()>target.getX()+1)
                ||(start.getX()>target.getX()&&landing.getX()<target.getX()-1);
        boolean pastZ=(start.getZ()<target.getZ()&&landing.getZ()>target.getZ()+1)
                ||(start.getZ()>target.getZ()&&landing.getZ()<target.getZ()-1);
        return above||pastX||pastZ;
    }
    private static double score(Goal goal,BlockPos p){
        double dy=p.getY()-goal.target().getY();
        if(goal.kind()==Kind.LEVEL)return dy*dy*4+.05*p.distSqr(goal.target());
        if(goal.kind()==Kind.EXIT)return p.distSqr(goal.target())+Math.max(0,dy)*Math.max(0,dy)*4;
        return p.distSqr(goal.target())+dy*dy;
    }
    static List<BlockPos> candidateLaunchCells(BlockPos at,boolean slopes){
        var result=new LinkedHashSet<BlockPos>();result.add(at);
        int[][] compass={{1,0},{-1,0},{0,1},{0,-1},
                {1,1},{1,-1},{-1,1},{-1,-1}};
        for(int radius=1;radius<=3;radius++){
            for(int[] v:compass){
                BlockPos same=at.offset(radius*v[0],0,radius*v[1]);
                result.add(same);
                if(slopes)for(int deltaY:new int[]{1,-1,2,-2})
                    result.add(at.offset(radius*v[0],deltaY,radius*v[1]));
            }
        }
        return List.copyOf(result);
    }
    private static List<BlockPos> launches(ServerLevel level,BuilderGolem worker,BlockPos at){
        var result=new ArrayList<BlockPos>();
        for(BlockPos p:candidateLaunchCells(at,SLOPED_LAUNCHES)){
            // Uncertified standing cells cannot become launch candidates.
            // A DIFFERENT launch is subsequently checked for actual A* reachability
            // before any flight is accepted by the physical planner.
            if(WorkerNavigation.canStandOn(level,worker,p))result.add(p);
            if(result.size()>=(SLOPED_LAUNCHES?32:13))break;
        }
        return result;
    }
    private static List<BlockPos> landings(ServerLevel level,BuilderGolem worker,Goal goal,BlockPos launch){
        var result=new ArrayList<BlockPos>();
        int margin=LONG_DETOUR?7:3;
        int y0=Math.max(level.getMinY()+1,Math.min(launch.getY(),goal.target().getY())-margin);
        int y1=Math.min(level.getMaxY()-2,Math.max(launch.getY(),goal.target().getY())+margin);
        // Limit the per-column search, but include the actual goal level.
        // This is a fallback planner, not an unbounded global 3D scan.
        if(y1-y0>48){
            if(goal.target().getY()>launch.getY())y1=y0+48;
            else y0=y1-48;
        }
        // The optional 3.5m short roof-gap hop can land across a wider
        // separation, but every edge is still validated by the same real
        // collision-swept propeller path and a grounded landing. Normal
        // builder work keeps the legacy 2m envelope.
        int radius=BuilderFlight.HORIZONTAL_LIMIT>3.0?3:2;
        int radiusSq=radius*radius;
        for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++){
            if(dx*dx+dz*dz>radiusSq)continue;
            for(int y=y0;y<=y1;y++){
                // Traversal-only Dome routes must bridge roof seams at
                // the SAME elevation, or across a one-block roof lip.
                // Previously every same-level/one-level landing was
                // discarded BEFORE the physical flight checker ran,
                // making isolated full-block roof islands unreachable.
                // Only attempt a supported DIFFERENT tile; canFlyBetween
                // still checks the complete swept 2m powered maneuver.
                if(goal.kind()!=Kind.STAND&&Math.abs(y-launch.getY())<2)continue;
                BlockPos p=new BlockPos(launch.getX()+dx,y,launch.getZ()+dz);
                if(p.equals(launch))continue;
                if(!level.hasChunkAt(p)||!WorkerNavigation.canStandOn(level,worker,p))continue;
                result.add(p);
            }
        }
        result.sort(Comparator.comparingDouble((BlockPos p)->score(goal,p))
                .thenComparingDouble(launch::distSqr));
        if(LONG_DETOUR&&(goal.kind()==Kind.WORK||goal.kind()==Kind.STAND)){
            // Earlier "closest first" searches used up the bounded probe
            // budget before considering a high roof or the far side.
            // Interleave already-supported candidates; no safety predicate
            // or maximum search budget is relaxed.
            List<BlockPos> roof=new ArrayList<>(),below=new ArrayList<>(),
                    farSide=new ArrayList<>(),progressive=new ArrayList<>();
            // A 26-block climb cannot begin with a high roof pose if a
            // solid overhang blocks that vertical shaft. A landing near
            // +16 Y often forms the first physically possible ascent.
            int remaining=goal.target().getY()-launch.getY();
            int firstLegDelta=Math.clamp(remaining,-16,16);
            for(BlockPos landing:result){
                if(remaining>2&&landing.getY()>launch.getY()+2
                        ||remaining< -2&&landing.getY()<launch.getY()-2
                        ||Math.abs(remaining)<=2
                            &&Math.abs(landing.getY()-launch.getY())<=1)
                    progressive.add(landing);
                if(landing.getY()>=goal.target().getY()+2)roof.add(landing);
                if(landing.getY()<=goal.target().getY()-2)below.add(landing);
                if((launch.getX()<goal.target().getX()
                            &&landing.getX()>goal.target().getX()+1)
                        ||(launch.getX()>goal.target().getX()
                            &&landing.getX()<goal.target().getX()-1)
                        ||(launch.getZ()<goal.target().getZ()
                            &&landing.getZ()>goal.target().getZ()+1)
                        ||(launch.getZ()>goal.target().getZ()
                            &&landing.getZ()<goal.target().getZ()-1))
                    farSide.add(landing);
            }
            progressive.sort(Comparator
                    .comparingDouble((BlockPos p)->
                            Math.abs((p.getY()-launch.getY())-firstLegDelta)*3.0
                            +.04*p.distSqr(goal.target()))
                    .thenComparingDouble(launch::distSqr));
            var mixed=new LinkedHashSet<BlockPos>();
            for(int i=0;i<Math.min(result.size(),30);i++){
                if(i<progressive.size())mixed.add(progressive.get(i));
                if(i<result.size())mixed.add(result.get(i));
                if(i<farSide.size())mixed.add(farSide.get(i));
                if(i<below.size())mixed.add(below.get(i));
                if(i<roof.size())mixed.add(roof.get(i));
            }
            mixed.addAll(result);
            result.clear();result.addAll(mixed);
        }
        return result;
    }
    private static List<Leg> plan(ServerLevel level,BuilderGolem worker,Goal goal,
            boolean commit){
        // A navigation-only waypoint is an actual safe landing objective,
        // not a fake block-placement task. Let the same route model learn
        // from successful physical arrival at this standing location.
        boolean neural=commit&&BuilderNNRoofManeuverPolicy.ENABLED
                &&(goal.kind()==Kind.WORK||goal.kind()==Kind.STAND);
        BlockPos start=worker.blockPosition();
        // Exact landings may be a few floating-point units below the
        // integer tile. Do not discard the next leg because floor() picked
        // the solid support instead of its supported feet cell.
        if(!WorkerNavigation.canStandOn(level,worker,start)){
            BlockPos rounded=BlockPos.containing(worker.getX(),worker.getY()+.001,worker.getZ());
            if(!WorkerNavigation.canStandOn(level,worker,rounded))return List.of();
            start=rounded;
        }
        var queue=new PriorityQueue<Node>(Comparator.comparingDouble(Node::score));
        queue.add(new Node(start,List.of(),score(goal,start)));
        var seen=new HashMap<BlockPos,Integer>();seen.put(start,0);
        int expanded=0,probes=0;
        int expandedLimit=LONG_DETOUR?(goal.kind()==Kind.STAND?48:36):18;
        int probeLimit=LONG_DETOUR?(goal.kind()==Kind.STAND?320:240):120;
        int checkedLimit=LONG_DETOUR?(goal.kind()==Kind.STAND?24:18):10;
        int maxLegs=LONG_DETOUR?6:4;
        Node bestPartial=null;
        var completed=new ArrayList<BuilderNNRoofManeuverPolicy.Candidate>();
        var alternatePartials=new ArrayList<Node>();
        double startScore=score(goal,start);
        search: while(!queue.isEmpty()&&expanded++<expandedLimit&&probes<probeLimit){
            Node n=queue.remove();
            if(!n.legs().isEmpty()&&good(level,worker,goal,n.at())){
                if(!neural)return n.legs();
                completed.add(new BuilderNNRoofManeuverPolicy.Candidate(n.legs(),true));
                if(completed.size()>=6)break;
                continue;
            }
            if(n.legs().size()>=maxLegs)continue;
            for(BlockPos launch:launches(level,worker,n.at())){
                boolean walkChecked=launch.equals(n.at()),walkOK=walkChecked;
                int checked=0;
                for(BlockPos land:landings(level,worker,goal,launch)){
                    if(seen.getOrDefault(land,Integer.MAX_VALUE)<=n.legs().size()+1)continue;
                    if(++probes>probeLimit)break;
                    Leg physical=new Leg(launch,land);
                    if(rejected(worker,physical,level.getGameTime())
                            ||!BuilderFlight.canFlyBetween(level,worker,launch,land))continue;
                    // Count only collision-verified candidate branches here.
                    // Previously 24 physically impossible high landings
                    // consumed the entire per-launch quota before lower,
                    // genuinely executable roof entrances were considered.
                    if(++checked>checkedLimit)break;
                    if(!walkChecked){
                        walkChecked=true;
                        walkOK=WorkerNavigation.hasGroundRouteFrom(level,worker,n.at(),List.of(launch));
                    }
                    if(!walkOK)break;
                    var next=new ArrayList<>(n.legs());next.add(new Leg(launch,land));
                    if(good(level,worker,goal,land)){
                        if(!neural)return List.copyOf(next);
                        completed.add(new BuilderNNRoofManeuverPolicy.Candidate(
                                List.copyOf(next),true));
                        if(completed.size()>=6)break search;
                        continue;
                    }
                    seen.put(land,next.size());
                    Node candidate=new Node(land,List.copyOf(next),score(goal,land)+next.size()*2);
                    queue.add(candidate);
                    // Long journeys may exceed this search window. Only
                    // commit an incomplete chain if it ends on real support
                    // with a strictly better goal score; no sideways cycle.
                    if(usefulPartial(startScore,score(goal,land),start,
                            goal.target(),land,LONG_DETOUR)){
                        if(bestPartial==null||candidate.score()<bestPartial.score())
                            bestPartial=candidate;
                        if(neural&&alternatePartials.size()<40)
                            alternatePartials.add(candidate);
                    }
                }
                if(probes>=probeLimit)break;
            }
        }
        if(neural){
            if(completed.size()>1)
                return BuilderNNRoofManeuverPolicy.choose(
                        level,worker,goal.target(),completed,0);
            if(completed.size()==1)
                return BuilderNNRoofManeuverPolicy.choose(
                        level,worker,goal.target(),completed,0);
            if(bestPartial!=null){
                alternatePartials.sort(Comparator.comparingDouble(Node::score));
                var choices=new ArrayList<BuilderNNRoofManeuverPolicy.Candidate>();
                choices.add(new BuilderNNRoofManeuverPolicy.Candidate(
                        bestPartial.legs(),false));
                // The baseline closest valid partial must remain available.
                // Distinct outcomes include uphill, over-X/Z and lower-roof
                // supported landings. Do not expand physical probe budgets.
                boolean aboveAdded=false,overshootAdded=false,belowAdded=false;
                for(Node option:alternatePartials){
                    if(choices.size()>=8)break;
                    if(option.legs().equals(bestPartial.legs()))continue;
                    boolean above=option.at().getY()>goal.target().getY()+1;
                    boolean overshoot=(start.getX()<goal.target().getX()
                                    &&option.at().getX()>goal.target().getX()+1)
                            ||(start.getX()>goal.target().getX()
                                    &&option.at().getX()<goal.target().getX()-1)
                            ||(start.getZ()<goal.target().getZ()
                                    &&option.at().getZ()>goal.target().getZ()+1)
                            ||(start.getZ()>goal.target().getZ()
                                    &&option.at().getZ()<goal.target().getZ()-1);
                    boolean below=option.at().getY()<goal.target().getY()-1;
                    if((above&&!aboveAdded)||(overshoot&&!overshootAdded)
                            ||(below&&!belowAdded)||choices.size()<4){
                        if(choices.stream().noneMatch(prev->
                                prev.legs().getLast().landing().equals(option.at()))){
                            choices.add(new BuilderNNRoofManeuverPolicy.Candidate(
                                    option.legs(),false));
                            aboveAdded|=above;overshootAdded|=overshoot;
                            belowAdded|=below;
                        }
                    }
                }
                return BuilderNNRoofManeuverPolicy.choose(
                        level,worker,goal.target(),choices,0);
            }
        }
        if(bestPartial!=null&&LONG_DETOUR&&
                score(goal,bestPartial.at())>=startScore-4)
            System.out.println("[NN ROUTE OVERSHOOT PLAN] goal="+goal.target()
                    +" from="+start+" via="+bestPartial.at()
                    +" legs="+bestPartial.legs().size()
                    +" originCost="+startScore+" viaCost="+score(goal,bestPartial.at()));
        return bestPartial==null?List.of():bestPartial.legs();
    }
    private static boolean tick(ServerLevel level,BuilderGolem worker,Goal goal){
        if(!BuilderFlight.enabled(worker))return false;
        Trip t=trips.get(worker);
        if(t!=null&&!t.goal.equals(goal)){trips.remove(worker);t=null;}
        if(BuilderGrappleAccess.active(worker)||BuilderScaffolding.isControllingMovement(worker))return false;
        long now=level.getGameTime();
        if(t==null){
            if(BuilderFlight.active(worker)||!worker.onGround()||now<searchAfter.getOrDefault(worker,0L))return false;
            searchAfter.put(worker,now+60);
            var legs=plan(level,worker,goal,true);
            if(legs.isEmpty())return false;
            var selection=(goal.kind()==Kind.WORK||goal.kind()==Kind.STAND)
                    ?BuilderNNRoofManeuverPolicy.committed(worker,goal.target(),legs):null;
            t=new Trip(goal,legs,now,selection);trips.put(worker,t);
            BuilderTrace.decision(level,worker,"MULTI_TRIP_COMMIT",goal.target(),"verified flight legs="+legs);
        }
        if(t.index>=t.legs.size()){trips.remove(worker);return false;}
        Leg leg=t.legs.get(t.index);
        if(BuilderFlight.moving(worker))return true;
        if(t.flying){
            if(arrived(level,worker,leg.landing())){
                BuilderTrace.decision(level,worker,"MULTI_TRIP_LAND",leg.landing(),"finished leg "+(t.index+1)+"/"+t.legs.size());
                t.index++;t.flying=false;t.progress=now;t.best=Double.POSITIVE_INFINITY;
                if(t.index<t.legs.size())
                    BuilderNNRoofManeuverPolicy.legLanded(level,worker,t.neural,t.index);
                if(t.index==t.legs.size()){
                    trips.remove(worker);searchAfter.remove(worker);
                    boolean reachedGoal=good(level,worker,goal,leg.landing());
                    BuilderNNRoofManeuverPolicy.finished(
                            level,worker,t.neural,reachedGoal);
                    if(reachedGoal)return false;
                    BuilderTrace.decision(level,worker,"MULTI_TRIP_CONTINUE",leg.landing(),"intermediate chain complete; search onward from real support");
                    return true;
                }
                return true;
            }
            if(BuilderFlight.active(worker)&&now-t.progress<400){
                if(BuilderFlight.requestSupportedLanding(level,worker,leg.landing()))return true;
                // A different emergency landing invalidates this leg.
                // Never keep waiting for the old landing after diverting.
                if(BuilderFlight.land(level,worker,false)){
                    BuilderTrace.decision(level,worker,"MULTI_TRIP_REPLAN",leg.landing(),"diverted to safe landing");
                    trips.remove(worker);searchAfter.put(worker,now+20);return true;
                }
            }
            // A stopped/aborted flight is NOT continuing progress toward
            // its promised landing. The old controller waited 400 ticks
            // after a dead leg, spending half of a 750-tick training trial
            // without finding another route.
            if(staleFlightLeg(BuilderFlight.moving(worker),now-t.progress)){
                reject(worker,leg,now,"flight_ended_short");
                BuilderNNRoofManeuverPolicy.failed(
                        level,worker,t.neural,"flight_ended_short");
                trips.remove(worker);searchAfter.put(worker,now+20);
                if(!BuilderFlight.active(worker))WorkerNavigation.stop(worker);
                return false;
            }
            if(now-t.progress<180)return true;
        }else{
            double d=worker.position().distanceToSqr(feet(level,leg.launch()));
            if(d<t.best-.04){t.best=d;t.progress=now;}
            if(now-t.progress<400&&WorkerNavigation.canStandOn(level,worker,leg.launch())
                    &&WorkerNavigation.canStandOn(level,worker,leg.landing())){
                if(centeredLaunch(level,worker,leg.launch())){
                    if(BuilderFlight.requestSupportedLanding(level,worker,leg.landing())){
                        t.flying=true;t.rejectedLaunches=0;t.progress=now;return true;
                    }
                    t.rejectedLaunches++;
                    BuilderTrace.decision(level,worker,"MULTI_TRIP_LAUNCH_REJECT",leg.landing(),
                            "centered launch="+leg.launch()+" feet="+worker.position()
                            +" physicalRejects="+t.rejectedLaunches);
                    if(t.rejectedLaunches>=3){
                        reject(worker,leg,now,"repeated_centered_launch_reject");
                        BuilderNNRoofManeuverPolicy.failed(
                                level,worker,t.neural,"rejected_takeoff");
                        trips.remove(worker);searchAfter.put(worker,now+20);
                        return false;
                    }
                }
                WorkerNavigation.routeToExact(level,worker,leg.launch(),1.0);return true;
            }
        }
        BuilderTrace.decision(level,worker,"MULTI_TRIP_REPLAN",leg.landing(),"blocked support or physical leg stopped");
        reject(worker,leg,now,"timeout_or_blocked");
        BuilderNNRoofManeuverPolicy.failed(level,worker,t.neural,"stalled_or_blocked_leg");
        trips.remove(worker);searchAfter.put(worker,now+60);
        if(!BuilderFlight.active(worker))WorkerNavigation.stop(worker);
        return false;
    }
}
