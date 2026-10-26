package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.worker.WorkerPhase;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * EXPERIMENTAL CREATIVE FLIGHT. Builders deliberately do not require towers
 * or scaffold bridges for construction on this testing branch.
 *
 * No instant block placement, teleportation, x-ray movement, remote material
 * pickup or collision suppression. Flight only changes physical movement.
 * Each Builder still stages real material at OnSite and pays for placements.
 */
public final class BuilderCreativeFlight {
    public static final boolean ENABLED=true;
    private static final Map<BuilderGolem,Flight> flights=new WeakHashMap<>();
    private static final class Flight {
        BlockPos goal;
        Vec3 destination;
        boolean depot;
        double cruiseY=Double.NaN;
        Vec3 last;
        long lastCloser;
        double best;
        long escapingUntil;
        List<Vec3> airRoute=List.of();
        int routeIndex;
        Flight(BlockPos goal,Vec3 destination,boolean depot,
                BuilderGolem worker,ServerLevel level){
            this.goal=goal.immutable();
            this.destination=destination;
            this.depot=depot;
            last=worker.position();
            lastCloser=level.getGameTime();
            best=worker.distanceToSqr(destination);
        }
    }
    private BuilderCreativeFlight(){}

    public static boolean moving(BuilderGolem worker){
        return flights.containsKey(worker);
    }

    public static void stop(BuilderGolem worker){
        flights.remove(worker);
        worker.setNoGravity(false);
        worker.setDeltaMovement(0,Math.min(0,worker.getDeltaMovement().y),0);
    }

    /** Each real entity performs bounded 3D movement with Minecraft collision.
     * A blocked straight route rises into clear air before crossing a roof.
     */
    public static boolean tickMotion(ServerLevel level,BuilderGolem worker){
        Flight flight=flights.get(worker);
        if(flight==null)return false;
        if(!level.hasChunkAt(worker.home())
                ||!(level.getBlockEntity(worker.home()) instanceof WorkerStationBlockEntity table)
                ||table.phase()==WorkerPhase.PAUSED
                ||table.phase()==WorkerPhase.COMPLETE
                ||table.activeOrder()==null){
            stop(worker);
            return false;
        }
        worker.setNoGravity(true);
        worker.setShiftKeyDown(false);
        worker.setJumping(false);
        worker.getNavigation().stop();

        Vec3 finalPosition=flight.destination;
        double distance=worker.distanceToSqr(finalPosition);
        // Stalled flight inside a closed Dome must find a real air
        // corridor to ANY legal placement tile, not keep pressing its
        // head against the same roof or wall.
        if(!flight.depot&&flight.escapingUntil<=level.getGameTime()
                &&flight.airRoute.isEmpty()
                &&level.getGameTime()-flight.lastCloser>55){
            var alternative=BuilderCreativeAirRoute.plan(level,worker,flight.goal);
            if(!alternative.isEmpty()){
                flight.airRoute=alternative;
                flight.routeIndex=0;
                flight.cruiseY=Double.NaN;
                flight.lastCloser=level.getGameTime();
            }
        }
        if(!flight.airRoute.isEmpty()){
            // Progress through physically adjacent 3D waypoints; don't
            // resume the old straight-line flight destination mid-route.
            while(flight.routeIndex<flight.airRoute.size()
                    &&worker.distanceToSqr(
                            flight.airRoute.get(flight.routeIndex))<.20){
                flight.routeIndex++;
                flight.lastCloser=level.getGameTime();
            }
            if(flight.routeIndex>=flight.airRoute.size()){
                flight.destination=flight.airRoute.get(
                        flight.airRoute.size()-1);
                flight.airRoute=List.of();
                finalPosition=flight.destination;
            }
        }
        distance=worker.distanceToSqr(finalPosition);
        if(distance<.08&&flight.airRoute.isEmpty()){
            worker.setDeltaMovement(0,0,0);
            flight.cruiseY=Double.NaN;
            return true;
        }
        if(distance<flight.best-.10){
            flight.best=distance;
            flight.lastCloser=level.getGameTime();
        }
        // "Creative fly" is a recovery option as well as the primary
        // movement type. Fly over obstructions instead of repeatedly
        // walking into the same wall.
        if(level.getGameTime()-flight.lastCloser>28
                &&Double.isNaN(flight.cruiseY)){
            flight.cruiseY=Math.max(worker.getY(),finalPosition.y)+3.5;
            flight.lastCloser=level.getGameTime();
        }
        Vec3 waypoint=flight.airRoute.isEmpty()?finalPosition
                :flight.airRoute.get(flight.routeIndex);
        if(flight.airRoute.isEmpty()&&!Double.isNaN(flight.cruiseY)){
            double horizontal=Math.hypot(finalPosition.x-worker.getX(),
                    finalPosition.z-worker.getZ());
            if(horizontal>.9&&worker.getY()<flight.cruiseY-.35)
                waypoint=new Vec3(worker.getX(),flight.cruiseY,worker.getZ());
            else if(horizontal>.9)
                waypoint=new Vec3(finalPosition.x,flight.cruiseY,finalPosition.z);
            else flight.cruiseY=Double.NaN; // descend at work platform
        }
        double dx=waypoint.x-worker.getX();
        double dy=waypoint.y-worker.getY();
        double dz=waypoint.z-worker.getZ();
        double vx=clamp(dx*.28,.24);
        double vy=clamp(dy*.26,.22);
        double vz=clamp(dz*.28,.24);
        AABB current=worker.getBoundingBox();
        if(!level.noCollision(worker,current.move(vx,vy,vz))){
            // Creative flight NEVER phases through a completed wall or
            // another Builder. A blocked leg goes vertically first.
            if(level.noCollision(worker,current.move(0,.22,0))){
                flight.cruiseY=Math.max(
                        Double.isNaN(flight.cruiseY)?worker.getY()+1.0
                                :flight.cruiseY+1.0,
                        finalPosition.y+2.5);
                vx=0;vy=.22;vz=0;
            }else {
                // Seek a clear side to go around an overhead obstacle.
                boolean found=false;
                for(int[] d:new int[][]{{1,0},{0,1},{-1,0},{0,-1}}){
                    if(level.noCollision(worker,current.move(d[0]*.20,0,d[1]*.20))){
                        vx=d[0]*.20;vy=0;vz=d[1]*.20;
                        found=true;break;
                    }
                }
                if(!found){vx=0;vy=0;vz=0;}
            }
        }
        worker.setDeltaMovement(vx,vy,vz);
        flight.last=worker.position();
        return true;
    }
    private static double clamp(double value,double max){
        return Math.max(-max,Math.min(max,value));
    }

