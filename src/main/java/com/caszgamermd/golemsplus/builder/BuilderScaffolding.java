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
                                boolean descending,long expires){}
    private static final java.util.Map<BuilderGolem,ClimbSession> sessions=
            new java.util.WeakHashMap<>();
    private record ReturnRoute(BlockPos base,BlockPos landing,long expires){}
    private static final java.util.Map<BuilderGolem,ReturnRoute> returning=
            new java.util.WeakHashMap<>();
    private record ExitRoute(BlockPos ground,long expires){}
    private static final java.util.Map<BuilderGolem,ExitRoute> exiting=
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
        if(session!=null)return (session.descending()?"DOWN":"UP")
                +" column="+session.column().toShortString()
                +" stopY="+String.format(java.util.Locale.ROOT,"%.1f",session.stopY());
        ReturnRoute route=returning.get(worker);
        if(route!=null)return "RETURN column="+route.base().toShortString()
                +" via="+route.landing().toShortString();
        ExitRoute exit=exiting.get(worker);
        return exit==null?"NONE":"EXIT via="+exit.ground().toShortString();
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
        ExitRoute exit=exiting.get(worker);
        if(exit!=null){
            if(level.getGameTime()>exit.expires()
                    ||!com.caszgamermd.golemsplus.worker.WorkerNavigation
                            .canStandOn(level,worker,exit.ground())){
                exiting.remove(worker);
            }else if(worker.distanceToSqr(exit.ground().getX()+.5,
                    exit.ground().getY(),exit.ground().getZ()+.5)<.65){
                exiting.remove(worker);
                worker.getNavigation().stop();
                return false;
            }else{
                worker.getNavigation().stop();
                worker.setNoGravity(false);
                worker.setShiftKeyDown(false);
                worker.getMoveControl().setWantedPosition(exit.ground().getX()+.5,
                        exit.ground().getY(),exit.ground().getZ()+.5,1.0);
                return true;
            }
        }
        if(!climbingNow(level,worker))return false;
        ClimbSession session=sessions.get(worker);
        if(session==null)return false;
        double remain=session.stopY()-worker.getY();
        if((session.descending()&&remain>=-.16)
                ||(!session.descending()&&remain<=.16)){
            clearMotion(worker);
            return false;
        }
        worker.getNavigation().stop();
        double cx=session.column().getX()+.5,cz=session.column().getZ()+.5;
        double horizontal=Math.hypot(worker.getX()-cx,worker.getZ()-cz);
        if(horizontal>.30){
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
        returning.remove(worker);
        exiting.remove(worker);
        ClimbSession current=sessions.get(worker);
        if(current!=null&&current.column().equals(column)
                &&current.descending()==down
                &&Math.abs(current.stopY()-stopY)<.15){
            sessions.put(worker,new ClimbSession(column.immutable(),stopY,down,
                    level.getGameTime()+30));
            return; // do not reset A* or velocity five times a second
        }
        sessions.put(worker,new ClimbSession(column.immutable(),stopY,down,
                level.getGameTime()+30));
        com.caszgamermd.golemsplus.worker.WorkerNavigation.stop(worker);
        worker.getNavigation().stop();
    }

    /** A tracked tower has a base and a top; all cells are physical and
     * already registered by their owning Planner. A worker may have
     * several old towers but only the nearest reachable *upper* one
     * qualifies as an elevator for its current position.
     */
    private record Tower(BlockPos base,int topY){}
    private static java.util.List<Tower> towers(ServerLevel level,
            WorkerStationBlockEntity table,BuilderGolem worker){
        java.util.Map<Long,BlockPos> bottoms=new java.util.LinkedHashMap<>();
        java.util.Map<Long,Integer> tops=new java.util.HashMap<>();
        for(BlockPos pos:table.scaffoldPositions(worker.getUUID())){
            if(!level.hasChunkAt(pos)
                    ||!level.getBlockState(pos).is(Blocks.SCAFFOLDING))continue;
            long key=BlockPos.asLong(pos.getX(),0,pos.getZ());
            BlockPos former=bottoms.get(key);
            if(former==null||former.getY()>pos.getY())
                bottoms.put(key,pos.immutable());
            tops.merge(key,pos.getY(),Math::max);
        }
        java.util.List<Tower> result=new java.util.ArrayList<>();
        bottoms.forEach((key,base)->result.add(new Tower(base,tops.get(key))));
        result.sort(Comparator.comparingDouble(t->
                worker.distanceToSqr(t.base().getX()+.5,worker.getY(),
                        t.base().getZ()+.5)
                        +Math.abs(worker.getY()-(t.topY()+1))*.5));
        return result;
    }
    private static BlockPos returnLanding(ServerLevel level,BuilderGolem worker,
                                           Tower tower){
        java.util.List<BlockPos> choices=new java.util.ArrayList<>();
        for(int dy:new int[]{0,-1,1})
            for(Direction side:Direction.Plane.HORIZONTAL){
                BlockPos p=new BlockPos(tower.base().getX(),worker.blockPosition().getY(),
                        tower.base().getZ()).relative(side).offset(0,dy,0);
                if(p.getY()<tower.base().getY()
                        ||p.getY()>tower.topY()+2
                        ||!com.caszgamermd.golemsplus.worker.WorkerNavigation
                                .canStandOn(level,worker,p))continue;
                choices.add(p);
            }
        choices.sort(Comparator.comparingDouble(p->
                p.distSqr(worker.blockPosition())));
        for(BlockPos p:choices)
            if(com.caszgamermd.golemsplus.worker.WorkerNavigation
                    .canReachExact(level,worker,p))return p;
        return null;
    }
    private static BlockPos exitGround(ServerLevel level,BuilderGolem worker,
                                        BlockPos base){
        for(Direction side:Direction.Plane.HORIZONTAL){
            BlockPos p=base.relative(side);
            if(com.caszgamermd.golemsplus.worker.WorkerNavigation
                    .canStandOn(level,worker,p))return p;
        }
        return null;
    }
    /** Return physically to a worker's own upper scaffold landing,
     * descend through its column, then step off onto nearby solid ground.
     * A valid return route is kept while the golem travels; ordinary A*
     * must never take over a vertical climb.
     */
    public static boolean descendForTravel(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table){
        // A long tower may require more than 30 ticks of descent.
        // Renew the permit while this worker's material/lower-floor task
        // still requests descent, without restarting the physics session.
        if(sessions.containsKey(worker)&&climbingNow(level,worker)
                &&sessions.get(worker).descending()){
            var active=sessions.get(worker);
            sessions.put(worker,new ClimbSession(active.column(),
                    active.stopY(),true,level.getGameTime()+30));
            return true;
        }
        if(exiting.containsKey(worker))return true;
        var found=towers(level,table,worker);
        if(found.isEmpty()){
            returning.remove(worker);return false;
        }
        for(Tower tower:found){
            BlockPos base=tower.base();
            // Do not turn a normal ground trip into a journey to a tower.
            if(worker.getY()<=base.getY()+1.2)continue;
            if(worker.getY()>tower.topY()+3.5)continue;
            double dx=worker.getX()-(base.getX()+.5);
            double dz=worker.getZ()-(base.getZ()+.5);
            if(dx*dx+dz*dz>20*20)continue;
            if(dx*dx+dz*dz>1.35){
                ReturnRoute cached=returning.get(worker);
                BlockPos landing=cached!=null&&cached.base().equals(base)
                        &&cached.expires()>level.getGameTime()
                        &&com.caszgamermd.golemsplus.worker.WorkerNavigation
                                .canStandOn(level,worker,cached.landing())
                        ?cached.landing():returnLanding(level,worker,tower);
                if(landing==null)continue; // try another owned tower
                returning.put(worker,new ReturnRoute(base,landing,
                        level.getGameTime()+100));
                com.caszgamermd.golemsplus.worker.WorkerNavigation
                        .routeToExact(level,worker,landing,1.0);
                return true;
            }
            returning.remove(worker);
            double bottom=base.getY()+1.0;
            if(worker.getY()<=bottom+.16){
                clearMotion(worker);
                BlockPos exit=exitGround(level,worker,base);
                if(exit!=null){
                    exiting.put(worker,new ExitRoute(exit,level.getGameTime()+100));
                    return true;
                }
                return false;
            }
            begin(level,worker,base,bottom,true);
            return true;
        }
        returning.remove(worker);
        return false;
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
            // Existing scaffolding is not a walkable A* node. When a
            // Builder is already on an upper platform it must return to
            // that SAME elevation beside the tower, rather than walking
            // to its ground-floor base through completed walls.
            BlockPos approach=null;
            if(level.getBlockState(base).is(Blocks.SCAFFOLDING)
                    &&worker.getY()>base.getY()+1.2){
                final BlockPos selectedColumn=base;
                int high=existing.stream()
                        .filter(at->at.getX()==selectedColumn.getX()
                                &&at.getZ()==selectedColumn.getZ())
                        .mapToInt(BlockPos::getY).max()
                        .orElse(selectedColumn.getY());
                approach=returnLanding(level,worker,new Tower(base,high));
            }
            if(approach==null){
                Direction side=Math.abs(approachX)>=Math.abs(approachZ)
                        ?(approachX>=0?Direction.EAST:Direction.WEST)
                        :(approachZ>=0?Direction.SOUTH:Direction.NORTH);
                approach=level.getBlockState(base).is(Blocks.SCAFFOLDING)
                        ?base.relative(side).immutable():base;
            }
            if(!com.caszgamermd.golemsplus.worker.WorkerNavigation
                    .routeToExact(level,worker,approach,1.0))
                com.caszgamermd.golemsplus.worker.WorkerNavigation
                        .routeTo(level,worker,approach,1.0);
            return Result.CLIMBING;
        }
        int top=target.getY()-1;
        if(top-base.getY()+1>MAX_HEIGHT)return Result.NO_SAFE_TOWER;
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
