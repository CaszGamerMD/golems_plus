package com.caszgamermd.golemsplus.builder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Experimental real-air-path recovery for Builders with creative flight.
 *
 * Search for a reachable, collision-clear AIR cell within original real
 * placement reach, rather than insisting on a particular scaffold/ground
 * landing. No teleporting and no removal/creation of terrain or materials.
 * Every node requires a real loaded chunk and a collision-free mob AABB.
 */
public final class BuilderCreativeAirRoute {
    private static final int LIMIT=13000;
    private static final int MAX_HORIZONTAL=28;
    private static final int MAX_VERTICAL=25;
    private static final int[][] DIRECTIONS={
            {0,1,0}, {1,0,0}, {0,0,1}, {-1,0,0}, {0,0,-1}, {0,-1,0}
    };
    private BuilderCreativeAirRoute(){}

    private static AABB bodyAt(BuilderGolem worker,BlockPos p){
        return worker.getBoundingBox().move(
                p.getX()+.5-worker.getX(),
                p.getY()-worker.getY(),
                p.getZ()+.5-worker.getZ());
    }

    private record Candidate(BlockPos pos,int steps,double estimate){}
    private static double estimate(BlockPos node,BlockPos target){
        double dx=node.getX()-target.getX();
        double dy=node.getY()-(target.getY()+.5);
        double dz=node.getZ()-target.getZ();
        return Math.max(0,Math.sqrt(dx*dx+dy*dy+dz*dz)-3.4);
    }

    public static List<Vec3> plan(ServerLevel level,BuilderGolem worker,
                                  BlockPos target){
        BlockPos start=worker.blockPosition();
        AABB goalBox=new AABB(target);
        // A* prefers air corridors going TOWARD the needed placement,
        // rather than spending thousands of BFS nodes in irrelevant
        // side corridors before reaching a work zone 20+ blocks away.
        PriorityQueue<Candidate> open=new PriorityQueue<>(
                Comparator.comparingDouble(Candidate::estimate));
        Map<BlockPos,BlockPos> parent=new HashMap<>();
        Map<BlockPos,Integer> distances=new HashMap<>();
        open.add(new Candidate(start,0,estimate(start,target)));
        parent.put(start,start);
        distances.put(start,0);
        BlockPos found=null;
        int expanded=0;
        while(!open.isEmpty()&&expanded++<LIMIT){
            Candidate current=open.poll();
            BlockPos at=current.pos();
            if(current.steps()>distances.getOrDefault(at,Integer.MAX_VALUE))
                continue;
            AABB body=bodyAt(worker,at);
            double dx=at.getX()+.5-target.getX()-.5;
            double dz=at.getZ()+.5-target.getZ()-.5;
            double dy=at.getY()-(target.getY()+.5);
            if(dx*dx+dy*dy+dz*dz<13.4
                    &&!body.intersects(goalBox)
                    &&level.noCollision(worker,body)){
                found=at;break;
            }
            List<BlockPos> options=new ArrayList<>(6);
            for(int[] d:DIRECTIONS)
                options.add(at.offset(d[0],d[1],d[2]));
            options.sort(Comparator.comparingDouble(p->p.distSqr(target)));
            for(BlockPos next:options){
                if(Math.abs(next.getX()-start.getX())>MAX_HORIZONTAL
                        ||Math.abs(next.getZ()-start.getZ())>MAX_HORIZONTAL
                        ||Math.abs(next.getY()-start.getY())>MAX_VERTICAL
                        ||!level.hasChunkAt(next)
                        ||!level.hasChunkAt(next.above())
                        ||!level.noCollision(worker,bodyAt(worker,next)))
                    continue;
                int steps=current.steps()+1;
                if(steps>=distances.getOrDefault(next,Integer.MAX_VALUE))
                    continue;
                distances.put(next,steps);
                parent.put(next,at);
                open.add(new Candidate(next,steps,
                        steps+estimate(next,target)*1.30));
            }
        }
        if(found==null||found.equals(start))return List.of();
        List<Vec3> result=new ArrayList<>();
        for(BlockPos cursor=found;!cursor.equals(start);cursor=parent.get(cursor))
            result.add(new Vec3(cursor.getX()+.5,cursor.getY(),cursor.getZ()+.5));
        java.util.Collections.reverse(result);
        return List.copyOf(result);
    }
}
