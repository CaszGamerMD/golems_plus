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
    /** Target roughly four physical placements per active Builder.
     * This keeps a crew of up to eight productive on broad layers without
     * parking most of the crew until a layer contains seventy blocks.
     * Empty/sparse zones are still excluded by the ownership ranking.
     */
    public static int workersForLayer(int blocks,int crewSize){
        if(blocks<=0||crewSize<=0)return 0;
        return Math.min(crewSize,1+(blocks-1)/4);
    }
    /** Prefer workers whose original X/Z zones own the most of this
     * layer. A stable roster-index tie breaker prevents golem churn.
     * Restricted crews then share all layer cells rather than leaving
     * parked teammates' original partitions inaccessible. */
    public static List<UUID> staffForLayer(List<UUID> roster,
            int physicalBlocks,int[] ownership){
        if(roster.size()!=ownership.length)
            throw new IllegalArgumentException("Layer ownership/crew mismatch");
        int occupiedZones=0;
        for(int count:ownership)if(count>0)occupiedZones++;
        int limit=Math.min(workersForLayer(physicalBlocks,roster.size()),
                occupiedZones);
        if(limit==0&&physicalBlocks==0&&!roster.isEmpty())limit=1;
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
    /** Exactly one crew member may start a costly full frontier search in
     * each five-tick work window. Turns rotate; an inaccessible task cannot
     * monopolize planning time at the next layer transition. */
    public static boolean frontierTurn(long gameTick,int rosterIndex,int crewSize){
        return crewSize>0&&rosterIndex>=0&&rosterIndex<crewSize
                &&Math.floorMod(Math.floorDiv(gameTick,5L),crewSize)==rosterIndex;
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
