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
    private BuilderNNRoofFrontier(){}
    static boolean enabled(){
        return Boolean.parseBoolean(System.getProperty("golems.nn.roofFrontier",
                System.getenv().getOrDefault("GOLEMS_NN_ROOF_FRONTIER","false")));
    }
    /** Rotation-neutral admission; never authorizes a flight or placement. */
    static boolean nearElevated(BlockPos from,BlockPos target,int pending){
        if(from==null||target==null||pending<=24)return false;
        int rise=target.getY()-from.getY();
        if(rise<2||rise>64)return false;
        long dx=(long)target.getX()-from.getX();
        long dz=(long)target.getZ()-from.getZ();
        return dx*dx+dz*dz<=24L*24L;
    }
    static boolean canApproach(ServerLevel level,BuilderGolem worker,
            BlockPos target,boolean bodyBlocking,int pending){
        if(!enabled()||!BuilderFlight.enabled(worker)||!worker.onGround()
                ||!nearElevated(worker.blockPosition(),target,pending)
                ||BuilderFlight.active(worker)||BuilderGrappleAccess.active(worker)
                ||BuilderScaffolding.isControllingMovement(worker))return false;
        long now=level.getGameTime();
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
