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
 * Bounded eight-direction, terrain-weighted A* for the single live worker navigation policy.
 *
 * Search from actual feet, never from a guessed line to the goal. Only
 * collision-checked, loaded, standable feet positions are admitted by the
 * caller. Diagonal travel is allowed ONLY with BOTH orthogonal corners
 * physically clear and a valid sub-0.6-block height change. This follows
 * the successful physical Trial 3 rule without learning a fixed maze route.
 * A single A* compares short routes, doors, turns, slopes and safety.
 *
 * Routes longer than the local search window return a checked advancing
 * leg; the caller follows the leg and plans the next. A missing nearby
 * destination NEVER returns a fake partial arrival. No world loading.
 */
public final class WorkerRoutePlanner {
    public static final int RADIUS=40;
    public static final int NODE_BUDGET=2800;
    private static final int MOVE_COST=10;
    private static final int DIAGONAL_COST=14;
    private static final int HEIGHT_COST=15;
    private static final int TURN_COST=1;
    private static final int[][] DIRECTIONS={
        {1,0},{-1,0},{0,1},{0,-1},
        {1,1},{1,-1},{-1,1},{-1,-1}
    };
    private static final int[] HEIGHT_STEPS={0,-1,1};
    private record Node(BlockPos pos,int travelled,int estimate){}
    public record Route(List<BlockPos> steps,boolean complete,int cost,int explored){}
    public record SearchStats(int explored,int queued,boolean budgetExhausted){}
    private WorkerRoutePlanner(){}

    private static int heuristic(BlockPos at,List<BlockPos> goals){
        int best=Integer.MAX_VALUE;
        for(BlockPos goal:goals){
            int dx=Math.abs(at.getX()-goal.getX());
            int dz=Math.abs(at.getZ()-goal.getZ());
            // Octile distance is admissible for 10/14 movement weights.
            int distance=DIAGONAL_COST*Math.min(dx,dz)
                    +MOVE_COST*Math.abs(dx-dz)
                    +MOVE_COST*Math.abs(at.getY()-goal.getY());
            best=Math.min(best,distance);
        }
        return best;
    }
    public static Route plan(BlockPos start,List<BlockPos> goals,
            Predicate<BlockPos> standable,ToDoubleFunction<BlockPos> floor,
            BlockPos avoid){
        return plan(start,goals,standable,floor,avoid,unused->0);
    }
    /** Terrain penalty is a scored preference, not a hard-coded route.
     * The same rules operate in newly generated and player-built worlds.
     * Physical collision checks always outrank any optimized preference.
     */
    public static Route plan(BlockPos start,List<BlockPos> goals,
            Predicate<BlockPos> standable,ToDoubleFunction<BlockPos> floor,
            BlockPos avoid,ToDoubleFunction<BlockPos> terrainPenalty){
        return plan(start,goals,standable,floor,avoid,terrainPenalty,null);
    }
    public static Route plan(BlockPos start,List<BlockPos> goals,
            Predicate<BlockPos> standable,ToDoubleFunction<BlockPos> floor,
            BlockPos avoid,ToDoubleFunction<BlockPos> terrainPenalty,
            java.util.function.Consumer<SearchStats> observer){
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
            for(int[] dir:DIRECTIONS){
                boolean diagonal=dir[0]!=0&&dir[1]!=0;
                for(int dy:HEIGHT_STEPS){
                    BlockPos next=current.offset(dir[0],dy,dir[1]);
                    if(next.equals(avoid)||Math.abs(next.getX()-start.getX())>RADIUS
                            ||Math.abs(next.getZ()-start.getZ())>RADIUS
                            ||Math.abs(next.getY()-start.getY())>8
                            ||!validCache.computeIfAbsent(next,standable::test))continue;
                    double fromHeight=heightCache.computeIfAbsent(current,
                            floor::applyAsDouble);
                    double toHeight=heightCache.computeIfAbsent(next,
                            floor::applyAsDouble);
                    double rise=Math.abs(toHeight-fromHeight);
                    if(!Double.isFinite(rise)||rise>1.01)continue;
                    if(diagonal){
                        // Swept 0.65+ Builder hitboxes cannot corner-cut
                        // between two blocked side cells. Both side
                        // corridors must be standable on the SAME level;
                        // diagonals never jump up or descend 1-block drops.
                        if(rise>.6001)continue;
                        BlockPos sideX=current.offset(dir[0],0,0);
                        BlockPos sideZ=current.offset(0,0,dir[1]);
                        if(!validCache.computeIfAbsent(sideX,standable::test)
                                ||!validCache.computeIfAbsent(sideZ,standable::test))
                            continue;
                        double xHeight=heightCache.computeIfAbsent(sideX,
                                floor::applyAsDouble);
                        double zHeight=heightCache.computeIfAbsent(sideZ,
                                floor::applyAsDouble);
                        if(!Double.isFinite(xHeight)||!Double.isFinite(zHeight)
                                ||Math.abs(xHeight-fromHeight)>.6001
                                ||Math.abs(zHeight-fromHeight)>.6001
                                ||Math.abs(xHeight-toHeight)>.6001
                                ||Math.abs(zHeight-toHeight)>.6001)continue;
                    }
                    int turn=0;
                    BlockPos parent=parents.get(current);
                    if(parent!=null){
                        int oldX=Integer.signum(current.getX()-parent.getX());
                        int oldZ=Integer.signum(current.getZ()-parent.getZ());
                        if(oldX!=dir[0]||oldZ!=dir[1])turn=TURN_COST;
                    }
                    double extra=terrainPenalty.applyAsDouble(next);
                    if(!Double.isFinite(extra)||extra<0)continue;
                    // Prefer smooth moves and realistic open corridors,
                    // never raw straight-line guesses through walls.
                    int weight=(diagonal?DIAGONAL_COST:MOVE_COST)
                            +(rise>.51?HEIGHT_COST:0)+turn
                            +(int)Math.round(Math.min(100,extra)*MOVE_COST);
                    int candidate=node.travelled()+weight;
                    if(candidate>=cost.getOrDefault(next,Integer.MAX_VALUE))continue;
                    cost.put(next,candidate);
                    parents.put(next,current);
                    queue.add(new Node(next,candidate,
                            candidate+heuristic(next,goals)));
                }
            }
        }
        if(observer!=null)observer.accept(new SearchStats(explored,queue.size(),
                finish==null&&explored>=NODE_BUDGET&&!queue.isEmpty()));
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
