package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Plan A: a Builder stands at the SAME feet Y as the work cell.
 * If it cannot walk a legal 0/1-block elevation change, use a tower
 * exactly three blocks away along X or Z and a two-cell scaffold bridge.
 *
 * The return route is reserved before construction, and is walked back
 * in reverse before descending. Every scaffold comes from real inventory.
 * No teleportation, synthetic path, side-attached scaffold, or old-AI fallback.
 */
public final class BuilderScaffoldPlanA {
    public enum Result { MOVING, NEED_MATERIALS, NO_SAFE_ROUTE, READY }
    private enum Phase {
        APPROACH, BUILD, CLIMB, EXTEND_FIRST, WALK_FIRST,
        EXTEND_SECOND, WALK_SECOND, READY,
        RETURN_FIRST, RETURN_TOWER, DESCEND, DISMOUNT
    }
    private static final class Job {
        final BlockPos target,base,exit;
        final Direction towardTarget;
        final int top;
        Phase phase=Phase.APPROACH;
        long lastProgress;
        int lastTick;
        double lastDistance=Double.POSITIVE_INFINITY;
        Job(BlockPos target,BlockPos base,BlockPos exit,
                Direction direction,int top,long now){
            this.target=target.immutable();
            this.base=base.immutable();
            this.exit=exit.immutable();
            this.towardTarget=direction;
            this.top=top;
            this.lastProgress=now;
        }
        BlockPos first(){return base.relative(towardTarget).atY(top);}
        BlockPos second(){return base.relative(towardTarget,2).atY(top);}
    }
    private static final Map<BuilderGolem,Job> jobs=new WeakHashMap<>();
    private BuilderScaffoldPlanA(){}

    public static boolean hasPlan(BuilderGolem worker){return jobs.containsKey(worker);}
    public static boolean controlsMotion(BuilderGolem worker){
        Job j=jobs.get(worker);
        return j!=null&&switch(j.phase){
            case WALK_FIRST,WALK_SECOND,RETURN_FIRST,RETURN_TOWER->true;
            default->false;
        };
    }
    public static String status(BuilderGolem worker){
        Job j=jobs.get(worker);
        return j==null?"NONE":"PLAN_A "+j.phase+" tower="+j.base.toShortString()
                +" bridge="+j.first().toShortString()+","+j.second().toShortString()
                +" targetY="+j.target.getY()+" exit="+j.exit.toShortString();
    }
    public static void cancel(BuilderGolem worker){jobs.remove(worker);}

