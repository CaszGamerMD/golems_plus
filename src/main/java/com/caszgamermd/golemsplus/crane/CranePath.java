package com.caszgamermd.golemsplus.crane;

import java.util.*;
import java.util.function.*;

/** Six-axis bounded A*: reused for the load and for grounded/wall rail nodes. */
public final class CranePath {
    public record Cell(int x,int y,int z){
        public Cell add(int dx,int dy,int dz){return new Cell(x+dx,y+dy,z+dz);}
        public int distance(Cell b){return Math.abs(x-b.x)+Math.abs(y-b.y)+Math.abs(z-b.z);}
    }
    private record Node(Cell cell,int cost,int score){}
    private static final int[][] SIDES={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};
    public static List<Cell> find(Cell start,Predicate<Cell> goal,ToIntFunction<Cell> estimate,
            Predicate<Cell> allowed,BiPredicate<Cell,Cell> edge,int budget){
        var open=new PriorityQueue<Node>(Comparator.comparingInt(Node::score).thenComparingInt(Node::cost));
        var cost=new HashMap<Cell,Integer>();var parent=new HashMap<Cell,Cell>();
        cost.put(start,0);open.add(new Node(start,0,estimate.applyAsInt(start)));
        while(!open.isEmpty()&&budget-->0){
            Node n=open.remove();if(n.cost()!=cost.getOrDefault(n.cell(),Integer.MAX_VALUE))continue;
            if(goal.test(n.cell())){
                var path=new ArrayList<Cell>();Cell at=n.cell();
                while(!at.equals(start)){path.add(at);at=parent.get(at);}
                Collections.reverse(path);return path;
            }
            for(int[] d:SIDES){Cell at=n.cell().add(d[0],d[1],d[2]);int next=n.cost()+1;
                if(next>=cost.getOrDefault(at,Integer.MAX_VALUE)||!allowed.test(at)||!edge.test(n.cell(),at))continue;
                cost.put(at,next);parent.put(at,n.cell());open.add(new Node(at,next,next+estimate.applyAsInt(at)));
            }
        }
        return null; // blocked/budget exhausted; never fabricate a straight route
    }
}
