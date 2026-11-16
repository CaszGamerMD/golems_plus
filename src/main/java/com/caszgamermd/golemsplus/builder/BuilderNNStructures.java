package com.caszgamermd.golemsplus.builder;

import java.util.*;

/** Conservative, deterministic component partition; never renumbers saved steps. */
public final class BuilderNNStructures {
    private record Cell(int x,int y,int z) {}
    private record Column(int x,int z) {}
    private record Footprint(int root,int minY,int count,Map<Column,Integer> bottom) {}
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
        // Attach elevated satellites to a substantially larger underlying
        // component using actual projected occupied columns. No arbitrary
        // vertical distance limit, and no lateral bounding-box-only merges.
        if(smallestFirst)attachElevated(cells,parent);
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
            for(int i:group)membership.set(i,id);
            if(smallestFirst){
                for(int i:group)if(!manualTail(steps.get(i)))order.add(i);
            }else order.addAll(group);
        }
        // Finish all physical projects before attempting any dynamic result
        // checks or unsupported states. They remain real pending indices,
        // not fake completions; the player can finish/commission them.
        if(smallestFirst){
            for(var group:groups)for(int i:group)
                if(BuilderDeferredState.verification(steps.get(i).encodedState()))order.add(i);
            for(var group:groups)for(int i:group)
                if(manualTail(steps.get(i))&&!BuilderDeferredState.verification(steps.get(i).encodedState()))order.add(i);
        }
        return new Plan(List.copyOf(groups),List.copyOf(order),List.copyOf(membership));
    }
    public static boolean manualTail(BuilderConstructionPlan.Step s){
        return !s.ignorePlant()&&(BuilderDeferredState.verification(s.encodedState())||s.requiredItem()==null);
    }
    private static void attachElevated(Map<Cell,Integer> cells,int[] parent){
        var columns=new HashMap<Integer,Map<Column,Integer>>();
        var counts=new HashMap<Integer,Integer>();
        var bottoms=new HashMap<Integer,Integer>();
        for(var e:cells.entrySet()){
            int r=root(parent,e.getValue());var c=e.getKey();
            columns.computeIfAbsent(r,k->new HashMap<>()).merge(new Column(c.x,c.z),c.y,Math::min);
            counts.merge(r,1,Integer::sum);bottoms.merge(r,c.y,Math::min);
        }
        var footprints=new ArrayList<Footprint>();
        var at=new HashMap<Column,List<Footprint>>();
        for(var e:columns.entrySet()){
            int r=e.getKey();
            var f=new Footprint(r,bottoms.get(r),counts.get(r),e.getValue());
            footprints.add(f);
            for(var col:f.bottom.keySet())at.computeIfAbsent(col,k->new ArrayList<>()).add(f);
        }
        // Evaluate on the original components, not an incrementally
        // expanding bounding rectangle. Smaller satellites can share a
        // parent without accidentally linking neighboring buildings.
        footprints.sort(Comparator.comparingInt(Footprint::count).thenComparingInt(Footprint::root));
        for(var child:footprints){
            if(child.count<4||child.bottom.size()<2)continue;
            var votes=new HashMap<Integer,Integer>();
            for(var col:child.bottom.keySet()){
                var seen=new HashSet<Integer>();
                for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++){
                    var near=new Column(col.x+dx,col.z+dz);
                    for(var candidate:at.getOrDefault(near,List.of())){
                        if(candidate.count<Math.max(32,child.count*4L)
                                ||candidate.minY>child.minY-3
                                ||candidate.bottom.get(near)>child.minY-3)continue;
                        seen.add(candidate.root);
                    }
                }
                for(int r:seen)votes.merge(r,1,Integer::sum);
            }
            int best=-1,coverage=0,second=0;
            for(var e:votes.entrySet()){
                int v=e.getValue();
                if(v>coverage){second=coverage;coverage=v;best=e.getKey();}
                else if(v>second)second=v;
            }
            // A majority footprint and unambiguous owner. Equal or close
            // overlaps remain independent for safety.
            if(best>=0&&coverage*100L>=child.bottom.size()*75L
                    &&(second==0||(coverage-second)*100L>=child.bottom.size()*15L))
                join(parent,child.root,best);
        }
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
