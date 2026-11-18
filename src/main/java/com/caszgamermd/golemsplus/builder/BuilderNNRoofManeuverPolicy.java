package com.caszgamermd.golemsplus.builder;

import com.google.gson.Gson;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
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
 * Online-trained policy for roof maneuvers on NNGolems. The physical planner
 * creates every candidate using real supported launch, legal walking route,
 * collision-tested flight and supported landing. The neural policy can rank
 * candidates; it CANNOT synthesize a route or relax any validity condition.
 *
 * Concrete outcomes: a supported completed multi-leg journey, a failed
 * execute/replan, and a verified world placement. No training is performed
 * from an unexecuted path simply because it looked promising.
 */
final class BuilderNNRoofManeuverPolicy {
    static final boolean ENABLED=Boolean.parseBoolean(System.getProperty(
            "golems.nn.roofManeuverPolicy",
            System.getenv().getOrDefault("GOLEMS_NN_ROOF_MANEUVER_POLICY","false")));
    private static final Gson JSON=new Gson();
    private static final boolean FROZEN=Boolean.parseBoolean(System.getenv()
            .getOrDefault("GOLEMS_NN_FROZEN","false"));
    private static final int INPUTS=14, HIDDEN=8, SAVE_EVERY=8;
    private static final Network MODEL=Network.initial();
    private static final Map<UUID,Selection> proposed=new HashMap<>();
    private static final Map<UUID,Recent> recent=new HashMap<>();
    private static int decisions,changes,explorations,updates,successfulRoutes,failedRoutes,placements;
    record Candidate(List<BuilderMultiLegTravel.Leg> legs,boolean complete){}
    record Selection(BlockPos target,List<BuilderMultiLegTravel.Leg> legs,
            double[] features,int baselineIndex,int selectedIndex,
            boolean explored,boolean complete){}
    private record Recent(BlockPos target,double[] features,long time){}
    private record DecisionLog(int schema,String worker,long tick,int options,
            int selected,int baseline,boolean changed,boolean exploration,
            boolean complete,boolean aboveTarget,boolean beyondXZ,
            int updates){}
    private record OutcomeLog(int schema,String worker,long tick,String outcome,
            int legs,boolean complete,boolean aboveTarget,boolean beyondXZ,
            double reward,int updates,int completed,int failures){}
    record Saved(int schema,int updates,int completed,int failed,int placements,
            double[][] w,double[] b,double[] v,double c){}

