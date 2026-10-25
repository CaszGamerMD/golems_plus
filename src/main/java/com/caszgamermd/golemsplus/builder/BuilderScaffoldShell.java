package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;

/**
 * The virtual, three-block-wide outer scaffold shell for a WHOLE schematic.
 *
 * Nothing is placed when this plan is made. Only the actual Builder later
 * acquires and spends materials for the portion of the shell it needs.
 * At each elevation a flood fill distinguishes exterior air from enclosed
 * pockets. The shell follows the changing silhouette of domes and ledges,
 * not just the bounding box or a ground-level ring.
 *
 * Every possible standing platform keeps the Builder's FEET and HEAD
 * outside all future solid cells. The physical route checks terrain,
 * ownership, supports, collision and reach again before placing anything.
 */
public final class BuilderScaffoldShell {
    private BuilderScaffoldShell(){}

    /** Four cardinal, common-use tower foundations derived from the
     * schematic EXTREMES, never from a single worker's temporary task.
     * Base Y is the first scaffold block above the expected ground plane.
     */
    public record TowerAnchor(String side,BlockPos base){}

    public record Blueprint(Set<BlockPos> solids,Set<BlockPos> shell,
            Map<Integer,Set<BlockPos>> floors,int lowY,int highY,
            List<TowerAnchor> towers){
        public Blueprint {
            solids=Set.copyOf(solids);
            shell=Set.copyOf(shell);
            floors=Map.copyOf(floors);
            towers=List.copyOf(towers);
        }
        public boolean isShell(BlockPos at){return shell.contains(at);}
        public Set<BlockPos> atHeight(int y){
            return floors.getOrDefault(y,Set.of());
        }
        public int suggestedWorkPositions(BlockPos target){
            int count=0;
            for(int y=target.getY()-2;y<=target.getY();y++)
                for(BlockPos p:atHeight(y)){
                    int dx=p.getX()-target.getX();
                    int dz=p.getZ()-target.getZ();
                    int dy=p.getY()+1-target.getY();
                    if(dx*dx+dz*dz+dy*dy<=12.5)count++;
                }
            return count;
        }
    }

    private record Cached(List<BuilderConstructionPlan.Step> steps,
            Blueprint blueprint){}
    private static final Map<WorkerStationBlockEntity,Cached> CACHE=
            new WeakHashMap<>();
    public static Blueprint forWorksite(WorkerStationBlockEntity table,
            List<BuilderConstructionPlan.Step> steps){
        Cached old=CACHE.get(table);
        if(old!=null&&old.steps()==steps)return old.blueprint();
        Blueprint fresh=fromSteps(steps);
        CACHE.put(table,new Cached(steps,fresh));
        return fresh;
    }

    public static Blueprint fromSteps(List<BuilderConstructionPlan.Step> steps){
        return fromSolids(BuilderScaffoldSections.solids(steps));
    }
    private static boolean validFloor(Set<BlockPos> solids,BlockPos p){
        return !solids.contains(p)&&!solids.contains(p.above())
                &&!solids.contains(p.above().above());
    }

    private static int medianEdge(Set<BlockPos> solids,boolean extremeX,int edge){
        List<Integer> values=new ArrayList<>();
        for(BlockPos p:solids)
            if((extremeX?p.getX():p.getZ())==edge)
                values.add(extremeX?p.getZ():p.getX());
        values.sort(Integer::compareTo);
        return values.get(values.size()/2);
    }

