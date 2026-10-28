package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * Plan B: physically climb/rappel a reusable hook. The anchor is an actual
 * sturdy block, the rope hangs in an adjacent AIR column, and its clearance
 * is checked for the Builder's full 0.65x1.62 collision box.
 *
 * Real movement is integrated by Minecraft's entity physics. No teleport,
 * world block creation, scaffold or alternative navigation controller.
 */
public final class BuilderGrappleAccess {
    public static final int MAX_REACH=20;
    public enum Result { MOVING, READY, NEED_HOOK, NO_SAFE_ANCHOR }
    private enum Phase { WALK_TO_LINE, CLIMB, STEP_ON_TOP, STEP_OFF, RAPPEL }
    private static final class Rope {
        final BlockPos anchor,column,landing,goal;
        final Direction side;
        final boolean down;
        final long started;
        Phase phase;
        int stalledTicks;
        double lastDistance=Double.POSITIVE_INFINITY;
        Rope(BlockPos anchor,Direction side,BlockPos landing,BlockPos goal,
                boolean down,long time) {
            this.anchor=anchor.immutable();
            this.side=side;
            this.column=anchor.relative(side);
            this.landing=landing.immutable();
            this.goal=goal.immutable();
            this.down=down;
            this.started=time;
            this.phase=down?Phase.STEP_OFF:Phase.WALK_TO_LINE;
        }
        double topY(){return anchor.getY()+1.0;}
    }
    private static final Map<BuilderGolem,Rope> active=new WeakHashMap<>();
    private BuilderGrappleAccess(){}

