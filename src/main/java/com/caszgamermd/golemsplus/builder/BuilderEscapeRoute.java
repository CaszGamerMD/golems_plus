package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.worker.WorkerRoutePlanner;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Pre-interaction escape contract shared by placement and demolition.
 *
 * Preview the AFTER-action topology, with the target forbidden as a path
 * cell AND as footing, even if it is currently solid. A prospective new
 * block is treated as a full collision volume (conservative for slabs,
 * stairs and thin blocks). Require a complete traversable trained route
 * from the worker's actual feet to a separate supported refuge at least
 * two steps away. One neighboring empty air cell is never proof of escape.
 *
 * This is read-only, never modifies the world, never issues navigation,
 * never teleports, and never treats partial A* paths as a proof.
 */
public final class BuilderEscapeRoute {
    public enum Action { PLACE, BREAK }
    private static final int RADIUS=5;
    private static final int MIN_ROUTE_STEPS=2;
    private BuilderEscapeRoute(){}

    private static double floor(ServerLevel world,BlockPos feet){
        BlockState here=world.getBlockState(feet);
        VoxelShape inside=here.getCollisionShape(world,feet);
        boolean partial=here.getBlock() instanceof StairBlock
                ||here.getBlock() instanceof SlabBlock
                    &&here.getValue(SlabBlock.TYPE)!=SlabType.DOUBLE;
        if(partial&&!inside.isEmpty())
            return feet.getY()+inside.max(Direction.Axis.Y);
        BlockPos below=feet.below();
        VoxelShape foundation=world.getBlockState(below)
                .getCollisionShape(world,below);
        if(foundation.isEmpty())return Double.NaN;
        double y=below.getY()+foundation.max(Direction.Axis.Y);
        if(!inside.isEmpty()){
            // The only legal occupied-footing case is partial support.
            // Full blocks (including the target) are not a route cell.
            return Double.NaN;
        }
        return y;
    }

    private static AABB body(BuilderGolem worker,BlockPos cell,double y){
        double half=worker.getBbWidth()*.5;
        return new AABB(cell.getX()+.5-half,y+.01,
                cell.getZ()+.5-half,
                cell.getX()+.5+half,y+worker.getBbHeight()-.025,
                cell.getZ()+.5+half);
    }

    private static boolean independent(ServerLevel world,BuilderGolem worker,
            BlockPos feet,BlockPos target,AABB future){
        if(feet.equals(target)||feet.below().equals(target)
                ||!WorkerNavigation.canStandOn(world,worker,feet))
            return false;
        double y=floor(world,feet);
        if(!Double.isFinite(y))return false;
        AABB occupied=body(worker,feet,y);
        return future==null||!occupied.intersects(future);
    }

    private static boolean avoidsFuture(List<BlockPos> steps,
            BlockPos start,BuilderGolem worker,ServerLevel world,AABB future,
            BlockPos forbidden){
        BlockPos from=start;
        for(BlockPos to:steps){
            if(to.equals(forbidden)||to.below().equals(forbidden)
                    ||!independent(world,worker,to,forbidden,future))
                return false;
            if(future!=null){
                // Swept body samples check the POST-placement obstruction,
                // not only the destination block center. Conservative
                // horizontal interpolation for vanilla step/jump movement.
                double ay=floor(world,from),by=floor(world,to);
                if(!Double.isFinite(ay)||!Double.isFinite(by))return false;
                AABB initial=body(worker,from,ay);
                for(int n=1;n<=8;n++){
                    double f=n/8.0;
                    double y=ay+(by-ay)*f;
                    AABB swept=initial.move(
                            (to.getX()-from.getX())*f,y-ay,
                            (to.getZ()-from.getZ())*f);
                    if(swept.intersects(future))return false;
                }
            }
            from=to;
        }
        return true;
    }