    private static void navigate(ServerLevel level,BuilderGolem worker,
            BlockPos goal,Vec3 destination,boolean depot){
        Flight old=flights.get(worker);
        if(old!=null&&old.goal.equals(goal)&&old.depot==depot
                &&old.destination.distanceToSqr(destination)<.01)return;
        flights.put(worker,new Flight(goal,destination,depot,worker,level));
        worker.getNavigation().stop();
    }

    /** Fly to the real OnSite inventory, no remote material transfers. */
    public static boolean reachDepot(ServerLevel level,BuilderGolem worker,
                                     BlockPos depot){
        if(depot==null)return false;
        if(worker.distanceToSqr(depot.getX()+.5,depot.getY()+.5,
                depot.getZ()+.5)<=11.5){
            // Cancel the old WORK destination, not just its velocity;
            // otherwise the flight controller restarts it next entity tick.
            Flight previous=flights.get(worker);
            if(previous==null||!previous.depot||!previous.goal.equals(depot)
                    ||previous.destination.distanceToSqr(worker.position())>.3)
                navigate(level,worker,depot,worker.position(),true);
            worker.setDeltaMovement(0,0,0);
            return true;
        }
        Vec3 target=new Vec3(depot.getX()+.5,depot.getY()+1.3,depot.getZ()+1.5);
        navigate(level,worker,depot,target,true);
        return false;
    }

    /** Fly beside each block to preserve the original real placement
     * range and leave that block's collision volume empty.
     */
    public static boolean reachWork(ServerLevel level,BuilderGolem worker,
                                    BlockPos target){
        AABB placement=new AABB(target);
        boolean clear= !worker.getBoundingBox().intersects(placement);
        if(clear&&worker.distanceToSqr(target.getX()+.5,target.getY()+.5,
                target.getZ()+.5)<13.8){
            // Hold in the real normal placement radius; NEVER resume a
            // previous depot/placement destination after placing.
            Flight previous=flights.get(worker);
            if(previous==null||previous.depot||!previous.goal.equals(target)
                    ||previous.destination.distanceToSqr(worker.position())>.3)
                navigate(level,worker,target,worker.position(),false);
            worker.setDeltaMovement(0,0,0);
            return true;
        }
        Flight old=flights.get(worker);
        if(old!=null&&old.escapingUntil>level.getGameTime())return false;
        if(old==null||!old.goal.equals(target)||old.depot
                ||old.airRoute.isEmpty()
                        &&old.destination.distanceToSqr(worker.position())<.16
                        &&old.destination.distanceToSqr(
                                target.getX()+.5,target.getY()+.5,
                                target.getZ()+.5)<11.0){
            Vec3 destination=chooseWorkPosition(level,worker,target);
            if(destination==null){
                // Try to clear the target's own body volume, even when
                // its ideal work-radius candidates have become blocked.
                requestClearance(level,worker,target);
                return false;
            }
            navigate(level,worker,target,destination,false);
        }
        return false;
    }

