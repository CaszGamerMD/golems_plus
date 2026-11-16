package com.caszgamermd.golemsplus.builder;

import com.google.gson.Gson;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import net.minecraft.server.level.ServerLevel;
import java.util.*;

/** Observe crew state at one boundary and actual physical progress at
 * the next. Never label proposed actions as successful placements. */
final class BuilderNNTeamTrace {
    private static final Gson JSON=new Gson();
    private final boolean enabled=Boolean.parseBoolean(System.getenv().getOrDefault("GOLEMS_NN_TEAM_RECORD","false"));
    private final String run=UUID.randomUUID().toString(),course,mode,policy;
    private final int crewSize,total;
    private Snapshot previous;
    private record Snapshot(long tick,int correct,double distance,int layer,int alive,int claimed,
            int stocked,int onsite,int elevated,int nearPairs,int remaining,boolean retreat) {}
    private record Window(int schema,String run,String course,String mode,String policy,int crew,int total,
            Snapshot start,long endTick,int endCorrect,int placedDelta,double distanceDelta,boolean terminal) {}
    BuilderNNTeamTrace(String course,String mode,int crewSize,int total){
        this.course=course;this.mode=mode;this.crewSize=crewSize;this.total=total;
        this.policy=System.getenv().getOrDefault("GOLEMS_NN_TEAM_LOCAL","false");
    }
    boolean enabled(){return enabled;}
    void sample(ServerLevel level,WorkerStationBlockEntity table,List<BuilderGolem> crew,
            List<BuilderConstructionPlan.Step> steps,long tick,int correct,double distance,boolean terminal){
        if(!enabled)return;
        var depot=table.builderUnloadingStorage();
        int alive=0,claimed=0,stocked=0,onsite=0,elevated=0,pairs=0;
        for(int i=0;i<crew.size();i++){
            var w=crew.get(i);
            if(!w.isAlive())continue;
            alive++;
            int claim=table.claimedStep(w.getUUID());
            if(claim>=0&&claim<steps.size())claimed++;
            boolean material=false;
            for(int slot=0;slot<BuilderGolem.SCAFFOLD_BELT_SLOT;slot++)
                if(!w.getItem(slot).isEmpty()){material=true;break;}
            if(material)stocked++;
            if(depot!=null&&BuilderReturnService.withinStorageReach(w,depot))onsite++;
            if(depot!=null&&w.getY()-depot.getY()>=12)elevated++;
            for(int j=0;j<i;j++)if(crew.get(j).isAlive()&&w.distanceToSqr(crew.get(j))<4)pairs++;
        }
        int layer=table.builderActiveLayer(steps);
        int remaining=layer==Integer.MAX_VALUE?0:BuilderCrewRecovery.remaining(table,steps,layer);
        var now=new Snapshot(tick,correct,distance,layer==Integer.MAX_VALUE?-1:layer-table.builderOrigin().getY(),
                alive,claimed,stocked,onsite,elevated,pairs,remaining,BuilderCrewRecovery.recalling(table));
        if(previous!=null&&tick>previous.tick())
            System.out.println("[NNGOLEMS_TEAM] "+JSON.toJson(new Window(1,run,course,mode,policy,crewSize,total,
                    previous,tick,correct,correct-previous.correct(),distance-previous.distance(),terminal)));
        previous=now;
    }
}
