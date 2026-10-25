package com.caszgamermd.golemsplus.worker;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.UUID;

/** One FIFO for one physical door. Server tick time, not wall time.
 * Records expire if workers despawn or stop checking in.
 */
public final class WorkerDoorQueue {
    private static final long STALE_TICKS=100;
    private final LinkedHashMap<UUID,Long> users=new LinkedHashMap<>();

    public boolean claim(UUID worker,long gameTime){
        expire(gameTime);
        users.put(worker,gameTime); // update does not move FIFO position
        return !users.isEmpty()&&users.keySet().iterator().next().equals(worker);
    }
    /** A worker ALREADY occupying the threshold must be permitted to
     * leave before queued arrivals. Never rotate two physical occupants
     * back and forth; the traffic controller calls this only if the
     * current head is outside the doorway.
     */
    public void prioritizeOccupant(UUID worker,long gameTime){
        expire(gameTime);
        var order=new LinkedHashMap<UUID,Long>();
        order.put(worker,gameTime);
        users.forEach((id,last)->{
            if(!id.equals(worker))order.put(id,last);
        });
        users.clear();
        users.putAll(order);
    }
    public UUID head(){
        return users.isEmpty()?null:users.keySet().iterator().next();
    }
    public void release(UUID worker){users.remove(worker);}
    public boolean owns(UUID worker){
        return !users.isEmpty()&&users.keySet().iterator().next().equals(worker);
    }
    public int position(UUID worker){
        int rank=0;
        for(UUID id:users.keySet()){
            rank++;
            if(id.equals(worker))return rank;
        }
        return 0;
    }
    public int count(){return users.size();}
    public void expire(long gameTime){
        Iterator<java.util.Map.Entry<UUID,Long>> iterator=users.entrySet().iterator();
        while(iterator.hasNext())
            if(gameTime-iterator.next().getValue()>STALE_TICKS)iterator.remove();
    }
}

