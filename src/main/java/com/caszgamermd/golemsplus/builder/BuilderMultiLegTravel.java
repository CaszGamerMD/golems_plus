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
    private record Goal(Kind kind,BlockPos target,boolean bodyBlocking,BuilderEscapeBox.Bounds box){}
    private record Leg(BlockPos launch,BlockPos landing){}
    private record Node(BlockPos at,List<Leg> legs,double score){}
    private static final class Trip {
        final Goal goal;
        final List<Leg> legs;
        int index;
        boolean flying;
        long progress;
        double best=Double.POSITIVE_INFINITY;
        Trip(Goal goal,List<Leg> legs,long now){this.goal=goal;this.legs=legs;progress=now;}
    }
    private static final Map<BuilderGolem,Trip> trips=new WeakHashMap<>();
    private static final Map<BuilderGolem,Long> searchAfter=new WeakHashMap<>();
    static void clear(BuilderGolem worker){trips.remove(worker);searchAfter.remove(worker);}
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
        var legs=plan(level,worker,goal);
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
    private static List<BlockPos> launches(ServerLevel level,BuilderGolem worker,BlockPos at){
        var result=new ArrayList<BlockPos>();result.add(at);
        for(int d=1;d<=3;d++)for(int[] v:new int[][]{{1,0},{-1,0},{0,1},{0,-1}}){
            BlockPos p=at.offset(d*v[0],0,d*v[1]);
            if(WorkerNavigation.canStandOn(level,worker,p))result.add(p);
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
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++){
            if(dx*dx+dz*dz>4)continue;
            for(int y=y0;y<=y1;y++){
                if(Math.abs(y-launch.getY())<2)continue;
                BlockPos p=new BlockPos(launch.getX()+dx,y,launch.getZ()+dz);
                if(!level.hasChunkAt(p)||!WorkerNavigation.canStandOn(level,worker,p))continue;
                result.add(p);
            }
        }
        result.sort(Comparator.comparingDouble((BlockPos p)->score(goal,p))
                .thenComparingDouble(launch::distSqr));
        return result;
    }
    private static List<Leg> plan(ServerLevel level,BuilderGolem worker,Goal goal){
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
        int expandedLimit=LONG_DETOUR?36:18;
        int probeLimit=LONG_DETOUR?240:120;
        int checkedLimit=LONG_DETOUR?18:10;
        int maxLegs=LONG_DETOUR?6:4;
        Node bestPartial=null;
        double startScore=score(goal,start);
        while(!queue.isEmpty()&&expanded++<expandedLimit&&probes<probeLimit){
            Node n=queue.remove();
            if(!n.legs().isEmpty()&&good(level,worker,goal,n.at()))return n.legs();
            if(n.legs().size()>=maxLegs)continue;
            for(BlockPos launch:launches(level,worker,n.at())){
                boolean walkChecked=launch.equals(n.at()),walkOK=walkChecked;
                int checked=0;
                for(BlockPos land:landings(level,worker,goal,launch)){
                    if(seen.getOrDefault(land,Integer.MAX_VALUE)<=n.legs().size()+1)continue;
                    if(++checked>checkedLimit||++probes>probeLimit)break;
                    if(!BuilderFlight.canFlyBetween(level,worker,launch,land))continue;
                    if(!walkChecked){
                        walkChecked=true;
                        walkOK=WorkerNavigation.hasGroundRouteFrom(level,worker,n.at(),List.of(launch));
                    }
                    if(!walkOK)break;
                    var next=new ArrayList<>(n.legs());next.add(new Leg(launch,land));
                    if(good(level,worker,goal,land))return List.copyOf(next);
                    seen.put(land,next.size());
                    Node candidate=new Node(land,List.copyOf(next),score(goal,land)+next.size()*2);
                    queue.add(candidate);
                    // Long journeys may exceed this search window. Only
                    // commit an incomplete chain if it ends on real support
                    // with a strictly better goal score; no sideways cycle.
                    if(usefulPartial(startScore,score(goal,land),start,
                            goal.target(),land,LONG_DETOUR)
                            &&(bestPartial==null||candidate.score()<bestPartial.score()))
                        bestPartial=candidate;
                }
                if(probes>=probeLimit)break;
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
            var legs=plan(level,worker,goal);
            if(legs.isEmpty())return false;
            t=new Trip(goal,legs,now);trips.put(worker,t);
            BuilderTrace.decision(level,worker,"MULTI_TRIP_COMMIT",goal.target(),"verified flight legs="+legs);
        }
        if(t.index>=t.legs.size()){trips.remove(worker);return false;}
        Leg leg=t.legs.get(t.index);
        if(BuilderFlight.moving(worker))return true;
        if(t.flying){
            if(arrived(level,worker,leg.landing())){
                BuilderTrace.decision(level,worker,"MULTI_TRIP_LAND",leg.landing(),"finished leg "+(t.index+1)+"/"+t.legs.size());
                t.index++;t.flying=false;t.progress=now;t.best=Double.POSITIVE_INFINITY;
                if(t.index==t.legs.size()){
                    trips.remove(worker);searchAfter.remove(worker);
                    if(good(level,worker,goal,leg.landing()))return false;
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
            if(now-t.progress<400)return true;
        }else{
            double d=worker.position().distanceToSqr(feet(level,leg.launch()));
            if(d<t.best-.04){t.best=d;t.progress=now;}
            if(now-t.progress<400&&WorkerNavigation.canStandOn(level,worker,leg.launch())
                    &&WorkerNavigation.canStandOn(level,worker,leg.landing())){
                if(centeredLaunch(level,worker,leg.launch())){
                    if(BuilderFlight.requestSupportedLanding(level,worker,leg.landing())){
                        t.flying=true;t.progress=now;return true;
                    }
                    BuilderTrace.decision(level,worker,"MULTI_TRIP_LAUNCH_REJECT",leg.landing(),
                            "centered launch="+leg.launch()+" feet="+worker.position());
                }
                WorkerNavigation.routeToExact(level,worker,leg.launch(),1.0);return true;
            }
        }
        BuilderTrace.decision(level,worker,"MULTI_TRIP_REPLAN",leg.landing(),"blocked support or physical leg stopped");
        trips.remove(worker);searchAfter.put(worker,now+60);
        if(!BuilderFlight.active(worker))WorkerNavigation.stop(worker);
        return false;
    }
}
