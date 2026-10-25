package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * Temporary physical climb tower. Every block is purchased with one real
 * scaffolding item and recorded on the station before use. Cleans only its
 * own surviving scaffold blocks (top-down), never a player's scaffolding.
 */
public final class BuilderScaffolding {
    public enum Result { CLIMBING, NEED_MATERIALS, NO_SAFE_TOWER, NOT_NEEDED }
    private static final int MAX_HEIGHT=48;
    /** Construction climb and descent are continuous actions rather than
     * five-tick velocity impulses. Each entity has its own session, and
     * ordinary A* must not override it until the vertical job completes.
     */
    private record ClimbSession(BlockPos column,double stopY,
                                boolean descending,long expires,net.minecraft.world.phys.Vec3 lastProgress){}
    private static final java.util.Map<BuilderGolem,ClimbSession> sessions=
            new java.util.WeakHashMap<>();
    private BuilderScaffolding(){}

    private static void clearMotion(BuilderGolem worker){
        sessions.remove(worker);
        worker.setJumping(false);
        worker.setShiftKeyDown(false);
        worker.setNoGravity(false);
        var velocity=worker.getDeltaMovement();
        worker.setDeltaMovement(velocity.x,Math.min(velocity.y,0),velocity.z);
    }
    public static String debugClimb(BuilderGolem worker){
        ClimbSession session=sessions.get(worker);
        return session==null?"NONE":(session.descending()?"DOWN":"UP")
                +" column="+session.column().toShortString()
                +" stopY="+String.format(java.util.Locale.ROOT,"%.1f",session.stopY());
    }
    public static boolean climbingNow(ServerLevel level,BuilderGolem worker){
        ClimbSession session=sessions.get(worker);
        if(session==null)return false;
        if(level.getGameTime()>session.expires()){
            clearMotion(worker);return false;
        }
        BlockPos pos=worker.blockPosition();
        double cx=session.column().getX()+.5,cz=session.column().getZ()+.5;
        if(Math.abs(worker.getX()-cx)>1.15||Math.abs(worker.getZ()-cz)>1.15){
            clearMotion(worker);return false;
        }
        BlockPos columnLevel=new BlockPos(session.column().getX(),
                pos.getY(),session.column().getZ());
        boolean onScaffold=level.getBlockState(columnLevel).is(Blocks.SCAFFOLDING)
                ||level.getBlockState(columnLevel.below()).is(Blocks.SCAFFOLDING)
                ||level.getBlockState(columnLevel.above()).is(Blocks.SCAFFOLDING);
        if(!onScaffold){clearMotion(worker);return false;}
        return true;
    }
    /** Runs on every ENTITY tick. Repeating a vertical impulse only every
     * 5 ticks allowed gravity/collision to cancel it, leaving golems beside
     * scaffold columns indefinitely. Using normal gravity and the mob's
     * jump/sneak controls lets vanilla resolve scaffold collisions.
     */
    public static boolean tickMotion(ServerLevel level,BuilderGolem worker){
        if(!climbingNow(level,worker))return false;
        ClimbSession session=sessions.get(worker);
        if(session==null)return false;
        double remain=session.stopY()-worker.getY();
        if((session.descending()&&remain>=-.16)
                ||(!session.descending()&&remain<=0)){
            clearMotion(worker);
            return false;
        }
        if(worker.position().distanceToSqr(session.lastProgress())>.01){
            session=new ClimbSession(session.column(),session.stopY(),session.descending(),
                    level.getGameTime()+60,worker.position());
            sessions.put(worker,session);
        }
        worker.getNavigation().stop();
        double cx=session.column().getX()+.5,cz=session.column().getZ()+.5;
        double horizontal=Math.hypot(worker.getX()-cx,worker.getZ()-cz);
        if(horizontal>Math.max(.04,(1-worker.getBbWidth())*.5-.02)){
            // Physically enter the scaffold footprint. Never teleport and
            // never force vertical motion from alongside a solid wall.
            worker.setNoGravity(false);
            worker.setJumping(false);
            worker.setShiftKeyDown(false);
            worker.getMoveControl().setWantedPosition(cx,worker.getY(),cz,1.0);
            return true;
        }
        worker.setNoGravity(false);
        if(session.descending()){
            worker.setJumping(false);
            worker.setShiftKeyDown(true);
            worker.setDeltaMovement(0,-Math.min(.16,Math.max(.07,-remain/4)),0);
        }else{
            worker.setShiftKeyDown(false);
            worker.setJumping(true);
            worker.getJumpControl().jump();
            // Keep the climb moving every tick while preserving physical
            // collision: unlike noGravity the worker cannot hover when
            // support or its assignment disappears.
            worker.setDeltaMovement(0,Math.min(.23,Math.max(.12,remain/5)),0);
        }
        return true;
    }
    private static void begin(ServerLevel level,BuilderGolem worker,
                              BlockPos column,double stopY,boolean down){
        // A session is renewed by the owning Builder task, never shared
        // across colleagues or over a remote scaffold.
        ClimbSession active=sessions.get(worker);
        if(active!=null&&active.column().equals(column)
                &&active.stopY()==stopY&&active.descending()==down)return;
        sessions.put(worker,new ClimbSession(column.immutable(),stopY,down,
                level.getGameTime()+60,worker.position()));
        com.caszgamermd.golemsplus.worker.WorkerNavigation.stop(worker);
        worker.getNavigation().stop();
    }

