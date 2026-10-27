package com.caszgamermd.golemsplus.builder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Test-only contextual strategy selection backed by actual episode outcomes.
 * Geometry/collision rules remain authoritative. No routes or coordinates are
 * stored here, and a plan's theoretical length never counts as a success.
 */
public final class NavigationStrategyMemory {
    public static final String FORMAT="golems-context-strategies-v1";
    public enum Strategy { DIRECT, SMOOTH, DOOR }
    public record Observation(String id,long sequence,String context,
            Strategy strategy,int arrivedTicks,int deadlineTicks,
            int stationaryTicks,int collisions,double distanceMeters) {
        public Observation {
            if(id==null||id.isBlank()||sequence<0||context==null
                    ||context.isBlank()||context.length()>200||strategy==null
                    ||deadlineTicks<=0||arrivedTicks < -1
                    ||arrivedTicks>deadlineTicks||stationaryTicks<0
                    ||stationaryTicks>deadlineTicks||collisions<0
                    ||!Double.isFinite(distanceMeters)||distanceMeters<0)
                throw new IllegalArgumentException("Invalid physical strategy evidence");
        }
        double reward(){
            if(arrivedTicks<0)return 0;
            // Completed physical trips dominate; faster, smoother trips
            // receive more credit. No route-relative waypoint percentage.
            return Math.max(0,.6+.4*(1.0-arrivedTicks/(double)deadlineTicks)
                    -.1*stationaryTicks/deadlineTicks
                    -Math.min(.1,collisions*.005));
        }
    }
    public record Summary(int trials,int finished,double meanReward,
                          double meanArrivalTicks){}
    public record Decision(Strategy strategy,String reason,int evidenceTrials){}
    private record Key(String context,Strategy strategy){}
    private final Map<String,Observation> evidence=new LinkedHashMap<>();
    private final Map<Key,Integer> pending=new HashMap<>();

    public void observe(Observation observation){
        Observation old=evidence.get(observation.id());
        if(old!=null){
            if(!old.equals(observation))throw new IllegalStateException(
                    "Conflicting physical episode "+observation.id());
            return; // Restore/merge/retry cannot teach the same trip twice.
        }
        evidence.put(observation.id(),observation);
        Key key=new Key(observation.context(),observation.strategy());
        pending.computeIfPresent(key,(k,n)->n<=1?null:n-1);
    }
    public List<Observation> observations(){
        return evidence.values().stream().sorted(Comparator
                .comparingLong(Observation::sequence)
                .thenComparing(Observation::id)).toList();
    }
    public Summary summary(String context,Strategy strategy){
        List<Observation> rows=observations().stream()
                .filter(o->o.context().equals(context)&&o.strategy()==strategy)
                .toList();
        // Recent experience can overturn an old preference after traffic
        // or controllers change. Full provenance remains in the archive.
        rows=rows.subList(Math.max(0,rows.size()-32),rows.size());
        int finished=0;double reward=0,ticks=0;
        for(Observation o:rows){
            reward+=o.reward();
            if(o.arrivedTicks()>=0){finished++;ticks+=o.arrivedTicks();}
        }
        return new Summary(rows.size(),finished,
                rows.isEmpty()?0:reward/rows.size(),
                finished==0?-1:ticks/finished);
    }
    private int visits(String context,Strategy strategy){
        return (int)evidence.values().stream().filter(o->
                o.context().equals(context)&&o.strategy()==strategy).count()
                +pending.getOrDefault(new Key(context,strategy),0);
    }
    public Decision choose(String context,List<Strategy> available,
                           boolean validationOnly){
        if(available.isEmpty()||!available.contains(Strategy.DIRECT))
            throw new IllegalArgumentException("A legal baseline route is required");
        Summary baseline=summary(context,Strategy.DIRECT);
        Strategy chosen=Strategy.DIRECT;
        double best=baseline.meanReward();
        double bestPaceGain=0;
        String reason="baseline";
        for(Strategy strategy:available){
            if(strategy==Strategy.DIRECT)continue;
            Summary candidate=summary(context,strategy);
            // A single lucky arrival cannot establish a rule. Compare
            // repeated REAL arrivals in the SAME abstract context.
            // Previously a route was rejected whenever its success rate
            // was even slightly lower than direct, even when completing
            // workers arrived ~20 ticks sooner. That kept the already
            // observed slab->door shortcut in exploration mode forever.
            if(baseline.trials()<3||candidate.trials()<3
                    ||baseline.finished()<2||candidate.finished()<2)
                continue;
            double baselineRate=baseline.finished()/(double)baseline.trials();
            double candidateRate=candidate.finished()/(double)candidate.trials();
            double paceGain=baseline.meanArrivalTicks()-candidate.meanArrivalTicks();
            boolean reliable=candidateRate>=baselineRate
                    &&candidate.meanReward()>best+.02;
            // Accept a demonstrably faster route IF its completion rate
            // stays within 12 percentage points of direct. Require an
            // 8-tick improvement and repeated successful physical trips.
            // This is a learned route STRATEGY, never saved coordinates.
            boolean faster=paceGain>=8.0&&candidateRate>=baselineRate-.12;
            if(reliable||(faster
                    &&(chosen==Strategy.DIRECT||paceGain>bestPaceGain))){
                chosen=strategy;
                best=candidate.meanReward();
                bestPaceGain=paceGain;
                reason=faster?"faster-physical-arrivals":"repeated-outcomes";
            }
        }
        int decisions=0;
        for(Strategy strategy:Strategy.values())decisions+=visits(context,strategy);
        // Four uses of the retained preference, one explicit exploration.
        // New strategies gain evidence without rewriting the proven policy
        // after every trial. Validation never explores.
        if(!validationOnly&&available.size()>1&&decisions%5==4){
            chosen=available.stream().min(Comparator
                    .comparingInt((Strategy s)->visits(context,s))
                    .thenComparingInt(Strategy::ordinal)).orElse(Strategy.DIRECT);
            reason="exploration";
        }
        int trials=summary(context,chosen).trials();
        pending.merge(new Key(context,chosen),1,Integer::sum);
        return new Decision(chosen,reason,trials);
    }
}
