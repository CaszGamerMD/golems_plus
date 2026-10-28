package com.caszgamermd.golemsplus.builder;

import java.util.Map;
import java.util.WeakHashMap;

/** Small server-thread metrics per ACTUAL Builder entity.
 * Captures cause and effect for scaffolding course generations; not a
 * saved map or a fake navigation path. Export snapshots through tests
 * or Builder debug status before the worker is unloaded.
 */
public final class BuilderScaffoldTrialStats {
    public record Snapshot(int plans,int rejected,int scaffoldPlaced,
                           int retreatSteps,int landed,int turns,
                           int longestSpan){}
    private static final class Counter {
        int plans,rejected,scaffoldPlaced,retreatSteps,landed,turns,longestSpan;
        Snapshot snapshot(){
            return new Snapshot(plans,rejected,scaffoldPlaced,
                    retreatSteps,landed,turns,longestSpan);
        }
    }
    private static final Map<BuilderGolem,Counter> counters=new WeakHashMap<>();
    private BuilderScaffoldTrialStats(){}
    private static Counter of(BuilderGolem worker){
        return counters.computeIfAbsent(worker,ignored->new Counter());
    }
    static void selected(BuilderGolem worker,
                         BuilderScaffoldBridgePlanner.Route route){
        var counter=of(worker);
        counter.plans++;
        counter.longestSpan=Math.max(counter.longestSpan,route.cells().size());
        int previousDx=0,previousDz=0;
        var previous=route.anchor();
        boolean first=true;
        for(var cell:route.cells()){
            int dx=cell.getX()-previous.getX();
            int dz=cell.getZ()-previous.getZ();
            if(!first&&(dx!=previousDx||dz!=previousDz))counter.turns++;
            previousDx=dx;previousDz=dz;first=false;previous=cell;
        }
    }
    static void rejected(BuilderGolem worker){of(worker).rejected++;}
    static void placed(BuilderGolem worker){of(worker).scaffoldPlaced++;}
    static void retreat(BuilderGolem worker){of(worker).retreatSteps++;}
    static void landed(BuilderGolem worker){of(worker).landed++;}
    public static Snapshot snapshot(BuilderGolem worker){
        var counter=counters.get(worker);
        return counter==null?new Snapshot(0,0,0,0,0,0,0):counter.snapshot();
    }
    public static void reset(BuilderGolem worker){counters.remove(worker);}
}