    /**
     * A stable corridor shared by up to THREE demolition targets. The
     * entire group is treated as already absent for every route cell,
     * footing and headroom position. No prospective target can be used as
     * an escape platform, regardless of when it is eventually removed.
     *
     * These are REAL coordinates, not an alternate Minecraft world; all
     * other physical collision and footing checks use the live server.
     * A complete trained A* to a 2+ step refuge is mandatory.
     */
    public static List<BlockPos> previewBreakGroup(ServerLevel world,
            BuilderGolem worker,BlockPos stance,List<BlockPos> targets,
            boolean atStance){
        if(world==null||worker==null||stance==null
                ||!validGroup(world,targets)
                ||atStance&&(!worker.onGround()
                        ||!worker.blockPosition().equals(stance))
                ||!independentGroup(world,worker,stance,targets))
            return null;
        List<BlockPos> refuges=new ArrayList<>();
        for(int dx=-RADIUS;dx<=RADIUS;dx++)
            for(int dz=-RADIUS;dz<=RADIUS;dz++){
                int span=Math.max(Math.abs(dx),Math.abs(dz));
                if(span<MIN_ROUTE_STEPS||span>RADIUS)continue;
                for(int dy:new int[]{0,-1,1,-2}){
                    BlockPos refuge=stance.offset(dx,dy,dz);
                    if(!independentGroup(world,worker,refuge,targets))
                        continue;
                    boolean neighbor=false;
                    for(Direction side:Direction.Plane.HORIZONTAL){
                        for(int step:new int[]{0,-1,1}){
                            if(independentGroup(world,worker,
                                    refuge.relative(side).offset(0,step,0),
                                    targets)){
                                neighbor=true;break;
                            }
                        }
                        if(neighbor)break;
                    }
                    if(neighbor)refuges.add(refuge);
                }
            }
        refuges.sort(Comparator.comparingDouble(p->p.distSqr(stance)));
        if(refuges.isEmpty())return null;
        var route=WorkerRoutePlanner.plan(stance,refuges,
                p->independentGroup(world,worker,p,targets),
                p->floor(world,p),null,p->0.0,2);
        if(route==null||!route.complete()
                ||route.steps().size()<MIN_ROUTE_STEPS
                ||!groupPathStillValid(world,worker,stance,
                        targets,route.steps()))
            return null;
        if(atStance&&WorkerNavigation.selectExecutableStand(
                world,worker,List.of(route.steps().get(0)))==null)
            return null;
        return List.copyOf(route.steps());
    }

    private static boolean validGroup(ServerLevel world,List<BlockPos> targets){
        if(targets==null||targets.isEmpty()||targets.size()>3)
            return false;
        for(int i=0;i<targets.size();i++){
            BlockPos p=targets.get(i);
            if(p==null||!world.hasChunkAt(p))return false;
            for(int j=0;j<i;j++){
                // At most TWO blocks apart, in actual 3D space, for
                // every pair (not merely chained via a central member).
                if(p.equals(targets.get(j))
                        ||p.distSqr(targets.get(j))>4.0)return false;
            }
        }
        return true;
    }

    private static boolean independentGroup(ServerLevel world,
            BuilderGolem worker,BlockPos feet,List<BlockPos> targets){
        if(!world.hasChunkAt(feet)
                ||!WorkerNavigation.canStandOn(world,worker,feet)
                ||!Double.isFinite(floor(world,feet)))
            return false;
        // Head, body and footing never use the target's volume.
        for(BlockPos target:targets)
            if(feet.equals(target)||feet.below().equals(target)
                    ||feet.above().equals(target))
                return false;
        return true;
    }

    /**
     * Recheck EXACTLY the cached escape corridor, not a newly chosen
     * replacement path. Reuse this after every block is removed.
     * Return false rather than silently switching to an untested route.
     */
    public static boolean groupPathStillValid(ServerLevel world,
            BuilderGolem worker,BlockPos stance,List<BlockPos> targets,
            List<BlockPos> route){
        if(world==null||worker==null||stance==null
                ||!validGroup(world,targets)||route==null
                ||route.size()<MIN_ROUTE_STEPS
                ||!independentGroup(world,worker,stance,targets))
            return false;
        BlockPos from=stance;
        for(BlockPos next:route){
            if(!independentGroup(world,worker,next,targets))
                return false;
            int dx=Math.abs(next.getX()-from.getX());
            int dz=Math.abs(next.getZ()-from.getZ());
            if(dx>1||dz>1||dx+dz==0)return false;
            double dy=floor(world,next)-floor(world,from);
            if(!Double.isFinite(dy)||dy>1.01||dy< -2.01)
                return false;
            // A diagonal is legal only with both clear, supported
            // cardinal corridors and no sudden step height.
            if(dx==1&&dz==1){
                if(Math.abs(dy)>.6001)return false;
                BlockPos sideX=new BlockPos(next.getX(),from.getY(),
                        from.getZ());
                BlockPos sideZ=new BlockPos(from.getX(),from.getY(),
                        next.getZ());
                if(!independentGroup(world,worker,sideX,targets)
                        ||!independentGroup(world,worker,sideZ,targets))
                    return false;
            }
            from=next;
        }
        return true;
    }

