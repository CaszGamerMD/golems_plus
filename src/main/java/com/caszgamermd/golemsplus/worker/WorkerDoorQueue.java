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