    public static Blueprint fromSolids(Set<BlockPos> solids){
        if(solids.isEmpty())return new Blueprint(Set.of(),Set.of(),Map.of(),0,0,List.of());
        int minX=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE;
        int minZ=Integer.MAX_VALUE,maxZ=Integer.MIN_VALUE;
        int minY=Integer.MAX_VALUE,maxY=Integer.MIN_VALUE;
        Map<Integer,Set<BlockPos>> byY=new HashMap<>();
        for(BlockPos p:solids){
            minX=Math.min(minX,p.getX());maxX=Math.max(maxX,p.getX());
            minZ=Math.min(minZ,p.getZ());maxZ=Math.max(maxZ,p.getZ());
            minY=Math.min(minY,p.getY());maxY=Math.max(maxY,p.getY());
            byY.computeIfAbsent(p.getY(),k->new HashSet<>()).add(p);
        }
        // Three-block SHELL plus two extra cells to establish the OUTSIDE.
        int xmin=minX-5,xmax=maxX+5,zmin=minZ-5,zmax=maxZ+5;
        Map<Integer,Set<BlockPos>> floors=new HashMap<>();
        Set<BlockPos> all=new HashSet<>();
        int[][] sides={{1,0},{-1,0},{0,1},{0,-1}};
        // A proposed platform is a scaffold block at floorY. The entity
        // stands at floorY+1 and must fit through floorY+2 as well.
        for(int floorY=minY-1;floorY<=maxY;floorY++){
            Set<BlockPos> occupied=new HashSet<>();
            for(int y=floorY;y<=floorY+2;y++)
                for(BlockPos b:byY.getOrDefault(y,Set.of()))
                    occupied.add(new BlockPos(b.getX(),floorY,b.getZ()));
            if(occupied.isEmpty())continue;
            Set<BlockPos> exterior=new HashSet<>();
            ArrayDeque<BlockPos> queue=new ArrayDeque<>();
            BlockPos start=new BlockPos(xmin,floorY,zmin);
            queue.add(start);exterior.add(start);
            while(!queue.isEmpty()){
                BlockPos p=queue.removeFirst();
                for(int[] side:sides){
                    BlockPos next=p.offset(side[0],0,side[1]);
                    if(next.getX()<xmin||next.getX()>xmax
                            ||next.getZ()<zmin||next.getZ()>zmax
                            ||occupied.contains(next)||!exterior.add(next))
                        continue;
                    queue.addLast(next);
                }
            }
            // Flood three walkable OUTWARD rings from the actual
            // silhouette, following overhangs and bends at every height.
            // Never fill enclosed interior courtyards or roof cavities.
            Set<BlockPos> layer=new HashSet<>();
            Set<BlockPos> frontier=new HashSet<>();
            for(BlockPos p:occupied)for(int[] side:sides){
                BlockPos next=p.offset(side[0],0,side[1]);
                if(exterior.contains(next)&&validFloor(solids,next)){
                    layer.add(next);frontier.add(next);
                }
            }
            for(int ring=2;ring<=3;ring++){
                Set<BlockPos> following=new HashSet<>();
                for(BlockPos p:frontier)for(int[] side:sides){
                    BlockPos next=p.offset(side[0],0,side[1]);
                    if(exterior.contains(next)&&validFloor(solids,next)
                            &&layer.add(next))following.add(next);
                }
                frontier=following;
            }
            if(!layer.isEmpty()){
                Set<BlockPos> immutable=Set.copyOf(layer);
                floors.put(floorY,immutable);
                all.addAll(immutable);
            }
        }
        // The long-lived shared towers are fixed to the outer X+/X-/Z+/Z-
        // extremes, with the other coordinate taken from the median of
        // the matching outer edge's schematic cells. Use the second shell
        // lane for a clear worker approach on either side of the tower.
        List<TowerAnchor> anchors=new ArrayList<>();
        anchors.add(new TowerAnchor("X+",new BlockPos(maxX+2,minY,
                medianEdge(solids,true,maxX))));
        anchors.add(new TowerAnchor("X-",new BlockPos(minX-2,minY,
                medianEdge(solids,true,minX))));
        anchors.add(new TowerAnchor("Z+",new BlockPos(
                medianEdge(solids,false,maxZ),minY,maxZ+2)));
        anchors.add(new TowerAnchor("Z-",new BlockPos(
                medianEdge(solids,false,minZ),minY,minZ-2)));
        return new Blueprint(solids,all,floors,minY,maxY,anchors);
    }
}
