package com.caszgamermd.golemsplus.builder;

import java.util.HashMap;
import java.util.Map;

/** Session-local right-of-way leases, never persisted as blocked floor. */
public final class NavigationRightOfWay {
    private static final class Lease {
        int owner,lastSeen,lastProgress;double bestRemaining;
        Lease(int owner,int tick,double remaining){
            this.owner=owner;lastSeen=lastProgress=tick;bestRemaining=remaining;
        }
    }
    private final Map<Long,Lease> leases=new HashMap<>();
    public int owner(int a,double aRemaining,int b,double bRemaining,int tick){
        long key=((long)Math.min(a,b)<<32)|(Math.max(a,b)&0xffffffffL);
        Lease lease=leases.get(key);
        if(lease==null||tick-lease.lastSeen>6){
            int first=aRemaining<bRemaining-.01?a:bRemaining<aRemaining-.01?b:Math.min(a,b);
            lease=new Lease(first,tick,first==a?aRemaining:bRemaining);
            leases.put(key,lease);
        }
        double remaining=lease.owner==a?aRemaining:bRemaining;
        if(remaining<lease.bestRemaining-.05){
            lease.bestRemaining=remaining;lease.lastProgress=tick;
        }
        // Give a stalled owner a bounded opportunity, then permit its
        // peer to clear. Near-equal priorities cannot flip every tick.
        if(tick-lease.lastProgress>=20){
            lease.owner=lease.owner==a?b:a;
            lease.bestRemaining=lease.owner==a?aRemaining:bRemaining;
            lease.lastProgress=tick;
        }
        lease.lastSeen=tick;
        return lease.owner;
    }
}
