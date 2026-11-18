package com.caszgamermd.golemsplus.builder;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Experimental, online-trained contextual neural frontier selector.
 *
 * Hard safety checks stay entirely in BuilderConstructionPlanner. The neural
 * network can ONLY rerank feasible SAME-phase, SAME stock-priority and
 * SAME structural-tier tasks, within a bounded cost difference. It never
 * supplies a world pose, route, claim, material, or permission to place.
 *
 * Supervision is physical: the actual worker must claim a suggested task,
 * and the build service must report real world completion or a failed
 * handoff. A completed task is worth more if it takes fewer ticks.
 * Missing feedback is censored, never mislabeled as failure.
 *
 * Test-only opt-in on NNGolems; main gameplay is completely unchanged.
 */
final class BuilderNeuralFrontierPolicy {
    static final boolean ENABLED=Boolean.parseBoolean(System.getProperty(
            "golems.nn.frontierControl",
            System.getenv().getOrDefault("GOLEMS_NN_FRONTIER_CONTROL","false")));
    private static final Gson JSON=new Gson();
    private static final int INPUTS=12,HIDDEN=8,MAX_POOL=8;
    private static final double MAX_EXTRA_COST=64.0;
    private static final int SAVE_EVERY=16;
    private static final Map<UUID,Proposal> proposed=new HashMap<>();
    private static final Map<UUID,Pending> claimed=new HashMap<>();
    private static final Network MODEL=Network.initial();
    private static int decisions,changes,updates,successes,failures;
    private static long lastPersistTick=-1;

    record Candidate(BuilderConstructionPlanner.Choice choice,BlockPos target,
            int stockRank,int tier,double cost,double[] features){}
    private record Proposal(int index,BlockPos target,double[] features,long tick){}
    private record Pending(int index,BlockPos target,double[] features,long tick){}
    private record Decision(int schema,String worker,long tick,int considered,
            int baseline,int chosen,boolean changed,boolean exploring,
            double baselineScore,double chosenScore,int updates){}
    private record Feedback(int schema,String worker,int index,long ticks,
            double reward,String outcome,int successes,int failures,int updates){}
    record Saved(int schema,int updates,int successes,int failures,
            double[][] w,double[] b,double[] v,double c){}

    static final class Network {
        final double[][] w=new double[HIDDEN][INPUTS];
        final double[] b=new double[HIDDEN],v=new double[HIDDEN];
        double c;
        private Network(){}
        static Network initial(){
            Network net=new Network();
            Random random=new Random(26021177L);
            for(int j=0;j<HIDDEN;j++){
                v[j]=(random.nextDouble()-.5)*.3;
                for(int i=0;i<INPUTS;i++)net.w[j][i]=(random.nextDouble()-.5)*.3;
            }
            return net;
        }
        double score(double[] x){
            double z=c;
            for(int j=0;j<HIDDEN;j++){
                double h=b[j];
                for(int i=0;i<INPUTS;i++)h+=w[j][i]*x[i];
                z+=v[j]*Math.tanh(h);
            }
            return 1/(1+Math.exp(-Math.clamp(z,-40,40)));
        }
        void learn(double[] x,double reward){
            if(x.length!=INPUTS||!Double.isFinite(reward))return;
            double target=Math.clamp(reward,0,1);
            // Multiple small online gradients per ACTUAL task outcome. No
            // optimization uses hypothetical/counterfactual outcomes.
            for(int epoch=0;epoch<3;epoch++){
                double[] hidden=new double[HIDDEN],oldV=v.clone();
                double z=c;
                for(int j=0;j<HIDDEN;j++){
                    double a=b[j];
                    for(int i=0;i<INPUTS;i++)a+=w[j][i]*x[i];
                    hidden[j]=Math.tanh(a);z+=v[j]*hidden[j];
                }
                double probability=1/(1+Math.exp(-Math.clamp(z,-40,40)));
                double grad=probability-target;
                double lr=.022;
                for(int j=0;j<HIDDEN;j++){
                    v[j]-=lr*grad*hidden[j];
                    double dh=grad*oldV[j]*(1-hidden[j]*hidden[j]);
                    b[j]-=lr*dh;
                    for(int i=0;i<INPUTS;i++)w[j][i]-=lr*dh*x[i];
                }
                c-=lr*grad;
            }
        }
        Saved snapshot(int updates,int successes,int failures){
            double[][] weights=new double[HIDDEN][];
            for(int j=0;j<HIDDEN;j++)weights[j]=w[j].clone();
            return new Saved(1,updates,successes,failures,weights,b.clone(),v.clone(),c);
        }
        boolean restore(Saved saved){
            if(saved==null||saved.schema()!=1||saved.w()==null||saved.w().length!=HIDDEN
                    ||saved.b()==null||saved.b().length!=HIDDEN
                    ||saved.v()==null||saved.v().length!=HIDDEN
                    ||!Double.isFinite(saved.c()))return false;
            for(int j=0;j<HIDDEN;j++){
                if(saved.w()[j]==null||saved.w()[j].length!=INPUTS
                        ||!Double.isFinite(saved.b()[j])||!Double.isFinite(saved.v()[j]))return false;
                for(double val:saved.w()[j])if(!Double.isFinite(val))return false;
            }
            for(int j=0;j<HIDDEN;j++){
                System.arraycopy(saved.w()[j],0,w[j],0,INPUTS);
                b[j]=saved.b()[j];v[j]=saved.v()[j];
            }
            c=saved.c();return true;
        }
    }

