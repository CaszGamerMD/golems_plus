package com.caszgamermd.golemsplus.builder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;

/**
 * Escape-only 3D air search. Reach the real OnSite chest without breaking
 * the Dome, phasing through solids, teleporting, or fabricating materials.
 * A closed door is traversable ONLY when it can be opened in-world.
 * The movement controller opens that exact door before crossing it.
 */
public final class BuilderCreativeEscapeRoute {
    private static final int MAX_EXPANSIONS=24000;
    private static final int MAX_HORIZONTAL=92;
    private static final int MAX_VERTICAL=56;
    private static final int[][] DIRECTIONS={
            {0,-1,0},{0,0,1},{1,0,0},{0,0,-1},{-1,0,0},{0,1,0}
    };
    private record Candidate(BlockPos at,int cost,double priority){}
    private BuilderCreativeEscapeRoute(){}

    private static AABB bodyAt(BuilderGolem worker,BlockPos p){
        return worker.getBoundingBox().move(p.getX()+.5-worker.getX(),
                p.getY()-worker.getY(),p.getZ()+.5-worker.getZ());
    }

    /** Plans through CLOSED doors without opening them during exploration.
     * Block collision checking is real for everything except door panels
     * that the executor will physically open before entering their volume.
     */
    public static boolean clearIncludingOpenableDoors(ServerLevel level,
            BuilderGolem worker,AABB bounds){
        if(level.noCollision(worker,bounds))return true;
        boolean sawDoor=false;
        for(int x=(int)Math.floor(bounds.minX);x<Math.ceil(bounds.maxX);x++)
            for(int y=(int)Math.floor(bounds.minY);y<Math.ceil(bounds.maxY);y++)
                for(int z=(int)Math.floor(bounds.minZ);z<Math.ceil(bounds.maxZ);z++){
                    BlockPos pos=new BlockPos(x,y,z);
                    if(!level.hasChunkAt(pos))return false;
                    BlockState state=level.getBlockState(pos);
                    if(state.getBlock() instanceof DoorBlock){
                        sawDoor=true;continue;
                    }
                    var shape=state.getCollisionShape(level,pos);
                    if(!shape.isEmpty()&&Shapes.joinIsNotEmpty(
                            Shapes.create(bounds),
                            shape.move(x,y,z),BooleanOp.AND))
                        return false;
                }
        return sawDoor;
    }

    public static List<Vec3> toDepot(ServerLevel level,BuilderGolem worker,
                                     BlockPos depot){
        if(depot==null||!level.hasChunkAt(depot))return List.of();
        BlockPos start=worker.blockPosition();
        PriorityQueue<Candidate> frontier=new PriorityQueue<>(
                Comparator.comparingDouble(Candidate::priority));
        Map<BlockPos,Integer> best=new HashMap<>();
        Map<BlockPos,BlockPos> parent=new HashMap<>();
        frontier.add(new Candidate(start,0,start.distSqr(depot)));
        best.put(start,0);
        parent.put(start,start);
        BlockPos found=null;
        int expanded=0;
        while(!frontier.isEmpty()&&expanded++<MAX_EXPANSIONS){
            Candidate current=frontier.poll();
            BlockPos at=current.at();
            if(current.cost()!=best.getOrDefault(at,Integer.MAX_VALUE))
                continue;
            AABB body=bodyAt(worker,at);
            double dx=at.getX()+.5-(depot.getX()+.5);
            double dy=at.getY()-(depot.getY()+.5);
            double dz=at.getZ()+.5-(depot.getZ()+.5);
            if(dx*dx+dy*dy+dz*dz<=8.0
                    &&clearIncludingOpenableDoors(level,worker,body)
                    &&!body.intersects(new AABB(depot))){
                found=at;break;
            }
            for(int[] direction:DIRECTIONS){
                BlockPos next=at.offset(direction[0],direction[1],direction[2]);
                if(Math.abs(next.getX()-start.getX())>MAX_HORIZONTAL
                        ||Math.abs(next.getZ()-start.getZ())>MAX_HORIZONTAL
                        ||Math.abs(next.getY()-start.getY())>MAX_VERTICAL
                        ||!level.hasChunkAt(next)||!level.hasChunkAt(next.above())
                        ||!clearIncludingOpenableDoors(level,worker,
                                bodyAt(worker,next)))continue;
                int newCost=current.cost()+1;
                if(newCost>=best.getOrDefault(next,Integer.MAX_VALUE))
                    continue;
                best.put(next,newCost);
                parent.put(next,at);
                double rx=next.getX()-depot.getX();
                double ry=next.getY()-(depot.getY()+1);
                double rz=next.getZ()-depot.getZ();
                // Mild A* guidance; the legal ground-floor door route is
                // usually shorter than flying against the finished roof.
                double estimate=Math.sqrt(rx*rx+ry*ry+rz*rz);
                frontier.add(new Candidate(next,newCost,
                        newCost+estimate*1.15));
            }
        }
        if(found==null)return List.of();
        if(found.equals(start))
            return List.of(worker.position());
        List<Vec3> route=new ArrayList<>();
        for(BlockPos node=found;!node.equals(start);node=parent.get(node))
            route.add(new Vec3(node.getX()+.5,node.getY(),node.getZ()+.5));
        java.util.Collections.reverse(route);
        return List.copyOf(route);
    }

    /** Resolve either door half; only actual vanilla DoorBlocks can open. */
    public static BlockPos doorBottom(ServerLevel level,BlockPos pos){
        BlockState state=level.getBlockState(pos);
        if(!(state.getBlock() instanceof DoorBlock))return null;
        return state.getValue(DoorBlock.HALF)
                ==net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER
                ?pos.below():pos;
    }

    public static boolean openDoor(ServerLevel level,BlockPos position){
        BlockPos bottom=doorBottom(level,position);
        if(bottom==null)return false;
        BlockPos upper=bottom.above();
        BlockState lowerState=level.getBlockState(bottom);
        BlockState upperState=level.getBlockState(upper);
        if(!(lowerState.getBlock() instanceof DoorBlock)
                ||!(upperState.getBlock() instanceof DoorBlock))
            return false;
        if(lowerState.getValue(DoorBlock.OPEN))return true;
        level.setBlock(bottom,lowerState.setValue(DoorBlock.OPEN,true),3);
        level.setBlock(upper,upperState.setValue(DoorBlock.OPEN,true),3);
        return true;
    }

    public static void closeDoor(ServerLevel level,BlockPos bottom){
        if(bottom==null||!level.hasChunkAt(bottom))return;
        BlockState lower=level.getBlockState(bottom);
        BlockState upper=level.getBlockState(bottom.above());
        if(lower.getBlock() instanceof DoorBlock
                &&upper.getBlock() instanceof DoorBlock){
            if(lower.getValue(DoorBlock.OPEN))
                level.setBlock(bottom,lower.setValue(DoorBlock.OPEN,false),3);
            if(upper.getValue(DoorBlock.OPEN))
                level.setBlock(bottom.above(),
                        upper.setValue(DoorBlock.OPEN,false),3);
        }
    }
}
