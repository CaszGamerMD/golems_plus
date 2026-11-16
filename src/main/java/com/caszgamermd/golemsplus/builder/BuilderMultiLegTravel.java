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
    private record VerticalNode(BlockPos at,List<Leg> legs,double score){}
    private record Visit(BlockPos at,int legs){}
    private static final int MAX_LEGS=8, MAX_EXPANDED=24, MAX_PROBES=120;
    private static final int PROBES_PER_LAUNCH=6;
    private static final class Trip {
        final Goal goal;
        List<Leg> legs;
        int index,repairs;
        boolean flying;
        long progress;
        double best=Double.POSITIVE_INFINITY;
        Trip(Goal goal,List<Leg> legs,long now){this.goal=goal;this.legs=legs;progress=now;}
    }
    private static final Map<BuilderGolem,Trip> trips=new WeakHashMap<>();
    private static final Map<BuilderGolem,Long> searchAfter=new WeakHashMap<>();
    static void clear(BuilderGolem worker){trips.remove(worker);searchAfter.remove(worker);}
    static int repairCount(BuilderGolem worker){Trip t=trips.get(worker);return t==null?0:t.repairs;}
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
    private static List<BlockPos> landings(ServerLevel level,BuilderGolem worker,Goal goal,BlockPos launch,boolean verticalOnly){
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
                if(verticalOnly&&Math.abs(y-launch.getY())<2)continue;
                BlockPos p=new BlockPos(launch.getX()+dx,y,launch.getZ()+dz);
                if(!level.hasChunkAt(p)||!WorkerNavigation.canStandOn(level,worker,p))continue;
                result.add(p);
            }
        }
        result.sort(Comparator.comparingDouble((BlockPos p)->verticalOnly?score(goal,p):estimate(goal,p)+flightCost(launch,p))
                .thenComparingDouble(launch::distSqr));
        return result;
    }
    private static List<Leg> detourPlan(ServerLevel level,BuilderGolem worker,Goal goal){
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
                for(BlockPos land:landings(level,worker,goal,launch,false)){
                    if(land.equals(start)||n.legs().stream().anyMatch(l->l.landing().equals(land)))continue;
                    double cost=n.cost()+Math.sqrt(n.at().distSqr(launch))+flightCost(launch,land);
                    Visit visit=new Visit(land,n.legs().size()+1);
                    if(seen.getOrDefault(visit,Double.POSITIVE_INFINITY)<=cost)continue;
                    if(checked>=PROBES_PER_LAUNCH||probes>=MAX_PROBES)break;
                    checked++;probes++;
                    // A flight is not a substitute for a short supported
                    // walking approach. Preserve normal walking on platforms.
                    if(Math.abs(land.getY()-launch.getY())<2
                            &&WorkerNavigation.hasGroundRouteFrom(level,worker,launch,List.of(land)))continue;
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
    private static boolean complete(ServerLevel level,BuilderGolem worker,Goal goal,List<Leg> legs){
        return !legs.isEmpty()&&good(level,worker,goal,legs.getLast().landing());
    }
    private static List<Leg> plan(ServerLevel level,BuilderGolem worker,Goal goal){
        // Preserve the proven vertical/walking route on elevated builds.
        // Only use the wider detour search when the old route cannot
        // reach its goal, or when the goal is on the same elevation.
        List<Leg> vertical=Math.abs(worker.getY()-goal.target().getY())>=2
                ?verticalPlan(level,worker,goal):List.of();
        if(complete(level,worker,goal,vertical))return vertical;
        List<Leg> detour=detourPlan(level,worker,goal);
        if(complete(level,worker,goal,detour))return detour;
        return vertical.isEmpty()?detour:vertical;
    }
    private static List<Leg> verticalPlan(ServerLevel level,BuilderGolem worker,Goal goal){
        BlockPos start=worker.blockPosition();
        // Exact landings may be a few floating-point units below the
        // integer tile. Do not discard the next leg because floor() picked
        // the solid support instead of its supported feet cell.
        if(!WorkerNavigation.canStandOn(level,worker,start)){
            BlockPos rounded=BlockPos.containing(worker.getX(),worker.getY()+.001,worker.getZ());
            if(!WorkerNavigation.canStandOn(level,worker,rounded))return List.of();
            start=rounded;
        }
        var queue=new PriorityQueue<VerticalNode>(Comparator.comparingDouble(VerticalNode::score));
        queue.add(new VerticalNode(start,List.of(),score(goal,start)));
        var seen=new HashMap<BlockPos,Integer>();seen.put(start,0);
        int expanded=0,probes=0;
        VerticalNode bestPartial=null;
        double startScore=score(goal,start);
        while(!queue.isEmpty()&&expanded++<18&&probes<120){
            VerticalNode n=queue.remove();
            if(!n.legs().isEmpty()&&good(level,worker,goal,n.at()))return n.legs();
            if(n.legs().size()>=4)continue;
            for(BlockPos launch:launches(level,worker,n.at())){
                boolean walkChecked=launch.equals(n.at()),walkOK=walkChecked;
                int checked=0;
                for(BlockPos land:landings(level,worker,goal,launch,true)){
                    if(seen.getOrDefault(land,Integer.MAX_VALUE)<=n.legs().size()+1)continue;
                    if(++checked>10||++probes>120)break;
                    if(!BuilderFlight.canFlyBetween(level,worker,launch,land))continue;
                    if(!walkChecked){
                        walkChecked=true;
                        walkOK=WorkerNavigation.hasGroundRouteFrom(level,worker,n.at(),List.of(launch));
                    }
                    if(!walkOK)break;
                    var next=new ArrayList<>(n.legs());next.add(new Leg(launch,land));
                    if(good(level,worker,goal,land))return List.copyOf(next);
                    seen.put(land,next.size());
                    VerticalNode candidate=new VerticalNode(land,List.copyOf(next),score(goal,land)+next.size()*2);
                    queue.add(candidate);
                    // Long journeys may exceed this search window. Only
                    // commit an incomplete chain if it ends on real support
                    // with a strictly better goal score; no sideways cycle.
                    if(score(goal,land)+4<startScore
                            &&(bestPartial==null||candidate.score()<bestPartial.score()))bestPartial=candidate;
                }
                if(probes>=120)break;
            }
        }
        return bestPartial==null?List.of():bestPartial.legs();
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
    /** Rejoin a later, still valid portion of the committed trip. Only
     * run from physical ground, and never accept an incomplete connector. */
    private static boolean repair(ServerLevel level,BuilderGolem worker,Trip t,long now){
        if(t.repairs>=2||!worker.onGround()||BuilderFlight.active(worker))return false;
        BlockPos here=BlockPos.containing(worker.getX(),worker.getY()+.001,worker.getZ());
        if(!WorkerNavigation.canStandOn(level,worker,here))return false;
        t.repairs++;
        for(int j=t.index+1;j<t.legs.size();j++){
            Leg next=t.legs.get(j);
            // Validate the saved suffix against the world as it is now.
            boolean valid=true;
            for(int k=j;k<t.legs.size();k++){
                Leg leg=t.legs.get(k);
                if(!BuilderFlight.canFlyBetween(level,worker,leg.launch(),leg.landing())
                        ||(k>j&&!t.legs.get(k-1).landing().equals(leg.launch())
                        &&!WorkerNavigation.hasGroundRouteFrom(level,worker,t.legs.get(k-1).landing(),List.of(leg.launch())))){
                    valid=false;break;
                }
            }
            if(!valid)continue;
            List<Leg> connector=List.of();
            if(!here.equals(next.launch())&&!WorkerNavigation.hasGroundRouteFrom(level,worker,here,List.of(next.launch()))){
                Goal rejoin=new Goal(Kind.STAND,next.launch(),false,null);
                connector=plan(level,worker,rejoin);
                if(!complete(level,worker,rejoin,connector))continue;
            }
            var repaired=new ArrayList<>(connector);
            repaired.addAll(t.legs.subList(j,t.legs.size()));
            t.legs=List.copyOf(repaired);t.index=0;t.flying=false;t.progress=now;t.best=Double.POSITIVE_INFINITY;
            WorkerNavigation.stop(worker);
            BuilderTrace.decision(level,worker,"MULTI_TRIP_REPAIR",next.launch(),"retained suffix legs="+(repaired.size()-connector.size())+" connector="+connector);
            return true;
        }
        return false;
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
                    if(repair(level,worker,t,now))return true;
                }
                WorkerNavigation.routeToExact(level,worker,leg.launch(),1.0);return true;
            }
        }
        if(repair(level,worker,t,now))return true;
        BuilderTrace.decision(level,worker,"MULTI_TRIP_REPLAN",leg.landing(),"blocked support or physical leg stopped");
        trips.remove(worker);searchAfter.put(worker,now+60);
        if(!BuilderFlight.active(worker))WorkerNavigation.stop(worker);
        return false;
    }
}
