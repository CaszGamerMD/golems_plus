package com.caszgamermd.golemsplus.builder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/** Mutates SCORING LOGIC, never learned coordinates or a specific route.
 *
 * Every recorded Trial must originate from a real Builder entity. Course
 * completion and a successful return to safe ground outrank speed, and
 * unsafe results NEVER beat safe results merely for being faster.
 *
 * Nine strategies per generation: retain each of the three best
 * strategies unchanged, and generate two deterministic mutated children
 * from each. Evolution only changes preferences; Minecraft collision,
 * blueprint, distance, and safe-descent guards remain immutable.
 */
public final class BuilderScaffoldPolicyTrainer {
    public static final int POPULATION=9;
    private BuilderScaffoldPolicyTrainer(){}

    public record Trial(BuilderScaffoldBridgePlanner.Weights weights,
                        boolean reachedGoal,boolean returnedSafely,
                        int placedBlocks,int invalidAttempts,
                        int collisions,int ticks){
        public Trial {
            if(weights==null||placedBlocks<0||invalidAttempts<0
                    ||collisions<0||ticks<0)
                throw new IllegalArgumentException("Invalid physical scaffold trial");
        }
        public boolean passed(){
            return reachedGoal&&returnedSafely&&collisions==0;
        }
    }

    public static List<BuilderScaffoldBridgePlanner.Weights> nextGeneration(
            List<Trial> measured,long seed){
        if(measured==null||measured.size()!=POPULATION
                ||measured.stream().anyMatch(x->x==null))
            throw new IllegalArgumentException("Require exactly nine measured trials");
        List<Trial> ranked=new ArrayList<>(measured);
        ranked.sort(Comparator.comparing(Trial::passed).reversed()
                .thenComparingInt(Trial::collisions)
                .thenComparing(Comparator.comparing(Trial::reachedGoal).reversed())
                .thenComparing(Comparator.comparing(Trial::returnedSafely).reversed())
                .thenComparingInt(Trial::ticks)
                .thenComparingInt(Trial::invalidAttempts)
                .thenComparingInt(Trial::placedBlocks));
        Random rng=new Random(seed);
        List<BuilderScaffoldBridgePlanner.Weights> result=new ArrayList<>(POPULATION);
        for(int i=0;i<3;i++){
            var elite=ranked.get(i).weights();
            result.add(elite);
            result.add(mutate(elite,rng));
            result.add(mutate(elite,rng));
        }
        return List.copyOf(result);
    }

    private static BuilderScaffoldBridgePlanner.Weights mutate(
            BuilderScaffoldBridgePlanner.Weights w,Random random){
        // Modest bounded perturbations preserve good rules. Never allow
        // invalid/negative NaN values to enter the physical planner.
        return new BuilderScaffoldBridgePlanner.Weights(
                bounded(w.itemCost()*(1+(random.nextDouble()-.5)*.35),.5,15),
                bounded(w.turnCost()+(random.nextDouble()-.5)*1.0,0,8),
                bounded(w.endGapCost()*(1+(random.nextDouble()-.5)*.4),.01,6));
    }
    private static double bounded(double value,double min,double max){
        return Math.max(min,Math.min(max,value));
    }
}
