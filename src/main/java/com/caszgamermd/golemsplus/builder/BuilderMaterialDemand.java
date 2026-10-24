package com.caszgamermd.golemsplus.builder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small forward-looking batches avoid reserving an entire large structure. */
public final class BuilderMaterialDemand {
    private BuilderMaterialDemand(){}
    /** Reserve for the next three vertical Y-levels, bottom-up.
     * For each needed material request a complete stack whenever possible;
     * if fewer remain, request exactly the partial stack. Staged physical
     * stock is subtracted first, so deliveries never double count.
     */
    public static Map<String,Integer> batch(List<BuilderConstructionPlan.Step> steps,
            int cursor,Map<String,Integer> stocked){
        if(cursor<0||cursor>=steps.size())return Map.of();
        int firstY=steps.get(cursor).y();
        Map<String,Integer> needed=new LinkedHashMap<>();
        for(int i=cursor;i<steps.size();i++){
            var step=steps.get(i);
            if(step.y()>=firstY+3)break;
            String item=step.requiredItem();
            if(item==null||item.isBlank())continue;
            if(!needed.containsKey(item)&&needed.size()>=16)continue;
            needed.merge(item,1,Integer::sum);
        }
        Map<String,Integer> entireJob=new LinkedHashMap<>();
        for(int i=cursor;i<steps.size();i++){
            String id=steps.get(i).requiredItem();
            if(needed.containsKey(id))
                entireJob.merge(id,1,Integer::sum);
        }
        needed.replaceAll((k,v)->{
            int shortage=Math.max(0,entireJob.getOrDefault(k,0)
                    -Math.max(0,stocked.getOrDefault(k,0)));
            return shortage==0?0:Math.min(64,shortage);
        });
        needed.values().removeIf(v->v<=0);
        return Map.copyOf(needed);
    }
}