    public static boolean active(BuilderGolem worker){return active.containsKey(worker);}
    public static String debug(BuilderGolem worker){
        Rope r=active.get(worker);
        return r==null?"NONE":"GRAPPLE "+r.phase+" anchor="+r.anchor.toShortString()
                +" column="+r.column.toShortString()+" landing="
                +r.landing.toShortString()+" ticks="+r.stalledTicks;
    }
    public static void cancel(BuilderGolem worker){
        // Cancellation is legal only on solid ground. Do not suddenly drop
        // an entity suspended halfway up a real rope.
        if(!worker.onGround()&&active.containsKey(worker))return;
        active.remove(worker);
        worker.setNoGravity(false);
    }
    private static boolean solidTop(ServerLevel world,BlockPos p){
        return world.hasChunkAt(p)
                &&world.getBlockState(p).isFaceSturdy(world,p,Direction.UP);
    }
    private static boolean empty(ServerLevel world,BlockPos p){
        if(!world.hasChunkAt(p)||!world.getFluidState(p).isEmpty())return false;
        return world.getBlockState(p).getCollisionShape(world,p).isEmpty();
    }
    private static boolean headroom(ServerLevel world,BlockPos feet){
        return empty(world,feet)&&empty(world,feet.above());
    }
    /** The AIR rope column cannot pass through ANY wall, fluids, or roof.
     * Require enough room for the complete Builder body at each elevation.
     */
    private static boolean lineClear(ServerLevel world,BlockPos column,
            int lowestFeetY,int highestFeetY){
        if(highestFeetY-lowestFeetY>MAX_REACH)return false;
        for(int y=lowestFeetY;y<=highestFeetY+1;y++)
            if(!empty(world,column.atY(y)))return false;
        return true;
    }
    private static boolean hasHook(BuilderGolem worker){
        return worker.carriedCount(WorksiteContent.GRAPPLE_HOOK)>0;
    }
    private static List<BlockPos> solidAnchors(ServerLevel world,
            BlockPos goal){
        List<BlockPos> out=new ArrayList<>();
        for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++){
            BlockPos at=goal.offset(dx,-1,dz);
            if(dx*dx+dz*dz>16||!solidTop(world,at)
                    ||!headroom(world,at.above()))continue;
            out.add(at);
        }
        out.sort(Comparator.comparingInt(p->p.distManhattan(goal)));
        return out;
    }
    /** A hook always anchors to the solid surface BELOW the landing feet.
     * The line must be on an open cardinal face and must reach an actual
     * walkable entry cell underneath it.
     */
    private static Rope chooseAscent(ServerLevel level,BuilderGolem worker,
            BlockPos goal){
        int startY=worker.blockPosition().getY();
        if(goal.getY()-startY>MAX_REACH||goal.getY()-startY<2)return null;
        List<Rope> candidates=new ArrayList<>();
        for(BlockPos anchor:solidAnchors(level,goal)){
            for(Direction side:Direction.Plane.HORIZONTAL){
                BlockPos column=anchor.relative(side);
                BlockPos entry=column.atY(startY);
                if(!solidTop(level,entry.below())
                        ||!headroom(level,entry)
                        ||!lineClear(level,column,startY,goal.getY()))
                    continue;
                if(!WorkerNavigation.canReachExact(level,worker,entry))
                    continue;
                // The final shift across the edge must have enough room
                // for the full Builder body; the roof itself stays intact.
                candidates.add(new Rope(anchor,side,entry,goal,false,
                        level.getGameTime()));
            }
        }
        candidates.sort(Comparator.comparingDouble(r->
                r.landing.distSqr(worker.blockPosition())
                +r.anchor.distSqr(goal)*2.0));
        return candidates.isEmpty()?null:candidates.get(0);
    }
    private static Rope chooseDescent(ServerLevel level,BuilderGolem worker,
            BlockPos goal){
        BlockPos feet=worker.blockPosition();
        BlockPos anchor=feet.below();
        if(!solidTop(level,anchor)||!headroom(level,feet))return null;
        List<Rope> candidates=new ArrayList<>();
        for(Direction side:Direction.Plane.HORIZONTAL){
            BlockPos column=anchor.relative(side);
            // Select the FIRST safe supported landing on the rope, up to
            // twenty blocks below. A higher roof/floor prevents descending
            // through it, and its top is a valid landing itself.
            for(int delta=1;delta<=MAX_REACH;delta++){
                int landingY=feet.getY()-delta;
                BlockPos landing=column.atY(landingY);
                if(!empty(level,landing)||!empty(level,landing.above()))
                    break;
                if(!solidTop(level,landing.below()))continue;
                if(!lineClear(level,column,landingY,feet.getY()))break;
                candidates.add(new Rope(anchor,side,landing,goal,true,
                        level.getGameTime()));
                break;
            }
        }
        candidates.sort(Comparator.comparingDouble(r->
                r.landing.distSqr(goal)+.2*r.landing.distSqr(feet)));
        return candidates.isEmpty()?null:candidates.get(0);
    }
    /** Called when construction requires feet at the elevated block's Y.
     * The original trained A* still handles all ordinary terrain travel.
     */
    public static Result reach(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,BlockPos goal){
        Rope current=active.get(worker);
        if(current!=null)return Result.MOVING;
        if(worker.blockPosition().getY()>=goal.getY()-1)return Result.READY;
        if(!hasHook(worker)){
            return switch(BuilderSupplyInventory.loadGrapple(level,worker,table)){
                case READY->Result.MOVING;
                case MOVING->Result.MOVING;
                case MISSING->Result.NEED_HOOK;
            };
        }
        Rope route=chooseAscent(level,worker,goal);
        if(route==null)return Result.NO_SAFE_ANCHOR;
        active.put(worker,route);
        WorkerNavigation.routeToExact(level,worker,route.landing,1.0);
        return Result.MOVING;
    }
    /** Returns true while the hook owns descent or when a descent starts. */
    public static boolean rappelForTravel(ServerLevel level,
            BuilderGolem worker,WorkerStationBlockEntity table){
        BlockPos goal=table.builderUnloadingStorage();
        return goal!=null&&rappelToward(level,worker,table,goal);
    }
    public static boolean rappelToward(ServerLevel level,
            BuilderGolem worker,WorkerStationBlockEntity table,BlockPos goal){
        if(active.containsKey(worker))return true;
        if(worker.blockPosition().getY()-goal.getY()<2)return false;
        if(!hasHook(worker)){
            var load=BuilderSupplyInventory.loadGrapple(level,worker,table);
            return load!=BuilderSupplyInventory.LoadStatus.MISSING;
        }
        Rope route=chooseDescent(level,worker,goal);
        if(route==null)return false;
        active.put(worker,route);
        WorkerNavigation.stop(worker);
        return true;
    }
    private static void emitLine(ServerLevel level,Rope r,BuilderGolem mob){
        if(mob.tickCount%5!=0)return;
        // Lightweight rope preview at the real, fixed anchor line.
        // The particles trace the rope; they do not create collisions.
        for(double y=Math.min(mob.getY(),r.topY());y<=r.topY();y+=1.0)
            level.sendParticles(ParticleTypes.CRIT,
                    r.column.getX()+.5,y+.25,r.column.getZ()+.5,
                    1,0,0,0,0);
    }
    private static boolean steer(ServerLevel level,BuilderGolem mob,
            double x,double y,double z,double cap,boolean vertical){
        double dx=x-mob.getX(),dy=y-mob.getY(),dz=z-mob.getZ();
        double vx=Math.max(-cap,Math.min(cap,dx));
        double vy=vertical?Math.max(-cap,Math.min(cap,dy)):0;
        double vz=Math.max(-cap,Math.min(cap,dz));
        AABB next=mob.getBoundingBox().move(vx,vy,vz);
        if(!level.noCollision(mob,next)){
            mob.setDeltaMovement(0,0,0);
            return false;
        }
        mob.setDeltaMovement(vx,vy,vz);
        return true;
    }
    /** Called BEFORE the real mob's super.tick(). During a rope traverse,
     * no-gravity means weight is held by the taut rope, not free flight;
     * movement is still collision-checked every tick and never teleported.
     */
    public static boolean tickMotion(ServerLevel level,BuilderGolem mob){
        Rope r=active.get(mob);
        if(r==null)return false;
        if(r.phase==Phase.WALK_TO_LINE){
            BlockPos entry=r.landing;
            if(mob.blockPosition().getY()!=entry.getY()
                    ||Math.abs(mob.getX()-entry.getX()-.5)>.24
                    ||Math.abs(mob.getZ()-entry.getZ()-.5)>.24){
                if(mob.getNavigation().isDone())
                    WorkerNavigation.routeToExact(level,mob,entry,1.0);
                return false;
            }
            WorkerNavigation.stop(mob);
            r.phase=Phase.CLIMB;
        }
        // Anchor may be modified by a player while the hook is attached:
        // stay suspended instead of dropping the Builder to its death.
        if(!solidTop(level,r.anchor)){
            mob.setNoGravity(true);
            mob.setDeltaMovement(0,0,0);
            return true;
        }
        mob.setNoGravity(true);
        mob.setShiftKeyDown(false);
        emitLine(level,r,mob);
        double x=r.column.getX()+.5,z=r.column.getZ()+.5;
        boolean moved=true;
        switch(r.phase){
            case CLIMB -> {
                if(mob.getY()>=r.topY()-.06){
                    r.phase=Phase.STEP_ON_TOP;
                }else{
                    moved=steer(level,mob,x,r.topY(),z,.16,true);
                }
            }
            case STEP_ON_TOP -> {
                moved=steer(level,mob,
                        r.anchor.getX()+.5,r.topY(),
                        r.anchor.getZ()+.5,.15,true);
                if(Math.abs(mob.getX()-r.anchor.getX()-.5)<.18
                        &&Math.abs(mob.getZ()-r.anchor.getZ()-.5)<.18
                        &&Math.abs(mob.getY()-r.topY())<.18){
                    active.remove(mob);
                    mob.setNoGravity(false);
                    mob.setDeltaMovement(0,0,0);
                    System.out.println("[GRAPPLE] ASCENDED at "+r.anchor+
                            " feet="+mob.position());
                }
            }
            case STEP_OFF -> {
                moved=steer(level,mob,x,r.topY(),z,.14,true);
                if(Math.abs(mob.getX()-x)<.12
                        &&Math.abs(mob.getZ()-z)<.12)
                    r.phase=Phase.RAPPEL;
            }
            case RAPPEL -> {
                if(mob.onGround()
                        &&mob.getY()<=r.landing.getY()+.20){
                    active.remove(mob);
                    mob.setNoGravity(false);
                    mob.setDeltaMovement(0,0,0);
                    System.out.println("[GRAPPLE] RAPPELLED at "+r.landing+
                            " feet="+mob.position());
                }else{
                    moved=steer(level,mob,x,r.landing.getY(),z,.14,true);
                }
            }
            default -> {}
        }
        if(!moved)r.stalledTicks++;
        else r.stalledTicks=0;
        return true;
    }
}