    private static Path file(){
        return FabricLoader.getInstance().getConfigDir()
                .resolve("golems_plus").resolve("nn-frontier-policy.json");
    }
    static {
        if(ENABLED){
            try{
                Path path=file();
                if(Files.isRegularFile(path)&&Files.size(path)<=100_000){
                    Saved saved=JSON.fromJson(Files.readString(path),Saved.class);
                    if(MODEL.restore(saved)){
                        updates=saved.updates();successes=saved.successes();failures=saved.failures();
                        System.out.println("[NN FRONTIER LOAD] updates="+updates
                                +" successes="+successes+" failures="+failures);
                    }else System.out.println("[NN FRONTIER LOAD] incompatible checkpoint, fresh policy");
                }
            }catch(Exception e){
                System.out.println("[NN FRONTIER LOAD] fresh policy; "+e.getMessage());
            }
            System.out.println("[NN FRONTIER ENABLED] trainable=12x8x1"
                    +" safety=verified_frontier_same_tier model_updates="+updates);
        }
    }
    private static void save(){
        try{
            Path path=file();Files.createDirectories(path.getParent());
            // Atomic replacement prevents interrupted training from
            // corrupting the last usable checkpoint.
            Path pending=path.resolveSibling(path.getFileName()+".tmp");
            Files.writeString(pending,JSON.toJson(MODEL.snapshot(updates,successes,failures)),
                    StandardCharsets.UTF_8);
            try{Files.move(pending,path,java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);}
            catch(java.nio.file.AtomicMoveNotSupportedException e){
                Files.move(pending,path,java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }catch(Exception e){
            System.out.println("[NN FRONTIER SAVE ERROR] "+e.getMessage());
        }
    }
    private static double bounded(double x,double limit){
        return Math.clamp(x/limit,0,1);
    }
    static Candidate candidate(BuilderConstructionPlanner.Choice choice,
            BuilderGolem worker,BlockPos target,BlockPos stance,
            int touch,int risk,int stockRank,int tier,double cost,
            boolean sky,boolean grapple,boolean scaffold,boolean propeller){
        BlockPos from=worker.blockPosition(),feet=stance==null?target:stance;
        double[] x={
            bounded(Math.sqrt(from.distSqr(target)),40),      // target travel
            bounded(Math.sqrt(from.distSqr(feet)),40),        // verified approach
            bounded(Math.sqrt(feet.distSqr(target)),8),       // place from stance
            bounded(touch,4),                                 // built adjacency
            bounded(risk,4),                                  // enclosure hazard
            stockRank==0?1:0,                                 // held material
            sky?1:0,
            grapple?1:0,
            scaffold?1:0,
            propeller?1:0,
            Math.clamp((target.getY()-from.getY())/32.0,-1,1),
            bounded(Math.abs(cost),500)
        };
        return new Candidate(choice,target.immutable(),stockRank,tier,cost,x);
    }
    static boolean eligible(Candidate baseline,Candidate other){
        return baseline!=null&&other!=null
                &&other.stockRank()==baseline.stockRank()
                &&other.tier()==baseline.tier()
                &&other.cost()<=baseline.cost()+MAX_EXTRA_COST
                &&other.features()!=null&&other.features().length==INPUTS;
    }
    static BuilderConstructionPlanner.Choice select(ServerLevel level,
            BuilderGolem worker,List<Candidate> options,
            BuilderConstructionPlanner.Choice baseline){
        if(!ENABLED||baseline==null||options.isEmpty())return baseline;
        Candidate original=null;
        for(Candidate candidate:options)
            if(candidate.choice().index()==baseline.index()){original=candidate;break;}
        if(original==null)return baseline;
        List<Candidate> feasible=new ArrayList<>();
        for(Candidate candidate:options)
            if(eligible(original,candidate))feasible.add(candidate);
        feasible.sort(Comparator.comparingDouble(Candidate::cost)
                .thenComparingInt(a->a.choice().index()));
        if(feasible.size()>MAX_POOL){
            feasible=new ArrayList<>(feasible.subList(0,MAX_POOL));
            // The traditional choice is an ALWAYS-available fallback, even
            // if the worker's best-safe choice was not among cheapest N.
            if(feasible.stream().noneMatch(candidate->
                    candidate.choice().index()==baseline.index())){
                feasible.removeLast();feasible.add(original);
            }
        }
        if(feasible.isEmpty())return baseline;
        int winner=0;
        double top=-1;
        for(int i=0;i<feasible.size();i++){
            Candidate candidate=feasible.get(i);
            double probability=MODEL.score(candidate.features());
            // Bounded cost regularization, not a false safety certificate.
            double value=probability-.035*Math.max(0,candidate.cost()-original.cost())/MAX_EXTRA_COST;
            if(value>top){top=value;winner=i;}
        }
        boolean exploration=feasible.size()>1
                &&decisions<120
                &&Math.floorMod(decisions,3)==0;
        if(exploration){
            // Deterministic 1-in-3 bounded exploration provides causal
            // feedback about alternate VERIFIED tasks, not invented poses.
            int alternative=(int)Math.floorMod(decisions/3,feasible.size()-1)+1;
            Candidate candidate=feasible.get(alternative);
            if(candidate.choice().index()==baseline.index())
                alternative=(alternative+1)%feasible.size();
            winner=alternative;
        }
        Candidate selected=feasible.get(winner);
        int now=decisions++;
        boolean changed=selected.choice().index()!=baseline.index();
        if(changed)changes++;
        proposed.put(worker.getUUID(),new Proposal(selected.choice().index(),
                selected.target(),selected.features().clone(),level.getGameTime()));
        if(now<30||changed||now%60==0)
            System.out.println("[NN FRONTIER DECISION] "+JSON.toJson(new Decision(
                    1,worker.getUUID().toString(),level.getGameTime(),feasible.size(),
                    baseline.index(),selected.choice().index(),changed,exploration,
                    MODEL.score(original.features()),MODEL.score(selected.features()),updates)));
        return selected.choice();
    }
    static void claim(ServerLevel level,BuilderGolem worker,int actual){
        if(!ENABLED)return;
        Proposal proposal=proposed.remove(worker.getUUID());
        if(proposal==null||actual!=proposal.index())return;
        Pending old=claimed.put(worker.getUUID(),new Pending(
                actual,proposal.target(),proposal.features(),level.getGameTime()));
        if(old!=null&&old.index()!=actual)
            System.out.println("[NN FRONTIER CENSORED] worker="+worker.getUUID()
                    +" abandoned="+old.index()+" no labeled outcome");
    }
    static void completed(BuilderGolem worker,BlockPos pos){
        if(!ENABLED)return;
        Pending old=claimed.get(worker.getUUID());
        if(old==null||!old.target().equals(pos))return;
        claimed.remove(worker.getUUID());
        long ticks=Math.max(0,worker.level().getGameTime()-old.tick());
        double reward=1.0/(1.0+ticks/350.0);
        feedback(worker.getUUID(),old,ticks,reward,"WORLD_COMPLETED");
    }
    static void failed(UUID worker,int index,long now,String reason){
        if(!ENABLED)return;
        Pending old=claimed.get(worker);
        if(old==null||old.index()!=index)return;
        claimed.remove(worker);
        feedback(worker,old,Math.max(0,now-old.tick()),0.0,"HANDOFF_"+reason);
    }
    private static void feedback(UUID worker,Pending chosen,long ticks,double reward,String outcome){
        MODEL.learn(chosen.features(),reward);updates++;
        if(reward>0)successes++;else failures++;
        System.out.println("[NN FRONTIER FEEDBACK] "+JSON.toJson(new Feedback(
                1,worker.toString(),chosen.index(),ticks,reward,outcome,
                successes,failures,updates)));
        if(updates%SAVE_EVERY==0)save();
    }
    private BuilderNeuralFrontierPolicy(){}
}