    private static Vec3 chooseWorkPosition(ServerLevel level,BuilderGolem worker,
                                            BlockPos target){
        Vec3 best=null;
        double bestScore=Double.POSITIVE_INFINITY;
        // Candidates deliberately remain within the normal 14 squared
        // reach and outside the block to be placed. Flight is only a
        // substitute for the access path, NEVER extended placement range.
        for(int h:new int[]{1,2,0,3,-1}){
            for(Direction dir:Direction.Plane.HORIZONTAL){
                for(int offset:new int[]{0,1,2}){
                    if(offset==0&&dir!=Direction.NORTH)continue;
                    BlockPos landing=target.relative(dir,offset).offset(0,h,0);
                    double x=landing.getX()+.5;
                    double y=landing.getY();
                    double z=landing.getZ()+.5;
                    double reach=(x-target.getX()-.5)*(x-target.getX()-.5)
                            +(y-target.getY())*(y-target.getY())
                            +(z-target.getZ()-.5)*(z-target.getZ()-.5);
                    if(reach>=13.8||!level.hasChunkAt(landing)
                            ||!level.hasChunkAt(landing.above()))continue;
                    AABB box=worker.getBoundingBox().move(
                            x-worker.getX(),y-worker.getY(),z-worker.getZ());
                    if(!level.noCollision(worker,box)
                            ||box.intersects(new AABB(target)))continue;
                    double distance=worker.distanceToSqr(x,y,z);
                    // Prefer a clear exterior approach over stepping into
                    // adjacent planned blocks that are already solid.
                    double score=distance+(offset==1?1.25:0);
                    if(score<bestScore){
                        bestScore=score;
                        best=new Vec3(x,y,z);
                    }
                }
            }
        }
        return best;
    }

    /** A Builder's collision body may overlap a future placement, even
     * after it has reached the 14-squared-block placing radius. Fly out
     * of the volume; never let a ground-only pathfinder steal this motion.
     */
    public static boolean requestClearance(ServerLevel level,
                                           BuilderGolem worker,BlockPos target){
        Flight old=flights.get(worker);
        if(old!=null&&old.escapingUntil>level.getGameTime())return true;
        Vec3 candidate=null;
        double score=Double.POSITIVE_INFINITY;
        AABB forbidden=new AABB(target).inflate(.15);
        // Prefer a short vertical escape from an obstructing footprint.
        for(double lift:new double[]{1.2,2.2,-1.2}){
            Vec3 at=worker.position().add(0,lift,0);
            BlockPos cell=BlockPos.containing(at.x,at.y,at.z);
            AABB dest=worker.getBoundingBox().move(0,lift,0);
            if(level.hasChunkAt(cell)&&!dest.intersects(forbidden)
                    &&level.noCollision(worker,dest)){
                candidate=at;score=.01;break;
            }
        }
        for(int y:new int[]{2,3,1,0,-1}){
            for(Direction side:Direction.Plane.HORIZONTAL){
                for(int radius:new int[]{2,3}){
                    double x=target.getX()+.5+side.getStepX()*radius;
                    double z=target.getZ()+.5+side.getStepZ()*radius;
                    double py=target.getY()+y;
                    AABB dest=worker.getBoundingBox().move(
                            x-worker.getX(),py-worker.getY(),z-worker.getZ());
                    BlockPos cell=BlockPos.containing(x,py,z);
                    if(!level.hasChunkAt(cell)||dest.intersects(forbidden)
                            ||!level.noCollision(worker,dest))continue;
                    double cost=worker.distanceToSqr(x,py,z);
                    if(cost<score){score=cost;candidate=new Vec3(x,py,z);}
                }
            }
        }
        if(candidate==null)return false;
        navigate(level,worker,target,candidate,false);
        Flight now=flights.get(worker);
        if(now!=null)now.escapingUntil=level.getGameTime()+45;
        return true;
    }

    public static String debug(BuilderGolem worker){
        Flight flight=flights.get(worker);
        if(flight==null)return "OFF";
        return (flight.depot?"DEPOT":"BUILD")+" destination="+flight.destination
                +" cruiseY="+flight.cruiseY+" bestSq="+flight.best
                +" escaping="+flight.escapingUntil
                +" airRoute="+flight.routeIndex+"/"+flight.airRoute.size();
    }
}
