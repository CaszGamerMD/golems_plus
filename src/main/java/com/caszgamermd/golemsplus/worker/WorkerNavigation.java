package com.caszgamermd.golemsplus.worker;

import java.util.Comparator;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * One shared, server-side ground-navigation controller for every worker.
 *
 * - Do not rebuild a path on every job tick.
 * - Open an actual wooden door before pushing against its collision shape.
 * - Close only doors the worker opened, after everyone clears the doorway.
 * - Route to standing tiles NEXT TO solid work blocks, not their centers.
 * - Detect lack of forward progress and retry another approach tile.
 * - Yield briefly to an approaching worker instead of body-pushing.
 *
 * Transient tracking does not belong in saves; only the actual job and cargo
 * are persisted. A fresh worker can resume navigating safely after reload.
 */
public final class WorkerNavigation {
    private static final Map<PathfinderMob,Progress> states=new WeakHashMap<>();
    private static final int STALL_TICKS=55;
    private static final int ROUTE_RETRY=18;
    private WorkerNavigation(){}

    private static final class Progress {
        BlockPos requested,approach,openedDoor;
        Vec3 previous;
        int stalled,attempts,wait,doorAge,nextRoute;
        boolean narrowDoorway;
    }
    private static Progress state(PathfinderMob mob){
        return states.computeIfAbsent(mob,m->new Progress());
    }
    /** Open wooden doors leave only 13/16 of a block of width. A .85
     * worker is physically too wide, so while it is *crossing a doorway*
     * its real collision box briefly narrows to .70. Away from a doorway
     * it resumes its normal .85 width. The visible model never changes.
     */
    public static boolean inDoorway(PathfinderMob mob){
        Progress progress=states.get(mob);
        return progress!=null&&progress.narrowDoorway;
    }
    private static void updateDoorClearance(ServerLevel level,PathfinderMob mob,Progress progress){
        BlockPos pos=mob.blockPosition();
        boolean near=false;
        if(progress.requested!=null||progress.openedDoor!=null)
            for(int dx=-2;dx<=2&&!near;dx++)
                for(int dy=-1;dy<=1&&!near;dy++)
                    for(int dz=-2;dz<=2&&!near;dz++){
                        BlockPos probe=pos.offset(dx,dy,dz);
                        if(!handDoor(level,probe))continue;
                        double distance=mob.distanceToSqr(probe.getX()+.5,
                                probe.getY()+.4,probe.getZ()+.5);
                        if(distance<4.0)near=true;
                    }
        if(progress.narrowDoorway!=near){
            progress.narrowDoorway=near;
            if(!near)mob.refreshDimensions();
        }
        if(near){
            // LivingEntity.getDimensions(Pose) is FINAL in 26.2.
            // Use a transient collision AABB for the door passage,
            // while leaving the entity's registered .85 dimensions and
            // visible rendering completely unchanged. The normal box
            // returns immediately once the doorway has been cleared.
            double half=.35;
            mob.setBoundingBox(new AABB(mob.getX()-half,mob.getY(),mob.getZ()-half,
                    mob.getX()+half,mob.getY()+mob.getBbHeight(),mob.getZ()+half));
        }
    }

    private static boolean handDoor(ServerLevel world,BlockPos pos){
        if(!world.hasChunkAt(pos))return false;
        BlockState block=world.getBlockState(pos);
        return block.getBlock() instanceof DoorBlock door
                &&door.type().canOpenByHand();
    }

