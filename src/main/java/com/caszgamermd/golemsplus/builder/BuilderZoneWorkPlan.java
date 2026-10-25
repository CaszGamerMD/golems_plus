package com.caszgamermd.golemsplus.builder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Compiles the COMPLETE placed schematic into ordered, disjoint per-worker
 * tasks ONCE. Runtime work orders are only material-staging windows into
 * these plans, not repeated global assignment decisions.
 *
 * The original construction steps already have their required lower-first
 * Y order; keep it, including same-layer supporting cells. Each worker's
 * subsequent route, stock and placement state is independent.
 */
public final class BuilderZoneWorkPlan {
    private final Map<UUID,List<Integer>> byWorker;
    private final List<UUID> roster;

    private BuilderZoneWorkPlan(List<UUID> roster,Map<UUID,List<Integer>> data){
        this.roster=List.copyOf(roster);
        var immutable=new LinkedHashMap<UUID,List<Integer>>();
        data.forEach((id,list)->immutable.put(id,List.copyOf(list)));
        this.byWorker=Map.copyOf(immutable);
    }
    public List<UUID> roster(){return roster;}
    public List<Integer> steps(UUID worker){
        return byWorker.getOrDefault(worker,List.of());
    }
    public static BuilderZoneWorkPlan compile(
            List<BuilderConstructionPlan.Step> steps,
            BuilderZones.Layout footprint,List<UUID> activeWorkers){
        if(steps==null||footprint==null||activeWorkers==null)
            throw new IllegalArgumentException("Builder plan missing");
        if(activeWorkers.isEmpty()||activeWorkers.size()>8)
            throw new IllegalArgumentException("Builder plan needs 1 to 8 workers");
        if(activeWorkers.stream().distinct().count()!=activeWorkers.size())
            throw new IllegalArgumentException("Duplicate builder in crew");
        var data=new LinkedHashMap<UUID,List<Integer>>();
        for(UUID id:activeWorkers)data.put(id,new ArrayList<>());
        for(int index=0;index<steps.size();index++){
            var step=steps.get(index);
            int owner=footprint.zone(step.x(),step.z(),activeWorkers.size());
            data.get(activeWorkers.get(owner)).add(index);
        }
        return new BuilderZoneWorkPlan(activeWorkers,data);
    }
}
