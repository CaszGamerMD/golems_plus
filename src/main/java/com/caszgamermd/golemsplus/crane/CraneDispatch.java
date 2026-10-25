package com.caszgamermd.golemsplus.crane;

import java.util.*;

/** Bounded, stable requests. Repeated worker calls never jump the queue. */
public final class CraneDispatch {
    public record Request(UUID worker,int fromY,int toY,long arrived) {
        public int direction(){return Integer.compare(toY,fromY);}
    }
    private final LinkedHashMap<UUID,Request> waiting=new LinkedHashMap<>();
    public void request(UUID worker,int fromY,int toY,long now){
        waiting.putIfAbsent(worker,new Request(worker,fromY,toY,now));
    }
    public List<UUID> batch(){
        if(waiting.isEmpty())return List.of();
        Request head=waiting.values().iterator().next();
        return waiting.values().stream().filter(r->r.direction()==head.direction())
                .limit(4).map(Request::worker).toList();
    }
    public void remove(UUID worker){waiting.remove(worker);}
    public void clear(){waiting.clear();}
    public int size(){return waiting.size();}
    public static int reach(int upgrades){return 30+30*Math.max(0,Math.min(2,upgrades));}
}
