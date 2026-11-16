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

/** Frozen flight model. Shadow mode only: no movement writes, ever. */
final class BuilderNeuralAdvisor {
    private static final Gson JSON=new Gson();
    private static final List<String> FEATURES=List.of(
        "dx_2","dy_32","dz_2","route_distance_64","legs_3","grounded","direct_distance_64",
        "start_clear_xp","start_clear_xm","start_clear_yp","start_clear_ym","start_clear_zp","start_clear_zm",
        "goal_clear_xp","goal_clear_xm","goal_clear_yp","goal_clear_ym","goal_clear_zp","goal_clear_zm",
        "vertical_first_clear","side_first_clear","over_two_clear","under_two_clear","nearby_builders_4");
    private static final boolean ENABLED=Boolean.parseBoolean(System.getenv().getOrDefault("GOLEMS_NN_SHADOW","false"));
    private static final double MIN_GAIN=.10;
    private static final double MAX_LENGTH_RATIO=1.25;
    private static final int LIMIT=100_000;
    private static int emitted;
    private static final Model MODEL=load();
    record Model(List<String> features,double threshold,double[][] w,double[] b,double[] v,double c) {
        boolean valid(){
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
            double z=c;
            for(int j=0;j<8;j++){
                double a=b[j];
                for(int i=0;i<24;i++)a+=w[j][i]*x[i];
                z+=v[j]*Math.tanh(a);
            }
            return 1/(1+Math.exp(-Math.clamp(z,-40,40)));
        }
    }
    private record Candidate(int legs,double length,double score,List<Vec3> waypoints){}
    private record Row(int schema,String run,String worker,long id,long tick,
            double threshold,List<Candidate> candidates,int recommended,boolean wouldChange){}
    private static Model load(){
        if(!ENABLED)return null;
        try{
            Path path=Path.of(System.getenv().getOrDefault("GOLEMS_NN_MODEL",""));
            if(!Files.isRegularFile(path)||Files.size(path)>100_000)throw new IllegalArgumentException("Missing/oversized model");
            byte[] bytes=Files.readAllBytes(path);
            Model m=JSON.fromJson(new String(bytes,java.nio.charset.StandardCharsets.UTF_8),Model.class);
            if(m==null||!m.valid())throw new IllegalArgumentException("Incompatible flight model");
            String digest=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            System.out.println("[NNGOLEMS_SHADOW_MODEL] sha256="+digest+" inputs=24 hidden=8");
            return m;
        }catch(Exception e){
            System.out.println("[NNGOLEMS_SHADOW_DISABLED] "+e.getMessage());
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
    private static double length(Vec3 from,List<Vec3> path){
        double n=0;
        for(Vec3 p:path){n+=from.distanceTo(p);from=p;}
        return n;
    }
    static void observe(ServerLevel level,BuilderGolem worker,String run,long id,long tick,
            List<Vec3> accepted,Vec3 to,double[] geometry,double[] knowledge){
        if(MODEL==null||emitted>=LIMIT)return;
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