    private static Set<BlockPos> future(List<BuilderConstructionPlan.Step> steps){
        Set<BlockPos> result=new HashSet<>();
        for(var step:steps)if(!step.ignorePlant())
            result.add(new BlockPos(step.x(),step.y(),step.z()));
        return result;
    }
    /** The two bridge cells lie along the axis from the tower toward
     * the work, at y=targetY-1. Their heads must stay free all the way
     * back: never approve a temporary route inside future architecture.
     */
    private static boolean bridgeClear(ServerLevel world,BlockPos base,
            Direction toward,int workY,Set<BlockPos> reserved,
            WorkerStationBlockEntity table,BuilderGolem worker){
        for(int n=1;n<=2;n++){
            BlockPos p=base.relative(toward,n).atY(workY-1);
            if(!world.hasChunkAt(p)||reserved.contains(p)
                    ||reserved.contains(p.above())
                    ||reserved.contains(p.above(2)))return false;
            if(world.getBlockState(p).is(Blocks.SCAFFOLDING)){
                if(!table.scaffoldPositions(worker.getUUID()).contains(p))
                    return false;
            }else if(!world.getBlockState(p).isAir())return false;
            if(!world.getBlockState(p.above()).isAir()
                    ||!world.getBlockState(p.above(2)).isAir())return false;
        }
        return true;
    }
    private static boolean shaftClear(ServerLevel world,BlockPos base,int top,
            Set<BlockPos> reserved,WorkerStationBlockEntity table,
            BuilderGolem worker){
        if(base.getY()>top||!world.hasChunkAt(base.below())
                ||!world.getBlockState(base.below()).isFaceSturdy(
                        world,base.below(),Direction.UP))return false;
        for(int y=base.getY();y<=top;y++){
            BlockPos p=base.atY(y);
            if(!world.hasChunkAt(p)||reserved.contains(p))return false;
            if(world.getBlockState(p).is(Blocks.SCAFFOLDING)){
                if(!table.scaffoldPositions(worker.getUUID()).contains(p))
                    return false;
            }else if(!world.getBlockState(p).isAir())return false;
        }
        return true;
    }
    /** Prefer a previously erected owned axis-3 tower; otherwise
     * choose a genuinely reachable ground shaft with a verified exit.
     * No diagonal tower in Plan A.
     */
    private static Job choose(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,BlockPos target,
            List<BuilderConstructionPlan.Step> steps){
        Set<BlockPos> reserved=future(steps);
        List<Job> options=new ArrayList<>();
        for(Direction outward:Direction.Plane.HORIZONTAL){
            BlockPos towerXZ=target.relative(outward,3);
            Direction inward=outward.getOpposite();
            for(int dy=0;dy<=5;dy++){
                BlockPos base=towerXZ.atY(worker.blockPosition().getY()-dy);
                int top=target.getY()-1;
                if(!shaftClear(level,base,top,reserved,table,worker)
                        ||!bridgeClear(level,base,inward,target.getY(),
                                reserved,table,worker))continue;
                BlockPos exit=BuilderScaffolding.safeGroundExit(
                        level,worker,base,reserved);
                if(exit==null)continue;
                int existing=0;
                for(int y=base.getY();y<=top;y++){
                    if(level.getBlockState(base.atY(y)).is(Blocks.SCAFFOLDING))
                        existing++;
                }
                for(int n=1;n<=2;n++)if(level.getBlockState(
                        base.relative(inward,n).atY(top)).is(Blocks.SCAFFOLDING))
                    existing++;
                double score=BuilderScaffoldSiteScore.score(base,
                        worker.blockPosition(),target,
                        Math.max(0,top-base.getY()+3-existing),0,0,
                        existing>0);
                // Extra credit for not changing X/Z aisle from worker.
                score+=Math.abs(worker.getX()-base.getX()-.5)
                        +Math.abs(worker.getZ()-base.getZ()-.5);
                Job candidate=new Job(target,base,exit,inward,top,
                        level.getGameTime());
                candidate.lastDistance=score;
                options.add(candidate);
            }
        }
        options.sort(Comparator.comparingDouble(j->j.lastDistance));
        int probes=0;
        for(Job candidate:options){
            if(++probes>24)break;
            // A scaffold already occupying its own base is not a native
            // walking goal. In that case validate a real adjacent entry.
            boolean onBase=worker.blockPosition().getX()==candidate.base.getX()
                    &&worker.blockPosition().getZ()==candidate.base.getZ();
            if(onBase||WorkerNavigation.canReachExact(
                    level,worker,candidate.base))return candidate;
        }
        return null;
    }
    private static boolean present(ServerLevel level,WorkerStationBlockEntity table,
            BuilderGolem worker,BlockPos at){
        return level.getBlockState(at).is(Blocks.SCAFFOLDING)
                &&table.scaffoldPositions(worker.getUUID()).contains(at);
    }
    private static Result place(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,ServerPlayer owner,BlockPos at,
            BlockPos previous){
        if(present(level,table,worker,at))return Result.READY;
        if(!level.hasChunkAt(at)||!level.mayInteract(owner,at)
                ||!level.getBlockState(at).isAir()
                ||!present(level,table,worker,previous)
                ||!level.getBlockState(at.above()).isAir()
                ||!level.getBlockState(at.above(2)).isAir())
            return Result.NO_SAFE_ROUTE;
        var load=BuilderSupplyInventory.loadScaffold(level,worker,table,1);
        if(load==BuilderSupplyInventory.ScaffoldLoad.MOVING)return Result.MOVING;
        if(load==BuilderSupplyInventory.ScaffoldLoad.MISSING)
            return Result.NEED_MATERIALS;
        var receipt=BuilderSupplyInventory.consume(worker,Items.SCAFFOLDING);
        if(receipt==null)return Result.NEED_MATERIALS;
        if(!level.setBlock(at,Blocks.SCAFFOLDING.defaultBlockState(),3)
                ||!level.getBlockState(at).is(Blocks.SCAFFOLDING)){
            BuilderSupplyInventory.refund(table,receipt);
            return Result.NO_SAFE_ROUTE;
        }
        if(!table.trackScaffold(worker.getUUID(),at)){
            level.setBlock(at,Blocks.AIR.defaultBlockState(),3);
            BuilderSupplyInventory.refund(table,receipt);
            return Result.NO_SAFE_ROUTE;
        }
        return Result.READY;
    }
    private static Result shaft(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,Job j){
        int existingTop=j.base.getY()-1;
        for(int y=j.base.getY();y<=j.top;y++)
            if(present(level,table,worker,j.base.atY(y)))
                existingTop=y;
            else break;
        int top=ScaffoldVerticalRoute.stageTop(
                j.base.getY(),existingTop,j.top);
        int count=0;
        for(int y=j.base.getY();y<=top;y++){
            BlockPos p=j.base.atY(y);
            if(present(level,table,worker,p))continue;
            if(!level.getBlockState(p).isAir())return Result.NO_SAFE_ROUTE;
            count++;
        }
        var load=BuilderSupplyInventory.loadScaffold(
                level,worker,table,count);
        if(load==BuilderSupplyInventory.ScaffoldLoad.MOVING)return Result.MOVING;
        if(load==BuilderSupplyInventory.ScaffoldLoad.MISSING)
            return Result.NEED_MATERIALS;
        for(int y=j.base.getY();y<=top;y++){
            BlockPos at=j.base.atY(y);
            if(present(level,table,worker,at))continue;
            if(!level.getBlockState(at.below()).isFaceSturdy(
                    level,at.below(),Direction.UP)
                    &&!present(level,table,worker,at.below()))
                return Result.NO_SAFE_ROUTE;
            var item=BuilderSupplyInventory.consume(worker,Items.SCAFFOLDING);
            if(item==null)return Result.NEED_MATERIALS;
            if(!level.setBlock(at,Blocks.SCAFFOLDING.defaultBlockState(),3)
                    ||!table.trackScaffold(worker.getUUID(),at)){
                if(level.getBlockState(at).is(Blocks.SCAFFOLDING))
                    level.setBlock(at,Blocks.AIR.defaultBlockState(),3);
                BuilderSupplyInventory.refund(table,item);
                return Result.NO_SAFE_ROUTE;
            }
        }
        j.phase=Phase.CLIMB;
        BuilderScaffolding.begin(level,worker,j.base,top+1.0,false);
        return Result.MOVING;
    }
    private static boolean close(BuilderGolem worker,BlockPos p){
        return worker.distanceToSqr(p.getX()+.5,p.getY(),
                p.getZ()+.5)<.12
                &&Math.abs(worker.getY()-p.getY())<.30;
    }
    /** Physical bridge motion is reserved exclusively to the scaffold
     * controller; vanilla A* cannot treat an air-above-scaffold cell as
     * a generic walkable ground node. Keep gravity and actual collisions.
     */
    public static boolean tickMotion(ServerLevel level,BuilderGolem worker){
        Job j=jobs.get(worker);
        if(j==null)return false;
        BlockPos destination=switch(j.phase){
            case WALK_FIRST,RETURN_FIRST->j.first().above();
            case WALK_SECOND->j.second().above();
            case RETURN_TOWER->j.base.atY(j.target.getY());
            default->null;
        };
        if(destination==null)return false;
        if(!present(level,(WorkerStationBlockEntity)level.getBlockEntity(
                worker.home()),worker,destination.below())){
            return true; // never deliberately step onto unsupported air
        }
        worker.getNavigation().stop();
        worker.setJumping(false);
        worker.setShiftKeyDown(false);
        worker.setNoGravity(false);
        double tx=destination.getX()+.5,tz=destination.getZ()+.5;
        if(close(worker,destination)){
            j.phase=switch(j.phase){
                case WALK_FIRST->Phase.EXTEND_SECOND;
                case WALK_SECOND->Phase.READY;
                case RETURN_FIRST->Phase.RETURN_TOWER;
                case RETURN_TOWER->Phase.DESCEND;
                default->j.phase;
            };
            j.lastProgress=level.getGameTime();
            return true;
        }
        double dx=tx-worker.getX(),dz=tz-worker.getZ();
        double mx=Math.max(-.12,Math.min(.12,dx*.3));
        double mz=Math.max(-.12,Math.min(.12,dz*.3));
        if(level.noCollision(worker,worker.getBoundingBox().move(mx,0,mz))){
            worker.getMoveControl().setWantedPosition(tx,destination.getY(),tz,1.0);
            var v=worker.getDeltaMovement();
            worker.setDeltaMovement(mx,v.y,mz);
        }
        return true;
    }
    public static Result reach(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,ServerPlayer owner,
            BlockPos target,List<BuilderConstructionPlan.Step> steps){
        Job j=jobs.get(worker);
        if(j!=null&&!j.target.equals(target)){
            if(worker.blockPosition().getY()==target.getY()
                    &&worker.distanceToSqr(target.getX()+.5,
                            target.getY()+.5,target.getZ()+.5)<=4
                    &&j.phase==Phase.READY)return Result.READY;
            if(returnToGround(level,worker,table))return Result.MOVING;
            j=null;
        }
        if(j==null){
            j=choose(level,worker,table,target,steps);
            if(j==null)return Result.NO_SAFE_ROUTE;
            jobs.put(worker,j);
        }
        switch(j.phase){
            case APPROACH -> {
                if(Math.abs(worker.getX()-j.base.getX()-.5)>.3
                        ||Math.abs(worker.getZ()-j.base.getZ()-.5)>.3
                        ||Math.abs(worker.getY()-j.base.getY())>.4){
                    WorkerNavigation.routeToExact(level,worker,j.base,1.0);
                    return Result.MOVING;
                }
                j.phase=Phase.BUILD;
            }
            case CLIMB -> {
                if(worker.getY()<j.target.getY()-.15){
                    // Full vertical column, no mandatory jump while climbing.
                    return Result.MOVING;
                }
                if(j.top>j.target.getY()-1)return Result.NO_SAFE_ROUTE;
                j.phase=Phase.EXTEND_FIRST;
            }
            case EXTEND_FIRST -> {
                Result result=place(level,worker,table,owner,j.first(),j.base.atY(j.top));
                if(result!=Result.READY)return result;
                j.phase=Phase.WALK_FIRST;
                return Result.MOVING;
            }
            case EXTEND_SECOND -> {
                Result result=place(level,worker,table,owner,j.second(),j.first());
                if(result!=Result.READY)return result;
                j.phase=Phase.WALK_SECOND;
                return Result.MOVING;
            }
            case READY -> {
                if(worker.blockPosition().getY()!=target.getY())
                    return Result.MOVING;
                return Result.READY;
            }
            case RETURN_FIRST,RETURN_TOWER,DESCEND,DISMOUNT,
                    WALK_FIRST,WALK_SECOND-> {return Result.MOVING;}
            default -> {}
        }
        if(j.phase==Phase.BUILD)return shaft(level,worker,table,j);
        return Result.MOVING;
    }
    public static boolean returnToGround(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table){
        Job j=jobs.get(worker);
        if(j==null)return false;
        if(j.phase==Phase.APPROACH
                ||j.phase==Phase.BUILD&&worker.getY()<=j.base.getY()+.2){
            jobs.remove(worker);
            return false;
        }
        if(j.phase==Phase.READY||j.phase==Phase.WALK_SECOND){
            j.phase=Phase.RETURN_FIRST;
            return true;
        }
        if(j.phase==Phase.RETURN_FIRST||j.phase==Phase.RETURN_TOWER)
            return true;
        if(j.phase==Phase.CLIMB){
            if(worker.getY()>j.base.getY()+1.1)
                BuilderScaffolding.begin(level,worker,j.base,
                        j.base.getY()+1.0,true);
            j.phase=Phase.DESCEND;
            return true;
        }
        if(j.phase==Phase.DESCEND){
            if(worker.getY()>j.base.getY()+1.16)return true;
            j.phase=Phase.DISMOUNT;
        }
        if(j.phase==Phase.DISMOUNT){
            if(close(worker,j.exit)&&worker.onGround()){
                jobs.remove(worker);return false;
            }
            if(!BuilderScaffolding.isControllingMovement(worker)){
                BuilderScaffolding.dismountAtBase(level,worker,j.base);
            }
            return true;
        }
        return true;
    }
}