    /** Continue down a tracked column until feet reach its lowest safe
     * scaffold platform. The Builder must then walk away before cleanup.
     */
    public static boolean descendForTravel(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table){
        var owned=table.scaffoldPositions(worker.getUUID());
        if(owned.isEmpty())return false;
        BlockPos column=owned.stream()
                .filter(p->Math.abs(worker.getX()-p.getX()-.5)<=1.05
                        &&Math.abs(worker.getZ()-p.getZ()-.5)<=1.05)
                .min(Comparator.comparingInt(BlockPos::getY)).orElse(null);
        if(column==null||!level.getBlockState(column).is(Blocks.SCAFFOLDING))
            return false;
        double cx=column.getX()+.5,cz=column.getZ()+.5;
        if(Math.abs(worker.getX()-cx)>1.05
                ||Math.abs(worker.getZ()-cz)>1.05)return false;
        double bottom=column.getY();
        if(worker.getY()<=bottom+.16){
            clearMotion(worker);
            return false;
        }
        begin(level,worker,column,bottom,true);
        return true;
    }

    public static Result reach(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,ServerPlayer player,BlockPos target,
            List<BuilderConstructionPlan.Step> plan){
        int feet=worker.blockPosition().getY();
        // A block two levels up can still be unreachable from the
        // current X/Z. The caller enters this mode only when the
        // destination is genuinely above AND outside working reach.
        if(target.getY()-worker.getY()<=1.35){
            clearMotion(worker);
            return Result.NOT_NEEDED;
        }
        if(target.getY()-feet>MAX_HEIGHT)return Result.NO_SAFE_TOWER;
        // Prefer an existing walkable staircase or completed platform at
        // the target's exact X/Z before spending a single scaffold item.
        // Only route on real validated physical paths; never teleport.
        // Reuse a real tracked tower FIRST. Its scaffold cells are not
        // valid vanilla walking nodes, so repeatedly reconsidering a
        // path to an upper platform can abandon an active ascent.
        var existing=table.scaffoldPositions(worker.getUUID());
        BlockPos base=existing.stream()
                .filter(p->{
                    int dx=p.getX()-target.getX(),dz=p.getZ()-target.getZ();
                    return dx*dx+dz*dz<=5;
                })
                .filter(p->level.getBlockState(p).is(Blocks.SCAFFOLDING))
                .min(Comparator.comparingInt(BlockPos::getY)).orElse(null);
        if(base==null)for(Direction side:Direction.Plane.HORIZONTAL){
            BlockPos landing=target.relative(side).below();
            if(!level.hasChunkAt(landing))continue;
            if(!com.caszgamermd.golemsplus.worker.WorkerNavigation
                    .canReachExact(level,worker,landing))continue;
            // When a Builder is already on a viable existing platform,
            // it must continue to PLACE rather than re-requesting a route
            // to its own feet and returning CLIMBING forever.
            if(worker.distanceToSqr(landing.getX()+.5,
                    landing.getY()+.5,landing.getZ()+.5)<2.25){
                clearMotion(worker);
                return Result.NOT_NEEDED;
            }
            if(com.caszgamermd.golemsplus.worker.WorkerNavigation
                    .routeToExact(level,worker,landing,1.0))
                return Result.CLIMBING;
        }
        // The complete 2-block perimeter (including diagonal offsets)
        // must match both selection AND reuse, or towers are forgotten.
        if(base==null)base=findBase(level,worker,target,feet,table,plan);
        if(base==null)return Result.NO_SAFE_TOWER;
        double bx=base.getX()+.5,bz=base.getZ()+.5;
        double approachX=worker.getX()-bx,approachZ=worker.getZ()-bz;
        // Position at the foot of the selected column *first*. The old
        // implementation placed its full 48-block tower remotely, then
        // tried to walk through buildings to reach it.
        if(approachX*approachX+approachZ*approachZ>1.20){
            clearMotion(worker);
            // Existing scaffolding is not an ordinary walkable A* node.
            // Approach its unobstructed side from genuine ground, then
            // let the continuous climb session enter the column.
            Direction side=Math.abs(approachX)>=Math.abs(approachZ)
                    ?(approachX>=0?Direction.EAST:Direction.WEST)
                    :(approachZ>=0?Direction.SOUTH:Direction.NORTH);
            BlockPos approach=level.getBlockState(base).is(Blocks.SCAFFOLDING)
                    ?base.relative(side).immutable():base;
            if(!com.caszgamermd.golemsplus.worker.WorkerNavigation
                    .routeToExact(level,worker,approach,1.0))
                com.caszgamermd.golemsplus.worker.WorkerNavigation
                        .routeTo(level,worker,approach,1.0);
            return Result.CLIMBING;
        }
        int top=target.getY()-1;
        if(top-base.getY()+1>MAX_HEIGHT)return Result.NO_SAFE_TOWER;
        for(int y=top+1;y<top+1+Math.ceil(worker.getBbHeight());y++){
            BlockPos head=new BlockPos(base.getX(),y,base.getZ());
            if(!level.hasChunkAt(head)||!level.getBlockState(head)
                    .getCollisionShape(level,head).isEmpty())return Result.NO_SAFE_TOWER;
        }
        int needed=0;
        for(int y=base.getY();y<=top;y++){
            var p=new BlockPos(base.getX(),y,base.getZ());
            if(!level.hasChunkAt(p)||!level.mayInteract(player,p))
                return Result.NO_SAFE_TOWER;
            if(level.getBlockState(p).is(Blocks.SCAFFOLDING)){
                if(!table.isTrackedScaffold(p))return Result.NO_SAFE_TOWER;
            }else if(level.getBlockState(p).isAir())needed++;
            else return Result.NO_SAFE_TOWER;
        }
        var load=BuilderSupplyInventory.loadScaffold(level,worker,table,needed);
        if(load==BuilderSupplyInventory.ScaffoldLoad.MOVING)return Result.CLIMBING;
        if(load==BuilderSupplyInventory.ScaffoldLoad.MISSING)return Result.NEED_MATERIALS;
        for(int y=base.getY();y<=top;y++){
            var at=new BlockPos(base.getX(),y,base.getZ());
            if(level.getBlockState(at).is(Blocks.SCAFFOLDING))continue;
            if(!level.getBlockState(at.below()).isFaceSturdy(level,at.below(),Direction.UP)
                    &&!level.getBlockState(at.below()).is(Blocks.SCAFFOLDING))
                return Result.NO_SAFE_TOWER;
            var receipt=BuilderSupplyInventory.consume(worker,Items.SCAFFOLDING);
            if(receipt==null)return Result.NEED_MATERIALS;
            if(!level.setBlock(at,Blocks.SCAFFOLDING.defaultBlockState(),3)){
                BuilderSupplyInventory.refund(table,receipt);
                return Result.NO_SAFE_TOWER;
            }
            if(!table.trackScaffold(worker.getUUID(),at)){
                level.setBlock(at,Blocks.AIR.defaultBlockState(),3);
                BuilderSupplyInventory.refund(table,receipt);
                return Result.NO_SAFE_TOWER;
            }
        }
        double cx=base.getX()+.5,cz=base.getZ()+.5;
        double dx=worker.getX()-cx,dz=worker.getZ()-cz;
        if(dx*dx+dz*dz>1.5)return Result.CLIMBING;
        if(worker.getY()<top+.82){
            // A normal scaffold occupies the target's Y-1 cell, whose
            // walkable top is Y; climb high enough to stand on it.
            begin(level,worker,base,top+1.0,false);
            return Result.CLIMBING;
        }
        clearMotion(worker);
        return Result.NOT_NEEDED;
    }
    private static BlockPos findBase(ServerLevel level,BuilderGolem worker,
            BlockPos target,int feet,WorkerStationBlockEntity table,
            List<BuilderConstructionPlan.Step> plan){
        // A completed wall can occupy every directly adjacent column.
        // Search nearby 1-2 block offsets for an existing safe foundation.
        // The farthest columns remain within the Builder's placing reach
        // after it climbs to target Y-1.
        java.util.List<BlockPos> offsets=new java.util.ArrayList<>();
        for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++){
            int distance=dx*dx+dz*dz;
            if(distance<1||distance>5)continue;
            offsets.add(target.offset(dx,0,dz));
        }
        offsets.sort(Comparator.comparingDouble(at->
                at.distSqr(target)*2
                +worker.distanceToSqr(at.getX()+.5,feet,at.getZ()+.5)*.1));
        for(BlockPos adjacent:offsets)
            for(int dy=0;dy<=5;dy++){
                BlockPos base=new BlockPos(adjacent.getX(),feet-dy,adjacent.getZ());
                if(!level.hasChunkAt(base)||!level.hasChunkAt(base.below()))continue;
                if(!level.getBlockState(base.below()).isFaceSturdy(level,
                        base.below(),Direction.UP))continue;
                boolean clear=true;
                for(int y=base.getY();y<target.getY();y++){
                    BlockPos at=new BlockPos(base.getX(),y,base.getZ());
                    if(!level.hasChunkAt(at)||!level.getBlockState(at).isAir()
                            ||table.isTrackedScaffold(at)){
                        clear=false;break;
                    }
                    for(var step:plan)
                        if(step.x()==at.getX()&&step.y()==at.getY()
                                &&step.z()==at.getZ()){clear=false;break;}
                    if(!clear)break;
                }
                if(clear&&(worker.blockPosition().distSqr(base)>16*16
                        ||com.caszgamermd.golemsplus.worker.WorkerNavigation
                                .canReachExact(level,worker,base)))
                    return base;
            }
        return null;
    }
    /**
     * Instant top-down removal only when worker is safely on the ground.
     * Refunds exactly one block per confirmed tracked scaffold; if storage
     * is full leaves the corresponding real block intact for later cleanup.
     */
    public static void retrieve(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,boolean force){
        var positions=new java.util.ArrayList<>(table.scaffoldPositions(worker.getUUID()));
        if(positions.isEmpty())return;
        int minY=positions.stream().mapToInt(BlockPos::getY).min().orElse(0);
        if(!force){
            if(!worker.onGround()||worker.getY()>minY+2.1)return;
            // Never reclaim a column from beneath a Builder that is
            // still standing on its bottom or top platform.
            for(BlockPos scaffold:positions){
                if(Math.abs(scaffold.getX()+.5-worker.getX())<.9
                        &&Math.abs(scaffold.getZ()+.5-worker.getZ())<.9)
                    return;
            }
        }
        positions.sort(Comparator.comparingInt((BlockPos block)->block.getY()).reversed());
        for(BlockPos pos:positions){
            if(!level.hasChunkAt(pos))continue;
            if(!level.getBlockState(pos).is(Blocks.SCAFFOLDING)){
                table.untrackScaffold(worker.getUUID(),pos);continue;
            }
            // Check storage before removal. The real block is removed once,
            // then the exact scaffold item is returned to the Planner.
            if(!BuilderSupplyInventory.canStoreScaffolding(table))break;
            if(!level.setBlock(pos,Blocks.AIR.defaultBlockState(),3))break;
            if(!BuilderSupplyInventory.storeScaffolding(table,
                    new ItemStack(Items.SCAFFOLDING))){
                // Unexpected insertion failure: return physical item as a
                // drop instead of losing it or synthesizing a later refund.
                net.minecraft.world.Containers.dropItemStack(level,
                        pos.getX()+.5,pos.getY()+.5,pos.getZ()+.5,
                        new ItemStack(Items.SCAFFOLDING));
            }
            table.untrackScaffold(worker.getUUID(),pos);
        }
    }
}

