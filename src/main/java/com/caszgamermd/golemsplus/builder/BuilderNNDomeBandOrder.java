package com.caszgamermd.golemsplus.builder;

import java.util.*;

/** Exact Dome pilot: preserve complex dependency bands and reorder only
 * known dry structural tasks in three-height bands above the foundation. */
public final class BuilderNNDomeBandOrder {
    private static final Set<String> DRY=Set.of("minecraft:cobblestone","minecraft:mossy_cobblestone",
            "minecraft:diorite","minecraft:polished_diorite","minecraft:spruce_planks",
            "minecraft:pink_stained_glass","minecraft:magenta_stained_glass","minecraft:purple_stained_glass",
            "minecraft:sea_lantern","minecraft:smooth_stone_slab","minecraft:mossy_cobblestone_stairs",
            "minecraft:polished_diorite_stairs","minecraft:cobblestone_slab","minecraft:mossy_cobblestone_slab",
            "minecraft:diorite_stairs","minecraft:cobblestone_stairs","minecraft:polished_diorite_slab",
            "minecraft:mud_brick_stairs","minecraft:stone_bricks","minecraft:mud_bricks","minecraft:moss_block",
            "minecraft:stripped_spruce_log","minecraft:cobblestone_wall","minecraft:mossy_cobblestone_wall",
            "minecraft:diorite_wall","minecraft:sandstone_wall","minecraft:spruce_fence","minecraft:birch_fence",
            "minecraft:purple_stained_glass_pane");
    private BuilderNNDomeBandOrder(){}
    private static int band(int y,int base){return y<base+6?-1:Math.floorDiv(y-base-6,3);}
    private static int coord(BuilderConstructionPlan.Step s,String axis){return axis.equals("X")?s.x():s.z();}
    private static boolean simple(BuilderConstructionPlan.Step s){
        return !s.ignorePlant()&&s.companion()==null&&s.requiredItem()!=null&&BuilderNNStripOrder.phase(s)==0
                &&DRY.contains(s.encodedState().split("\\[",2)[0]);
    }
    public record Plan(List<Integer> order,List<Integer> bands,Set<Integer> verticalBands,int verticalTasks){
        public boolean vertical(int i){return verticalBands.contains(bands.get(i));}
        public boolean sameVerticalBand(int a,int b){return vertical(a)&&vertical(b)&&bands.get(a).equals(bands.get(b));}
        public boolean sameVerticalGroup(List<BuilderConstructionPlan.Step> steps,int a,int b,String axis){
            return sameVerticalBand(a,b)&&steps.get(a).y()==steps.get(b).y()
                    &&coord(steps.get(a),axis)==coord(steps.get(b),axis);
        }
    }
    public static Plan compile(List<BuilderConstructionPlan.Step> steps,String axis){
        if(steps.isEmpty()||steps.size()>4096||!Set.of("X","Z").contains(axis))
            throw new IllegalArgumentException("Dome pilot plan");
        int base=steps.stream().mapToInt(BuilderConstructionPlan.Step::y).min().orElseThrow();
        var protectedBands=new HashSet<Integer>();var bands=new ArrayList<Integer>();
        var allBands=new HashSet<Integer>();var order=new ArrayList<Integer>();
        for(int i=0;i<steps.size();i++){
            var s=steps.get(i);int b=band(s.y(),base);
            bands.add(b);order.add(i);allBands.add(b);
            if(!simple(s)){
                for(int y=s.y()-1;y<=s.y()+1;y++)protectedBands.add(band(y,base));
                if(s.companion()!=null)protectedBands.add(band(s.companion().y(),base));
            }
        }
        allBands.remove(-1);allBands.removeAll(protectedBands);
        order.sort(Comparator.comparingInt((Integer i)->BuilderDeferredState.verification(steps.get(i).encodedState())?1:0)
                .thenComparingInt(bands::get)
                .thenComparingInt(i->allBands.contains(bands.get(i))?coord(steps.get(i),axis):0)
                .thenComparingInt(i->steps.get(i).y())
                .thenComparingInt(i->BuilderWaterPass.isWaterStep(steps.get(i).encodedState())?1:0)
                .thenComparingInt(Integer::intValue));
        int count=(int)order.stream().filter(i->allBands.contains(bands.get(i))).count();
        return new Plan(List.copyOf(order),List.copyOf(bands),Set.copyOf(allBands),count);
    }
}
