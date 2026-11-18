package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final int STAGE_TTL=105;
    private static final int RETAIN=12;
    private static final Map<BuilderGolem,Plan> plans=new WeakHashMap<>();
    private static final class Plan {
        final BlockPos goal;
        final ArrayDeque<BlockPos> visited=new ArrayDeque<>();
        BlockPos stage;
        long stageTick, nextSurvey;
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
    private static void mark(Plan plan,BlockPos cell){
        if(cell==null)return;
        plan.visited.remove(cell);
        plan.visited.addLast(cell.immutable());
        while(plan.visited.size()>RETAIN)plan.visited.removeFirst();
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
            if(horizontal<=12&&BuilderMultiLegTravel.stand(level,worker,destination))
                return true;
        }else if(high&&horizontal<=16){
            if(BuilderMultiLegTravel.stand(level,worker,destination))return true;
        }else if(!high&&!lower){
            if(horizontal<=12){
                WorkerNavigation.routeToExact(level,worker,destination,1.0);
                return true;
            }
            if(WorkerNavigation.routeTo(level,worker,destination,1.0))
                return true;
        }
        // When far from an elevated goal, walk on the CURRENT level
        // towards a legitimate approach point. A full vertical route is
        // not required until the entity reaches the Dome boundary.
        if(now>=p.nextSurvey){
            p.nextSurvey=now+75;
            Set<BlockPos> connected=WorkerNavigation.groundReachableFrom(level,worker,here);
            BlockPos candidate=chooseStage(here,destination,connected,
                    new ArrayList<>(p.visited));
            if(candidate!=null){
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
