package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.GolemsPlus;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.worker.WorkerPhase;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;

/**
 * Temporary, simulation-capable chunk tickets belonging to ACTIVE Builder
 * jobs, never to the player or to a permanent /forceload entry.
 *
 * The Planner is the job's stable anchor; a small rolling set of tickets
 * follows each real Builder and the next target as the courier travels.
 * Inactive/paused/cancelled jobs release their complete ticket sets, and
 * removing the Planner also releases them. A 20-tick reconciliation avoids
 * repeatedly touching chunk tickets every entity tick.
 */
public final class BuilderChunkTickets {
    // Deliberately NOT FLAG_PERSIST. After a server restart the first loaded
    // Planner resumes its active job and reconstructs the transient tickets.
    // Radius 2 grants entity ticking at the anchor, including real Golems.
    private static final int RADIUS=2;
    private static final int MAX_DISTANCE=4096;
    private static final TicketType BUILDER=Registry.register(
            BuiltInRegistries.TICKET_TYPE,GolemsPlus.id("active_builder"),
            new TicketType(0L,TicketType.FLAG_LOADING
                    |TicketType.FLAG_SIMULATION
                    |TicketType.FLAG_KEEP_DIMENSION_ACTIVE));

    private static final class Lease {
        final Set<ChunkPos> held=new HashSet<>();
        final Map<UUID,BlockPos> destinations=new HashMap<>();
    }
    private static final Map<ServerLevel,Map<Long,Lease>> jobs=new WeakHashMap<>();
    // Chunk tickets are identified by type and radius, not workstation.
    // Reference-count shared centers so cancelling one of two nearby jobs
    // cannot accidentally unload the other job's Builder or supply chest.
    private static final Map<ServerLevel,Map<ChunkPos,Integer>> holders=new WeakHashMap<>();
    private BuilderChunkTickets(){}
    public static void initialize(){} // Force the ticket type to register at mod initialization.

    private static Map<Long,Lease> world(ServerLevel level){
        return jobs.computeIfAbsent(level,ignored->new HashMap<>());
    }
    private static void acquire(ServerLevel level,ChunkPos center){
        Map<ChunkPos,Integer> counts=holders.computeIfAbsent(level,
                ignored->new HashMap<>());
        int owners=counts.getOrDefault(center,0);
        if(owners==0)level.getChunkSource().addTicketWithRadius(BUILDER,center,RADIUS);
        counts.put(center,owners+1);
    }
    private static void relinquish(ServerLevel level,ChunkPos center){
        Map<ChunkPos,Integer> counts=holders.get(level);
        if(counts==null)return;
        int owners=counts.getOrDefault(center,0);
        if(owners<=0)return;
        if(owners==1){
            counts.remove(center);
            level.getChunkSource().removeTicketWithRadius(BUILDER,center,RADIUS);
            if(counts.isEmpty())holders.remove(level);
        }else counts.put(center,owners-1);
    }
    private static boolean running(WorkerStationBlockEntity table){
        if(!table.isBuilder())return false;
        // Keep the actual assigned crew simulated while they return real
        // inventory and walk to an Unemployed Center after job completion.
        // Release every ticket once all UUIDs have physically checked in.
        if((table.phase()==WorkerPhase.COMPLETE
                ||table.builderRecallRequested())
                &&!table.builderWorkerIds().isEmpty())return true;
        // A linked crane must continue ticking its physical operator
        // and passengers while the construction Planner is PAUSED.
        // Otherwise the vehicle freezes with tickCount=0 in a nearby
        // non-player-simulated chunk and can never pick anyone up.
        // Job cancellation still releases the lease because serves()
        // requires the SAME non-null active order and its owner.
        if(table.activeOrder()!=null&&table.phase()==WorkerPhase.PAUSED
                &&!com.caszgamermd.golemsplus.crane.CraneService
                        .simulationPositions(table).isEmpty())return true;
        return table.activeOrder()!=null
                &&table.phase()!=WorkerPhase.PAUSED
                &&table.phase()!=WorkerPhase.COMPLETE;
    }
    private static void add(Set<ChunkPos> chunks,BlockPos center,BlockPos reference){
        if(center==null||reference==null||chunks.size()>=24)return;
        if(Math.abs((long)center.getX()-reference.getX())>MAX_DISTANCE
                ||Math.abs((long)center.getZ()-reference.getZ())>MAX_DISTANCE)return;
        chunks.add(new ChunkPos(center.getX() >> 4,center.getZ() >> 4));
    }
    private static BlockPos ahead(BlockPos from,BlockPos goal){
        long dx=(long)goal.getX()-from.getX(),dz=(long)goal.getZ()-from.getZ();
        double distance=Math.hypot(dx,dz);
        if(distance<=24)return goal;
        double fraction=32.0/distance;
        return new BlockPos(from.getX()+(int)Math.round(dx*fraction),
                from.getY(),from.getZ()+(int)Math.round(dz*fraction));
    }

