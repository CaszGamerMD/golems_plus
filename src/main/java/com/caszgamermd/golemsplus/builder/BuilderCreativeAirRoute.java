package com.caszgamermd.golemsplus.builder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private static final int LIMIT=6500;
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

    public static List<Vec3> plan(ServerLevel level,BuilderGolem worker,
                                  BlockPos target){
        BlockPos start=worker.blockPosition();
        AABB goalBox=new AABB(target);
        ArrayDeque<BlockPos> queue=new ArrayDeque<>();
        Map<BlockPos,BlockPos> parent=new HashMap<>();
        queue.add(start);parent.put(start,start);
        BlockPos found=null;
        while(!queue.isEmpty()&&parent.size()<LIMIT){
            BlockPos at=queue.removeFirst();
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
            // Expand closer legal air cells first, preserving BFS depth.
            options.sort(Comparator.comparingDouble(p->p.distSqr(target)));
            for(BlockPos next:options){
                if(parent.containsKey(next)
                        ||Math.abs(next.getX()-start.getX())>MAX_HORIZONTAL
                        ||Math.abs(next.getZ()-start.getZ())>MAX_HORIZONTAL
                        ||Math.abs(next.getY()-start.getY())>MAX_VERTICAL
                        ||!level.hasChunkAt(next)
                        ||!level.hasChunkAt(next.above())
                        ||!level.noCollision(worker,bodyAt(worker,next)))
                    continue;
                parent.put(next,at);
                queue.addLast(next);
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
