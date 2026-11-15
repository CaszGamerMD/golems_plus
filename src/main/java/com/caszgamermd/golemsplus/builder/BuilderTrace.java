package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** Opt-in bounded flight recorder. Read-only: never calls a route planner,
 * changes claims, consumes items or steers a worker. Server thread only. */
public final class BuilderTrace {
    private BuilderTrace(){}
    private static final boolean ENABLED=Boolean.parseBoolean(
            System.getProperty("golems.builder.trace",
                    System.getenv().getOrDefault("GOLEMS_BUILDER_TRACE","false")));
    public static boolean enabled(){return ENABLED;}
    private static final int LIMIT=384;
    private record Event(long tick,String kind,String detail){}
    private static final class History {
        final ArrayDeque<Event> events=new ArrayDeque<>();
        long start=-1,lastSample=-1,lastDecision=-1,lastCheckpoint=-1;
        String owner="",decision="",lastKey="";
        int repetitions;
        Vec3 lastPosition;
    }
    private static final Map<BuilderGolem,History> histories=new WeakHashMap<>();
    private static History history(BuilderGolem worker,long now){
        History h=histories.computeIfAbsent(worker,k->new History());
        if(h.start<0)h.start=now;
        return h;
    }
    private static void add(History h,long now,String kind,String detail){
        if(h.events.size()>=LIMIT)h.events.removeFirst();
        h.events.addLast(new Event(now-h.start,kind,detail));
    }
    private static String clean(String value){return value.replace('\n',' ').replace('\r',' ').replace('\t',' ');}
    private static String pos(Vec3 p){
        return String.format(Locale.ROOT,"%.3f,%.3f,%.3f",p.x,p.y,p.z);
    }
    private static String block(BlockPos p){return p==null?"none":p.getX()+","+p.getY()+","+p.getZ();}
    /** Record the actual branch that accepted, deferred or rejected motion.
     * Repeated identical requests are counted rather than flooding the log. */
    public static void decision(ServerLevel level,PathfinderMob mob,String kind,BlockPos goal,String reason){
        if(!ENABLED||!(mob instanceof BuilderGolem worker))return;
        long now=level.getGameTime();History h=history(worker,now);
        String key=kind+" goal="+block(goal)+" reason="+clean(reason);
        if(key.equals(h.lastKey)&&now-h.lastDecision<40){h.repetitions++;return;}
        if(h.repetitions>0)add(h,now,"REPEAT","count="+h.repetitions+" previous="+h.lastKey);
        h.repetitions=0;h.lastKey=key;h.lastDecision=now;h.decision=key;
        add(h,now,kind,"goal="+block(goal)+" reason="+clean(reason)+" feet="+pos(worker.position()));
    }
    static void sample(ServerLevel level,BuilderGolem worker,String controller){
        if(!ENABLED||worker.owner()==null||!level.hasChunkAt(worker.home())
                ||!(level.getBlockEntity(worker.home()) instanceof WorkerStationBlockEntity table)
                ||table.activeOrder()==null)return;
        long now=level.getGameTime();History h=history(worker,now);
        boolean changed=!controller.equals(h.owner);
        // Keep aligned observations even after the recent-event ring fills.
        boolean periodic=now%100==0&&now!=h.lastCheckpoint;
        if(!changed&&!periodic&&now-h.lastSample<10)return;
        h.owner=controller;h.lastSample=now;
        Vec3 feet=worker.position();
        double displacement=h.lastPosition==null?0:feet.distanceTo(h.lastPosition);
        h.lastPosition=feet;
        BlockPos cell=worker.blockPosition();
        String detail="owner="+controller+" feet="+pos(feet)
                +" velocity="+pos(worker.getDeltaMovement())
                +" moved="+String.format(Locale.ROOT,"%.3f",displacement)
                +" grounded="+worker.onGround()+" noGravity="+worker.isNoGravity()
                +" floor="+WorkerNavigation.surveyFloorHeight(level,cell)
                +" footing="+level.getBlockState(cell.below())
                +" feetBlock="+level.getBlockState(cell)
                +" fluid="+level.getFluidState(cell)
                +" claim="+table.claimedStep(worker.getUUID())
                +" workDone="+table.builderWorkDone()
                +" staged="+table.builderOrderPrepared(worker.getUUID())
                +" action="+BuilderBuildService.traceAction(worker)
                +" flight="+BuilderFlight.traceStatus(worker)
                +" grapple="+BuilderGrappleAccess.debug(worker)
                +" scaffold="+BuilderScaffolding.debugClimb(worker)
                +" recovery="+BuilderCrewRecovery.traceStatus(table,worker)
                +" nav="+WorkerNavigation.debugStatus(worker)
                +" decision="+h.decision;
        add(h,now,changed?"OWNER":"SAMPLE",detail);
        // Retain an aligned, low-volume chronology across the whole run.
        // Include world time so GameTest's MATRIX CLOCK can map it to test ticks.
        if(periodic||(changed&&h.events.size()<LIMIT)||now-h.start<200&&now%20==0){
            h.lastCheckpoint=now;
            System.out.println("[BUILDER TRACE CHECKPOINT] worker="+worker.getName().getString()
                    +" tick="+(now-h.start)+" worldTick="+now+" "+detail);
        }
    }
    static void dump(ServerLevel level,WorkerStationBlockEntity table,String reason){
        if(!ENABLED)return;
        System.out.println("[BUILDER TRACE BEGIN] reason="+clean(reason)+" worldTick="+level.getGameTime()
                +" workDone="+table.builderWorkDone());
        for(UUID id:table.builderWorkerIds()){
            if(!(level.getEntity(id) instanceof BuilderGolem worker)){
                System.out.println("[BUILDER TRACE MISSING] worker="+id);continue;
            }
            History h=histories.get(worker);
            if(h==null)continue;
            String name=worker.getName().getString();
            for(Event e:h.events)System.out.println("[BUILDER TRACE EVENT] worker="+name
                    +" tick="+e.tick()+" kind="+e.kind()+" "+e.detail());
            if(h.repetitions>0)System.out.println("[BUILDER TRACE REPEAT] worker="+name+" count="+h.repetitions+" "+h.lastKey);
        }
        System.out.println("[BUILDER TRACE END] reason="+clean(reason));
    }
}
