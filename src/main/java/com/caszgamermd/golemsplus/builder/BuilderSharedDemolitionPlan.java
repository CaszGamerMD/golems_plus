package com.caszgamermd.golemsplus.builder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Experimental one-stance, three-block demolition work package.
 *
 * All target pairs are at most 2 world blocks apart (Euclidean distance),
 * every block is usable from the SAME real 3-block work reach, and one
 * complete escape corridor remains independent of ALL targets even after
 * their removal in any permitted order.
 */
public final class BuilderSharedDemolitionPlan {
    public record Batch(BlockPos stance,List<BlockPos> targets,
                        List<BlockPos> escape){
        public Batch {
            stance=stance.immutable();
            targets=List.copyOf(targets);
            escape=List.copyOf(escape);
        }
        public BlockPos safePoint(){return escape.get(escape.size()-1);}
    }
    private BuilderSharedDemolitionPlan(){}

    public static boolean pairwiseWithinTwo(List<BlockPos> targets){
        if(targets==null||targets.isEmpty()||targets.size()>3)
            return false;
        for(int i=0;i<targets.size();i++){
            if(targets.get(i)==null)return false;
            for(int j=0;j<i;j++)
                if(targets.get(i).equals(targets.get(j))
                        ||targets.get(i).distSqr(targets.get(j))>4.0)
                    return false;
        }
        return true;
    }

    /**
     * Plan at most three actual remaining targets, honoring work reach
     * supplied by the SAME 3m ray-trace visibility test used by mining.
     * A dependency leaf already in a group is ordered before its support.
     * Otherwise its support is not included until the leaf is handled.
     */
    public static Batch select(ServerLevel world,BuilderGolem golem,
            BlockPos stance,BlockPos first,
            Map<BlockPos,BlockState> remaining,
            List<BlockPos> nearby,Predicate<BlockPos> inReach,
            Predicate<BlockPos> eligible,boolean atStance){
        if(stance==null||first==null||remaining==null||nearby==null
                ||inReach==null||eligible==null
                ||!remaining.containsKey(first)||!inReach.test(first)
                ||!eligible.test(first))
            return null;
        List<BlockPos> group=new ArrayList<>();
        group.add(first);
        for(BlockPos target:nearby){
            if(group.size()==3)break;
            if(target.equals(first)||!remaining.containsKey(target)
                    ||target.getY()>first.getY()
                    ||!inReach.test(target)||!eligible.test(target))
                continue;
            var proposed=new ArrayList<>(group);
            proposed.add(target);
            if(!pairwiseWithinTwo(proposed))continue;
            group.add(target);
        }
        // Resolve known immediate lone-leaf dependencies within the
        // package, without silently removing a support's outside leaf.
        for(int i=group.size()-1;i>=0;i--){
            BlockPos target=group.get(i);
            BlockPos orphan=BuilderDemolitionOrder.loneBlockFirst(
                    world,remaining,target);
            if(orphan!=null&&!group.contains(orphan))
                group.remove(i);
        }
        if(group.isEmpty()||!group.contains(first))return null;
        // Simple stable order: a lone dependent comes before its support.
        for(int round=0;round<3;round++)
            for(int i=0;i<group.size();i++){
                BlockPos dependent=BuilderDemolitionOrder.loneBlockFirst(
                        world,remaining,group.get(i));
                int j=group.indexOf(dependent);
                if(j>i){
                    group.remove(j);
                    group.add(i,dependent);
                }
            }
        // If a three-block escape fails, try two and then one. Never
        // silently weaken the "shared" rule for a retained batch.
        while(!group.isEmpty()){
            var path=BuilderEscapeRoute.previewBreakGroup(
                    world,golem,stance,group,atStance);
            if(path!=null)return new Batch(stance,group,path);
            group.remove(group.size()-1);
        }
        return null;
    }
}
