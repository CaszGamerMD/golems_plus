package com.caszgamermd.golemsplus.builder;

import java.util.Collection;

/**
 * Deterministic server-thread-only shared Builder queue.
 * Completions may arrive out of order; the checkpoint never skips holes.
 */
public final class BuilderCrewSchedule {
    public record Progress(int cursor,long finishedAhead) {}
    private BuilderCrewSchedule(){}

    public static int next(int cursor,int total,long finishedAhead,
            Collection<Integer> inFlight){
        int until=Math.min(total,cursor+32);
        for(int i=cursor;i<until;i++)
            if(((finishedAhead >>> (i-cursor))&1L)==0&&!inFlight.contains(i))
                return i;
        return -1;
    }
    public static Progress finish(int cursor,int total,long finishedAhead,int index){
        if(index<cursor||index>=total||index-cursor>=32
                ||((finishedAhead >>> (index-cursor))&1L)!=0)
            throw new IllegalArgumentException("Not a pending Builder step");
        long bits=finishedAhead | (1L<<(index-cursor));
        while((bits&1L)!=0 && cursor<total){bits>>>=1;cursor++;}
        return new Progress(cursor,bits);
    }
}
