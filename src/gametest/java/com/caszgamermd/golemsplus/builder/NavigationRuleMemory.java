package com.caszgamermd.golemsplus.builder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Matched physical experiments synthesize context -> recovery-action rules.
 * Contexts contain obstacle/traffic features, never coordinates or routes.
 * Stored trials are evidence; bindings are derived again after restore.
 */
public final class NavigationRuleMemory {
    public static final String FORMAT="golems-local-rules-v1";
    public enum Rule { BASELINE, STABLE_MERGE, YIELD_ASIDE, CENTER_STEP }
    public record Episode(String id,int slot,String context,Rule action,
            boolean cleared,int elapsedTicks,int stationaryTicks,int collisions) {
        public Episode {
            if(id==null||id.isBlank()||slot<0||slot>=10||context==null
                    ||context.isBlank()||context.length()>200||action==null
                    ||elapsedTicks<1||elapsedTicks>60||stationaryTicks<0
                    ||stationaryTicks>elapsedTicks||collisions<0)
                throw new IllegalArgumentException("Invalid local physical episode");
        }
        double reward(){return cleared?Math.max(0,.6+.4*(1-elapsedTicks/60.0)
                -.1*stationaryTicks/elapsedTicks-Math.min(.1,collisions*.005)):0;}
    }
    public record Trial(String id,long sequence,String block,int repeat,
            boolean challenger,String fingerprint,int finished,
            double meanArrivalTicks,List<Episode> episodes) {
        public Trial {
            if(id==null||id.isBlank()||sequence<0||block==null||block.isBlank()
                    ||repeat<0||repeat>2||fingerprint==null||fingerprint.isBlank()
                    ||finished<0||finished>10||!Double.isFinite(meanArrivalTicks)
                    ||meanArrivalTicks<0||meanArrivalTicks>260||episodes==null)
                throw new IllegalArgumentException("Invalid matched physical trial");
            episodes=List.copyOf(episodes);
        }
    }
    public record Binding(String context,Rule action,String evidenceBlock,
            int matchedPairs,double baselineReward,double challengerReward){}
    private final Map<String,Trial> trials=new LinkedHashMap<>();
    public void observe(Trial trial){
        Trial old=trials.putIfAbsent(trial.id(),trial);
        if(old!=null&&!old.equals(trial))
            throw new IllegalStateException("Conflicting physical trial "+trial.id());
    }
    public List<Trial> trials(){return trials.values().stream().sorted(Comparator
            .comparingLong(Trial::sequence).thenComparing(Trial::id)).toList();}
    public List<Trial> checkpointTrials(){
        // Keep proof for EVERY current rule, plus the two newest comparison
        // blocks (including an in-progress block). Full episodes also live
        // in immutable per-round replay files, so working memory stays bounded.
        java.util.Set<String> keep=new java.util.HashSet<>();
        for(Binding b:bindings().values())keep.add(b.evidenceBlock());
        List<String> ordered=new ArrayList<>();
        for(Trial t:trials()){
            ordered.remove(t.block());ordered.add(t.block());
        }
        keep.addAll(ordered.subList(Math.max(0,ordered.size()-2),ordered.size()));
        return trials().stream().filter(t->keep.contains(t.block())).toList();
    }
    private static int medianFinished(List<Trial> rows){
        return rows.stream().mapToInt(Trial::finished).sorted().toArray()[1];
    }
    public Map<String,Binding> bindings(){
        Map<String,List<Trial>> blocks=new LinkedHashMap<>();
        for(Trial t:trials())blocks.computeIfAbsent(t.block(),k->new ArrayList<>()).add(t);
        Map<String,Binding> result=new LinkedHashMap<>();
        for(var entry:blocks.entrySet()){
            List<Trial> control=entry.getValue().stream().filter(t->!t.challenger())
                    .sorted(Comparator.comparingInt(Trial::repeat)).toList();
            List<Trial> change=entry.getValue().stream().filter(Trial::challenger)
                    .sorted(Comparator.comparingInt(Trial::repeat)).toList();
            if(control.size()!=3||change.size()!=3)continue;
            String fingerprint=control.get(0).fingerprint();
            boolean matched=true;
            for(int i=0;i<3;i++)if(control.get(i).repeat()!=i||change.get(i).repeat()!=i
                    ||!control.get(i).fingerprint().equals(fingerprint)
                    ||!change.get(i).fingerprint().equals(fingerprint))matched=false;
            if(!matched||medianFinished(change)<medianFinished(control))continue;
            if(change.stream().mapToInt(Trial::finished).min().orElse(0)
                    <control.stream().mapToInt(Trial::finished).min().orElse(0)
                    ||change.stream().filter(t->t.finished()==10).count()
                        <control.stream().filter(t->t.finished()==10).count())continue;
            double baseTrip=control.stream().mapToDouble(Trial::meanArrivalTicks).average().orElse(260);
            double newTrip=change.stream().mapToDouble(Trial::meanArrivalTicks).average().orElse(260);
            if(newTrip>baseTrip+1)continue;
            // At most one episode per worker/context/repeat earns a vote.
            Map<String,List<double[]>> scores=new LinkedHashMap<>();
            Map<String,Rule> actions=new HashMap<>();
            Map<String,Integer> repeatMasks=new HashMap<>();
            for(int repeat=0;repeat<3;repeat++){
                Map<String,Episode> baseline=new HashMap<>();
                for(Episode e:control.get(repeat).episodes())
                    baseline.putIfAbsent(e.slot()+":"+e.context(),e);
                Map<String,Episode> unique=new LinkedHashMap<>();
                for(Episode e:change.get(repeat).episodes())
                    unique.putIfAbsent(e.slot()+":"+e.context(),e);
                for(var pair:unique.entrySet()){
                    Episode b=baseline.get(pair.getKey()),c=pair.getValue();
                    if(b==null||b.action()==c.action())continue;
                    Rule previous=actions.putIfAbsent(c.context(),c.action());
                    if(previous!=null&&previous!=c.action())continue;
                    scores.computeIfAbsent(c.context(),k->new ArrayList<>()).add(
                            new double[]{b.reward(),c.reward(),b.cleared()?1:0,c.cleared()?1:0});
                    repeatMasks.merge(c.context(),1<<repeat,(a,v)->a|v);
                }
            }
            for(var scored:scores.entrySet()){
                String context=scored.getKey();List<double[]> pairs=scored.getValue();
                if(pairs.size()<3||repeatMasks.get(context)!=7)continue;
                double br=0,cr=0,bc=0,cc=0;
                for(double[] p:pairs){br+=p[0];cr+=p[1];bc+=p[2];cc+=p[3];}
                br/=pairs.size();cr/=pairs.size();
                if(cc>=bc&&cc>=3&&cr>br+.03)
                    result.put(context,new Binding(context,actions.get(context),
                            entry.getKey(),pairs.size(),br,cr));
            }
        }
        return Map.copyOf(result);
    }
}
