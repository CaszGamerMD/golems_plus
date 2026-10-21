package com.caszgamermd.golemsplus.builder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small forward-looking batches avoid reserving an entire large structure. */
public final class BuilderMaterialDemand {
    private BuilderMaterialDemand(){}
    public static Map<String,Integer> batch(List<BuilderConstructionPlan.Step> steps,
            int cursor,Map<String,Integer> stocked){
        if(cursor<0||cursor>=steps.size())return Map.of();
        Map<String,Integer> wanted=new LinkedHashMap<>();
        for(int i=cursor;i<steps.size()&&i<cursor+256;i++){
            String key=steps.get(i).requiredItem();
            if(key==null||key.isBlank())continue;
            if(!wanted.containsKey(key)&&wanted.size()>=16)break;
            wanted.compute(key,(k,v)->Math.min(64,(v==null?0:v)+1));
        }
        wanted.replaceAll((k,v)->Math.max(0,v-Math.max(0,stocked.getOrDefault(k,0))));
        wanted.values().removeIf(v->v==0);
        return Map.copyOf(wanted);
    }
}
