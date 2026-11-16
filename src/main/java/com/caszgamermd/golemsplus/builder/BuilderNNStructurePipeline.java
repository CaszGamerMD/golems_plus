package com.caszgamermd.golemsplus.builder;
import java.util.*;
import net.minecraft.core.BlockPos;
/** Immutable geometry-only preparation; no world access. */
public final class BuilderNNStructurePipeline {
    public record Prepared(BuilderZoneWorkPlan tasks,String sweep,int direction,long cost,
            BuilderNNStrategyAdvisor.Choice candidate){}
    private BuilderNNStructurePipeline(){}
    private static long cost(List<BuilderConstructionPlan.Step> steps,BuilderZoneWorkPlan tasks,BlockPos depot){
        long total=0;
        for(UUID id:tasks.roster()){
            int x=depot.getX(),y=depot.getY(),z=depot.getZ();
            for(int i:tasks.steps(id)){
                var s=steps.get(i);if(s.ignorePlant())continue;
                total+=Math.abs((long)x-s.x())+Math.abs((long)y-s.y())+Math.abs((long)z-s.z());
                x=s.x();y=s.y();z=s.z();
            }
            total+=Math.abs((long)x-depot.getX())+Math.abs((long)y-depot.getY())+Math.abs((long)z-depot.getZ());
        }
        return total;
    }
    public static Prepared prepare(List<BuilderConstructionPlan.Step> steps,List<Integer> members,
            List<UUID> roster,BlockPos depot){
        var local=members.stream().map(steps::get).toList();
        var footprint=BuilderZones.footprint(local);
        var candidate=BuilderNNStrategyAdvisor.choose(local,depot,true);
        Prepared best=null;
        for(String sweep:List.of("X","Z"))for(int direction:new int[]{1,-1}){
            var tasks=BuilderZoneWorkPlan.compile(steps,footprint,roster,false,members,sweep,direction);
            long score=cost(steps,tasks,depot);
            if(best==null||score<best.cost())best=new Prepared(tasks,sweep,direction,score,candidate);
        }
        return best;
    }
}
