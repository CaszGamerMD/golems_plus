package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** A return is two separate trips: escape the placed build, then seek the depot.
 * A selected physical exit owns the route until reached or demonstrably blocked. */
final class BuilderEscapeBox {
    private BuilderEscapeBox(){}
    record Bounds(int minX,int minY,int minZ,int maxX,int maxY,int maxZ){
        static Bounds from(List<BuilderConstructionPlan.Step> steps){
            if(steps.isEmpty())return null;
            int x=Integer.MAX_VALUE,y=x,z=x,X=Integer.MIN_VALUE,Y=X,Z=X;
            for(var p:steps){
                x=Math.min(x,p.x());y=Math.min(y,p.y());z=Math.min(z,p.z());
                X=Math.max(X,p.x());Y=Math.max(Y,p.y());Z=Math.max(Z,p.z());
                var companion=p.companion();
                if(companion!=null){
                    x=Math.min(x,companion.x());y=Math.min(y,companion.y());z=Math.min(z,companion.z());
                    X=Math.max(X,companion.x());Y=Math.max(Y,companion.y());Z=Math.max(Z,companion.z());
                }
            }
            return new Bounds(x-1,y-1,z-1,X+1,Y+1,Z+1);
        }
        boolean outside(BlockPos p){
            return p.getX()<=minX||p.getX()>=maxX||p.getY()<=minY
                    ||p.getY()>=maxY||p.getZ()<=minZ||p.getZ()>=maxZ;
        }
        // Loaded, supported shell tiles; limit survey around the worker
        // rather than scanning an arbitrarily huge schematic in one tick.
        List<BlockPos> candidates(ServerLevel level,BuilderGolem worker){
            BlockPos p=worker.blockPosition();
            int x0=Math.max(minX,p.getX()-48),x1=Math.min(maxX,p.getX()+48);
            int z0=Math.max(minZ,p.getZ()-48),z1=Math.min(maxZ,p.getZ()+48);
            int y0=Math.max(minY,p.getY()-12),y1=Math.min(maxY,p.getY()+12);
            Set<BlockPos> cells=new HashSet<>();
            for(int y=y0;y<=y1;y++){
                for(int x=x0;x<=x1;x++){
                    cells.add(new BlockPos(x,y,minZ));cells.add(new BlockPos(x,y,maxZ));
                }
                for(int z=z0;z<=z1;z++){
                    cells.add(new BlockPos(minX,y,z));cells.add(new BlockPos(maxX,y,z));
                }
            }
            for(int x=x0;x<=x1;x++)for(int z=z0;z<=z1;z++){
                cells.add(new BlockPos(x,minY,z));cells.add(new BlockPos(x,maxY,z));
            }
            var result=new ArrayList<BlockPos>();
            for(BlockPos at:cells)
                if(level.hasChunkAt(at)&&WorkerNavigation.canStandOn(level,worker,at))
                    result.add(at);
            result.sort(Comparator.comparingDouble((BlockPos at)->distance(worker,at))
                    .thenComparingInt(BlockPos::getX).thenComparingInt(BlockPos::getY).thenComparingInt(BlockPos::getZ));
            return result;
        }
        boolean sideOutside(BlockPos p){
            return p.getX()<=minX||p.getX()>=maxX||p.getZ()<=minZ||p.getZ()>=maxZ;
        }
        List<BlockPos> sideAir(BlockPos p){
            var result=new ArrayList<BlockPos>();
            int y=p.getY();
            for(int d=-48;d<=48;d++){
                int z=p.getZ()+d,x=p.getX()+d;
                if(z>=minZ&&z<=maxZ){result.add(new BlockPos(minX,y,z));result.add(new BlockPos(maxX,y,z));}
                if(x>=minX&&x<=maxX){result.add(new BlockPos(x,y,minZ));result.add(new BlockPos(x,y,maxZ));}
            }
            return result;
        }
        List<BlockPos> localAirExits(BlockPos p){
            int x=p.getX(),y=p.getY(),z=p.getZ();
            return List.of(new BlockPos(minX,y,z),new BlockPos(maxX,y,z),
                    new BlockPos(x,minY,z),new BlockPos(x,maxY,z),
                    new BlockPos(x,y,minZ),new BlockPos(x,y,maxZ));
        }
    }
    private record Column(BlockPos launch,BlockPos air,BlockPos ground){}
    private static final class Trip {
        final Bounds box;
        final BlockPos depot;
        BlockPos waypoint,mobilityTarget;
        Column column;
        int columnStage;
        long columnRetry;
        Vec3 lastPosition;
        double best=Double.POSITIVE_INFINITY;
        long lastMove,lastProgress,nextSearch;
        boolean escaped;
        int batch;
        final Set<BlockPos> rejected=new HashSet<>();
        Trip(Bounds box,BlockPos depot){this.box=box;this.depot=depot.immutable();}
    }
    private static final Map<BuilderGolem,Trip> trips=new WeakHashMap<>();
    static void clear(BuilderGolem worker){trips.remove(worker);}
    static boolean escaping(BuilderGolem worker){
        Trip t=trips.get(worker);return t!=null&&!t.escaped;
    }
    static BlockPos waypoint(BuilderGolem worker){
        Trip t=trips.get(worker);return t==null?null:t.waypoint;
    }
    private static double distance(BuilderGolem worker,BlockPos p){
        return worker.distanceToSqr(p.getX()+.5,p.getY(),p.getZ()+.5);
    }
    private static void reject(ServerLevel level,BuilderGolem worker,Trip t,String reason){
        if(t.waypoint!=null){
            t.rejected.add(t.waypoint);
            BuilderTrace.decision(level,worker,"ESCAPE_BOX_REPLAN",t.waypoint,reason);
        }
        t.waypoint=null;t.batch=0;t.nextSearch=level.getGameTime()+10;
        WorkerNavigation.stop(worker);
    }
    private static Column selectColumn(ServerLevel level,BuilderGolem worker,Trip t){
        var air=t.box.sideAir(worker.blockPosition());
        air.sort(Comparator.comparingDouble(p->distance(worker,p)));
        var launches=new LinkedHashMap<BlockPos,Column>();
        // Prefer nearby side air at the current level. A real landing below
        // it is mandatory, even when the entire shell is unsupported.
        for(BlockPos p:air){
            if(t.rejected.contains(p))continue;
            BlockPos ground=BuilderFlight.escapeColumnGround(level,worker,p);
            if(ground==null||!t.box.sideOutside(ground))continue;
            for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++){
                if(dx*dx+dz*dz>4)continue;
                BlockPos launch=p.offset(dx,0,dz);
                if(t.box.sideOutside(launch)||!WorkerNavigation.canStandOn(level,worker,launch)
                        ||!BuilderFlight.canReachEscapeAir(level,worker,launch,p))continue;
                launches.putIfAbsent(launch,new Column(launch,p,ground));
            }
            if(launches.size()>=96)break;
        }
        if(launches.isEmpty())return null;
        var goals=new ArrayList<>(launches.keySet());
        goals.sort(Comparator.comparingDouble(p->distance(worker,p)));
        BlockPos chosen=WorkerNavigation.selectExecutableStand(level,worker,goals);
        if(chosen==null){
            for(BlockPos p:goals.subList(0,Math.min(8,goals.size())))
                if(WorkerNavigation.routeToExact(level,worker,p,1.0)){chosen=p;break;}
        }
        return chosen==null?null:launches.get(chosen);
    }
    private static boolean followColumn(ServerLevel level,BuilderGolem worker,Trip t){
        Column c=t.column;long now=level.getGameTime();
        if(BuilderFlight.moving(worker))return true;
        if(t.columnStage==0){
            double d=distance(worker,c.launch());
            if(d<t.best-.04){t.best=d;t.lastProgress=now;}
            if(now-t.lastProgress>400||!WorkerNavigation.canStandOn(level,worker,c.launch())){
                t.rejected.add(c.air());t.column=null;reject(level,worker,t,"side launch blocked or stalled");return true;
            }
            if(d<.04&&worker.onGround()){
                if(BuilderFlight.request(level,worker,c.air())){
                    t.columnStage=1;t.columnRetry=now+40;return true;
                }
            }else WorkerNavigation.routeToExact(level,worker,c.launch(),1.0);
            return true;
        }
        if(t.columnStage==1&&BuilderFlight.atEscapeAir(worker,c.air())){
            // Preserve the takeoff envelope and descend in this very same
            // column, not onto the roof from which we just escaped.
            if(BuilderFlight.requestSupportedLanding(level,worker,c.ground())){
                t.columnStage=2;t.columnRetry=now+40;return true;
            }
        }
        if(t.columnStage==2&&worker.onGround()&&!BuilderFlight.active(worker)
                &&worker.blockPosition().getX()==c.ground().getX()
                &&worker.blockPosition().getZ()==c.ground().getZ()
                &&Math.abs(worker.getY()-WorkerNavigation.surveyFloorHeight(level,c.ground()))<.2){
            t.column=null;t.escaped=true;
            BuilderTrace.decision(level,worker,"ESCAPE_BOX_REACHED",c.ground(),"outside side column; real ground reached");
            BuilderReturnService.resetApproach(worker);return false;
        }
        if(now>=t.columnRetry){
            // A failed powered leg may still be hovering. Keep the hat on
            // and recover safely rather than resetting gravity in air.
            t.rejected.add(c.air());t.column=null;reject(level,worker,t,"powered side leg did not reach verified pose");
        }
        return true;
    }
    /** True while the escape stage owns motion; false permits depot routing. */
    static boolean tick(ServerLevel level,BuilderGolem worker,Bounds box,BlockPos depot){
        return tick(level,worker,box,depot,null);
    }
    static boolean tick(ServerLevel level,BuilderGolem worker,Bounds box,BlockPos depot,
            com.caszgamermd.golemsplus.world.WorkerStationBlockEntity table){
        if(box==null||depot==null)return false;
        Trip t=trips.get(worker);
        if(t==null||!box.equals(t.box)||!depot.equals(t.depot)){
            t=new Trip(box,depot);t.lastProgress=level.getGameTime();trips.put(worker,t);
        }
        if(t.escaped)return false;
        if(t.column!=null)return followColumn(level,worker,t);
        // At negative world heights an exact powered endpoint can floor
        // one cell short. Use the physical pose, not a rounded block Y.
        if(box.outside(worker.blockPosition())||(t.waypoint!=null
                &&box.outside(t.waypoint)&&BuilderFlight.atEscapeAir(worker,t.waypoint))){
            t.escaped=true;
            BuilderTrace.decision(level,worker,"ESCAPE_BOX_REACHED",worker.blockPosition(),"physical exit; now route to OnSite");
            BuilderReturnService.resetApproach(worker);
            if(!BuilderFlight.active(worker)&&!BuilderGrappleAccess.active(worker)
                    &&!BuilderScaffolding.isControllingMovement(worker))WorkerNavigation.stop(worker);
            return false;
        }
        if(BuilderFlight.moving(worker)||BuilderGrappleAccess.active(worker)
                ||BuilderScaffolding.isControllingMovement(worker))return true;
        long now=level.getGameTime();
        // No competing ground route while a propeller is still hovering.
        if(BuilderFlight.active(worker)){
            BuilderFlight.land(level,worker,true,t.rejected);
            return true;
        }
        if(t.mobilityTarget!=null){
            if(BuilderFlight.hasDescentLaunch(level,worker,t.mobilityTarget)
                    &&BuilderFlight.approachDescentLaunch(level,worker,t.mobilityTarget,t.rejected))return true;
            t.mobilityTarget=null;
        }
        if(t.waypoint!=null){
            double d=distance(worker,t.waypoint);
            if(t.lastPosition==null||worker.position().distanceToSqr(t.lastPosition)>.09){
                t.lastPosition=worker.position();t.lastMove=now;
            }
            if(d<t.best-.04){t.best=d;t.lastProgress=now;}
            // Permit genuine detours, but not an endless moving cycle.
            if(!WorkerNavigation.canStandOn(level,worker,t.waypoint)
                    ||now-t.lastMove>=160||now-t.lastProgress>=400){
                reject(level,worker,t,"unsafe exit or no physical progress");
                return true;
            }
            // WorkerNavigation preserves its active path for the same goal.
            // Its own local obstruction recovery may replan to that goal.
            if(!WorkerNavigation.routeToExact(level,worker,t.waypoint,1.0)
                    &&now-t.lastMove>=80)
                reject(level,worker,t,"exact route no longer executable");
            return true;
        }
        if(now<t.nextSearch)return true;
        t.nextSearch=now+30;
        // First try a complete walk -> side air -> ground route. A walkable
        // shell tile is not necessary for a high build.
        if(BuilderFlight.enabled(worker)&&!BuilderFlight.active(worker)&&worker.onGround()){
            Column c=selectColumn(level,worker,t);
            if(c!=null){
                t.column=c;t.columnStage=0;t.waypoint=c.air();
                t.best=distance(worker,c.launch());t.lastProgress=now;
                BuilderTrace.decision(level,worker,"ESCAPE_BOX_COMMIT",c.air(),"side launch="+c.launch()+" verified ground="+c.ground());
                return followColumn(level,worker,t);
            }
        }
        var candidates=box.candidates(level,worker);
        candidates.removeAll(t.rejected);
        // Batch the nearest standable exits. A bounded multi-goal A* chooses
        // an executable exit rather than guessing from Euclidean distance.
        int start=Math.min(t.batch,candidates.size());
        int end=Math.min(start+64,candidates.size());
        BlockPos chosen=start<end?WorkerNavigation.selectExecutableStand(level,worker,
                candidates.subList(start,end)):null;
        if(chosen==null){
            // A large multi-goal search can exhaust its budget before a
            // simple nearby exact route is tried. Probe the nearest few
            // individually, never change a route that has been accepted.
            for(int i=start;i<Math.min(end,start+4);i++){
                BlockPos at=candidates.get(i);
                if(WorkerNavigation.routeToExact(level,worker,at,1.0)){
                    chosen=at;break;
                }
            }
        }
        if(chosen!=null){
            t.waypoint=chosen;t.lastPosition=worker.position();
            t.best=distance(worker,chosen);t.lastMove=t.lastProgress=now;t.batch=0;
            BuilderTrace.decision(level,worker,"ESCAPE_BOX_COMMIT",chosen,"retain physical exit until reached or blocked");
            WorkerNavigation.routeToExact(level,worker,chosen,1.0);
            return true;
        }
        if(end<candidates.size()){t.batch=end;return true;}
        t.batch=0;
        // A floating structure may have no supported shell point. A local
        // powered flight to clear shell air is still a real physical exit.
        // Do not enlarge the existing two-block takeoff envelope.
        if(BuilderFlight.enabled(worker)){
            var air=new ArrayList<>(box.localAirExits(worker.blockPosition()));
            air.sort(Comparator.comparingInt((BlockPos p)->box.sideOutside(p)?0:p.getY()<=box.minY()?1:2)
                    .thenComparingDouble(p->distance(worker,p)));
            for(BlockPos at:air)
                if(!t.rejected.contains(at)&&BuilderFlight.request(level,worker,at)){
                    t.waypoint=at;t.lastPosition=worker.position();
                    t.best=distance(worker,at);t.lastMove=t.lastProgress=now;
                    BuilderTrace.decision(level,worker,"ESCAPE_BOX_COMMIT",at,"collision-checked powered shell exit");
                    return true;
                }
            BlockPos lower=candidates.isEmpty()?air.get(0):candidates.get(0);
            t.rejected.add(worker.blockPosition().immutable());
            if(BuilderFlight.approachDescentLaunch(level,worker,lower,t.rejected)){
                t.mobilityTarget=lower;return true;
            }
            if(BuilderFlight.escapePocket(level,worker,lower,t.rejected)
                    ||BuilderFlight.land(level,worker,true,t.rejected))return true;
        }else if(table!=null){
            // Preserve the existing paid scaffold/grapple tools for
            // workers without a propeller, but aim at the shell, not home.
            var air=new ArrayList<>(box.localAirExits(worker.blockPosition()));
            air.sort(Comparator.comparingDouble(p->distance(worker,p)));
            BlockPos exit=candidates.isEmpty()?air.get(0):candidates.get(0);
            if(BuilderScaffolding.descendForTravel(level,worker,table)
                    ||BuilderGrappleAccess.rappelHome(level,worker,table,exit)
                    ||BuilderGrappleAccess.rappelToward(level,worker,table,exit)
                    ||BuilderGrappleAccess.escapeRailPocket(level,worker,exit))return true;
        }
        BuilderTrace.decision(level,worker,"ESCAPE_BOX_WAIT",worker.blockPosition(),"no executable exit; retry shell survey");
        // Geometry can change as others build. Revisit rejected exits only
        // after all alternatives have been explored and the crew has waited.
        if(!t.rejected.isEmpty()&&now-t.lastProgress>=1200){
            t.rejected.clear();t.lastProgress=now;
        }
        return true;
    }
}
