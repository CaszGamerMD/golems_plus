package com.caszgamermd.golemsplus.builder;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import net.minecraft.core.BlockPos;

/** Explicit upper-support dependencies, not a general permission to build
 * future layers. Keeps persisted step indices and item accounting intact. */
public final class BuilderCeilingDependencies {
    private BuilderCeilingDependencies(){}
    public static Map<Integer,Integer> compile(List<BuilderConstructionPlan.Step> steps){
        Map<BlockPos,Integer> dry=new HashMap<>();
        for(int i=0;i<steps.size();i++){
            var s=steps.get(i);
            if(!s.ignorePlant()&&s.requiredItem()!=null
                    &&!BuilderWaterPass.isWaterStep(s.encodedState())
                    &&!BuilderDeferredState.verification(s.encodedState()))
                dry.put(new BlockPos(s.x(),s.y(),s.z()),i);
        }
        Map<Integer,Integer> result=new HashMap<>();
        for(int i=0;i<steps.size();i++){
            var s=steps.get(i);
            String state=s.encodedState();
            if(s.ignorePlant()||s.requiredItem()==null||!ceilingAttachment(state))continue;
            Integer support=dry.get(new BlockPos(s.x(),s.y()+1,s.z()));
            if(support!=null&&support>i)result.put(support,i);
        }
        return Map.copyOf(result);
    }
    static boolean ceilingAttachment(String state){
        if(state==null)return false;
        int n=state.indexOf('[');
        if(n<0)return false;
        String id=state.substring(0,n);
        if(!id.equals("minecraft:lever")&&!id.endsWith("_button"))return false;
        for(String p:state.substring(n+1,state.length()-1).split(","))
            if(p.equals("face=ceiling"))return true;
        return false;
    }
}
