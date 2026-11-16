package com.caszgamermd.golemsplus.builder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Test-only indexed ordering for the stone pilot and the verified Dragon fixture.
 * No general attachment/physics support is inferred from a sortable coordinate. */
public final class BuilderNNStripOrder {
    private static final Set<String> DRAGON_DRY=Set.of("minecraft:amethyst_block",
            "minecraft:purple_stained_glass","minecraft:sea_lantern","minecraft:tuff_brick_slab",
            "minecraft:tuff_brick_stairs","minecraft:tuff_brick_wall","minecraft:tuff_bricks");
    private BuilderNNStripOrder(){}
    static int phase(BuilderConstructionPlan.Step s){
        if(BuilderDeferredState.verification(s.encodedState()))return 2;
        return BuilderWaterPass.isWaterStep(s.encodedState())?1:0;
    }
    private static int strip(BuilderConstructionPlan.Step s,String axis){
        return axis.equals("X")?s.x():s.z();
    }
    static boolean allowed(BuilderConstructionPlan.Step s,boolean dragon){
        if(s.ignorePlant()||s.companion()!=null)return false;
        if(!dragon)return "minecraft:stone".equals(s.encodedState())&&"minecraft:stone".equals(s.requiredItem());
        String state=s.encodedState();
        if(BuilderDeferredState.flowVerification(state))return state.startsWith("golems_plus:flow_verify|minecraft:water[");
        if(BuilderWaterPass.isWaterlogPass(state))state=BuilderWaterPass.originalState(state);
        String id=state.split("\\[",2)[0];
        if("minecraft:water".equals(id))return true;
        return DRAGON_DRY.contains(id)&&s.requiredItem()!=null;
    }
    public static List<Integer> compile(List<BuilderConstructionPlan.Step> steps,String axis,boolean dragon){
        if(!(Set.of("X","Z").contains(axis)||dragon&&"Y".equals(axis))||steps.isEmpty()||steps.size()>(dragon?4096:128)
                ||steps.stream().anyMatch(s->!allowed(s,dragon)))
            throw new IllegalArgumentException("Unsupported NN strip pilot plan");
        var order=new ArrayList<Integer>();
        for(int i=0;i<steps.size();i++)order.add(i);
        order.sort(Comparator.comparingInt((Integer i)->phase(steps.get(i)))
                .thenComparingInt(i->phase(steps.get(i))==0&&!"Y".equals(axis)?strip(steps.get(i),axis):0)
                .thenComparingInt(i->steps.get(i).y()).thenComparingInt(Integer::intValue));
        return List.copyOf(order);
    }
    // Build six complete horizontal layers before opening three-layer
    // vertical bands. No strip can rise an entire dragon above its neighbors.
    public static final int FOUNDATION_LAYERS=6,BAND_HEIGHT=3;
    private static int band(BuilderConstructionPlan.Step s,int baseY){
        return s.y()<baseY+FOUNDATION_LAYERS?-1:
                Math.floorDiv(s.y()-baseY-FOUNDATION_LAYERS,BAND_HEIGHT);
    }
    public static List<Integer> compileBanded(List<BuilderConstructionPlan.Step> steps,String axis,int baseY){
        var order=new ArrayList<>(compile(steps,axis,true));
        order.sort(Comparator.comparingInt((Integer i)->phase(steps.get(i)))
                .thenComparingInt(i->phase(steps.get(i))==0?band(steps.get(i),baseY):0)
                .thenComparingInt(i->phase(steps.get(i))==0&&band(steps.get(i),baseY)>=0
                        ?strip(steps.get(i),axis):0)
                .thenComparingInt(i->steps.get(i).y()).thenComparingInt(Integer::intValue));
        return List.copyOf(order);
    }
    public static boolean sameBandedGroup(BuilderConstructionPlan.Step a,BuilderConstructionPlan.Step b,String axis,int baseY){
        return sameGroup(a,b,band(a,baseY)<0?"Y":axis);
    }
    /** Bounded adjacent dry work is an access hypothesis, not a route proof. */
    public static List<Integer> adjacentRescue(List<BuilderConstructionPlan.Step> steps,
            int blocked,String axis,java.util.function.IntPredicate unfinished,int limit){
        if(blocked<0||blocked>=steps.size()||limit<1||limit>64
                ||!Set.of("X","Z").contains(axis))throw new IllegalArgumentException("rescue window");
        var target=steps.get(blocked);
        if(phase(target)!=0)return List.of();
        int at=strip(target,axis);
        var candidates=new ArrayList<Integer>();
        for(int i=0;i<steps.size();i++){
            var step=steps.get(i);
            if(unfinished.test(i)&&phase(step)==0&&step.y()<=target.y()
                    &&Math.abs(strip(step,axis)-at)==1)candidates.add(i);
        }
        candidates.sort(Comparator.comparingInt((Integer i)->steps.get(i).y())
                .thenComparingInt(i->strip(steps.get(i),axis)).thenComparingInt(Integer::intValue));
        return List.copyOf(candidates.subList(0,Math.min(limit,candidates.size())));
    }
    public static boolean sameGroup(BuilderConstructionPlan.Step a,BuilderConstructionPlan.Step b,String axis){
        return phase(a)==phase(b)&&a.y()==b.y()
                &&(phase(a)!=0||"Y".equals(axis)||strip(a,axis)==strip(b,axis));
    }
}
