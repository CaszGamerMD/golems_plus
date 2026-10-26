package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One bounded, read-only world comparison at the BEGINNING of a construction
 * job, ahead of the Builder crew's normal clearing/placement cycle.
 *
 * Crucial invariants:
 *   - matching existing blocks are NEVER destroyed;
 *   - an air position is eligible to skip clearing but is NEVER marked
 *     "built" merely because the blueprint eventually requires a block;
 *   - wrong blocks still go through the ordinary protected, tool-funded
 *     clearing service;
 *   - unloaded chunks are NOT synchronously loaded or rewritten;
 *   - a restart/reload safely repeats checks without duplicating items.
 *
 * Already matching construction steps are independently recognized by
 * BuilderBuildService when assigned, without walking to the block.
 */
public final class BuilderSiteReconciliation {
    private static final int MAX_PER_TICK=192;
    private static final Map<WorkerStationBlockEntity,Scan> scans=
            new WeakHashMap<>();
    private static final class Scan {
        final UUID job;
        final int size;
        int next,alreadyMatching,empty,obstructed,unloaded;
        Scan(UUID job,int size){this.job=job;this.size=size;}
    }
    private BuilderSiteReconciliation(){}

    static boolean safeToKeep(BlockState actual,BlockState expected){
        return BuilderPlacementState.matches(actual,expected);
    }

    static boolean noClearingRequired(ServerLevel world,
            BuilderSiteClearance.Target target,
            BuildSite.Rotation rotation,BuildSite.Mirror mirror,
            boolean ignorePlants){
        BlockState actual=world.getBlockState(target.pos());
        if(actual.isAir())return true;
        if(ignorePlants&&BuilderVegetation.ignoreSchematicState(target.state()))
            return false; // even a matching crop must be cleared when ignored
        if(target.state().equals("minecraft:air"))return false;
        var state=BuilderBlockResolver.resolve(target.state(),rotation,mirror);
        return state!=null&&safeToKeep(actual,state.state());
    }

    /** Called from the Planner dispatch before Builders begin clearing.
     * A large Dome is scanned incrementally, not on the main server thread
     * in one 41,000-position spike.
     */
    public static boolean tick(ServerLevel world,
            WorkerStationBlockEntity table,List<BuilderSiteClearance.Target> area){
        UUID job=table.activeOrder();
        if(job==null)return true;
        Scan scan=scans.get(table);
        if(scan==null||!scan.job.equals(job)||scan.size!=area.size()){
            scan=new Scan(job,area.size());
            scans.put(table,scan);
        }
        if(scan.next>=area.size())return true;
        var rotation=BuildSite.Rotation.values()[table.builderRotation()/90];
        var mirror=BuildSite.Mirror.values()[table.builderMirror()];
        int limit=Math.min(area.size(),scan.next+MAX_PER_TICK);
        while(scan.next<limit){
            int index=scan.next++;
            var target=area.get(index);
            BlockPos p=target.pos();
            // Do not stall the entire Dome on a far corner whose chunk
            // isn't ticking yet. Ordinary Builder clearing acquires the
            // corresponding bounded chunk ticket when required.
            if(!world.hasChunkAt(p)){scan.unloaded++;continue;}
            BlockState actual=world.getBlockState(p);
            if(noClearingRequired(world,target,rotation,mirror,
                    table.builderIgnorePlants())){
                if(actual.isAir())scan.empty++;else scan.alreadyMatching++;
                table.preverifyBuilderClear(index);
            }else scan.obstructed++;
        }
        if(scan.next>=area.size()){
            System.out.println("[BUILDER PREFLIGHT] matched="+scan.alreadyMatching
                    +" already-air="+scan.empty
                    +" incorrect="+scan.obstructed
                    +" unloaded="+scan.unloaded
                    +" positions="+area.size()
                    +" job="+job);
            return true;
        }
        return false;
    }
    public static String status(WorkerStationBlockEntity table){
        Scan scan=scans.get(table);
        if(scan==null||!java.util.Objects.equals(scan.job,table.activeOrder()))
            return "NOT SCANNED";
        return scan.next+"/"+scan.size+" scanned, "
                +scan.alreadyMatching+" correct blocks retained, "
                +scan.obstructed+" obstructions found";
    }
}
