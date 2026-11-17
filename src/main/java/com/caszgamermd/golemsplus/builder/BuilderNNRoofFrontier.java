package com.caszgamermd.golemsplus.builder;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/** Opt-in, throttled roof approach for large unfinished team layers.
 * This only asks the existing FULLY PHYSICAL roof-flight planner to
 * certify walk -> lift -> supported landing -> safe work stance. */
public final class BuilderNNRoofFrontier {
    private static final long PROBE_COOLDOWN=30;
    private static final Map<BuilderGolem,Long> nextProbe=new WeakHashMap<>();
    private static final Map<BuilderGolem,Long> nextGateNote=new WeakHashMap<>();
    private static final Map<BuilderGolem,Long> nextMultiLegProbe=new WeakHashMap<>();
    private BuilderNNRoofFrontier(){}
    static boolean enabled(){
        return Boolean.parseBoolean(System.getProperty("golems.nn.roofFrontier",
                System.getenv().getOrDefault("GOLEMS_NN_ROOF_FRONTIER","false")));
    }
    /** A bounded, experimental search-radius change. No flight is approved
     * without the existing physical collision, launch and landing proof.
     * Only the NNGolems dome batch opts in to 40; ordinary play stays at 24. */
    static int searchRadius(){
        String value=System.getProperty("golems.nn.roofProbeRadius",
                System.getenv().getOrDefault("GOLEMS_NN_ROOF_RADIUS","24"));
        try{
            int radius=Integer.parseInt(value);
            return radius>=24&&radius<=40?radius:24;
        }catch(RuntimeException ex){return 24;}
    }
    /** Rotation-neutral admission; never authorizes a flight or placement. */
    static boolean nearElevated(BlockPos from,BlockPos target,int pending){
        if(from==null||target==null||pending<=24)return false;
        int rise=target.getY()-from.getY();
        if(rise<2||rise>64)return false;
        long dx=(long)target.getX()-from.getX();
        long dz=(long)target.getZ()-from.getZ();
        long radius=searchRadius();
        return dx*dx+dz*dz<=radius*radius;
    }
    /**
     * Narrow cap-only escape from a single-layer walking assumption.
     * Walls, fences and slabs can separate genuinely grounded roofs at
     * different elevations. A sequence of SHORT, individually checked
     * propeller lifts can reach supported work without climbing through a
     * 1.5-high barrier. This is an admission filter only: the existing
     * multi-leg controller proves each landing, walk, flight and hand pose.
     */
    static boolean sparseRoofMultiLegCandidate(BlockPos from,BlockPos target,int pending){
        if(from==null||target==null||pending<1||pending>24)return false;
        int rise=target.getY()-from.getY();
        if(rise<2||rise>48)return false;
        long dx=(long)target.getX()-from.getX(),dz=(long)target.getZ()-from.getZ();
        return dx*dx+dz*dz<=40L*40L;
    }
    static boolean canMultiLegApproach(ServerLevel level,BuilderGolem worker,
            BlockPos target,boolean bodyBlocking,int pending){
        if(!enabled()||!Boolean.parseBoolean(System.getProperty(
                    "golems.nn.multiLegRoof",
                    System.getenv().getOrDefault("GOLEMS_NN_MULTI_LEG_ROOF","false")))
                ||!sparseRoofMultiLegCandidate(worker.blockPosition(),target,pending)
                ||!BuilderFlight.enabled(worker)||!worker.onGround()
                ||BuilderFlight.active(worker)
                ||BuilderGrappleAccess.active(worker)
                ||BuilderScaffolding.isControllingMovement(worker))return false;
        long now=level.getGameTime();
        // A rare bounded check, not 8 workers * 32 targets * A* each tick.
        if(now<nextMultiLegProbe.getOrDefault(worker,0L))return false;
        nextMultiLegProbe.put(worker,now+100);
        boolean approved=BuilderMultiLegTravel.canAccessWork(
                level,worker,target,bodyBlocking);
        System.out.println("[NN ROOF MULTILEG] worker="+worker.getUUID()
                +" target="+target+" pending="+pending
                +" approved="+approved);
        return approved;
    }
    static boolean canApproach(ServerLevel level,BuilderGolem worker,
            BlockPos target,boolean bodyBlocking,int pending){
        if(!enabled())return false;
        long now=level.getGameTime();
        String rejected=!BuilderFlight.enabled(worker)?"propeller_disabled"
                :!worker.onGround()?"not_grounded"
                :!nearElevated(worker.blockPosition(),target,pending)
                    ?"outside_bounded_near_elevated_window"
                :BuilderFlight.active(worker)?"flight_busy"
                :BuilderGrappleAccess.active(worker)?"grapple_busy"
                :BuilderScaffolding.isControllingMovement(worker)?"scaffold_busy":null;
        if(rejected!=null){
            // One diagnostic per worker per 160 ticks: a test with ZERO
            // actual roof probes must explain its eligibility gate.
            if(now>=nextGateNote.getOrDefault(worker,0L)){
                nextGateNote.put(worker,now+160);
                System.out.println("[NN ROOF GATE] worker="+worker.getUUID()
                        +" reason="+rejected+" from="+worker.blockPosition()
                        +" target="+target+" pending="+pending);
            }
            return false;
        }
        if(now<nextProbe.getOrDefault(worker,0L))return false;
        nextProbe.put(worker,now+PROBE_COOLDOWN);
        System.out.println("[NN ROOF PROBE] worker="+worker.getUUID()
                +" target="+target+" layerPending="+pending);
        // If this returns false, the roof planner could not prove a
        // supported, collision-safe route. Never fabricate access.
        boolean approved=BuilderFlight.canAccessRoofWork(
                level,worker,target,bodyBlocking);
        if(approved)
            System.out.println("[NN ROOF APPROVED] worker="+worker.getUUID()
                +" target="+target+" layerPending="+pending);
        return approved;
    }
}
