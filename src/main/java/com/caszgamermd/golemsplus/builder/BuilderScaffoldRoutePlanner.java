package com.caszgamermd.golemsplus.builder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Bounded orthogonal route search for paid Builder scaffold bridges.
 *
 * Existing, tracked scaffold is cheaper than placing another block. A small
 * bend penalty keeps equally cheap routes simple and reduces awkward zigzags.
 * The caller owns all world collision, permissions, support and blueprint
 * checks through {@code canStep}; this class only ranks legal transitions.
 */
public final class BuilderScaffoldRoutePlanner {
    private static final double NEW_BLOCK_COST=1.0;
    private static final double REUSED_BLOCK_COST=.18;
    private static final double TURN_COST=.24;

    public record Route(List<BlockPos> cells,double cost) {
        public Route {
            cells=List.copyOf(cells);
        }
    }

    private record Visit(BlockPos pos,Direction incoming,int length) {}
    private record Node(BlockPos pos,Direction incoming,int length,
                        double cost,Node previous) {}

    private BuilderScaffoldRoutePlanner(){}

    /**
     * Find the cheapest cardinal-only path from the top of an owned support
     * to any usable work stance. The start is excluded from the returned
     * bridge cells; the selected goal is included.
     *
     * @param start top cell of the supporting tower
     * @param goals valid terminal work-stance scaffold cells
     * @param maxCells maximum number of bridge cells, including reused ones
     * @param canStep validates the physical transition from one cell to next
     * @param alreadyBuilt identifies an existing crew-tracked scaffold cell
     */
    public static Route find(BlockPos start,Set<BlockPos> goals,int maxCells,
            BiPredicate<BlockPos,BlockPos> canStep,
            Predicate<BlockPos> alreadyBuilt){
        if(start==null||goals==null||goals.isEmpty()||maxCells<=0
                ||canStep==null||alreadyBuilt==null)return null;

        Comparator<Node> order=Comparator.comparingDouble(Node::cost)
                .thenComparingInt(Node::length)
                .thenComparingInt(node->node.pos().getX())
                .thenComparingInt(node->node.pos().getY())
                .thenComparingInt(node->node.pos().getZ())
                .thenComparingInt(node->node.incoming()==null
                        ?-1:node.incoming().ordinal());
        PriorityQueue<Node> open=new PriorityQueue<>(order);
        Map<Visit,Double> best=new HashMap<>();
        Node origin=new Node(start.immutable(),null,0,0.0,null);
        open.add(origin);
        best.put(new Visit(origin.pos(),null,0),0.0);

        while(!open.isEmpty()){
            Node current=open.remove();
            Visit currentKey=new Visit(current.pos(),current.incoming(),
                    current.length());
            double known=best.getOrDefault(currentKey,Double.POSITIVE_INFINITY);
            if(current.cost()>known+1.0e-9)continue;

            if(current.length()>0&&goals.contains(current.pos()))
                return build(current);
            if(current.length()>=maxCells)continue;

            for(Direction direction:Direction.Plane.HORIZONTAL){
                BlockPos next=current.pos().relative(direction);
                if(!canStep.test(current.pos(),next))continue;
                int length=current.length()+1;
                double stepCost=alreadyBuilt.test(next)
                        ?REUSED_BLOCK_COST:NEW_BLOCK_COST;
                if(current.incoming()!=null&&current.incoming()!=direction)
                    stepCost+=TURN_COST;
                double cost=current.cost()+stepCost;
                Visit key=new Visit(next,direction,length);
                if(cost+1.0e-9>=best.getOrDefault(
                        key,Double.POSITIVE_INFINITY))continue;
                best.put(key,cost);
                open.add(new Node(next,direction,length,cost,current));
            }
        }
        return null;
    }

    private static Route build(Node goal){
        List<BlockPos> cells=new ArrayList<>(goal.length());
        for(Node node=goal;node!=null&&node.previous()!=null;
                node=node.previous())
            cells.add(node.pos().immutable());
        Collections.reverse(cells);
        return new Route(cells,goal.cost());
    }
}