    /** Return the first independent refuge, or null if no complete
     * post-action route was proven. Never accepts a start node that
     * relies on the block that is about to be removed.
     */
    public static BlockPos preview(ServerLevel world,BuilderGolem worker,
            BlockPos target,Action action){
        return previewAt(world,worker,worker.blockPosition(),
                target,action,true);
    }

    /** Planning-stage preview from an as-yet-unoccupied work stance.
     * This ONLY rejects unsafe future poses; it never authorizes
     * interaction. At the block action the actual grounded worker must
     * run preview() again, including its physically executable first leg.
     */
    public static BlockPos previewFrom(ServerLevel world,BuilderGolem worker,
            BlockPos plannedStance,BlockPos target,Action action){
        return previewAt(world,worker,plannedStance,target,action,false);
    }
    private static BlockPos previewAt(ServerLevel world,BuilderGolem worker,
            BlockPos start,BlockPos target,Action action,boolean actual){
        if(world==null||worker==null||start==null||target==null
                ||action==null||actual&&!worker.onGround()
                ||!world.hasChunkAt(target))
            return null;
        AABB future=action==Action.PLACE?new AABB(target):null;
        if(!independent(world,worker,start,target,future))
            return null;
        List<BlockPos> candidates=new ArrayList<>();
        for(int dx=-RADIUS;dx<=RADIUS;dx++)
            for(int dz=-RADIUS;dz<=RADIUS;dz++)
                for(int dy:new int[]{0,-1,1,-2}){
                    // Manhattan distance 2 includes the diagonal
                    // neighbor (+1,+1), which is a SINGLE trained move.
                    // Require two actual horizontal moves (Chebyshev).
                    int span=Math.max(Math.abs(dx),Math.abs(dz));
                    if(span<MIN_ROUTE_STEPS||span>RADIUS)continue;
                    BlockPos tile=start.offset(dx,dy,dz);
                    if(!independent(world,worker,tile,target,future))
                        continue;
                    // Do not nominate another one-cell isolated perch.
                    int independentNeighbors=0;
                    for(Direction side:Direction.Plane.HORIZONTAL)
                        for(int step:new int[]{0,1,-1}){
                            BlockPos neighbor=tile.relative(side)
                                    .offset(0,step,0);
                            if(independent(world,worker,neighbor,target,future)){
                                independentNeighbors++;
                                break;
                            }
                        }
                    if(independentNeighbors<1)continue;
                    candidates.add(tile);
                }
        candidates.sort(Comparator.comparingDouble(
                p->p.distSqr(start)));
        if(candidates.isEmpty())return null;
        // A* never sees the target as traversable/supporting terrain;
        // every enumerated node uses the same live standability rules.
        var route=WorkerRoutePlanner.plan(start,candidates,
                p->independent(world,worker,p,target,future),
                p->floor(world,p),target,p->0.0,2);
        if(route==null||!route.complete()
                ||route.steps().size()<MIN_ROUTE_STEPS
                ||!avoidsFuture(route.steps(),start,worker,world,
                        future,target))
            return null;
        // A trained physical first leg must be executable too. Merely
        // finding a theoretical complete grid route is not enough.
        BlockPos first=route.steps().get(0);
        if(actual&&WorkerNavigation.selectExecutableStand(
                world,worker,List.of(first))==null)return null;
        return route.steps().get(route.steps().size()-1).immutable();
    }
}
