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
    private record Goal(Kind kind,BlockPos target,boolean bodyBlocking,BuilderEscapeBox.Bounds box){}
    private record Leg(BlockPos launch,BlockPos landing){}
    private record Node(BlockPos at,List<Leg> legs,double cost,double score){}
    private record Visit(BlockPos at,int legs){}
    private static final int MAX_LEGS=8, MAX_EXPANDED=24, MAX_PROBES=120;
    private static final int PROBES_PER_LAUNCH=6;
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
        int y0=Math.max(level.getMinY()+1,Math.min(launch.getY(),goal.target().getY())-3);
        int y1=Math.min(level.getMaxY()-2,Math.max(launch.getY(),goal.target().getY())+3);
        // Limit the per-column search, but include the actual goal level.
        // This is a fallback planner, not an unbounded global 3D scan.
        if(y1-y0>48){
            if(goal.target().getY()>launch.getY())y1=y0+48;
            else y0=y1-48;
        }
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++){
            if(dx*dx+dz*dz>4)continue;
            for(int y=y0;y<=y1;y++){
                if(dx==0&&dz==0&&y==launch.getY())continue;
                BlockPos p=new BlockPos(launch.getX()+dx,y,launch.getZ()+dz);
                if(!level.hasChunkAt(p)||!WorkerNavigation.canStandOn(level,worker,p))continue;
                result.add(p);
            }
        }
        result.sort(Comparator.comparingDouble((BlockPos p)->estimate(goal,p)+flightCost(launch,p))
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
        var queue=new PriorityQueue<Node>(Comparator.comparingDouble(Node::score)
                .thenComparingDouble(Node::cost)
                .thenComparingInt(n->n.at().getX())
                .thenComparingInt(n->n.at().getY())
                .thenComparingInt(n->n.at().getZ()));
        queue.add(new Node(start,List.of(),0,estimate(goal,start)));
        var seen=new HashMap<Visit,Double>();
        seen.put(new Visit(start,0),0.0);
        int expanded=0,probes=0;
        Node bestPartial=null;
        double startScore=score(goal,start);
        while(!queue.isEmpty()&&expanded<MAX_EXPANDED&&probes<MAX_PROBES){
            Node n=queue.remove();
            if(n.cost()>seen.getOrDefault(new Visit(n.at(),n.legs().size()),Double.POSITIVE_INFINITY))continue;
            if(!n.legs().isEmpty()&&good(level,worker,goal,n.at()))return n.legs();
            if(n.legs().size()>=MAX_LEGS)continue;
            expanded++;
            for(BlockPos launch:launches(level,worker,n.at())){
                if(probes>=MAX_PROBES)break;
                // Verify the walking approach once, before spending flight
                // probes on a launch we cannot physically reach.
                if(!launch.equals(n.at())&&!WorkerNavigation.hasGroundRouteFrom(level,worker,n.at(),List.of(launch)))continue;
                int checked=0;
                for(BlockPos land:landings(level,worker,goal,launch)){
                    if(land.equals(start)||n.legs().stream().anyMatch(l->l.landing().equals(land)))continue;
                    double cost=n.cost()+Math.sqrt(n.at().distSqr(launch))+flightCost(launch,land);
                    Visit visit=new Visit(land,n.legs().size()+1);
                    if(seen.getOrDefault(visit,Double.POSITIVE_INFINITY)<=cost)continue;
                    if(checked>=PROBES_PER_LAUNCH||probes>=MAX_PROBES)break;
                    checked++;probes++;
                    if(!BuilderFlight.canFlyBetween(level,worker,launch,land))continue;
                    var next=new ArrayList<>(n.legs());next.add(new Leg(launch,land));
                    seen.put(visit,cost);
                    Node candidate=new Node(land,List.copyOf(next),cost,cost+estimate(goal,land));
                    queue.add(candidate);
                    // A complete route may detour in any direction. An
                    // unfinished prefix still needs net progress, so repeated
                    // bounded searches cannot shuttle between two platforms.
                    if(score(goal,land)+4<startScore
                            &&(bestPartial==null||score(goal,land)<score(goal,bestPartial.at())
                            ||(score(goal,land)==score(goal,bestPartial.at())&&cost<bestPartial.cost())))bestPartial=candidate;
                }
            }
        }
        // The budget may expire just after discovering a complete route.
        // Prefer that verified route to an unfinished prefix.
        Node complete=null;
        for(Node n:queue)if(good(level,worker,goal,n.at())&&(complete==null||n.cost()<complete.cost()))complete=n;
        return complete!=null?complete.legs():bestPartial==null?List.of():bestPartial.legs();
    }
    private static double estimate(Goal goal,BlockPos p){
        // WORK/EXIT/LEVEL are goal regions, not exact target cells. This
        // is a weighted search heuristic, not a shortest-path guarantee.
        return Math.sqrt(score(goal,p));
    }
    private static double flightCost(BlockPos from,BlockPos to){
        // Landing/takeoff overhead plus nominal travel distance; walking
        // approaches are charged separately. Keep the physical flight
        // validator as the authority on whether the leg is legal.
        return 2.0+Math.sqrt(from.distSqr(to));
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
            if(legs.isEmpty()){
                BlockPos here=worker.blockPosition();
                BlockPos rounded=BlockPos.containing(worker.getX(),worker.getY()+.001,worker.getZ());
                if(!WorkerNavigation.canStandOn(level,worker,here)
                        &&!WorkerNavigation.canStandOn(level,worker,rounded))
                    return BuilderFlight.escapeAwkwardFooting(level,worker,goal.target());
                return false;
            }
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
                // The flight controller is responsible for releasing
                // verified contact. At the endpoint, wait for that handoff
                // instead of endlessly submitting another zero-length leg.
                if(worker.position().distanceToSqr(feet(level,leg.landing()))<.10)return true;
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
