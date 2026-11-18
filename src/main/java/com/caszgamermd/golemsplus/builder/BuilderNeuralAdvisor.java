package com.caszgamermd.golemsplus.builder;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/** Frozen trained flight-arrival model.
 * Normal gameplay is read-only shadow. The isolated NN pilot may allow the
 * network to choose among PREVALIDATED flight paths to the SAME destination.
 * It cannot create targets, change claims, skip route checks or place blocks. */
final class BuilderNeuralAdvisor {
    private static final Gson JSON=new Gson();
    private static final List<String> FEATURES=List.of(
        "dx_2","dy_32","dz_2","route_distance_64","legs_3","grounded","direct_distance_64",
        "start_clear_xp","start_clear_xm","start_clear_yp","start_clear_ym","start_clear_zp","start_clear_zm",
        "goal_clear_xp","goal_clear_xm","goal_clear_yp","goal_clear_ym","goal_clear_zp","goal_clear_zm",
        "vertical_first_clear","side_first_clear","over_two_clear","under_two_clear","nearby_builders_4");
    private static final boolean CONTROL=Boolean.parseBoolean(System.getProperty(
            "golems.nn.liveFlight",System.getenv().getOrDefault("GOLEMS_NN_CONTROL","false")));
    private static final boolean SHADOW=Boolean.parseBoolean(System.getenv().getOrDefault("GOLEMS_NN_SHADOW","false"));
    private static final boolean ENABLED=CONTROL||SHADOW;
    private static final double MIN_GAIN=.10;
    private static final double MAX_LENGTH_RATIO=1.25;
    private static final int LIMIT=100_000;
    private static int emitted,liveDecisions;
    private static boolean unavailableLogged;
    private static final int LIVE_LOG_LIMIT=25000;
    private static final Model MODEL=load();
    record Model(List<String> features,double threshold,double[][] w,double[] b,double[] v,double c,String symmetry) {
        Model(List<String> features,double threshold,double[][] w,double[] b,double[] v,double c){
            this(features,threshold,w,b,v,c,null);
        }
        boolean valid(){
            if(symmetry!=null&&!symmetry.equals("c4_y"))return false;
            if(!FEATURES.equals(features)||!Double.isFinite(threshold)||threshold<0||threshold>1
                    ||w==null||b==null||v==null||w.length!=8||b.length!=8||v.length!=8
                    ||!Double.isFinite(c))return false;
            for(int j=0;j<8;j++){
                if(w[j]==null||w[j].length!=24||!Double.isFinite(b[j])||!Double.isFinite(v[j]))return false;
                for(double a:w[j])if(!Double.isFinite(a))return false;
            }
            return true;
        }
        double score(double[] x){
            if(x.length!=24)throw new IllegalArgumentException("flight feature size");
            for(double value:x)if(!Double.isFinite(value))throw new IllegalArgumentException("nonfinite input");
            if("c4_y".equals(symmetry)){
                double[] probabilities=new double[4];
                double[] rotated=x;
                for(int i=0;i<4;i++){
                    probabilities[i]=rawScore(rotated);
                    rotated=rotate(rotated);
                }
                Arrays.sort(probabilities);
                double total=0;
                for(double probability:probabilities)total+=probability;
                return total/4;
            }
            return rawScore(x);
        }
        private double rawScore(double[] x){
            double z=c;
            for(int j=0;j<8;j++){
                double a=b[j];
                for(int i=0;i<24;i++)a+=w[j][i]*x[i];
                z+=v[j]*Math.tanh(a);
            }
            return 1/(1+Math.exp(-Math.clamp(z,-40,40)));
        }
    }
    static double[] rotate(double[] x){
        if(x.length!=24)throw new IllegalArgumentException("flight feature size");
        double[] y=x.clone();
        y[0]=-x[2];y[2]=x[0];
        for(int start:new int[]{7,13}){
            y[start]=x[start+5];y[start+1]=x[start+4];
            y[start+4]=x[start];y[start+5]=x[start+1];
        }
        return y;
    }
    private record Candidate(int legs,double length,double score,List<Vec3> waypoints){}
    private record Row(int schema,String run,String worker,long id,long tick,
            double threshold,List<Candidate> candidates,int recommended,boolean wouldChange){}
    private record LiveRow(int schema,String worker,long tick,int alternatives,
            int chosen,boolean changed,double baselineScore,double chosenScore,
            double baselineLength,double chosenLength,double threshold){}
    private static Model load(){
        if(!ENABLED)return null;
        try{
            Path path=Path.of(System.getenv().getOrDefault("GOLEMS_NN_MODEL",""));
            if(!Files.isRegularFile(path)||Files.size(path)>100_000)throw new IllegalArgumentException("Missing/oversized model");
            byte[] bytes=Files.readAllBytes(path);
            Model m=JSON.fromJson(new String(bytes,java.nio.charset.StandardCharsets.UTF_8),Model.class);
            if(m==null||!m.valid())throw new IllegalArgumentException("Incompatible flight model");
            String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            System.out.println("[NNGOLEMS_MODEL] sha256="+digest
                    +" inputs=24 hidden=8 live="+CONTROL+" shadow="+SHADOW);
            return m;
        }catch(Exception e){
            System.out.println("[NNGOLEMS_MODEL_DISABLED] live="+CONTROL+" "+e.getMessage());
            return null; // fail open to unchanged conventional controller
        }
    }
    static double[] combine(double[] geometry,double[] knowledge){
        double[] x=Arrays.copyOf(geometry,geometry.length+knowledge.length);
        System.arraycopy(knowledge,0,x,geometry.length,knowledge.length);
        return x;
    }
    static int recommend(double[] scores,double[] lengths){
        int best=0;
        for(int i=1;i<scores.length;i++){
            if(lengths[i]<=lengths[0]*MAX_LENGTH_RATIO+.02&&scores[i]>scores[best]+1e-9)best=i;
        }
        return best!=0&&scores[best]-scores[0]>=MIN_GAIN?best:0;
    }
    /** Pure, deterministic policy gate. No learned confidence can override
     * the physical validator: this only picks an index among legal paths. */
    static int liveChoice(double[] probabilities,double[] lengths,double threshold){
        if(probabilities==null||lengths==null||probabilities.length<1
                ||probabilities.length!=lengths.length||!Double.isFinite(threshold)
                ||threshold<0||threshold>1)return 0;
        for(int i=0;i<probabilities.length;i++)
            if(!Double.isFinite(probabilities[i])||!Double.isFinite(lengths[i])
                    ||lengths[i]<=0)return 0;
        int selected=recommend(probabilities,lengths);
        return selected>0&&probabilities[selected]>=threshold?selected:0;
    }
    /**
     * Live pilot inference only. The production controller supplies the
     * baseline path. All alternatives use identical destination, flight
     * envelope and real swept voxel checks. Decision failure ALWAYS
     * falls back to that proven baseline. This has no world writes.
     */
    static List<Vec3> decideLive(ServerLevel level,BuilderGolem worker,
            List<Vec3> baseline,Vec3 destination){
        if(!CONTROL)return baseline;
        if(MODEL==null){
            if(!unavailableLogged){
                unavailableLogged=true;
                System.out.println("[NNGOLEMS_CONTROL_FALLBACK] model_unavailable");
            }
            return baseline;
        }
        try{
            List<List<Vec3>> routes=BuilderFlight.shadowAlternatives(
                    level,worker,baseline,destination);
            double[] probabilities=new double[routes.size()];
            double[] lengths=new double[routes.size()];
            for(int i=0;i<routes.size();i++){
                var route=routes.get(i);
                double[] features=combine(
                        BuilderNeuralRecorder.flightGeometry(worker,route,destination),
                        BuilderFlight.observedKnowledge(level,worker,route,destination));
                probabilities[i]=MODEL.score(features);
                lengths[i]=length(worker.position(),route);
            }
            int selected=liveChoice(probabilities,lengths,MODEL.threshold());
            List<Vec3> chosen=routes.get(selected);
            if(!BuilderFlight.verifiedFlightRoute(level,worker,chosen,destination)){
                System.out.println("[NNGOLEMS_CONTROL_FALLBACK] route_failed_final_sweep");
                selected=0;chosen=baseline;
            }
            if(liveDecisions++<LIVE_LOG_LIMIT)
                System.out.println("[NNGOLEMS_CONTROL] "+JSON.toJson(new LiveRow(
                        1,worker.getUUID().toString(),level.getGameTime(),
                        routes.size(),selected,selected!=0,probabilities[0],
                        probabilities[selected],lengths[0],lengths[selected],
                        MODEL.threshold())));
            return chosen;
        }catch(RuntimeException e){
            System.out.println("[NNGOLEMS_CONTROL_FALLBACK] "+e.getMessage());
            return baseline;
        }
    }
    private static double length(Vec3 from,List<Vec3> path){
        double n=0;
        for(Vec3 p:path){n+=from.distanceTo(p);from=p;}
        return n;
    }
    static void observe(ServerLevel level,BuilderGolem worker,String run,long id,long tick,
            List<Vec3> accepted,Vec3 to,double[] geometry,double[] knowledge){
        if(!SHADOW||MODEL==null||emitted>=LIMIT)return;
        try{
            var paths=BuilderFlight.shadowAlternatives(level,worker,accepted,to);
            var candidates=new ArrayList<Candidate>();
            double[] scores=new double[paths.size()],lengths=new double[paths.size()];
            for(int i=0;i<paths.size();i++){
                var p=paths.get(i);
                double[] x=i==0?combine(geometry,knowledge):combine(
                        BuilderNeuralRecorder.flightGeometry(worker,p,to),
                        BuilderFlight.observedKnowledge(level,worker,p,to));
                scores[i]=MODEL.score(x);
                lengths[i]=length(worker.position(),p);
                candidates.add(new Candidate(p.size(),lengths[i],scores[i],List.copyOf(p)));
            }
            int chosen=recommend(scores,lengths);
            System.out.println("[NNGOLEMS_SHADOW] "+JSON.toJson(new Row(1,run,worker.getUUID().toString(),
                    id,tick,MODEL.threshold(),candidates,chosen,chosen!=0)));
            emitted++;
        }catch(RuntimeException e){
            // Observation must never break physical construction.
            System.out.println("[NNGOLEMS_SHADOW_ERROR] "+e.getMessage());
        }
    }
    private BuilderNeuralAdvisor(){}
}
