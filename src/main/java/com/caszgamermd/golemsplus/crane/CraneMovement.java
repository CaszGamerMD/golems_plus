package com.caszgamermd.golemsplus.crane;

import java.util.*;
import net.minecraft.core.*;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.*;
import net.minecraft.world.level.block.Blocks;
import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.builder.BuilderGolem;

/** Collision checks cover the chassis, load, telescoping boom and swept movement. */
final class CraneMovement {
    static Vec3 anchorPoint(ServerLevel world,BlockPos rail){
        var state=world.getBlockState(rail);
        if(state.is(CraneContent.RAIL)){
            Direction d=state.getValue(CraneRailBlock.FACING);
            return Vec3.atCenterOf(rail).add(d.getStepX()*1.3,d.getStepY()*.65,d.getStepZ()*1.3);
        }
        return Vec3.atBottomCenterOf(rail);
    }
    static boolean clear(ServerLevel world,BuildingCrane crane,AABB box){
        for(BlockPos p:BlockPos.betweenClosed(BlockPos.containing(box.minX,box.minY,box.minZ),
                BlockPos.containing(box.maxX,box.maxY,box.maxZ)))
            if(!world.hasChunkAt(p)||!world.isInWorldBounds(p))return false;
        return !world.getBlockCollisions(crane,box).iterator().hasNext();
    }
    static AABB chassis(Vec3 p){return new AABB(p.x-.65,p.y,p.z-.65,p.x+.65,p.y+2.8,p.z+.65);}
    static AABB load(BuildingCrane crane,Vec3 p){double h=crane.supplyMode()?2.05:1.1;
        return new AABB(p.x-h,p.y,p.z-h,p.x+h,p.y+2.7,p.z+h);}
    static boolean pose(ServerLevel world,BuildingCrane crane,Vec3 load,Vec3 anchor){
        Vec3 root=anchor.add(0,2.9,0),tip=load.add(0,2.8,0);
        if(root.distanceTo(tip)>crane.reach()||!clear(world,crane,chassis(anchor))
                ||!clear(world,crane,load(crane,load)))return false;
        int samples=Math.max(1,(int)Math.ceil(root.distanceTo(tip)*2));
        for(int i=0;i<=samples;i++){
            Vec3 at=root.lerp(tip,(double)i/samples);
            if(!clear(world,crane,new AABB(at.x-.13,at.y-.13,at.z-.13,at.x+.13,at.y+.13,at.z+.13)))return false;
        }
        return true;
    }
    static boolean swept(ServerLevel world,BuildingCrane crane,Vec3 a,Vec3 b,Vec3 anchorA,Vec3 anchorB){
        int n=Math.max(1,(int)Math.ceil(Math.max(a.distanceTo(b),anchorA.distanceTo(anchorB))/.25));
        for(int i=1;i<=n;i++)if(!pose(world,crane,a.lerp(b,(double)i/n),anchorA.lerp(anchorB,(double)i/n)))return false;
        return true;
    }
    static List<Vec3> loadPath(ServerLevel world,BuildingCrane crane,Vec3 target){
        Vec3 anchor=crane.anchorPoint();
        if(anchor.distanceTo(target)>crane.reach()+1)return null;
        Vec3 from=crane.position();
        if(swept(world,crane,from,target,anchor,anchor))
            return new ArrayList<>(List.of(target));
        // A raised basket must approach an elevated platform from ABOVE:
        // a direct diagonal can tunnel into the platform underside even
        // though an unobstructed overhead route exists. Every full swept
        // segment is collision-tested against the real world and boom.
        // Include a brief horizontal departure for a basket initially
        // under a roof or upper landing before lifting past the platform.
        double clearanceY=Math.max(from.y,target.y)+3.5;
        for(Vec3 side:List.of(Vec3.ZERO,
                new Vec3(0,0,-4),new Vec3(0,0,4),
                new Vec3(-4,0,0),new Vec3(4,0,0))){
            Vec3 offset=from.add(side);
            Vec3 highFrom=new Vec3(offset.x,clearanceY,offset.z);
            Vec3 highTo=new Vec3(target.x,clearanceY,target.z);
            if(!swept(world,crane,from,offset,anchor,anchor)
                    ||!swept(world,crane,offset,highFrom,anchor,anchor)
                    ||!swept(world,crane,highFrom,highTo,anchor,anchor)
                    ||!swept(world,crane,highTo,target,anchor,anchor))continue;
            var route=new ArrayList<Vec3>();
            if(from.distanceTo(offset)>.01)route.add(offset);
            route.add(highFrom);route.add(highTo);route.add(target);
            return route;
        }
        var start=cell(crane.blockPosition());var goal=cell(BlockPos.containing(target));
        var valid=new HashMap<CranePath.Cell,Boolean>();
        List<CranePath.Cell> path=CranePath.find(start,goal::equals,p->p.distance(goal),
                p->valid.computeIfAbsent(p,key->pose(world,crane,point(key),anchor)),
                (a,b)->swept(world,crane,point(a),point(b),anchor,anchor),1200);
        if(path==null)return null;
        List<Vec3> result=new ArrayList<>();for(var p:path)result.add(point(p));result.add(target);
        Vec3 previous=crane.position();for(Vec3 next:result){
            if(!swept(world,crane,previous,next,anchor,anchor))return null;previous=next;
        }
        return result;
    }
    static List<BlockPos> railPath(ServerLevel world,BuildingCrane crane,Vec3 target){
        BlockPos start=crane.anchor();if(!world.getBlockState(start).is(CraneContent.RAIL))return null;
        Vec3 offset=crane.position().subtract(crane.anchorPoint());
        List<CranePath.Cell> path=CranePath.find(cell(start),p->{Vec3 at=anchorPoint(world,pos(p));
                    return at.distanceTo(target)<=crane.reach()-2&&pose(world,crane,target,at);},
                p->Math.max(0,(int)(point(p).distanceTo(target)-crane.reach()+2)),
                p->world.hasChunkAt(pos(p))&&world.getBlockState(pos(p)).is(CraneContent.RAIL)
                    &&world.getBlockState(pos(p)).canSurvive(world,pos(p)),
                (a,b)->{Vec3 x=anchorPoint(world,pos(a)),y=anchorPoint(world,pos(b));
                    return swept(world,crane,x.add(offset),y.add(offset),x,y);},400);
        return path==null?null:path.stream().map(CraneMovement::pos).toList();
    }
    static BlockPos landing(ServerLevel world,BuildingCrane crane,BuilderGolem worker,BlockPos target){
        var candidates=new ArrayList<BlockPos>();
        for(int dy=-2;dy<=1;dy++)for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++){
            BlockPos p=target.offset(dx,dy,dz);
            if(p.distSqr(target)<=12)candidates.add(p);
        }
        // Try near landings first. Collision checks on a full supply box are
        // expensive, and the closest safe dock is the one builders should use.
        candidates.sort(Comparator.comparingDouble(p->p.distSqr(target)));
        for(BlockPos p:candidates)
            if(WorkerNavigation.canStandOn(world,worker,p)
                    &&clear(world,crane,load(crane,Vec3.atBottomCenterOf(p))))return p;
        return null;
    }
    static CranePath.Cell cell(BlockPos p){return new CranePath.Cell(p.getX(),p.getY(),p.getZ());}
    static BlockPos pos(CranePath.Cell p){return new BlockPos(p.x(),p.y(),p.z());}
    static Vec3 point(CranePath.Cell p){return new Vec3(p.x()+.5,p.y(),p.z()+.5);}
}