    static final class Network {
        final double[][] w=new double[HIDDEN][INPUTS];
        final double[] b=new double[HIDDEN],v=new double[HIDDEN];
        double c;
        private Network(){}
        static Network initial(){
            Network n=new Network();
            Random r=new Random(26021842L);
            for(int j=0;j<HIDDEN;j++){
                n.v[j]=(r.nextDouble()-.5)*.25;
                for(int i=0;i<INPUTS;i++)n.w[j][i]=(r.nextDouble()-.5)*.25;
            }
            return n;
        }
        double predict(double[] x){
            if(x==null||x.length!=INPUTS)throw new IllegalArgumentException("roof feature shape");
            double value=c;
            for(int j=0;j<HIDDEN;j++){
                double a=b[j];
                for(int i=0;i<INPUTS;i++)a+=w[j][i]*x[i];
                value+=v[j]*Math.tanh(a);
            }
            return 1.0/(1+Math.exp(-Math.clamp(value,-35,35)));
        }
        void learn(double[] x,double reward,double weight){
            if(x==null||x.length!=INPUTS||!Double.isFinite(reward))return;
            double target=Math.clamp(reward,0,1);
            for(int repeat=0;repeat<3;repeat++){
                double[] hidden=new double[HIDDEN];
                double score=c;
                for(int j=0;j<HIDDEN;j++){
                    double a=b[j];
                    for(int i=0;i<INPUTS;i++)a+=w[j][i]*x[i];
                    hidden[j]=Math.tanh(a);
                    score+=v[j]*hidden[j];
                }
                double gradient=(1.0/(1+Math.exp(-Math.clamp(score,-35,35)))-target)
                        *Math.clamp(weight,.25,3.0);
                double[] prior=v.clone();
                double rate=.018;
                for(int j=0;j<HIDDEN;j++){
                    v[j]-=rate*gradient*hidden[j];
                    double dh=gradient*prior[j]*(1-hidden[j]*hidden[j]);
                    b[j]-=rate*dh;
                    for(int i=0;i<INPUTS;i++)w[j][i]-=rate*dh*x[i];
                }
                c-=rate*gradient;
            }
        }
        Saved snapshot(int updates,int good,int bad,int placements){
            double[][] copy=new double[HIDDEN][];
            for(int j=0;j<HIDDEN;j++)copy[j]=w[j].clone();
            return new Saved(1,updates,good,bad,placements,copy,b.clone(),v.clone(),c);
        }
        boolean restore(Saved s){
            if(s==null||s.schema()!=1||s.w()==null||s.w().length!=HIDDEN
                    ||s.b()==null||s.b().length!=HIDDEN||s.v()==null
                    ||s.v().length!=HIDDEN||!Double.isFinite(s.c()))return false;
            for(int j=0;j<HIDDEN;j++){
                if(s.w()[j]==null||s.w()[j].length!=INPUTS
                        ||!Double.isFinite(s.b()[j])||!Double.isFinite(s.v()[j]))return false;
                for(double d:s.w()[j])if(!Double.isFinite(d))return false;
            }
            for(int j=0;j<HIDDEN;j++){
                System.arraycopy(s.w()[j],0,w[j],0,INPUTS);
                b[j]=s.b()[j];v[j]=s.v()[j];
            }
            c=s.c();
            return true;
        }
    }
    private static Path checkpoint(){
        String path=System.getenv().getOrDefault("GOLEMS_NN_ROOF_POLICY_PATH","");
        return path.isBlank()?FabricLoader.getInstance().getConfigDir()
                .resolve("golems_plus").resolve("roof-maneuver-policy.json"):Path.of(path);
    }
    static {
        if(ENABLED){
            try{
                Path f=checkpoint();
                if(Files.isRegularFile(f)&&Files.size(f)<100_000){
                    Saved s=JSON.fromJson(Files.readString(f),Saved.class);
                    if(MODEL.restore(s)){
                        updates=s.updates();successfulRoutes=s.completed();
                        failedRoutes=s.failed();placements=s.placements();
                        System.out.println("[NN ROOF POLICY LOAD] updates="+updates
                                +" successful="+successfulRoutes+" failures="+failedRoutes);
                    }
                }
            }catch(Exception e){System.out.println("[NN ROOF POLICY LOAD ERROR] "+e);}
            System.out.println("[NN ROOF POLICY ENABLED] features=14 hidden=8"
                    +" safety=physically_validated_candidates");
        }
    }
    private static void save(){
        try{
            Path dest=checkpoint();
            Files.createDirectories(dest.getParent());
            Path temp=dest.resolveSibling(dest.getFileName()+".tmp");
            Files.writeString(temp,JSON.toJson(MODEL.snapshot(
                    updates,successfulRoutes,failedRoutes,placements)),StandardCharsets.UTF_8);
            try{Files.move(temp,dest,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(java.nio.file.AtomicMoveNotSupportedException e){
                Files.move(temp,dest,StandardCopyOption.REPLACE_EXISTING);
            }
        }catch(Exception e){System.out.println("[NN ROOF POLICY SAVE ERROR] "+e);}
    }
    private static double normal(double x,double scale){
        return Math.clamp(x/scale,-1,1);
    }
    static double[] features(BlockPos from,BlockPos target,
            List<BuilderMultiLegTravel.Leg> legs,boolean complete,int exits){
        if(legs==null||legs.isEmpty())throw new IllegalArgumentException("No physical legs");
        BlockPos end=legs.getLast().landing();
        int highest=from.getY(),lowest=from.getY();
        double traveled=0;
        for(var leg:legs){
            highest=Math.max(highest,Math.max(leg.launch().getY(),leg.landing().getY()));
            lowest=Math.min(lowest,Math.min(leg.launch().getY(),leg.landing().getY()));
            traveled+=Math.sqrt(leg.launch().distSqr(leg.landing()));
        }
        boolean pastX=(from.getX()<target.getX()&&end.getX()>target.getX()+1)
                ||(from.getX()>target.getX()&&end.getX()<target.getX()-1);
        boolean pastZ=(from.getZ()<target.getZ()&&end.getZ()>target.getZ()+1)
                ||(from.getZ()>target.getZ()&&end.getZ()<target.getZ()-1);
        return new double[]{
            Math.min(1,Math.sqrt(from.distSqr(target))/48.0),
            Math.min(1,Math.sqrt(end.distSqr(target))/48.0),
            normal(target.getY()-from.getY(),32),
            normal(end.getY()-target.getY(),24),
            Math.min(1,Math.max(0,highest-target.getY())/20.0),
            Math.min(1,Math.max(0,target.getY()-lowest)/20.0),
            pastX?1:0,pastZ?1:0,
            highest>=target.getY()+2?1:0,
            Math.min(1,traveled/80.0),
            Math.min(1,legs.size()/6.0),
            Math.min(1,exits/4.0),
            Math.min(1,Math.sqrt(from.distSqr(end))/48.0),
            complete?1:0
        };
    }
    /** Neural rankings are noisy before enough real route successes exist.
     * A detour can still climb or overshoot, but should not replace a more
     * complete baseline plan merely on a tiny predicted probability delta. */
    static boolean compatibleRoute(double[] candidate,double[] baseline){
        if(candidate==null||baseline==null||candidate.length!=INPUTS
                ||baseline.length!=INPUTS)return false;
        if(candidate[13]+.01<baseline[13])return false;
        if(candidate[9]>baseline[9]+.24&&candidate[13]<.5)return false;
        if(candidate[10]>baseline[10]+.50&&candidate[13]<.5)return false;
        return true;
    }
    static int chooseIndex(Network model,List<double[]> contexts,int baseline,
            int decisionNumber){
        if(contexts==null||contexts.size()<2)return Math.max(0,baseline);
        double[] standard=contexts.get(baseline);
        double reference=model.predict(standard)-.018*standard[9];
        int winner=baseline;
        double best=reference+.10; // require an actual confidence margin
        for(int i=0;i<contexts.size();i++){
            if(i==baseline||!compatibleRoute(contexts.get(i),standard))continue;
            double score=model.predict(contexts.get(i))-.018*contexts.get(i)[9];
            if(score>best){best=score;winner=i;}
        }
        // Limited exploration is reserved for comparable, physically
        // certified routes. Never trade a complete route for a speculative
        // incomplete landing just because the network is inexperienced.
        if(decisionNumber<80&&decisionNumber%8==0){
            for(int offset=1;offset<contexts.size();offset++){
                int pick=(baseline+offset+decisionNumber/8)%contexts.size();
                if(pick!=baseline&&compatibleRoute(contexts.get(pick),standard))
                    return pick;
            }
        }
        return winner;
    }
    static List<BuilderMultiLegTravel.Leg> choose(ServerLevel level,
            BuilderGolem worker,BlockPos target,List<Candidate> options,int baseline){
        if(!ENABLED)return options.get(baseline).legs();
        // Even a SINGLE physically available route supplies genuine landing
        // and execution-failure labels; the model has nothing to choose
        // between yet, but it still needs this training signal.
        List<double[]> contexts=new ArrayList<>();
        BlockPos from=worker.blockPosition();
        for(var option:options){
            BlockPos end=option.legs().getLast().landing();
            int exits=0;
            for(var side:net.minecraft.core.Direction.Plane.HORIZONTAL)
                if(com.caszgamermd.golemsplus.worker.WorkerNavigation.canStandOn(
                        level,worker,end.relative(side)))exits++;
            contexts.add(features(from,target,option.legs(),option.complete(),exits));
        }
        int attempt=decisions++;
        int selected=chooseIndex(MODEL,contexts,baseline,FROZEN?200:attempt);
        boolean explore=!FROZEN&&attempt<80&&attempt%8==0
                &&selected!=baseline;
        if(selected!=baseline)changes++;
        if(explore)explorations++;
        var chosen=options.get(selected);
        proposed.put(worker.getUUID(),new Selection(target.immutable(),chosen.legs(),
                contexts.get(selected).clone(),baseline,selected,explore,chosen.complete()));
        if(attempt<30||selected!=baseline||attempt%50==0){
            double[] x=contexts.get(selected);
            System.out.println("[NN ROOF CHOICE] "+JSON.toJson(new DecisionLog(
                    1,worker.getUUID().toString(),level.getGameTime(),options.size(),
                    selected,baseline,selected!=baseline,explore,chosen.complete(),
                    x[8]>0,x[6]>0||x[7]>0,updates)));
        }
        return chosen.legs();
    }
    static Selection committed(BuilderGolem worker,BlockPos target,
            List<BuilderMultiLegTravel.Leg> actual){
        if(!ENABLED)return null;
        Selection selected=proposed.remove(worker.getUUID());
        return selected!=null&&selected.target().equals(target)
                &&selected.legs().equals(actual)?selected:null;
    }
    private static void learn(ServerLevel level,BuilderGolem worker,Selection selected,
            double reward,String outcome){
        if(selected==null||!ENABLED)return;
        if(!FROZEN){
            MODEL.learn(selected.features(),reward,reward<=0?2.5:1.0);
            updates++;
        }
        if("WORLD_PLACED".equals(outcome))placements++;
        else if(reward==0)failedRoutes++;
        else successfulRoutes++;
        if(!FROZEN&&updates%SAVE_EVERY==0)save();
        double[] x=selected.features();
        System.out.println("[NN ROOF OUTCOME] "+JSON.toJson(new OutcomeLog(
                1,worker.getUUID().toString(),level.getGameTime(),outcome,
                selected.legs().size(),selected.complete(),x[8]>0,
                x[6]>0||x[7]>0,reward,updates,successfulRoutes,failedRoutes)));
    }
    /** Reward a leg ONLY when the real entity arrives grounded at the
     * verified landing. A leg is useful evidence, not a complete route. */
    static double partialLegReward(int completed,int total){
        if(total<=0||completed<=0||completed>=total)return 0;
        return .15+.30*completed/(double)total;
    }
    static void legLanded(ServerLevel level,BuilderGolem worker,
            Selection selection,int completed){
        if(selection==null||!ENABLED||completed>=selection.legs().size()
                ||completed<1||!worker.onGround())return;
        double reward=partialLegReward(completed,selection.legs().size());
        if(reward>0)learn(level,worker,selection,reward,"SUPPORTED_LEG_LANDING");
    }
    static void finished(ServerLevel level,BuilderGolem worker,Selection selected,
            boolean atGoal){
        if(selected==null)return;
        // An intermediate, grounded landing is meaningful partial success
        // but not a completed BUILD. Actual work placement is labeled later.
        learn(level,worker,selected,atGoal?.80:.60,
                atGoal?"SAFE_WORK_POSE":"SUPPORTED_INTERMEDIATE_LANDING");
        recent.put(worker.getUUID(),new Recent(selected.target(),
                selected.features().clone(),level.getGameTime()));
    }
    static void failed(ServerLevel level,BuilderGolem worker,Selection selected,
            String reason){
        if(selected==null)return;
        learn(level,worker,selected,0.0,"EXECUTION_FAILED_"+reason);
        recent.remove(worker.getUUID());
    }
    static void worldPlaced(ServerLevel level,BuilderGolem worker,BlockPos target){
        if(!ENABLED)return;
        Recent result=recent.remove(worker.getUUID());
        if(result==null||!result.target().equals(target)
                ||level.getGameTime()-result.time()>2000)return;
        if(!FROZEN){
            MODEL.learn(result.features(),1.0,1.5);
            updates++;
        }
        placements++;
        if(!FROZEN&&updates%SAVE_EVERY==0)save();
        System.out.println("[NN ROOF PLACEMENT] worker="+worker.getUUID()
                +" target="+target+" updates="+updates+" totalPlacements="+placements);
    }
    /** A waypoint is a physical arrival, not a pretend block placement.
     * Only a maneuver the NN actually committed can receive this reward. */
    static boolean waypointReached(ServerLevel level,BuilderGolem worker,BlockPos feet){
        if(!ENABLED)return false;
        Recent route=recent.remove(worker.getUUID());
        if(route==null||!route.target().equals(feet)
                ||level.getGameTime()-route.time()>2000)return false;
        if(!FROZEN){
            MODEL.learn(route.features(),1.0,1.5);
            updates++;
            if(updates%SAVE_EVERY==0)save();
        }
        System.out.println("[NN ROOF WAYPOINT REWARD] worker="+worker.getUUID()
                +" target="+feet+" above="+(route.features()[8]>0)
                +" farSide="+(route.features()[6]>0||route.features()[7]>0)
                +" updates="+updates+" trained="+!FROZEN);
        return true;
    }
    static void flush(){
        if(ENABLED&&!FROZEN)save();
    }
    static void handedOff(UUID worker){
        if(ENABLED)recent.remove(worker);
    }
    private BuilderNNRoofManeuverPolicy(){}
}
