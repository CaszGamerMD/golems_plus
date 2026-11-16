package com.caszgamermd.golemsplus.builder;

import java.util.*;

/** Conservative, deterministic component partition; never renumbers saved steps. */
public final class BuilderNNStructures {
    private record Cell(int x,int y,int z) {}
    public record Plan(List<List<Integer>> groups,List<Integer> order,List<Integer> membership) {
        public int group(int index){return membership.get(index);}
        public List<Integer> members(int group){return groups.get(group);}
    }
    private BuilderNNStructures(){}
    private static int root(int[] parent,int i){
        while(parent[i]!=i){parent[i]=parent[parent[i]];i=parent[i];}return i;
    }
    private static void join(int[] p,int a,int b){
        a=root(p,a);b=root(p,b);if(a!=b)p[Math.max(a,b)]=Math.min(a,b);
    }
    private static void add(Map<Cell,Integer> cells,int[] parent,Cell c,int index){
        Integer previous=cells.putIfAbsent(c,index);
        if(previous!=null)join(parent,index,previous);
    }
    public static Plan compile(List<BuilderConstructionPlan.Step> steps){return compile(steps,false);}
    public static Plan compile(List<BuilderConstructionPlan.Step> steps,boolean smallestFirst){
        int n=steps.size();int[] parent=new int[n];
        var cells=new HashMap<Cell,Integer>();
        for(int i=0;i<n;i++){
            parent[i]=i;var s=steps.get(i);
            add(cells,parent,new Cell(s.x(),s.y(),s.z()),i);
            var c=s.companion();
            if(c!=null)add(cells,parent,new Cell(c.x(),c.y(),c.z()),i);
        }
        // A one-block gap, diagonal seam, or detached detail is not
        // enough evidence for independent projects. Merge within two
        // cells in each dimension. Shared roads/terrain stay connected.
        for(var e:cells.entrySet()){
            var c=e.getKey();
            for(int dx=-2;dx<=0;dx++)for(int dy=-2;dy<=2;dy++)for(int dz=-2;dz<=2;dz++){
                if(dx==0&&(dy>0||dy==0&&dz>=0))continue;
                Integer other=cells.get(new Cell(c.x()+dx,c.y()+dy,c.z()+dz));
                if(other!=null)join(parent,e.getValue(),other);
            }
        }
        // Source steps are already lower-first (including dry/water
        // phases). Preserve the exact original order within each group.
        var byRoot=new LinkedHashMap<Integer,List<Integer>>();
        for(int i=0;i<n;i++)byRoot.computeIfAbsent(root(parent,i),k->new ArrayList<>()).add(i);
        var groups=new ArrayList<List<Integer>>();
        var order=new ArrayList<Integer>(n);
        var membership=new ArrayList<Integer>(Collections.nCopies(n,-1));
        var ordered=new ArrayList<>(byRoot.values());
        if(smallestFirst){
            var volumes=new IdentityHashMap<List<Integer>,Integer>();
            for(var group:ordered)volumes.put(group,volume(steps,group));
            ordered.sort(Comparator.comparingInt(volumes::get));
        }
        for(var group:ordered){
            int id=groups.size();groups.add(List.copyOf(group));
            for(int i:group){order.add(i);membership.set(i,id);}
        }
        return new Plan(List.copyOf(groups),List.copyOf(order),List.copyOf(membership));
    }
    /** Count distinct non-air cells, including companions, not work passes. */
    public static int volume(List<BuilderConstructionPlan.Step> steps,List<Integer> members){
        var cells=new HashSet<Cell>();
        for(int i:members){
            var s=steps.get(i);
            if(s.ignorePlant()||s.encodedState().equals("minecraft:air"))continue;
            cells.add(new Cell(s.x(),s.y(),s.z()));
            var c=s.companion();
            if(c!=null)cells.add(new Cell(c.x(),c.y(),c.z()));
        }
        return cells.size();
    }
    /** Resume from original-index completion; no fabricated progress. */
    public static int nextOffset(Plan plan,int from,int cursor,Set<Integer> finished){
        int offset=Math.max(0,from);
        while(offset<plan.order().size()){
            int i=plan.order().get(offset);
            if(i>=cursor&&!finished.contains(i))break;
            offset++;
        }
        return offset;
    }
}
