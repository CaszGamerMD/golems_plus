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
 * The virtual, one-block-wide outer scaffold shell for a WHOLE schematic.
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

    public record Blueprint(Set<BlockPos> solids,Set<BlockPos> shell,
            Map<Integer,Set<BlockPos>> floors,int lowY,int highY){
        public Blueprint {
            solids=Set.copyOf(solids);
            shell=Set.copyOf(shell);
            floors=Map.copyOf(floors);
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
    public static Blueprint fromSolids(Set<BlockPos> solids){
        if(solids.isEmpty())return new Blueprint(Set.of(),Set.of(),Map.of(),0,0);
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
        // One-block margin plus two cells to establish the OUTSIDE.
        int xmin=minX-3,xmax=maxX+3,zmin=minZ-3,zmax=maxZ+3;
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
            Set<BlockPos> layer=new HashSet<>();
            // A ring exactly one block out from the finished solid body.
            // Include the wall and ledge at each Y but never the builder's
            // body or scaffold inside any cell of the completed schematic.
            for(BlockPos p:occupied)for(int[] side:sides){
                BlockPos next=p.offset(side[0],0,side[1]);
                if(exterior.contains(next)
                        &&!solids.contains(next)
                        &&!solids.contains(next.above())
                        &&!solids.contains(next.above().above()))
                    layer.add(next);
            }
            if(!layer.isEmpty()){
                Set<BlockPos> immutable=Set.copyOf(layer);
                floors.put(floorY,immutable);
                all.addAll(immutable);
            }
        }
        return new Blueprint(solids,all,floors,minY,maxY);
    }
}
