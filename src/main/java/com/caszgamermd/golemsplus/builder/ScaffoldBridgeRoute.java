package com.caszgamermd.golemsplus.builder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Small, bounded horizontal scaffold bridge search at one feet elevation.
 * The caller must stand over an owned, supported scaffold anchor.
 * A route consists only of connected, already-owned scaffolds, new scaffold
 * cells, and one safe, physically supported landing at the end.
 *
 * No changes to the world occur in this planner. The server must validate
 * and purchase every planned block before following the path.
 */
public final class ScaffoldBridgeRoute {
    public static final int MAX_NEW_SCAFFOLDS=6;
    private static final int MAX_STEPS=11;
    private static final int MAX_RADIUS=7;
    public record Plan(List<BlockPos> waypoints,int required){}
    private record Key(BlockPos pos,int material){}
    private record Node(Key key,int steps,int price){}
    private ScaffoldBridgeRoute(){}

    public static Plan plan(BlockPos start,Predicate<BlockPos> owned,
            Predicate<BlockPos> canAdd,Predicate<BlockPos> landing){
        if(start==null||!owned.test(start))return null;
        Key initial=new Key(start,0);
        PriorityQueue<Node> queue=new PriorityQueue<>(
                Comparator.comparingInt(Node::price)
                        .thenComparingInt(Node::steps));
        Map<Key,Integer> costs=new HashMap<>();
        Map<Key,Key> previous=new HashMap<>();
        costs.put(initial,0);
        queue.add(new Node(initial,0,0));
        int explored=0;
        while(!queue.isEmpty()&&explored++<1800){
            Node node=queue.poll();
            if(costs.getOrDefault(node.key(),Integer.MAX_VALUE)!=node.price())
                continue;
            BlockPos position=node.key().pos();
            if(node.steps()>0&&node.key().material()>0&&landing.test(position)){
                List<BlockPos> route=new ArrayList<>();
                for(Key at=node.key();!at.equals(initial);at=previous.get(at)){
                    if(at==null)return null;
                    route.add(at.pos());
                }
                Collections.reverse(route);
                return new Plan(List.copyOf(route),node.key().material());
            }
            if(node.steps()>=MAX_STEPS)continue;
            for(Direction direction:Direction.Plane.HORIZONTAL){
                BlockPos next=position.relative(direction);
                if(Math.abs(next.getX()-start.getX())
                        +Math.abs(next.getZ()-start.getZ())>MAX_RADIUS)
                    continue;
                boolean end=landing.test(next);
                boolean old=owned.test(next);
                if(!end&&!old&&!canAdd.test(next))continue;
                int material=node.key().material()+(end||old?0:1);
                if(material>MAX_NEW_SCAFFOLDS)continue;
                Key nextKey=new Key(next,material);
                int step=node.steps()+1;
                int price=step*10+material*30;
                if(price>=costs.getOrDefault(nextKey,Integer.MAX_VALUE))
                    continue;
                costs.put(nextKey,price);
                previous.put(nextKey,node.key());
                queue.add(new Node(nextKey,step,price));
            }
        }
        return null;
    }
}