    /** Recheck collision using the golem's real .85-block bounding width. */
    private static boolean standable(ServerLevel world,PathfinderMob worker,BlockPos p){
        if(!world.hasChunkAt(p)||!world.hasChunkAt(p.below())
                ||!world.hasChunkAt(p.above()))return false;
        BlockState at=world.getBlockState(p);
        BlockState head=world.getBlockState(p.above());
        if(!at.getCollisionShape(world,p).isEmpty()&&!handDoor(world,p))
            return false;
        if(!head.getCollisionShape(world,p.above()).isEmpty()
                &&!handDoor(world,p.above()))return false;
        return !world.getBlockState(p.below()).getCollisionShape(world,p.below()).isEmpty();
    }
    private static boolean neighborOccupied(ServerLevel world,PathfinderMob mover,BlockPos p){
        return !world.getEntitiesOfClass(PathfinderMob.class,
                new AABB(p).inflate(.10),
                other->other!=mover&&other.isAlive()).isEmpty();
    }
    private static BlockPos pathGoal(ServerLevel level,PathfinderMob mob,BlockPos requested,
                                     int avoid){
        // When asked to navigate to the inside of a solid workstation, hive,
        // crate or wall, choose a reachable position within interaction range.
        boolean solid=!standable(level,mob,requested);
        if(!solid&&avoid==0&&!neighborOccupied(level,mob,requested))
            return requested;
        BlockPos best=null;
        double score=Double.POSITIVE_INFINITY;
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)
            for(int dy=-1;dy<=1;dy++){
                BlockPos candidate=requested.offset(dx,dy,dz);
                if(candidate.equals(requested)&&solid)continue;
                if(!standable(level,mob,candidate)
                        ||neighborOccupied(level,mob,candidate))continue;
                // Prefer a neighboring tile on the same level; avoid
                // repeatedly choosing the same obstructed approach.
                double metric=candidate.distSqr(requested)*2.5
                        +candidate.distSqr(mob.blockPosition())*.11
                        +Math.abs(dy)*2;
                metric+=(Math.floorMod(candidate.hashCode(),4)==avoid-1)?9:0;
                if(metric<score){
                    Path path=mob.getNavigation().createPath(candidate,0);
                    if(path!=null&&path.canReach()){
                        best=candidate;score=metric;
                    }
                }
            }
        return best;
    }

    /**
     * Call on each worker job evaluation (~10-12 ticks). Returns whether a
     * safe route is in progress; "arrived" remains the caller's own decision.
     */
    public static boolean routeTo(ServerLevel level,PathfinderMob mob,BlockPos target,
                                  double speed){
        if(target==null||!level.hasChunkAt(target))return false;
        Progress p=state(mob);
        if(mob.getNavigation() instanceof GroundPathNavigation ground)
            ground.setCanOpenDoors(true);
        boolean changed=!target.equals(p.requested);
        if(changed){
            p.requested=target.immutable();p.approach=null;
            p.attempts=0;p.nextRoute=0;p.stalled=0;
        }
        updateDoorClearance(level,mob,p);
        // An obstructed door can make Minecraft reject the path outright.
        // Try opening the nearest door on the intended approach BEFORE
        // the first path calculation, not only after the golem hits it.
        if(p.openedDoor==null)openApproachDoor(level,mob,p);
        if(p.wait>0)return true;
        // Do not cancel a sound active route for every repeated request.
        if(!changed&&!mob.getNavigation().isDone()
                &&p.stalled<STALL_TICKS&&mob.tickCount<p.nextRoute)
            return true;
        if(!changed&&!mob.getNavigation().isDone()
                &&p.stalled<STALL_TICKS){
            p.nextRoute=mob.tickCount+35;
            return true;
        }
        // An unreachable direct goal must trigger a neighboring route,
        // even when vanilla reports "no path" immediately rather than
        // moving into a wall long enough to trigger stall detection.
        int variant=p.attempts>0||p.stalled>=STALL_TICKS
                ?(p.attempts++%4)+1:0;
        BlockPos destination=pathGoal(level,mob,target,variant);
        if(destination==null){
            if(p.stalled<STALL_TICKS){
                p.nextRoute=mob.tickCount+ROUTE_RETRY;
            }
            return false;
        }
        boolean started=mob.getNavigation().moveTo(
                destination.getX()+.5,destination.getY(),destination.getZ()+.5,speed);
        if(started){
            p.approach=destination;
            p.stalled=0;
            p.nextRoute=mob.tickCount+45;
        }else{
            p.nextRoute=mob.tickCount+ROUTE_RETRY;
            p.attempts++;
        }
        return started;
    }

    public static void stop(PathfinderMob mob){
        Progress p=state(mob);
        p.requested=null;p.approach=null;p.stalled=0;
        mob.getNavigation().stop();
        // Door clearance is reset during the next server navigation tick.
    }

    /** Call every server tick, including when idle, to operate and close doors. */
    public static void tick(ServerLevel level,PathfinderMob mob){
        Progress p=state(mob);
        if(mob.getNavigation() instanceof GroundPathNavigation nav)
            nav.setCanOpenDoors(true);
        if(mob.tickCount%5!=0)return;
        updateDoorClearance(level,mob,p);
        if(p.previous==null)p.previous=mob.position();
        if(mob.position().distanceToSqr(p.previous)>.065){
            p.stalled=0;p.previous=mob.position();
        }else if(p.requested!=null&&!mob.getNavigation().isDone()){
            p.stalled+=5;
        }
        if(p.wait>0){p.wait=Math.max(0,p.wait-5);return;}
        if(p.requested!=null&&!mob.getNavigation().isDone()
                &&yieldToWorker(level,mob,p)){
            p.wait=15+(Math.floorMod(mob.getId(),4)*5);
            mob.getNavigation().stop();
            // Keep requested target so the next evaluation resumes.
            p.nextRoute=0;p.stalled=STALL_TICKS;
            return;
        }
        // Open door based on actual next-step or desired route direction,
        // rather than a straight line toward the target villager.
        if(p.openedDoor==null&&p.requested!=null)
            openApproachDoor(level,mob,p);
        closeClearedDoor(level,mob,p);
    }

    private static boolean yieldToWorker(ServerLevel world,PathfinderMob mob,Progress p){
        if(p.requested==null)return false;
        Vec3 forward=new Vec3(p.requested.getX()+.5-mob.getX(),0,
                p.requested.getZ()+.5-mob.getZ());
        if(forward.lengthSqr()<.3)return false;
        forward=forward.normalize();
        for(PathfinderMob other:world.getEntitiesOfClass(PathfinderMob.class,
                mob.getBoundingBox().inflate(1.25),
                ent->ent!=mob&&ent.isAlive()
                    &&(ent instanceof com.caszgamermd.golemsplus.trader.TraderGolem
                        ||ent instanceof com.caszgamermd.golemsplus.builder.BuilderGolem
                        ||ent instanceof com.caszgamermd.golemsplus.beekeeper.BeekeeperGolem
                        ||ent instanceof com.caszgamermd.golemsplus.cooking.ChefGolem
                        ||ent instanceof com.caszgamermd.golemsplus.fishing.FisherGolem))){
            Vec3 delta=new Vec3(other.getX()-mob.getX(),0,other.getZ()-mob.getZ());
            double forwardDist=forward.dot(delta);
            if(forwardDist<.15||forwardDist>1.5||delta.lengthSqr()>2.0)
                continue;
            if(other.getNavigation().isDone())continue;
            // An asymmetric tie break ensures both workers don't yield.
            if(mob.getUUID().compareTo(other.getUUID())>0)
                return true;
        }
        return false;
    }
    private static void openApproachDoor(ServerLevel level,PathfinderMob mob,Progress p){
        Vec3 goal=p.approach==null?
                new Vec3(p.requested.getX()+.5-mob.getX(),0,
                        p.requested.getZ()+.5-mob.getZ()):
                new Vec3(p.approach.getX()+.5-mob.getX(),0,
                        p.approach.getZ()+.5-mob.getZ());
        if(goal.lengthSqr()<.09)return;
        Vec3 ahead=goal.normalize();
        BlockPos origin=mob.blockPosition();
        BlockPos doorPos=null;
        double closest=Double.MAX_VALUE;
        for(int dx=-3;dx<=3;dx++)for(int dy=-1;dy<=1;dy++)
            for(int dz=-3;dz<=3;dz++){
                BlockPos probe=origin.offset(dx,dy,dz);
                if(!handDoor(level,probe))continue;
                BlockState state=level.getBlockState(probe);
                if(state.getValue(DoorBlock.OPEN))continue;
                Vec3 delta=new Vec3(probe.getX()+.5-mob.getX(),0,
                        probe.getZ()+.5-mob.getZ());
                double along=delta.dot(ahead);
                double side=Math.abs(delta.x*ahead.z-delta.z*ahead.x);
                if(along<-.3||along>3.5||side>1.20)continue;
                double score=delta.lengthSqr()+side*2;
                if(score<closest){doorPos=probe;closest=score;}
            }
        if(doorPos!=null){
            BlockState state=level.getBlockState(doorPos);
            ((DoorBlock)state.getBlock()).setOpen(mob,level,state,doorPos,true);
            p.openedDoor=doorPos.immutable();p.doorAge=0;
            updateDoorClearance(level,mob,p);
            // Path planned while the door was closed may be impossible.
            // Re-evaluate only once after opening it.
            p.stalled=0;p.nextRoute=0;
            mob.getNavigation().stop();
        }
    }
    private static void closeClearedDoor(ServerLevel level,PathfinderMob mob,Progress p){
        BlockPos door=p.openedDoor;
        if(door==null)return;
        p.doorAge+=5;
        if(p.doorAge<15||mob.distanceToSqr(door.getX()+.5,
                door.getY()+.5,door.getZ()+.5)<6.3)return;
        // Don't slam a door into any mob or player; leave it open until
        // everyone clears instead of trapping the next worker.
        if(!level.getEntitiesOfClass(Entity.class,new AABB(door).inflate(.75),
                ent->ent!=mob&&ent.isAlive()).isEmpty())return;
        if(level.hasChunkAt(door)&&handDoor(level,door)){
            BlockState state=level.getBlockState(door);
            if(state.getValue(DoorBlock.OPEN))
                ((DoorBlock)state.getBlock()).setOpen(mob,level,state,door,false);
        }
        p.openedDoor=null;
    }
}
