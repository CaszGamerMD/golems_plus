package com.caszgamermd.golemsplus.builder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;

/** Read-only strategy candidates; never authorize a live claim or issue stock.
 * Geometry estimates are not route lengths or proofs of physical safety. */
public final class BuilderStrategyPreview {
    public enum Axis { Y, X, Z }
    public record Batch(List<Integer> indices,Map<String,Integer> materials){}
    public record Candidate(Axis axis,int steps,long coordinateTravel,int longestJump,
            int lowerSupportLate,int crossZoneLowerSupport,int surroundedCells,
            int batches10,int batches32,int batches64,Batch current,Batch next){}
    private BuilderStrategyPreview(){}

    static BlockPos pos(BuilderConstructionPlan.Step s){return new BlockPos(s.x(),s.y(),s.z());}
    static int distance(BlockPos a,BlockPos b){
        return Math.abs(a.getX()-b.getX())+Math.abs(a.getY()-b.getY())+Math.abs(a.getZ()-b.getZ());
    }
    private static Comparator<Integer> order(List<BuilderConstructionPlan.Step> steps,Axis axis){
        return (left,right)->{
            var a=steps.get(left);var b=steps.get(right);
            int c;
            if(axis==Axis.Y){
                c=Integer.compare(a.y(),b.y());if(c!=0)return c;
                c=Boolean.compare(BuilderWaterPass.isWaterStep(a.encodedState()),
                        BuilderWaterPass.isWaterStep(b.encodedState()));if(c!=0)return c;
                c=Integer.compare(a.x(),b.x());if(c!=0)return c;
                c=((a.x()&1)==0?1:-1)*Integer.compare(a.z(),b.z());
            }else if(axis==Axis.X){
                c=Integer.compare(a.x(),b.x());if(c!=0)return c;
                c=((a.x()&1)==0?1:-1)*Integer.compare(a.z(),b.z());if(c!=0)return c;
                c=Integer.compare(a.y(),b.y());
            }else{
                c=Integer.compare(a.z(),b.z());if(c!=0)return c;
                c=((a.z()&1)==0?1:-1)*Integer.compare(a.x(),b.x());if(c!=0)return c;
                c=Integer.compare(a.y(),b.y());
            }
            if(c!=0)return c;
            // Keep same-cell dry placement before waterlogging.
            c=Boolean.compare(BuilderWaterPass.isWaterStep(a.encodedState()),
                    BuilderWaterPass.isWaterStep(b.encodedState()));
            return c!=0?c:Integer.compare(left,right);
        };
    }

    /** Exact original indexed tasks, not a reservation. Reusable items
     * are requested once per preview batch, not once per target cell. */
    public static Batch batch(List<BuilderConstructionPlan.Step> steps,List<Integer> ordered,
            int offset,int limit){
        if(offset<0||limit<1||offset>ordered.size())throw new IllegalArgumentException("batch");
        var indices=new ArrayList<Integer>();
        var materials=new LinkedHashMap<String,Integer>();
        for(int j=offset;j<Math.min(ordered.size(),(long)offset+limit);j++){
            int i=ordered.get(j);var s=steps.get(i);
            indices.add(i);
            String item=s.requiredItem();
            if(item==null||s.ignorePlant())continue;
            if(BuilderBlockResolver.reusableSupply(item))materials.put(item,1);
            else materials.merge(item,1,Integer::sum);
        }
        return new Batch(List.copyOf(indices),Map.copyOf(materials));
    }

    public static Candidate analyze(List<BuilderConstructionPlan.Step> steps,List<Integer> zone,
            Axis axis,int previewSize){
        if(previewSize<1||previewSize>256)throw new IllegalArgumentException("preview size");
        if(new HashSet<>(zone).size()!=zone.size())throw new IllegalArgumentException("duplicate zone task");
        var ordered=new ArrayList<Integer>(zone);
        ordered.sort(order(steps,axis));
        var physical=new HashMap<BlockPos,Integer>();
        for(int i=0;i<steps.size();i++){
            var s=steps.get(i);
            if(s.ignorePlant()||s.requiredItem()==null||BuilderWaterPass.isWaterStep(s.encodedState())
                    ||BuilderDeferredState.verification(s.encodedState()))continue;
            physical.put(pos(s),i);
            if(s.companion()!=null)physical.put(new BlockPos(s.companion().x(),s.companion().y(),s.companion().z()),i);
        }
        var own=new HashSet<>(zone);
        var built=new HashSet<BlockPos>();
        long travel=0;
        int maxJump=0,late=0,cross=0,surrounded=0;
        BlockPos prev=null;
        for(int idx:ordered){
            var s=steps.get(idx);BlockPos at=pos(s);
            if(prev!=null){int jump=distance(prev,at);travel+=jump;maxJump=Math.max(maxJump,jump);}
            prev=at;
            if(s.ignorePlant()||s.requiredItem()==null||BuilderWaterPass.isWaterStep(s.encodedState())
                    ||BuilderDeferredState.verification(s.encodedState()))continue;
            Integer below=physical.get(at.below());
            if(below!=null&&below!=idx){
                if(!own.contains(below))cross++;
                else if(!built.contains(at.below()))late++;
            }
            if(built.contains(at.north())&&built.contains(at.south())
                    &&built.contains(at.east())&&built.contains(at.west()))surrounded++;
            built.add(at);
            if(s.companion()!=null)built.add(new BlockPos(s.companion().x(),s.companion().y(),s.companion().z()));
        }
        int n=ordered.size();
        return new Candidate(axis,n,travel,maxJump,late,cross,surrounded,
                (n+9)/10,(n+31)/32,(n+63)/64,
                batch(steps,ordered,0,previewSize),
                batch(steps,ordered,Math.min(n,previewSize),previewSize));
    }
}
