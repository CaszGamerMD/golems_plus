package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/**
 * Generalized, bounded scaffold walkway planner. It stores decisions
 * (the selected safe cells) for a CURRENT task, not learned map routes.
 *
 * Walkways MUST connect to the Builder's real, owned vertical column,
 * stay at one height, never exceed six horizontal scaffold segments
 * from that column, and leave a reversible walk back to the ladder.
 * A future schematic cell must never overlap the walkway or the
 * Builder's 1.6-block-high walking volume.
 */
public final class BuilderScaffoldBridgePlanner {
    public static final int MAX_SPAN=6;
    private static final int REACH_SQUARED=10; // margin inside 14 actual reach
    private BuilderScaffoldBridgePlanner(){}

    public record Route(BlockPos anchor,List<BlockPos> cells,
                        BlockPos work,double score){
        public Route {
            cells=List.copyOf(cells);
        }
        public BlockPos end(){
            return cells.isEmpty()?anchor:cells.get(cells.size()-1);
        }
    }

    public static boolean withinPlacementRange(BlockPos scaffold,BlockPos work){
        int dx=scaffold.getX()-work.getX();
        int dz=scaffold.getZ()-work.getZ();
        int dy=scaffold.getY()+1-work.getY();
        return dx*dx+dy*dy+dz*dz<=REACH_SQUARED;
    }

    public static boolean supportedSpan(int count){
        return count>=0&&count<=MAX_SPAN;
    }

    /** Plan purely from grid validity. No entity navigation, jumping, or
     * mutation of blocks. Suitable for training with unfamiliar courses.
     * Each accepted cell is within MAX_SPAN scaffold blocks of the anchor
     * along a contiguous, reversible cardinal route.
     */
    public static Route search(BlockPos anchor,BlockPos work,
            java.util.function.Predicate<BlockPos> safe){
        if(withinPlacementRange(anchor,work))return null;
        record Node(BlockPos at,List<BlockPos> cells){}
        var queue=new ArrayDeque<Node>();
        var visited=new HashSet<BlockPos>();
        queue.addLast(new Node(anchor,List.of()));
        visited.add(anchor);
        Route best=null;
        while(!queue.isEmpty()){
            Node node=queue.removeFirst();
            if(!node.cells().isEmpty()
                    &&withinPlacementRange(node.at(),work)){
                double score=node.cells().size()*5.0
                        +node.at().distSqr(work)*.3;
                if(best==null||score<best.score())
                    best=new Route(anchor,node.cells(),work,score);
            }
            if(node.cells().size()>=MAX_SPAN)continue;
            for(Direction side:Direction.Plane.HORIZONTAL){
                BlockPos next=node.at().relative(side);
                if(next.getY()!=anchor.getY()||!visited.add(next)
                        ||!safe.test(next))continue;
                var cells=new ArrayList<>(node.cells());
                cells.add(next.immutable());
                queue.addLast(new Node(next,List.copyOf(cells)));
            }
        }
        return best;
    }

    public static Set<BlockPos> reserved(List<BuilderConstructionPlan.Step> work){
        Set<BlockPos> cells=new HashSet<>();
        for(var step:work)if(!step.ignorePlant())
            cells.add(new BlockPos(step.x(),step.y(),step.z()));
        return cells;
    }

    /** Concrete physical world validation. No deferred "we'll figure
     * out the way down later": the anchor must be owned by this worker
     * and its column must already connect to real ground.
     */
    public static Route choose(ServerLevel level,BuilderGolem builder,
            WorkerStationBlockEntity table,ServerPlayer owner,
            BlockPos anchor,BlockPos target,
            Set<BlockPos> future){
        if(!level.getBlockState(anchor).is(Blocks.SCAFFOLDING)
                ||!table.scaffoldPositions(builder.getUUID()).contains(anchor)
                ||!level.mayInteract(owner,anchor))return null;
        BlockPos topFeet=anchor.above();
        if(Math.abs(builder.getY()-topFeet.getY())>1.3)return null;
        var base=anchor;
        while(level.getBlockState(base.below()).is(Blocks.SCAFFOLDING)
                &&table.scaffoldPositions(builder.getUUID()).contains(base.below()))
            base=base.below();
        if(BuilderScaffolding.safeGroundExit(level,builder,base,future)==null)
            return null;
        final int y=anchor.getY();
        return search(anchor,target,next->{
            if(!level.hasChunkAt(next)||!level.hasChunkAt(next.above())
                    ||!level.mayInteract(owner,next)
                    ||table.isTrackedScaffold(next)
                    ||!level.getBlockState(next).isAir()
                    ||!level.getBlockState(next.above()).isAir()
                    ||!level.getFluidState(next).isEmpty()
                    ||future.contains(next)||future.contains(next.above())
                    ||future.contains(next.above(2)))return false;
            // The eventual feet and shoulders must fit; this does not
            // require a floor because the scaffold underfoot is PLANNED.
            AABB futureBody=builder.getBoundingBox().move(
                    next.getX()+.5-builder.getX(),
                    y+1-builder.getY(),
                    next.getZ()+.5-builder.getZ());
            return level.noCollision(builder,futureBody);
        });
    }
}
