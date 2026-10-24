package com.caszgamermd.golemsplus.worker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Bounded, four-direction, terrain-weighted A* for worker navigation.
 *
 * Search from actual feet, never from a guessed line to the goal. Only
 * collision-checked, loaded, standable feet positions are admitted by the
 * caller. No diagonal corner cutting, jumps over two blocks, swimming, or
 * stairs/rack shortcuts. A cheap heap chooses the lowest traveled cost plus
 * an admissible remaining-distance estimate.
 *
 * Routes longer than the local search window return a checked advancing
 * leg; the caller follows the leg and plans the next. A missing nearby
 * destination NEVER returns a fake partial arrival. No world loading.
 */
public final class WorkerRoutePlanner {
    public static final int RADIUS=40;
    public static final int NODE_BUDGET=2800;
    private static final int MOVE_COST=10;
    private static final int HEIGHT_COST=15;
    private static final int[] HEIGHT_STEPS={0,-1,1};
    private record Node(BlockPos pos,int travelled,int estimate){}
    public record Route(List<BlockPos> steps,boolean complete,int cost,int explored){}
    private WorkerRoutePlanner(){}

    private static int heuristic(BlockPos at,List<BlockPos> goals){
        int best=Integer.MAX_VALUE;
        for(BlockPos goal:goals){
            int distance=Math.abs(at.getX()-goal.getX())
                    +Math.abs(at.getZ()-goal.getZ())
                    +Math.abs(at.getY()-goal.getY());
            best=Math.min(best,distance*MOVE_COST);
        }
        return best;
    }
    public static Route plan(BlockPos start,List<BlockPos> goals,
            Predicate<BlockPos> standable,ToDoubleFunction<BlockPos> floor,
            BlockPos avoid){
        if(start==null||goals==null||goals.isEmpty())return null;
        Set<BlockPos> ends=new HashSet<>(goals);
        if(ends.contains(start))return new Route(List.of(),true,0,0);
        int original=heuristic(start,goals);
        boolean distant=original>RADIUS*MOVE_COST;
        Map<BlockPos,Integer> cost=new HashMap<>();
        Map<BlockPos,BlockPos> parents=new HashMap<>();
        // Block collision/headroom checks dominate routing time. Within
        // one request every cell has only one actual world result, so
        // evaluate it once rather than up to 12 times as neighbors overlap.
        Map<BlockPos,Boolean> validCache=new HashMap<>();
        Map<BlockPos,Double> heightCache=new HashMap<>();
        PriorityQueue<Node> queue=new PriorityQueue<>(
                Comparator.comparingInt(Node::estimate)
                    .thenComparingInt(Node::travelled));
        queue.add(new Node(start,0,original));
        cost.put(start,0);
        BlockPos frontier=null,finish=null;
        int frontierScore=Integer.MAX_VALUE;
        int explored=0;
        while(!queue.isEmpty()&&explored<NODE_BUDGET){
            Node node=queue.poll();
            if(cost.getOrDefault(node.pos(),Integer.MAX_VALUE)!=node.travelled())
                continue;
            explored++;
            BlockPos current=node.pos();
            if(ends.contains(current)){finish=current;break;}
            int remaining=heuristic(current,goals);
            int walked=Math.abs(current.getX()-start.getX())
                    +Math.abs(current.getZ()-start.getZ());
            if(distant&&walked>=8&&remaining<=original-80){
                // Prefer getting closer, but favor short, safe detours over
                // circuitous candidates with the same remaining distance.
                int score=remaining*4+node.travelled();
                if(score<frontierScore){frontier=current;frontierScore=score;}
            }
            for(Direction direction:Direction.Plane.HORIZONTAL){
                BlockPos adjacent=current.relative(direction);
                for(int dy:HEIGHT_STEPS){
                    BlockPos next=adjacent.offset(0,dy,0);
                    if(next.equals(avoid)||Math.abs(next.getX()-start.getX())>RADIUS
                            ||Math.abs(next.getZ()-start.getZ())>RADIUS
                            ||Math.abs(next.getY()-start.getY())>8
                            ||!validCache.computeIfAbsent(next,standable::test))continue;
                    double rise=Math.abs(heightCache.computeIfAbsent(next,
                            floor::applyAsDouble)-heightCache.computeIfAbsent(
                                    current,floor::applyAsDouble));
                    if(!Double.isFinite(rise)||rise>1.01)continue;
                    int weight=MOVE_COST+(rise>.51?HEIGHT_COST:0);
                    int candidate=node.travelled()+weight;
                    if(candidate>=cost.getOrDefault(next,Integer.MAX_VALUE))continue;
                    cost.put(next,candidate);
                    parents.put(next,current);
                    queue.add(new Node(next,candidate,
                            candidate+heuristic(next,goals)));
                }
            }
        }
        boolean complete=finish!=null;
        if(!complete){
            if(!distant||frontier==null)return null;
            finish=frontier;
        }
        List<BlockPos> path=new ArrayList<>();
        for(BlockPos at=finish;!at.equals(start);at=parents.get(at)){
            if(at==null)return null;
            path.add(at);
        }
        Collections.reverse(path);
        return new Route(List.copyOf(path),complete,
                cost.getOrDefault(finish,0),explored);
    }
}
