package com.caszgamermd.golemsplus.builder;

import java.util.*;
import net.minecraft.core.BlockPos;

/** Conservative first live advisor. Scores are geometry estimates, not
 * measured routes or learned probabilities. No world mutation. */
public final class BuilderNNStrategyAdvisor {
    private static final Set<String> SOLID=Set.of("minecraft:stone","minecraft:cobblestone",
            "minecraft:stone_bricks","minecraft:tuff_bricks","minecraft:deepslate_bricks",
            "minecraft:smooth_stone","minecraft:bricks");
    public record Choice(String axis,int direction,String reason,Map<String,Long> scores){}
    private BuilderNNStrategyAdvisor(){}
    private static Choice y(String why,Map<String,Long> scores){
        return new Choice("Y",1,why,Map.copyOf(scores));
    }
    private static int coord(BuilderConstructionPlan.Step s,String axis){
        return axis.equals("X")?s.x():s.z();
    }
    private static int band(BuilderConstructionPlan.Step s,int base){
        return s.y()<base+BuilderNNStripOrder.FOUNDATION_LAYERS?-1:
                Math.floorDiv(s.y()-base-BuilderNNStripOrder.FOUNDATION_LAYERS,BuilderNNStripOrder.BAND_HEIGHT);
    }
    public static List<Integer> order(List<BuilderConstructionPlan.Step> steps,String axis,int direction){
        if(!Set.of("X","Y","Z").contains(axis)||Math.abs(direction)!=1)throw new IllegalArgumentException("axis");
        int base=steps.stream().mapToInt(BuilderConstructionPlan.Step::y).min().orElse(0);
        var out=new ArrayList<Integer>();
        for(int i=0;i<steps.size();i++)out.add(i);
        out.sort(Comparator.comparingInt((Integer i)->axis.equals("Y")?0:band(steps.get(i),base))
                .thenComparingInt(i->axis.equals("Y")||band(steps.get(i),base)<0?0:direction*coord(steps.get(i),axis))
                .thenComparingInt(i->steps.get(i).y()).thenComparingInt(Integer::intValue));
        return List.copyOf(out);
    }
    private static boolean same(BuilderConstructionPlan.Step a,BuilderConstructionPlan.Step b,String axis,int base){
        return a.y()==b.y()&&(axis.equals("Y")||band(a,base)<0||coord(a,axis)==coord(b,axis));
    }
    private static BlockPos pos(BuilderConstructionPlan.Step s){return new BlockPos(s.x(),s.y(),s.z());}
    private static long distance(BlockPos a,BlockPos b){
        return Math.abs((long)a.getX()-b.getX())+Math.abs((long)a.getY()-b.getY())+Math.abs((long)a.getZ()-b.getZ());
    }
    /** Straight-wall groups have two endpoints. Walk the closer endpoint
     * first, then the other; include transition and return to the depot.
     * This is a lower-bound-like travel proxy, not actual movement. */
    static long score(List<BuilderConstructionPlan.Step> steps,String axis,int direction,BlockPos depot,String wallAxis){
        var order=order(steps,axis,direction);
        int base=steps.stream().mapToInt(BuilderConstructionPlan.Step::y).min().orElse(0);
        BlockPos prev=depot;long cost=0;
        for(int i=0;i<order.size();){
            var start=steps.get(order.get(i));var lo=start;var hi=start;
            int j=i+1;
            while(j<order.size()&&same(start,steps.get(order.get(j)),axis,base)){
                var s=steps.get(order.get(j++));
                if(coord(s,wallAxis)<coord(lo,wallAxis))lo=s;
                if(coord(s,wallAxis)>coord(hi,wallAxis))hi=s;
            }
            BlockPos a=pos(lo),b=pos(hi);
            cost+=Math.min(distance(prev,a),distance(prev,b))+distance(a,b)+2;
            if(distance(prev,a)<=distance(prev,b))prev=b;else prev=a;
            i=j;
        }
        return cost+distance(prev,depot);
    }
    public static Choice choose(List<BuilderConstructionPlan.Step> steps,BlockPos depot,boolean eligible){
        if(!eligible)return y("crew_supply_or_existing_job",Map.of());
        if(steps.size()<16||steps.size()>4096)return y("outside_validated_size",Map.of());
        // No arbitrary states, fluids, attachments, companions, or ignored
        // actions may enter an alternate active-phase gate.
        if(steps.stream().anyMatch(s->s.ignorePlant()||s.companion()!=null
                ||!SOLID.contains(s.encodedState())||!s.encodedState().equals(s.requiredItem())))
            return y("complex_block_dependencies",Map.of());
        int minX=steps.stream().mapToInt(BuilderConstructionPlan.Step::x).min().orElseThrow();
        int maxX=steps.stream().mapToInt(BuilderConstructionPlan.Step::x).max().orElseThrow();
        int minZ=steps.stream().mapToInt(BuilderConstructionPlan.Step::z).min().orElseThrow();
        int maxZ=steps.stream().mapToInt(BuilderConstructionPlan.Step::z).max().orElseThrow();
        int minY=steps.stream().mapToInt(BuilderConstructionPlan.Step::y).min().orElseThrow();
        String axis=minZ==maxZ&&maxX-minX>=11?"X":minX==maxX&&maxZ-minZ>=11?"Z":"Y";
        if(axis.equals("Y"))return y("not_a_straight_open_wall",Map.of());
        var cells=new HashSet<BlockPos>();for(var s:steps)cells.add(pos(s));
        if(cells.size()!=steps.size())return y("duplicate_cells",Map.of());
        // Every column must have permanent support from the bottom and
        // the six-layer connected foundation; no overhangs or cavities.
        for(var s:steps)if(s.y()>minY&&!cells.contains(pos(s).below()))
            return y("disconnected_vertical_support",Map.of());
        for(int k=axis.equals("X")?minX:minZ;k<=(axis.equals("X")?maxX:maxZ);k++)
            for(int y=minY;y<minY+BuilderNNStripOrder.FOUNDATION_LAYERS;y++)
                if(!cells.contains(new BlockPos(axis.equals("X")?k:minX,y,axis.equals("Z")?k:minZ)))
                    return y("incomplete_connected_foundation",Map.of());
        long horizontal=score(steps,"Y",1,depot,axis),
                plus=score(steps,axis,1,depot,axis),minus=score(steps,axis,-1,depot,axis);
        var scores=new LinkedHashMap<String,Long>();scores.put("Y",horizontal);scores.put(axis+"+",plus);scores.put(axis+"-",minus);
        long best=Math.min(plus,minus);
        // Prefer the reliable baseline on ties or modest improvements.
        if(horizontal-best<12||best*100>horizontal*85)return y("vertical_gain_below_margin",scores);
        int direction=plus<=minus?1:-1;
        return new Choice(axis,direction,"open_wall_travel_proxy_gain",Map.copyOf(scores));
    }
}