    /** Called from the loaded Planner. Never scans all loaded chunks or
     * creates a Builder entity. Existing UUIDs and all their real cargo
     * remain authoritative across saves and reloads.
     */
    public static void tick(ServerLevel level,WorkerStationBlockEntity table){
        if(level.getGameTime()%20!=0)return;
        reconcile(level,table);
    }
    /** A newly activated Planner must begin simulation immediately. Waiting
     * for its first block-entity tick can deadlock when the region initially
     * has no entity-ticking ticket (also reproduced in the isolated Dome).
     * These are the SAME bounded, transient tickets used during ordinary work.
     */
    public static void prime(ServerLevel level,WorkerStationBlockEntity table){
        reconcile(level,table);
    }
    private static void reconcile(ServerLevel level,WorkerStationBlockEntity table){
        if(!running(table)){
            release(level,table.getBlockPos());
            return;
        }
        BlockPos home=table.getBlockPos();
        Lease lease=world(level).computeIfAbsent(home.asLong(),ignored->new Lease());
        Set<ChunkPos> required=new HashSet<>();
        add(required,home,home);
        if(table.hasPlacedSchematic())add(required,table.builderOrigin(),home);
        add(required,table.builderMaterialStorage(),home);
        var warehouse=table.warehouse();
        if(warehouse!=null&&level.dimension().identifier().toString()
                .equals(warehouse.dimension()))
            add(required,new BlockPos(warehouse.x(),warehouse.y(),warehouse.z()),home);
        if(table.supplyRequest()!=null&&!table.supplyPtp().equals(BlockPos.ZERO))
            add(required,table.supplyPtp(),home);

        // Persisted last-seen chunk locations rescue temporarily unloaded
        // builders; never replace or respawn one whose UUID is missing.
        for(UUID id:table.builderWorkerIds()){
            BlockPos previous=table.builderLastPosition(id);
            if(level.getEntity(id) instanceof BuilderGolem worker
                    &&id.equals(worker.getUUID())
                    &&home.equals(worker.home())
                    &&table.owner()!=null&&table.owner().equals(worker.owner())){
                previous=worker.blockPosition();
                table.rememberBuilderPosition(id,previous);
            }
            add(required,previous,home);
            BlockPos target=lease.destinations.get(id);
            add(required,target,home);
            if(previous!=null&&target!=null)
                add(required,ahead(previous,target),home);
        }
        for(BlockPos pos:com.caszgamermd.golemsplus.crane.CraneService.simulationPositions(table))
            add(required,pos,home);
        for(ChunkPos key:required)if(!lease.held.contains(key))
            acquire(level,key);
        for(ChunkPos key:Set.copyOf(lease.held))if(!required.contains(key))
            relinquish(level,key);
        lease.held.clear();
        lease.held.addAll(required);
    }

    /** A moving Builder renews its last known chunk without keeping chunks
     * permanently loaded after the Planner finishes.
     */
    public static void track(ServerLevel level,BuilderGolem worker){
        BlockPos home=worker.home();
        if(!level.hasChunkAt(home))return;
        if(!(level.getBlockEntity(home) instanceof WorkerStationBlockEntity table)
                ||!running(table)||!table.builderWorkerIds().contains(worker.getUUID())
                ||table.owner()==null||!table.owner().equals(worker.owner()))return;
        table.rememberBuilderPosition(worker.getUUID(),worker.blockPosition());
    }

    /** Prime a destination BEFORE the navigation code checks hasChunkAt().
     * A remote warehouse PTP is loaded while the Builder takes short,
     * collision-validated route legs to reach it.
     */
    public static void destination(ServerLevel level,BuilderGolem worker,BlockPos target){
        BlockPos home=worker.home();
        if(target==null||!level.hasChunkAt(home))return;
        if(!(level.getBlockEntity(home) instanceof WorkerStationBlockEntity table)
                ||!running(table)||!table.builderWorkerIds().contains(worker.getUUID())
                ||table.owner()==null||!table.owner().equals(worker.owner()))return;
        Lease lease=world(level).computeIfAbsent(home.asLong(),ignored->new Lease());
        lease.destinations.put(worker.getUUID(),target.immutable());
        track(level,worker);
        // Reconcile immediately for a new goal; repeating the same goal
        // costs no ticket operations on the next station tick.
        if(level.getGameTime()%20==0)tick(level,table);
    }

    /** Releases only this mod's own tickets, not user /forceload chunks. */
    public static void release(ServerLevel level,BlockPos home){
        Map<Long,Lease> entries=jobs.get(level);
        if(entries==null)return;
        Lease lease=entries.remove(home.asLong());
        if(lease==null)return;
        for(ChunkPos key:lease.held)
            relinquish(level,key);
        if(entries.isEmpty())jobs.remove(level);
    }
}

