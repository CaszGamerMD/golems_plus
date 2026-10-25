package com.caszgamermd.golemsplus.worker;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Localized door traffic, shared by ALL worker navigation controllers.
 * The flag is an entry for a specific worker UUID in the FIFO for this
 * specific door in this level. No global one-worker-at-a-time lock.
 */
public final class WorkerDoorTraffic {
    private WorkerDoorTraffic(){}
    private record Entry(double fromX,double fromZ,long started){}
    private static final class Lane {
        final WorkerDoorQueue queue=new WorkerDoorQueue();
        final Map<UUID,Entry> entry=new HashMap<>();
        final Map<UUID,Long> cooldown=new HashMap<>();
    }
    private static final Map<ServerLevel,Map<BlockPos,Lane>> doors=
            new WeakHashMap<>();

    public static BlockPos canonical(ServerLevel level,BlockPos at){
        if(!level.hasChunkAt(at))return at;
        var block=level.getBlockState(at);
        if(block.getBlock() instanceof DoorBlock
                &&block.hasProperty(DoorBlock.HALF)
                &&block.getValue(DoorBlock.HALF)==DoubleBlockHalf.UPPER)
            return at.below();
        return at;
    }
    private static Lane lane(ServerLevel level,BlockPos position){
        return doors.computeIfAbsent(level,ignored->new HashMap<>())
                .computeIfAbsent(canonical(level,position).immutable(),
                        ignored->new Lane());
    }
    /** A worker checks in at most five blocks from the door. Ownership
     * persists through the opening, doorway and exit; subsequent users
     * cannot rush through an occupied threshold.
     */
    public static boolean request(ServerLevel level,BlockPos position,
                                  PathfinderMob worker){
        BlockPos door=canonical(level,position);
        Lane lane=lane(level,door);
        UUID id=worker.getUUID();
        lane.entry.computeIfAbsent(id,ignored->{
            var state=level.getBlockState(door);
            var facing=state.getBlock() instanceof net.minecraft.world.level.block.FenceGateBlock
                    ?state.getValue(net.minecraft.world.level.block.FenceGateBlock.FACING)
                    :state.getValue(DoorBlock.FACING);
            double from=(worker.getX()-door.getX()-.5)*facing.getStepX()
                    +(worker.getZ()-door.getZ()-.5)*facing.getStepZ();
            double sign=from<0?-1:1;
            return new Entry(sign*facing.getStepX(),sign*facing.getStepZ(),level.getGameTime());
        });
        lane.cooldown.entrySet().removeIf(row->row.getValue()<=level.getGameTime());
        if(lane.cooldown.getOrDefault(id,0L)>level.getGameTime())
            return false;
        boolean first=lane.queue.claim(id,level.getGameTime());
        // The FIFO grants right of way, not collision immunity. The head
        // must keep moving while waiting workers clear the threshold;
        // parking the head too creates a circular wait with an occupant.
        lane.entry.keySet().removeIf(key->lane.queue.position(key)==0);
        return first;
    }
    public static boolean owns(ServerLevel level,BlockPos position,PathfinderMob worker){
        Map<BlockPos,Lane> map=doors.get(level);
        if(map==null)return false;
        Lane lane=map.get(canonical(level,position));
        if(lane==null)return false;
        lane.queue.expire(level.getGameTime());
        return lane.queue.owns(worker.getUUID());
    }
    public static int position(ServerLevel level,BlockPos door,PathfinderMob worker){
        Map<BlockPos,Lane> map=doors.get(level);
        if(map==null)return 0;
        Lane lane=map.get(canonical(level,door));
        return lane==null?0:lane.queue.position(worker.getUUID());
    }
    public static int count(ServerLevel level,BlockPos door){
        Map<BlockPos,Lane> map=doors.get(level);
        if(map==null)return 0;
        Lane lane=map.get(canonical(level,door));
        return lane==null?0:lane.queue.count();
    }
    /** Keep the door until a worker has genuinely passed to the opposite
     * side (>=1.5 blocks clear) or abandoned a route. A near-side retreat
     * does not count as a completed crossing.
     */
    public static boolean crossed(ServerLevel level,BlockPos position,
                                  PathfinderMob worker){
        Map<BlockPos,Lane> map=doors.get(level);
        if(map==null)return false;
        BlockPos door=canonical(level,position);
        Lane lane=map.get(door);
        if(lane==null)return false;
        Entry entry=lane.entry.get(worker.getUUID());
        if(entry==null)return false;
        double dx=worker.getX()-(door.getX()+.5);
        double dz=worker.getZ()-(door.getZ()+.5);
        double initial=Math.hypot(entry.fromX,entry.fromZ);
        if(initial<.35)return false;
        double projected=(dx*entry.fromX+dz*entry.fromZ)/initial;
        if(projected< -1.5){
            release(level,door,worker);
            return true;
        }
        return false;
    }
    /** Deprioritize a stationary head without allowing it to cut back
     * into the same FIFO on its next navigation tick.
     */
    public static void yieldStalled(ServerLevel level,BlockPos position,
                                    PathfinderMob worker){
        Map<BlockPos,Lane> map=doors.get(level);
        if(map==null)return;
        Lane lane=map.get(canonical(level,position));
        if(lane==null)return;
        UUID id=worker.getUUID();
        lane.queue.release(id);
        lane.entry.remove(id);
        lane.cooldown.put(id,level.getGameTime()+60);
    }
    public static void release(ServerLevel level,BlockPos position,PathfinderMob worker){
        Map<BlockPos,Lane> map=doors.get(level);
        if(map==null)return;
        BlockPos door=canonical(level,position);
        Lane lane=map.get(door);
        if(lane==null)return;
        lane.queue.release(worker.getUUID());
        lane.entry.remove(worker.getUUID());
        if(lane.queue.count()==0&&lane.cooldown.isEmpty())map.remove(door);
    }
}

