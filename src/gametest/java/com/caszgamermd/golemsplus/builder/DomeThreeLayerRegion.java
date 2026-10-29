package com.caszgamermd.golemsplus.builder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * TEST ONLY. Demolish three-block-high horizontal bands from the top down.
 *
 * The bottom layer remains as a physical working floor while the TWO
 * layers above it are cleared. Once both upper layers are empty throughout
 * the current band, floor clearing begins, still obeying escape and
 * structural-dependency checks at the actual mining action.
 *
 * Bands are anchored to the original highest dome block, never to the
 * highest REMAINING block: this prevents a partial roof from shifting its
 * own work-floor designation after every removed block.
 */
final class DomeThreeLayerRegion {
    enum Phase { UPPER_TWO, FLOOR, COMPLETE }
    record State(int number,int topY,int middleY,int floorY,
            Phase phase,int upperRemaining,int floorRemaining){
        boolean floorPhase(){return phase==Phase.FLOOR;}
        boolean upperPhase(){return phase==Phase.UPPER_TWO;}
        int workingFeetY(){return floorY+1;}
    }
    private DomeThreeLayerRegion(){}

    static State current(Map<BlockPos,BlockState> remaining,int initialTop){
        if(remaining==null||remaining.isEmpty())
            return new State(0,initialTop,initialTop-1,initialTop-2,
                    Phase.COMPLETE,0,0);
        int highest=remaining.keySet().stream().mapToInt(BlockPos::getY)
                .max().orElse(initialTop);
        // If an empty band is crossed, continue to the next nonempty one.
        int index=Math.max(0,Math.floorDiv(initialTop-highest,3));
        int top=initialTop-index*3;
        int middle=top-1;
        int bottom=top-2;
        int upper=0,floor=0;
        for(BlockPos cell:remaining.keySet()){
            if(cell.getY()==top||cell.getY()==middle)upper++;
            else if(cell.getY()==bottom)floor++;
        }
        return new State(index,top,middle,bottom,
                upper>0?Phase.UPPER_TWO:Phase.FLOOR,upper,floor);
    }

    /** Candidates from only the active band and active phase.
     * The top two levels are treated as ONE work stage: the golem can
     * use the same floor to mine either layer, optimizing travel.
     */
    static List<BlockPos> candidates(Map<BlockPos,BlockState> remaining,
            State stage,BlockPos worker){
        var out=new ArrayList<BlockPos>();
        if(stage.phase()==Phase.COMPLETE)return out;
        for(BlockPos cell:remaining.keySet()){
            if(stage.upperPhase()
                    &&(cell.getY()==stage.topY()
                        ||cell.getY()==stage.middleY())
                    ||stage.floorPhase()&&cell.getY()==stage.floorY())
                out.add(cell);
        }
        out.sort(Comparator.comparingDouble(
                (BlockPos cell)->cell.distSqr(worker))
                .thenComparingInt((BlockPos cell)->-cell.getY())
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getZ));
        return out;
    }

    /**
     * Ideal upper-layer work stances are supported by the preserved
     * bottom layer. Slabs and stairs may put feet WITHIN that layer,
     * not one block above; both are valid collision-aware positions.
     *
     * This is a ranking preference, never permission to stand in air or
     * bypass WorkerNavigation.canStandOn and the true 3-block raycast.
     */
    static boolean isWorkingFloorPose(State stage,BlockPos feet){
        return stage.upperPhase()
                &&(feet.getY()==stage.workingFeetY()
                        ||feet.getY()==stage.floorY());
    }

    /** When clearing floor cells, never require the worker to stand
     * ON the very target. Actual escape proof still has final authority.
     */
    static boolean isFloorClearancePose(State stage,BlockPos feet,
            BlockPos target){
        return stage.floorPhase()&&target.getY()==stage.floorY()
                &&!feet.equals(target)&&!feet.below().equals(target);
    }
}
