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
        return compile(steps,footprint,activeWorkers,false);
    }
    public static BuilderZoneWorkPlan compile(
            List<BuilderConstructionPlan.Step> steps,
            BuilderZones.Layout footprint,List<UUID> activeWorkers,
            boolean downward){
        return compile(steps,footprint,activeWorkers,downward,null);
    }
    public static BuilderZoneWorkPlan compile(
            List<BuilderConstructionPlan.Step> steps,
            BuilderZones.Layout footprint,List<UUID> activeWorkers,
            boolean downward,List<Integer> members){
        return compile(steps,footprint,activeWorkers,downward,members,"X",1);
    }
    public static BuilderZoneWorkPlan compile(List<BuilderConstructionPlan.Step> steps,
            BuilderZones.Layout footprint,List<UUID> activeWorkers,boolean downward,
            List<Integer> members,String sweep,int direction){
        if(!List.of("X","Z").contains(sweep)||Math.abs(direction)!=1)throw new IllegalArgumentException("sweep");
        if(steps==null||footprint==null||activeWorkers==null)
            throw new IllegalArgumentException("Builder plan missing");
        if(activeWorkers.isEmpty()||activeWorkers.size()>8)
            throw new IllegalArgumentException("Builder plan needs 1 to 8 workers");
        if(activeWorkers.stream().distinct().count()!=activeWorkers.size())
            throw new IllegalArgumentException("Duplicate builder in crew");
        var data=new LinkedHashMap<UUID,List<Integer>>();
        for(UUID id:activeWorkers)data.put(id,new ArrayList<>());
        int count=members==null?steps.size():members.size();
        for(int offset=0;offset<count;offset++){
            int index=members==null?offset:members.get(offset);
            var step=steps.get(index);
            int owner=footprint.zone(step.x(),step.z(),activeWorkers.size());
            data.get(activeWorkers.get(owner)).add(index);
        }
        // Minimize travel within each independent Y layer. Build strips
        // alternate Z direction as X advances instead of bouncing to
        // arbitrary coordinates from the original litematic palette order.
        // Steps retain their original indices for world/save progress.
        for(var zone:data.values())zone.sort((left,right)->{
            var a=steps.get(left);
            var b=steps.get(right);
            int height=downward?Integer.compare(b.y(),a.y()):
                    Integer.compare(a.y(),b.y());
            if(height!=0)return height;
            int pa=sweep.equals("X")?a.x():a.z(),pb=sweep.equals("X")?b.x():b.z();
            int strip=direction*Integer.compare(pa,pb);
            if(strip!=0)return strip;
            int sa=sweep.equals("X")?a.z():a.x(),sb=sweep.equals("X")?b.z():b.x();
            return ((pa&1)==0?1:-1)*direction*Integer.compare(sa,sb);
        });
        return new BuilderZoneWorkPlan(activeWorkers,data);
    }
}
