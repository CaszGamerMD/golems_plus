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
    // No arbitrary 48-block cap. Vertical routes extend a real scaffold
    // by sixteen blocks per resupply cycle up to the schematic target.
    /** Construction climb and descent are continuous actions rather than
     * five-tick velocity impulses. Each entity has its own session, and
     * ordinary A* must not override it until the vertical job completes.
     */
    private record ClimbSession(BlockPos column,double stopY,
                                boolean descending,long expires){}
    private static final java.util.Map<BuilderGolem,ClimbSession> sessions=
            new java.util.WeakHashMap<>();
    // Progress is measured in vertical feet, not in repeated A* route
    // submissions. A blocked tower is temporarily avoided after repeated
    // genuine no-motion intervals, with no teleportation or block phasing.
    private static final class MotionWatch {
        BlockPos column;
        boolean down;
        double lastY;
        int lastProgressTick,failures;
        MotionWatch(BlockPos col,boolean descending,double y,int tick){
            column=col;down=descending;lastY=y;lastProgressTick=tick;
        }
    }
    private record AvoidColumn(BlockPos column,long until){}
    private static final java.util.Map<BuilderGolem,MotionWatch> progress=
            new java.util.WeakHashMap<>();
    private static final java.util.Map<BuilderGolem,AvoidColumn> avoided=
            new java.util.WeakHashMap<>();
    private static boolean avoided(ServerLevel level,BuilderGolem worker,
                                    BlockPos candidate){
        AvoidColumn bad=avoided.get(worker);
        if(bad==null||level.getGameTime()>=bad.until())return false;
        return bad.column().getX()==candidate.getX()
                &&bad.column().getZ()==candidate.getZ();
    }
    private record ReturnRoute(BlockPos base,BlockPos landing,long expires){}
    private static final java.util.Map<BuilderGolem,ReturnRoute> returning=
            new java.util.WeakHashMap<>();
    private record ExitRoute(BlockPos ground,long expires){}
    private static final java.util.Map<BuilderGolem,ExitRoute> exiting=
            new java.util.WeakHashMap<>();
    private BuilderScaffolding(){}

    private static void clearMotion(BuilderGolem worker){
        sessions.remove(worker);
        progress.remove(worker);
        worker.setJumping(false);
        worker.setShiftKeyDown(false);
        worker.setNoGravity(false);
        var velocity=worker.getDeltaMovement();
        worker.setDeltaMovement(velocity.x,Math.min(velocity.y,0),velocity.z);
    }
    public static String debugClimb(BuilderGolem worker){
        ClimbSession session=sessions.get(worker);
        if(session!=null){
            MotionWatch watch=progress.get(worker);
            return (session.descending()?"DOWN":"UP")
                +" column="+session.column().toShortString()
                +" stopY="+String.format(java.util.Locale.ROOT,"%.1f",session.stopY())
                +(watch==null?"":" idle="+Math.max(0,
                        worker.tickCount-watch.lastProgressTick)+"t");
        }
        ReturnRoute route=returning.get(worker);
        if(route!=null)return "RETURN column="+route.base().toShortString()
                +" via="+route.landing().toShortString();
        ExitRoute exit=exiting.get(worker);
        return exit==null?"NONE":"EXIT via="+exit.ground().toShortString();
    }
    /** Keep an in-progress upward movement exclusively owned by its
     * builder task. Once the target becomes less than 1.35 blocks above
     * the Builder, the job loop must NOT switch back to normal A* before
     * the scaffold's actual landing height is reached.
     */
    /** Exclusive scaffold motion wins over the Planner's five-tick
     * work/navigation decisions. Normal A* resumes automatically after
     * a verified arrival/dismount (or after a safe abort).
     */
    public static boolean isControllingMovement(BuilderGolem worker){
        return sessions.containsKey(worker)||exiting.containsKey(worker);
    }
    public static void cancel(BuilderGolem worker){
        clearMotion(worker);
        returning.remove(worker);
        exiting.remove(worker);
    }
    public static boolean finishActiveAscent(ServerLevel level,BuilderGolem worker){
        ClimbSession session=sessions.get(worker);
        if(session==null||session.descending()
                ||!climbingNow(level,worker))return false;
        if(worker.getY()>=session.stopY()-.10)return false;
        sessions.put(worker,new ClimbSession(session.column(),
                session.stopY(),false,level.getGameTime()+30));
        return true;
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
        // The top scaffold is one block BELOW the Builder's feet.
        // During an approach from a platform one block higher, it can
        // temporarily be TWO blocks below. Accept that entry height,
        // but never accept a missing/distant column as support.
        boolean onScaffold=level.getBlockState(columnLevel).is(Blocks.SCAFFOLDING)
                ||level.getBlockState(columnLevel.below()).is(Blocks.SCAFFOLDING)
                ||level.getBlockState(columnLevel.above()).is(Blocks.SCAFFOLDING)
                ||session.descending()
                    &&level.getBlockState(columnLevel.below().below())
                            .is(Blocks.SCAFFOLDING);
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
            }else if(Math.abs(worker.getX()-exit.ground().getX()-.5)<.35
                    &&Math.abs(worker.getZ()-exit.ground().getZ()-.5)<.35
                    &&Math.abs(worker.getY()-exit.ground().getY())<.32
                    &&worker.onGround()){
                exiting.remove(worker);
                worker.getNavigation().stop();
                return false;
            }else{
                // Do not declare exit complete because the worker is only
                // horizontally close while still one block ABOVE the
                // ground. Steer off the scaffold with ordinary gravity
                // until the feet actually touch the selected safe floor.
                worker.getNavigation().stop();
                worker.setNoGravity(false);
                worker.setShiftKeyDown(false);
                double tx=exit.ground().getX()+.5;
                double tz=exit.ground().getZ()+.5;
                worker.getMoveControl().setWantedPosition(tx,
                        exit.ground().getY(),tz,1.0);
                double vx=Math.max(-.15,Math.min(.15,(tx-worker.getX())*.28));
                double vz=Math.max(-.15,Math.min(.15,(tz-worker.getZ())*.28));
                if(level.noCollision(worker,worker.getBoundingBox().move(vx,0,vz))){
                    var velocity=worker.getDeltaMovement();
                    worker.setDeltaMovement(vx,Math.min(velocity.y,0),vz);
                }
                return true;
            }
        }
        if(!climbingNow(level,worker))return false;
        ClimbSession session=sessions.get(worker);
        if(session==null)return false;
        // Refresh the active permit from actual controller ticks, not
        // arbitrary Planner calls. A 200-block climb must never expire
        // merely because a material check was offset by several ticks.
        sessions.put(worker,new ClimbSession(session.column(),
                session.stopY(),session.descending(),level.getGameTime()+30));
        double remain=session.stopY()-worker.getY();
        MotionWatch watch=progress.computeIfAbsent(worker,
                ignored->new MotionWatch(session.column(),
                        session.descending(),worker.getY(),worker.tickCount));
        if(!watch.column.equals(session.column())
                ||watch.down!=session.descending()){
            watch=new MotionWatch(session.column(),session.descending(),
                    worker.getY(),worker.tickCount);
            progress.put(worker,watch);
        }
        double progressed=session.descending()
                ?watch.lastY-worker.getY():worker.getY()-watch.lastY;
        if(progressed>.08){
            watch.lastY=worker.getY();
            watch.lastProgressTick=worker.tickCount;
            watch.failures=0;
        }else if(worker.tickCount-watch.lastProgressTick>55){
            watch.failures++;
            watch.lastProgressTick=worker.tickCount;
            // First timeout recenters on the column below. Repeated
            // timeouts indicate collision on this physical route: stop
            // forcing a stuck climb and let the Builder choose another
            // safe tower. Never remove the real column or teleport.
            if(watch.failures>=3){
                // Never strand an elevated Builder by cancelling gravity
                // control above a drop. Try a genuine nearby platform
                // exit; otherwise reverse a failed UP climb on the same
                // real tower and return to solid ground first.
                BlockPos atHeight=new BlockPos(session.column().getX(),
                        worker.blockPosition().getY(),session.column().getZ());
                BlockPos safe=exitGround(level,worker,atHeight);
                if(safe!=null){
                    clearMotion(worker);
                    exiting.put(worker,new ExitRoute(safe,
                            level.getGameTime()+140));
                    return true;
                }
                if(!session.descending()
                        &&worker.getY()>session.column().getY()+1.3){
                    avoided.put(worker,new AvoidColumn(session.column(),
                            level.getGameTime()+120));
                    begin(level,worker,session.column(),
                            session.column().getY()+1.0,true);
                    return true;
                }
                if(!session.descending()){
                    avoided.put(worker,new AvoidColumn(session.column(),
                            level.getGameTime()+120));
                    clearMotion(worker);
                    return false;
                }
                // No safe intermediate exit during descent; keep using
                // the actual owned scaffold. Never drop a worker from
                // height merely because a watchdog fired.
                watch.failures=0;
                worker.setShiftKeyDown(true);
                worker.setDeltaMovement(0,-.16,0);
            }
            worker.getNavigation().stop();
            worker.getMoveControl().setWantedPosition(
                    session.column().getX()+.5,worker.getY(),
                    session.column().getZ()+.5,1.0);
        }
        // Do not relinquish the session until the actual landing
        // elevation has been reached. The construction code checks target
        // proximity independently and must not cancel the ascent early.
        if(ScaffoldVerticalRoute.reached(worker.getY(),session.stopY(),
                session.descending())){
            clearMotion(worker);
            return false;
        }
        worker.getNavigation().stop();
        double cx=session.column().getX()+.5,cz=session.column().getZ()+.5;
        double horizontal=Math.hypot(worker.getX()-cx,worker.getZ()-cz);
        if(horizontal>.25){
            // Re-enter the physical column even from an upper ledge one
            // block above the scaffold. Moving off that ledge lets gravity
            // land the Builder on the top scaffold before descent starts.
            worker.setNoGravity(false);
            worker.setJumping(false);
            worker.setShiftKeyDown(false);
            worker.getMoveControl().setWantedPosition(cx,worker.getY(),cz,1.0);
            double mx=Math.max(-.13,Math.min(.13,(cx-worker.getX())*.25));
            double mz=Math.max(-.13,Math.min(.13,(cz-worker.getZ())*.25));
            var tryBox=worker.getBoundingBox().move(mx,0,mz);
            if(level.noCollision(worker,tryBox)){
                var motion=worker.getDeltaMovement();
                worker.setDeltaMovement(mx,Math.min(0,motion.y),mz);
            }
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
    static void begin(ServerLevel level,BuilderGolem worker,
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
        progress.put(worker,new MotionWatch(column.immutable(),down,
                worker.getY(),worker.tickCount));
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
        BlockPos best=null;
        double cost=Double.POSITIVE_INFINITY;
        for(Direction side:Direction.Plane.HORIZONTAL){
            // An adjacent safe floor may be one block LOWER than the
            // scaffold's supporting terrain; a Builder must be willing
            // to take this ordinary one-block drop to dismount.
            for(int dy:new int[]{0,-1}){
                BlockPos p=base.relative(side).offset(0,dy,0);
                if(!com.caszgamermd.golemsplus.worker.WorkerNavigation
                        .canStandOn(level,worker,p))continue;
                double score=worker.distanceToSqr(p.getX()+.5,worker.getY(),
                        p.getZ()+.5)+Math.abs(dy)*2;
                if(score<cost){cost=score;best=p;}
            }
        }
        return best;
    }
    /** Real side exit at the top of a scaffold. Vanilla pathfinding
     * cannot usually start from a scaffold node to an adjacent full-block
     * walkway. Move physically to the supported landing first, then
     * release the Builder back to normal A* navigation.
     */
    static boolean stepToPlatform(ServerLevel level,BuilderGolem worker,
                                  BlockPos destination){
        double dx=worker.getX()-(destination.getX()+.5);
        double dz=worker.getZ()-(destination.getZ()+.5);
        if(dx*dx+dz*dz>3.2
                ||Math.abs(worker.getY()-destination.getY())>1.3
                ||!com.caszgamermd.golemsplus.worker.WorkerNavigation
                        .canStandOn(level,worker,destination))return false;
        clearMotion(worker);
        exiting.put(worker,new ExitRoute(destination.immutable(),
                level.getGameTime()+140));
        return true;
    }

    /** If the next schematic location is far from a Builder still on
     * owned scaffolding, physically leave the tower onto a walkable
     * upper platform BEFORE requesting a normal walking path.
     */
    public static boolean stepOffTowardWork(ServerLevel level,
            BuilderGolem worker,WorkerStationBlockEntity table,BlockPos target){
        if(exiting.containsKey(worker))return true;
        BlockPos feet=worker.blockPosition();
        BlockPos under=feet.below();
        if(!level.getBlockState(under).is(Blocks.SCAFFOLDING)
                ||!table.scaffoldPositions(worker.getUUID()).contains(under))
            return false;
        BlockPos chosen=null;
        double best=Double.POSITIVE_INFINITY;
        for(Direction dir:Direction.Plane.HORIZONTAL){
            for(int dy:new int[]{0,1,-1}){
                BlockPos landing=feet.relative(dir).offset(0,dy,0);
                if(!com.caszgamermd.golemsplus.worker.WorkerNavigation
                        .canStandOn(level,worker,landing))continue;
                double score=landing.distSqr(target)+Math.abs(dy)*3;
                if(score<best){best=score;chosen=landing;}
            }
        }
        return chosen!=null&&stepToPlatform(level,worker,chosen);
    }

    /** Shared real dismount operation for both Builder jobs and the
     * Minecraft movement GameTest. The landing must be solid and near the
     * bottom of the owned scaffold; never teleport a worker.
     */
    static boolean dismountAtBase(ServerLevel level,BuilderGolem worker,
                                   BlockPos column){
        BlockPos exit=exitGround(level,worker,column);
        if(exit==null)return false;
        clearMotion(worker);
        exiting.put(worker,new ExitRoute(exit,level.getGameTime()+140));
        return true;
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
            if(avoided(level,worker,base))continue;
            if(worker.getY()>tower.topY()+3.5)continue;
            double dx=worker.getX()-(base.getX()+.5);
            double dz=worker.getZ()-(base.getZ()+.5);
            // Standing on the BOTTOM scaffold means feet are at baseY+1.
            // This is NOT "already on ground": the worker still needs
            // an exit tile. Previously this early skip made the lower
            // dismount branch unreachable for exactly that position.
            if(worker.getY()<=base.getY()+1.2
                    &&!ScaffoldVerticalRoute.onBottomScaffold(
                            worker.getY(),base.getY(),dx,dz))continue;
            if(dx*dx+dz*dz>20*20)continue;
            if(ScaffoldVerticalRoute.needsReturn(dx,dz)){
                ReturnRoute cached=returning.get(worker);
                BlockPos landing=cached!=null&&cached.base().equals(base)
                        &&cached.expires()>level.getGameTime()
                        &&com.caszgamermd.golemsplus.worker.WorkerNavigation
                                .canStandOn(level,worker,cached.landing())
                        ?cached.landing():returnLanding(level,worker,tower);
                if(landing==null)continue; // try another owned tower
                boolean underway=com.caszgamermd.golemsplus.worker.WorkerNavigation
                        .routeToExact(level,worker,landing,1.0);
                if(!underway){
                    // Do not strand this Builder indefinitely at an
                    // obsolete landing when the platform changes.
                    returning.remove(worker);
                    continue; // try another owned tower, or normal A*
                }
                returning.put(worker,new ReturnRoute(base,landing,
                        level.getGameTime()+100));
                return true;
            }
            returning.remove(worker);
            double bottom=base.getY()+1.0;
            if(!ScaffoldVerticalRoute.needsDescent(worker.getY(),base.getY())){
                return dismountAtBase(level,worker,base);
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
        // A Builder could previously abort its climb one block early:
        // as soon as the desired work Y was within 1.35 blocks,
        // clearMotion() erased an UP session even if its stopY had NOT
        // been reached. Respect the physical climb target instead.
        ClimbSession active=sessions.get(worker);
        if(active!=null&&!active.descending()
                &&active.stopY()>=target.getY()-.15
                &&climbingNow(level,worker)){
            if(!ScaffoldVerticalRoute.reached(worker.getY(),active.stopY(),false)){
                sessions.put(worker,new ClimbSession(active.column(),
                        active.stopY(),false,level.getGameTime()+30));
                return Result.CLIMBING;
            }
            clearMotion(worker);
        }
        if(target.getY()-worker.getY()<=1.35){
            return Result.NOT_NEEDED;
        }
        // Tall routes are divided into physical, inventory-bounded stages.
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
                    return dx*dx+dz*dz<=5&&!avoided(level,worker,p);
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
        int wantedTop=target.getY()-1;
        final BlockPos column=base;
        int existingTop=existing.stream()
                .filter(at->at.getX()==column.getX()&&at.getZ()==column.getZ()
                        &&level.getBlockState(at).is(Blocks.SCAFFOLDING))
                .mapToInt(BlockPos::getY).max().orElse(base.getY()-1);
        // Extend one vertical stage, spend only the scaffold items for
        // that stage, then climb before considering the next extension.
        // This supports builds far taller than the worker's inventory.
        int top=ScaffoldVerticalRoute.stageTop(base.getY(),existingTop,wantedTop);
        if(top<base.getY())return Result.NOT_NEEDED;
        int needed=0;
        for(int y=base.getY();y<=top;y++){
            var p=new BlockPos(base.getX(),y,base.getZ());
            if(!level.hasChunkAt(p)||!level.mayInteract(player,p))
                return Result.NO_SAFE_TOWER;
            if(level.getBlockState(p).is(Blocks.SCAFFOLDING)){
                if(!table.scaffoldPositions(worker.getUUID()).contains(p)){
                    avoided.put(worker,new AvoidColumn(base.immutable(),
                            level.getGameTime()+120));
                    return Result.NO_SAFE_TOWER;
                }
            }else if(level.getBlockState(p).isAir())needed++;
            else{
                avoided.put(worker,new AvoidColumn(base.immutable(),
                        level.getGameTime()+120));
                return Result.NO_SAFE_TOWER;
            }
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
        // This stage may end BELOW the ultimate target. The next work
        // evaluation will extend the SAME owned tower, not walk away.
        return top<wantedTop?Result.CLIMBING:Result.NOT_NEEDED;
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
                if(avoided(level,worker,base)
                        ||!level.hasChunkAt(base)
                        ||!level.hasChunkAt(base.below()))continue;
                if(!level.getBlockState(base.below()).isFaceSturdy(level,
                        base.below(),Direction.UP))continue;
                boolean clear=true;
                // Only probe the next material-backed extension. Later
                // obstructions are reconsidered when the worker reaches
                // that elevation rather than refusing a 200-block tower
                // before its first sixteen blocks are built.
                int previewTop=ScaffoldVerticalRoute.stageTop(base.getY(),
                        base.getY()-1,target.getY()-1);
                for(int y=base.getY();y<=previewTop;y++){
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
