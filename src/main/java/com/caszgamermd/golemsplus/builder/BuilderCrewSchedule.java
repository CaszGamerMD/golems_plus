package com.caszgamermd.golemsplus.builder;

import java.util.Collection;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

/**
 * Deterministic server-thread-only shared Builder queue.
 * Completions may arrive out of order; the checkpoint never skips holes.
 */
public final class BuilderCrewSchedule {
    public record Progress(int cursor,long finishedAhead) {}
    /**
     * Keep small layers moving with one Builder, then add one Builder for
     * every three placements after the first five:
     * 0-5 => 1, 6-8 => 2, 9-11 => 3, and so on.
     */
    public static int workersForLayer(int blocks,int crewSize){
        if(crewSize<=0)return 0;
        int requested=blocks<=5?1:2+(blocks-6)/3;
        return Math.min(crewSize,requested);
    }
    /** A selected worker must see the full layer if its own partition has
     * no cells there, even when every crew member is selected.
     */
    static boolean sharesLayerCells(int staffedCount,int crewSize,
            boolean ownsLayerCells){
        return staffedCount<crewSize||!ownsLayerCells;
    }
    /** Prefer workers whose original X/Z zones own the most of this
     * layer. A stable roster-index tie breaker prevents golem churn.
     * When a layer needs multiple Builders, they may share even one
     * occupied original zone; the selected crew sees the whole layer. */
    public static List<UUID> staffForLayer(List<UUID> roster,
            int physicalBlocks,int[] ownership){
        if(roster.size()!=ownership.length)
            throw new IllegalArgumentException("Layer ownership/crew mismatch");
        int limit=workersForLayer(physicalBlocks,roster.size());
        var ranked=new ArrayList<Integer>();
        for(int i=0;i<roster.size();i++)ranked.add(i);
        ranked.sort((a,b)->{
            int count=Integer.compare(ownership[b],ownership[a]);
            return count!=0?count:Integer.compare(a,b);
        });
        var chosen=new ArrayList<UUID>();
        for(int i=0;i<limit;i++)chosen.add(roster.get(ranked.get(i)));
        return List.copyOf(chosen);
    }
    /** BuilderGolem staggers each worker's work callback by entity ID.
     * Do not add a second crew-wide turn gate here: with eight workers it
     * made each Builder wait up to 40 ticks (2 seconds) just to select its
     * next frontier cell, despite the callbacks already being distributed
     * across server ticks. */
    public static boolean frontierTurn(long gameTick,int rosterIndex,int crewSize){
        return crewSize>0&&rosterIndex>=0&&rosterIndex<crewSize;
    }
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
