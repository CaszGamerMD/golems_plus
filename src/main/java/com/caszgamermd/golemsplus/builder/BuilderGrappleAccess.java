package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.AABB;

/**
 * Plan B: physically climb/rappel a reusable hook. The anchor is an actual
 * sturdy block, the rope hangs in an adjacent AIR column, and its clearance
 * is checked for the Builder's full 0.65x1.62 collision box.
 *
 * Real movement is integrated by Minecraft's entity physics. No teleport,
 * world block creation, scaffold or alternative navigation controller.
 */
public final class BuilderGrappleAccess {
    public static final int MAX_REACH=20;
    public enum Result { MOVING, READY, NEED_HOOK, NO_SAFE_ANCHOR }
    private enum Phase {
        WALK_TO_LINE, SWING_TO_LINE, WALK_TO_EDGE, CLIMB, STEP_ON_TOP, STEP_OFF,
        RAPPEL, STEP_TO_LOWER_LEDGE, RETREAT, RETURN_TO_START,
        RETURN_TO_TOP, RETURN_ON_LEDGE
    }
    /** Max ticks without ACTUAL progress, not just a move intent. */
    static final int STUCK_TICKS=45;
    private static final int TRAVEL_TIMEOUT=180;
    private static final class Rope {
        final BlockPos anchor,column,landing,goal,safeStart;
        final Direction side;
        final boolean down;
        final long started;
        Phase phase;
        int stalledTicks;
        int recoveryTicks;
        double lastX=Double.NaN,lastY=Double.NaN,lastZ=Double.NaN;
        Phase lastPhase;
        Rope(BlockPos anchor,Direction side,BlockPos landing,BlockPos goal,
                boolean down,long time) {
            this(anchor,side,landing,goal,down,time,null);
        }
        Rope(BlockPos anchor,Direction side,BlockPos landing,BlockPos goal,
                boolean down,long time,BlockPos safeStart) {
            this.anchor=anchor.immutable();
            this.side=side;
            this.column=anchor.relative(side);
            this.landing=landing.immutable();
            this.goal=goal.immutable();
            this.safeStart=safeStart==null?null:safeStart.immutable();
            this.down=down;
            this.started=time;
            this.phase=down?Phase.WALK_TO_EDGE:
                    (safeStart==null?Phase.WALK_TO_LINE:Phase.SWING_TO_LINE);
        }
        double topY(){return anchor.getY()+1.0;}
    }
    private static final Map<BuilderGolem,Rope> active=new WeakHashMap<>();
    // Per-worker, temporary physical return breadcrumbs. Only actual
    // completed rope ascents are remembered, never hypothetical paths.
    // A golem can safely rappel down its proven ascent line instead of
    // searching an unrelated hook face on a complex roof.
    private static final Map<BuilderGolem,List<Rope>> ascendedLines=
            new WeakHashMap<>();
    // A stable one-block step-down intermediate waypoint prevents an idle
    // Builder from grappling an upper roof simply because the faraway
    // OnSite chest is not EXACT-path-reachable in one A* search.
    private record NormalDrop(BlockPos goal,BlockPos landing,long until){}
    private static final Map<BuilderGolem,NormalDrop> normalDrops=
            new WeakHashMap<>();
    private static final Map<BuilderGolem,Long> nextDropSearch=
            new WeakHashMap<>();
    private static boolean tryLocalOneBlockDrop(ServerLevel level,
            BuilderGolem mob,BlockPos goal){
        if(!mob.onGround())return false;
        NormalDrop hold=normalDrops.get(mob);
        if(hold!=null){
            if(mob.blockPosition().getY()<=hold.landing().getY()
                    ||level.getGameTime()>hold.until()
                    ||!hold.goal().equals(goal)){
                normalDrops.remove(mob);
            }else if(WorkerNavigation.canStandOn(level,mob,hold.landing())
                    &&WorkerNavigation.routeToExact(
                            level,mob,hold.landing(),1.0))
                return true;
        }
        long now=level.getGameTime();
        if(nextDropSearch.getOrDefault(mob,0L)>now)return false;
        BlockPos origin=mob.blockPosition();
        List<BlockPos> landings=new ArrayList<>();
        // Scan the entire nearby rim, not just four blocks: a Golem
        // perched on a first-layer wall can be far from a natural drop.
        for(int radius=1;radius<=10;radius++)
            for(int dx=-radius;dx<=radius;dx++)
                for(int dz=-radius;dz<=radius;dz++){
                    if(Math.max(Math.abs(dx),Math.abs(dz))!=radius)
                        continue;
                    BlockPos at=origin.offset(dx,-1,dz);
                    if(!headroom(level,at)||!solidTop(level,at.below()))
                        continue;
                    landings.add(at);
                }
        landings.sort(Comparator.comparingDouble(p->
                p.distSqr(origin)+p.distSqr(goal)*.05));
        // A native path to an adjacent lower tile is not evidence that
        // the trained navigator can execute the first step. Preview up
        // to 96 eligible lower standing tiles with ONE trained A*,
        // including native short-waypoint verification, then physically
        // walk toward the selected safe drop. A grapple is Plan B.
        BlockPos selected=WorkerNavigation.selectExecutableStand(
                level,mob,landings.subList(0,Math.min(96,landings.size())));
        if(selected!=null
                &&WorkerNavigation.routeToExact(level,mob,selected,1.0)){
            normalDrops.put(mob,new NormalDrop(goal.immutable(),
                    selected.immutable(),level.getGameTime()+100));
            System.out.println("[GRAPPLE] TRAINED ONE-BLOCK STEP DOWN "
                    +"via="+selected+" instead of hook to "+goal);
            return true;
        }
        nextDropSearch.put(mob,now+40);
        return false;
    }
    // Keep short-lived failures by BOTH ledge and face. A failed hook
    // cannot immediately be reselected; other sides/ledges remain open.
    private record FailedAnchor(BlockPos anchor,Direction side,long until){}
    private static final Map<BuilderGolem,List<FailedAnchor>> blockedAnchors=
            new WeakHashMap<>();
    private static boolean rejected(ServerLevel level,BuilderGolem worker,
            BlockPos anchor,Direction side){
        return blockedAnchors.getOrDefault(worker,List.of()).stream()
                .anyMatch(b->b.until()>level.getGameTime()
                        &&b.anchor().equals(anchor)&&b.side()==side);
    }
    private static void reject(ServerLevel level,BuilderGolem mob,Rope rope){
        List<FailedAnchor> old=blockedAnchors.getOrDefault(mob,List.of());
        List<FailedAnchor> next=new ArrayList<>();
        for(FailedAnchor b:old)
            if(b.until()>level.getGameTime()&&next.size()<7)
                next.add(b);
        next.add(new FailedAnchor(rope.anchor,rope.side,
                level.getGameTime()+300));
        blockedAnchors.put(mob,next);
    }
    private static void release(BuilderGolem mob,Rope rope,String event){
        if(!rope.down&&"ASCENDED".equals(event)){
            List<Rope> history=ascendedLines.computeIfAbsent(
                    mob,ignored->new ArrayList<>());
            if(history.size()>=20)history.remove(0);
            history.add(rope);
        }
        active.remove(mob);
        mob.setNoGravity(false);
        mob.setDeltaMovement(0,0,0);
        WorkerNavigation.stop(mob);
        System.out.println("[GRAPPLE] "+event+" at "+mob.position()
                +" anchor="+rope.anchor+" side="+rope.side);
    }
    private static void beginRecovery(ServerLevel level,BuilderGolem mob,
            Rope rope){
        reject(level,mob,rope);
        rope.stalledTicks=0;
        rope.recoveryTicks=0;
        // A climb returns to its safe LOWER entry. A rappel returns up
        // to the solid UPPER ledge before releasing the rope.
        rope.phase=rope.down?Phase.RETURN_TO_TOP:Phase.RETREAT;
        rope.lastPhase=null;
        System.out.println("[GRAPPLE] STUCK: returning "
                +(rope.down?"UP to upper ledge":"DOWN to start")
                +" anchor="+rope.anchor+" side="+rope.side
                +" feet="+mob.position());
    }
    private BuilderGrappleAccess(){}

    public static boolean active(BuilderGolem worker){return active.containsKey(worker);}
    public static String debug(BuilderGolem worker){
        Rope r=active.get(worker);
        return r==null?"NONE":"GRAPPLE "+r.phase+" anchor="+r.anchor.toShortString()
                +" column="+r.column.toShortString()+" landing="
                +r.landing.toShortString()+" ticks="+r.stalledTicks;
    }
    public static void cancel(BuilderGolem worker){
        // Cancellation is legal only on solid ground. Do not suddenly drop
        // an entity suspended halfway up a real rope.
        if(!worker.onGround()&&active.containsKey(worker))return;
        active.remove(worker);
        worker.setNoGravity(false);
    }
    /**
     * Anchor/landing support is the ACTUAL upper collision surface, not
     * simply a full-cube test. Top-half slabs reach Y+1 and have the same
     * solid upper landing plane as a full block; bottom slabs stop at Y+.5
     * and must never be treated as a full-height grapple anchor.
     *
     * This is used by both ascent and descent, so a top-slab roof can be
     * climbed onto and later rappelled from. The rope itself still needs
     * an unobstructed AIR column and a physically reachable entry.
     */
    // Package-scoped for precise physical-fixture assertions. Testing the
    // support cell directly avoids confusing a nearby usable anchor with
    // a bottom slab being accepted.
    static boolean solidTop(ServerLevel world,BlockPos p){
        if(!world.hasChunkAt(p))return false;
        BlockState state=world.getBlockState(p);
        if(state.getBlock() instanceof SlabBlock){
            SlabType type=state.getValue(SlabBlock.TYPE);
            if(type==SlabType.BOTTOM)return false;
            return !state.getCollisionShape(world,p).isEmpty()
                    &&state.getCollisionShape(world,p)
                        .max(Direction.Axis.Y)>.999;
        }
        return state.isFaceSturdy(world,p,Direction.UP);
    }
    private static boolean empty(ServerLevel world,BlockPos p){
        if(!world.hasChunkAt(p)||!world.getFluidState(p).isEmpty())return false;
        return world.getBlockState(p).getCollisionShape(world,p).isEmpty();
    }
    private static boolean headroom(ServerLevel world,BlockPos feet){
        return empty(world,feet)&&empty(world,feet.above());
    }
    /** The AIR rope column cannot pass through ANY wall, fluids, or roof.
     * Require enough room for the complete Builder body at each elevation.
     */
    private static boolean lineClear(ServerLevel world,BlockPos column,
            int lowestFeetY,int highestFeetY){
        if(highestFeetY-lowestFeetY>MAX_REACH)return false;
        for(int y=lowestFeetY;y<=highestFeetY+1;y++)
            if(!empty(world,column.atY(y)))return false;
        return true;
    }
    private static boolean hasHook(BuilderGolem worker){
        return worker.carriedCount(WorksiteContent.GRAPPLE_HOOK)>0;
    }
    private static List<BlockPos> solidAnchors(ServerLevel world,
            BlockPos goal){
        List<BlockPos> out=new ArrayList<>();
        for(int dx=-4;dx<=4;dx++)for(int dz=-4;dz<=4;dz++){
            BlockPos at=goal.offset(dx,-1,dz);
            if(dx*dx+dz*dz>16||!solidTop(world,at)
                    ||!headroom(world,at.above()))continue;
            out.add(at);
        }
        out.sort(Comparator.comparingInt(p->p.distManhattan(goal)));
        return out;
    }
    /**
     * Real rope-supported horizontal entry when the vertical rope hangs
     * over open air. Its source MUST be grounded, the rope stays attached,
     * the full swept body must clear every intermediate point, and the
     * horizontal span cannot exceed six blocks and the combined rope
     * span must remain inside the carried hook's 20-block reach.
     *
     * This does NOT create a new walking path, magically grant footholds,
     * teleport, or permit unsupported motion without an actual anchor.
     */
    private static boolean swingClear(ServerLevel level,BuilderGolem mob,
            BlockPos entry,BlockPos goal){
        if(!mob.onGround()||!headroom(level,entry)
                ||mob.blockPosition().getY()!=entry.getY())return false;
        double dx=entry.getX()+.5-mob.getX();
        double dz=entry.getZ()+.5-mob.getZ();
        double len=Math.hypot(dx,dz);
        if(len>6.0 ||Math.hypot(len,goal.getY()-mob.getY())>MAX_REACH)
            return false;
        int steps=Math.max(1,(int)Math.ceil(len/.18));
        AABB body=mob.getBoundingBox();
        for(int i=1;i<=steps;i++){
            double fraction=(double)i/steps;
            if(!level.noCollision(mob,body.move(
                    dx*fraction,0,dz*fraction)))return false;
        }
        return true;
    }
    /** A hook anchors to the solid top of a full block or TOP slab,
     * one cell BELOW landing feet. When the same-level entry has no floor,
     * an already grounded Builder may swing toward the clear hanging line.
     */
    private static Rope chooseAscent(ServerLevel level,BuilderGolem worker,
            BlockPos goal){
        int startY=worker.blockPosition().getY();
        if(goal.getY()-startY>MAX_REACH||goal.getY()-startY<2)return null;
        List<Rope> candidates=new ArrayList<>();
        for(BlockPos anchor:solidAnchors(level,goal)){
            for(Direction side:Direction.Plane.HORIZONTAL){
                if(rejected(level,worker,anchor,side))continue;
                BlockPos column=anchor.relative(side);
                BlockPos entry=column.atY(startY);
                if(!headroom(level,entry)
                        ||!lineClear(level,column,startY,goal.getY()))
                    continue;
                boolean walk=solidTop(level,entry.below())
                        &&WorkerNavigation.canReachExact(level,worker,entry);
                boolean swing=!walk && swingClear(level,worker,entry,goal);
                if(!walk&&!swing)continue;
                // The rope takes the weight BEFORE stepping into open air.
                // The swing starts at the actual supported feet position.
                candidates.add(new Rope(anchor,side,entry,goal,false,
                        level.getGameTime(),
                        swing?worker.blockPosition():null));
            }
        }
        candidates.sort(Comparator.comparingDouble(r->
                r.landing.distSqr(worker.blockPosition())
                +r.anchor.distSqr(goal)*2.0
                +(r.safeStart!=null?18:0)));
        return candidates.isEmpty()?null:candidates.get(0);
    }
    /**
     * From a rope hanging in an unobstructed air column, a Builder can
     * shift a single block onto a supported roof lip before detaching.
     * Probe the entire 0.65-wide swept collision body, not merely the
     * destination block: a wall or roof overhang blocks the maneuver.
     */
    private static boolean clearLowerRopeStep(ServerLevel level,
            BuilderGolem worker,BlockPos column,BlockPos landing){
        if(column.getY()!=landing.getY()
                ||column.distManhattan(landing)!=1
                ||!WorkerNavigation.canStandOn(level,worker,landing)
                ||!headroom(level,landing))return false;
        double sx=column.getX()+.5,sz=column.getZ()+.5;
        double dx=landing.getX()-column.getX();
        double dz=landing.getZ()-column.getZ();
        AABB body=worker.getBoundingBox().move(
                sx-worker.getX(),column.getY()-worker.getY(),
                sz-worker.getZ());
        for(int i=0;i<=8;i++){
            double fraction=i/8.0;
            if(!level.noCollision(worker,body.move(
                    dx*fraction,0,dz*fraction)))return false;
        }
        return true;
    }
    /** A one-cell approach to a physically adjacent rope ledge is
     * permissible when BOTH floors are already sturdy and every sample
     * of the golem's actual bounding box fits. This is the same short
     * on-ground motion used to align a precise work stance, not a second
     * long-distance pathfinder or an unsupported swing.
     */
    private static boolean adjacentFloorWalk(ServerLevel level,
            BuilderGolem worker,BlockPos target){
        BlockPos here=worker.blockPosition();
        if(!worker.onGround()||here.getY()!=target.getY()
                ||here.distManhattan(target)!=1
                ||!solidTop(level,here.below())
                ||!solidTop(level,target.below())
                ||!WorkerNavigation.canStandOn(level,worker,target)
                ||!headroom(level,target))return false;
        double dx=target.getX()+.5-worker.getX();
        double dz=target.getZ()+.5-worker.getZ();
        if(Math.hypot(dx,dz)>1.7)return false;
        AABB body=worker.getBoundingBox();
        for(int i=1;i<=10;i++){
            double f=i/10.0;
            if(!level.noCollision(worker,body.move(dx*f,0,dz*f)))
                return false;
        }
        return true;
    }
    /** Find a real ledge that the Builder can reach BEFORE it hooks on.
     * A Builder may be several blocks from the rim after completing its
     * task; it must first WALK across real terrain to a viable edge.
     * A descent of one block is also allowed: 'up to 20' has no 2-block
     * minimum. Never fall off a rim to reach the anchor.
     */
    private static Rope chooseDescent(ServerLevel level,BuilderGolem worker,
            BlockPos goal){
        BlockPos feet=worker.blockPosition();
        List<Rope> candidates=new ArrayList<>();
        int bestLimit=16;
        for(int dx=-bestLimit;dx<=bestLimit;dx++)
            for(int dz=-bestLimit;dz<=bestLimit;dz++){
                if(Math.abs(dx)+Math.abs(dz)>bestLimit)continue;
                BlockPos anchor=feet.offset(dx,-1,dz);
                if(!solidTop(level,anchor)||!headroom(level,anchor.above()))
                    continue;
                int topY=anchor.getY()+1;
                for(Direction side:Direction.Plane.HORIZONTAL){
                    if(rejected(level,worker,anchor,side))continue;
                    BlockPos column=anchor.relative(side);
                    if(!headroom(level,column.atY(topY)))continue;
                    for(int delta=1;delta<=MAX_REACH;delta++){
                        int landingY=topY-delta;
                        BlockPos atRopeBottom=column.atY(landingY);
                        if(!empty(level,atRopeBottom)
                                ||!empty(level,atRopeBottom.above()))
                            break; // A real roof/wall blocks the hanging line
                        if(!lineClear(level,column,landingY,topY))break;
                        // Best case: the rope ends directly above safe floor.
                        // Otherwise step from the rope onto a lower shelf
                        // while STILL ATTACHED. The step is 1 block max
                        // and is swept-collision checked at the destination.
                        if(solidTop(level,atRopeBottom.below())){
                            candidates.add(new Rope(anchor,side,atRopeBottom,
                                    goal,true,level.getGameTime()));
                            break;
                        }
                        boolean foundLowerShelf=false;
                        for(Direction lip:Direction.Plane.HORIZONTAL){
                            BlockPos landing=atRopeBottom.relative(lip);
                            if(!clearLowerRopeStep(level,worker,
                                    atRopeBottom,landing))continue;
                            candidates.add(new Rope(anchor,side,landing,
                                    goal,true,level.getGameTime()));
                            foundLowerShelf=true;
                        }
                        if(foundLowerShelf)break;
                    }
                }
            }
        candidates.sort(Comparator.comparingDouble(r->
                r.anchor.above().distSqr(feet)*1.25
                +r.landing.distSqr(goal)*.7
                -Math.min(16,feet.getY()-r.landing.getY())*6.0));
        // Never commit to a rope whose attachment ledge is only native
        // A*-reachable but not executable under the trained movement
        // policy. One multi-goal route picks a physically traversable
        // candidate; otherwise keep trying normal access next tick.
        List<BlockPos> ledges=new ArrayList<>();
        int limit=Math.min(64,candidates.size());
        for(int i=0;i<limit;i++){
            BlockPos ledge=candidates.get(i).anchor.above();
            if(!ledges.contains(ledge))ledges.add(ledge);
        }
        BlockPos chosen=WorkerNavigation.selectExecutableStand(
                level,worker,ledges);
        if(chosen==null)return null;
        for(int i=0;i<limit;i++)
            if(candidates.get(i).anchor.above().equals(chosen))
                return candidates.get(i);
        return null;
    }
    /** A direct, collision-checked trained ground route always outranks
     * equipment. Native jump/step of ONE block is handled by A*; multi-
     * step stairs can also be preferred if a physically valid path exists.
     * Never claim a goal merely because it is nearby but unwalkable.
     */
    private static boolean tryGroundRoute(ServerLevel level,BuilderGolem mob,
            BlockPos goal,boolean ascent){
        if(!mob.onGround())return false;
        // A target ledge itself can be a valid footing. It was missing
        // from this probe, causing the one-block jump to be incorrectly
        // denied unless an adjacent block was also already built.
        // A schematic AIR target is simply skipped until support exists.
        List<BlockPos> choices=new ArrayList<>();
        if(headroom(level,goal)&&solidTop(level,goal.below()))
            choices.add(goal);
        for(Direction dir:Direction.Plane.HORIZONTAL){
            BlockPos at=goal.relative(dir);
            if(!headroom(level,at)||!solidTop(level,at.below()))continue;
            choices.add(at);
        }
        choices.sort(Comparator.comparingDouble(p->p.distSqr(mob.blockPosition())));
        int attempts=0;
        for(BlockPos p:choices){
            if(++attempts>5)break;
            if(p.equals(mob.blockPosition()))return true;
            if(WorkerNavigation.canReachExact(level,mob,p)
                    &&WorkerNavigation.routeToExact(level,mob,p,1.0))
                return true;
        }
        return false;
    }
    /** Read-only preflight for the construction planner. A legal grapple
     * is not a magic work stance: it needs a real carried hook, a valid
     * upper ledge with clear side/headroom and an A*-reachable grounded
     * entry to the vertical rope. Never attaches or changes navigation.
     */
    public static boolean canPlanAscent(ServerLevel level,
            BuilderGolem worker,BlockPos target){
        return hasHook(worker)&&!active.containsKey(worker)
                &&chooseAscent(level,worker,target)!=null;
    }

    /** Called when construction requires higher feet. Ordinary jumps and
     * safe A* stair paths are attempted BEFORE making a grapple decision.
     */
    public static Result reach(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,BlockPos goal){
        Rope current=active.get(worker);
        if(current!=null)return Result.MOVING;
        int diff=goal.getY()-worker.blockPosition().getY();
        if(diff<=0)return Result.READY;
        if(diff==1){
            if(tryGroundRoute(level,worker,goal,true))return Result.MOVING;
            // A one-block jump can't create missing footing. If no native
            // walkable route exists, only a safe grapple can proceed.
        }else if(tryGroundRoute(level,worker,goal,true))
            return Result.MOVING;
        if(!hasHook(worker)){
            return switch(BuilderSupplyInventory.loadGrapple(level,worker,table)){
                case READY->Result.MOVING;
                case MOVING->Result.MOVING;
                case MISSING->Result.NEED_HOOK;
            };
        }
        Rope route=chooseAscent(level,worker,goal);
        if(route==null)return Result.NO_SAFE_ANCHOR;
        active.put(worker,route);
        if(route.phase==Phase.WALK_TO_LINE)
            WorkerNavigation.routeToExact(level,worker,route.landing,1.0);
        else WorkerNavigation.stop(worker);
        return Result.MOVING;
    }
    /**
     * Retrace a REAL successfully completed ascent rope when returning from
     * a construction or demolition worksite. It is a temporary per-entity
     * breadcrumb, not a precomputed solution to this schematic or a bypass
     * for the trained walking policy. The recorded lower entry and upper
     * anchor are independently revalidated against today's blocks, and
     * the Builder must physically walk to the supported upper ledge first.
     *
     * An ascent that began with an unsupported lateral swing is deliberately
     * excluded until inverse swing safety is separately verified.
     */
    public static boolean rappelProvenAscent(ServerLevel level,
            BuilderGolem worker,BlockPos returnGoal){
        if(active.containsKey(worker)||!worker.onGround()
                ||!hasHook(worker))return false;
        List<Rope> history=ascendedLines.get(worker);
        if(history==null||history.isEmpty())return false;
        BlockPos here=worker.blockPosition();
        for(int i=history.size()-1;i>=0;i--){
            Rope past=history.get(i);
            if(past.down
                    ||past.anchor.getY()+1!=here.getY()
                    ||past.landing.getY()>=here.getY()
                    ||rejected(level,worker,past.anchor,past.side))
                continue;
            BlockPos top=past.anchor.above();
            BlockPos lower=past.safeStart==null?
                    past.landing:past.safeStart;
            if(!solidTop(level,past.anchor)
                    ||!headroom(level,top)
                    ||!solidTop(level,lower.below())
                    ||!headroom(level,lower)
                    ||!headroom(level,past.landing)
                    ||!lineClear(level,past.column,
                            past.landing.getY(),top.getY())
                    ||(past.safeStart!=null
                            &&!clearReverseSwing(level,worker,past))
                    ||!(WorkerNavigation.canReachExact(level,worker,top)
                            ||adjacentFloorWalk(level,worker,top)))
                continue;
            Rope rappel=new Rope(past.anchor,past.side,lower,
                    returnGoal,true,level.getGameTime());
            active.put(worker,rappel);
            WorkerNavigation.stop(worker);
            System.out.println("[GRAPPLE] PROVEN REVERSE: top="+top
                    +" lower="+lower+" ropeEntry="+past.landing
                    +" reverseSwing="+(past.safeStart!=null)+" side="+past.side
                    +" current="+here+" real walking to anchor");
            return true;
        }
        return false;
    }

    /** A reversed ascent swing remains rope-supported until the original
     * safe lower foothold is reached. Require collision-clear golem-sized
     * motion all along the previously executed lateral segment.
     */
    private static boolean clearReverseSwing(ServerLevel level,
            BuilderGolem worker,Rope past){
        if(past.safeStart==null)return false;
        BlockPos from=past.landing;
        BlockPos safe=past.safeStart;
        if(from.getY()!=safe.getY()||!solidTop(level,safe.below())
                ||!headroom(level,safe))return false;
        double dx=safe.getX()-from.getX();
        double dz=safe.getZ()-from.getZ();
        double distance=Math.hypot(dx,dz);
        if(distance>6.0||Math.hypot(distance,past.topY()-from.getY())
                >MAX_REACH)return false;
        AABB body=worker.getBoundingBox().move(
                from.getX()+.5-worker.getX(),
                from.getY()-worker.getY(),
                from.getZ()+.5-worker.getZ());
        int steps=Math.max(1,(int)Math.ceil(distance/.12));
        for(int i=0;i<=steps;i++){
            double f=(double)i/steps;
            if(!level.noCollision(worker,body.move(dx*f,0,dz*f)))
                return false;
        }
        return true;
    }
    /** Returns true while the hook owns descent or when a descent starts. */
    public static boolean rappelForTravel(ServerLevel level,
            BuilderGolem worker,WorkerStationBlockEntity table){
        BlockPos goal=table.builderUnloadingStorage();
        return goal!=null&&rappelToward(level,worker,table,goal);
    }
    public static boolean rappelToward(ServerLevel level,
            BuilderGolem worker,WorkerStationBlockEntity table,BlockPos goal){
        if(active.containsKey(worker))return true;
        int diff=worker.blockPosition().getY()-goal.getY();
        if(diff<1)return false;
        // One-block drops are normal ground navigation first. Do not
        // grapple for a step that the trained pathfinder can traverse.
        if(diff==1){
            // A trained one-block descent is an ACTIVE movement leg.
            // Returning false used to let the caller immediately replace
            // this valid short route with a distant chest/perimeter route
            // while the Builder was still on its upper ledge.
            if(tryGroundRoute(level,worker,goal,false))
                return true; // preserve physical normal A* until grounded
            if(tryLocalOneBlockDrop(level,worker,goal))
                return true; // hold the intermediate supported drop
        }
        if(!hasHook(worker)){
            var load=BuilderSupplyInventory.loadGrapple(level,worker,table);
            return load!=BuilderSupplyInventory.LoadStatus.MISSING;
        }
        Rope route=chooseDescent(level,worker,goal);
        if(route==null)return false;
        active.put(worker,route);
        WorkerNavigation.stop(worker);
        return true;
    }
    private static void emitLine(ServerLevel level,Rope r,BuilderGolem mob){
        if(mob.tickCount%5!=0)return;
        // Lightweight rope preview at the real, fixed anchor line.
        // The particles trace the rope; they do not create collisions.
        for(double y=Math.min(mob.getY(),r.topY());y<=r.topY();y+=1.0)
            level.sendParticles(ParticleTypes.CRIT,
                    r.column.getX()+.5,y+.25,r.column.getZ()+.5,
                    1,0,0,0,0);
    }
    private static boolean steer(ServerLevel level,BuilderGolem mob,
            double x,double y,double z,double cap,boolean vertical){
        double dx=x-mob.getX(),dy=y-mob.getY(),dz=z-mob.getZ();
        double vx=Math.max(-cap,Math.min(cap,dx));
        double vy=vertical?Math.max(-cap,Math.min(cap,dy)):0;
        double vz=Math.max(-cap,Math.min(cap,dz));
        AABB next=mob.getBoundingBox().move(vx,vy,vz);
        if(!level.noCollision(mob,next)){
            mob.setDeltaMovement(0,0,0);
            return false;
        }
        mob.setDeltaMovement(vx,vy,vz);
        return true;
    }
    /** Called BEFORE the real mob's super.tick(). During a rope traverse,
     * no-gravity means weight is held by the taut rope, not free flight;
     * movement is still collision-checked every tick and never teleported.
     */
    public static boolean tickMotion(ServerLevel level,BuilderGolem mob){
        Rope r=active.get(mob);
        if(r==null)return false;
        // Failed to WALK to a grapple attachment point. No line was
        // thrown, so grounded cancellation is safe. Forget this face
        // temporarily instead of making the worker hold a dead route.
        if(r.phase==Phase.WALK_TO_LINE
                &&level.getGameTime()-r.started>=TRAVEL_TIMEOUT
                &&mob.onGround()){
            reject(level,mob,r);
            active.remove(mob);
            WorkerNavigation.stop(mob);
            System.out.println("[GRAPPLE] ATTACHMENT ROUTE TIMED OUT; "
                    +"trying alternate anchor after grounded abort "+r.anchor);
            return false;
        }
        if(r.phase==Phase.WALK_TO_EDGE){
            BlockPos edge=r.anchor.above();
            if(mob.blockPosition().getY()!=edge.getY()
                    ||Math.abs(mob.getX()-edge.getX()-.5)>.27
                    ||Math.abs(mob.getZ()-edge.getZ()-.5)>.27){
                if(level.getGameTime()-r.started>200&&mob.onGround()){
                    active.remove(mob);
                    reject(level,mob,r);
                    WorkerNavigation.stop(mob);
                    return false;
                }
                if(adjacentFloorWalk(level,mob,edge)){
                    // Short real grounded movement across a shared floor
                    // when A* cannot start cleanly at the roof rim.
                    double dx=edge.getX()+.5-mob.getX();
                    double dz=edge.getZ()+.5-mob.getZ();
                    mob.getMoveControl().setWantedPosition(
                            edge.getX()+.5,edge.getY(),
                            edge.getZ()+.5,1.0);
                    double vx=Math.max(-.12,Math.min(.12,dx*.35));
                    double vz=Math.max(-.12,Math.min(.12,dz*.35));
                    var old=mob.getDeltaMovement();
                    if(level.noCollision(mob,
                            mob.getBoundingBox().move(vx,0,vz)))
                        mob.setDeltaMovement(vx,old.y,vz);
                }else if(mob.getNavigation().isDone())
                    WorkerNavigation.routeToExact(level,mob,edge,1.0);
                return false;
            }
            WorkerNavigation.stop(mob);
            r.phase=Phase.STEP_OFF;
        }
        if(r.phase==Phase.WALK_TO_LINE){
            BlockPos entry=r.landing;
            if(mob.blockPosition().getY()!=entry.getY()
                    ||Math.abs(mob.getX()-entry.getX()-.5)>.24
                    ||Math.abs(mob.getZ()-entry.getZ()-.5)>.24){
                if(mob.getNavigation().isDone())
                    WorkerNavigation.routeToExact(level,mob,entry,1.0);
                return false;
            }
            WorkerNavigation.stop(mob);
            r.phase=Phase.CLIMB;
        }
        // Anchor may be modified by a player while the hook is attached:
        // stay suspended instead of dropping the Builder to its death.
        if(!solidTop(level,r.anchor)){
            // Missing support: stay attached rather than free-falling.
            // Recovery is impossible without a solid upper anchor.
            mob.setNoGravity(true);
            mob.setDeltaMovement(0,0,0);
            return true;
        }
        // Compare ACTUAL world coordinates after the previous physics
        // tick. A velocity command is NOT progress when collision stops it.
        if(r.lastPhase!=r.phase){
            r.stalledTicks=0;
            r.lastPhase=r.phase;
        }else if(Double.isFinite(r.lastX)){
            double dx=mob.getX()-r.lastX,dy=mob.getY()-r.lastY,
                    dz=mob.getZ()-r.lastZ;
            if(dx*dx+dy*dy+dz*dz>.0016)r.stalledTicks=0;
            else r.stalledTicks++;
        }
        r.lastX=mob.getX();r.lastY=mob.getY();r.lastZ=mob.getZ();
        if((r.phase==Phase.SWING_TO_LINE||r.phase==Phase.CLIMB
                ||r.phase==Phase.STEP_ON_TOP||r.phase==Phase.STEP_OFF
                ||r.phase==Phase.RAPPEL
                ||r.phase==Phase.STEP_TO_LOWER_LEDGE)
                &&r.stalledTicks>=STUCK_TICKS)
            beginRecovery(level,mob,r);
        mob.setNoGravity(true);
        mob.setShiftKeyDown(false);
        emitLine(level,r,mob);
        double x=r.column.getX()+.5,z=r.column.getZ()+.5;
        boolean moved=true;
        switch(r.phase){
            case SWING_TO_LINE -> {
                // The taut rope holds the golem during the unsupported
                // lateral approach. Every velocity is collision tested.
                if(Math.abs(mob.getX()-x)<.115
                        &&Math.abs(mob.getZ()-z)<.115){
                    r.phase=Phase.CLIMB;
                    r.stalledTicks=0;
                }else moved=steer(level,mob,x,mob.getY(),z,.12,false);
            }
            case CLIMB -> {
                if(mob.getY()>=r.topY()+.17){
                    r.phase=Phase.STEP_ON_TOP;
                }else{
                    moved=steer(level,mob,x,r.topY()+.22,z,.16,true);
                }
            }
            case STEP_ON_TOP -> {
                moved=steer(level,mob,
                        r.anchor.getX()+.5,r.topY()+.12,
                        r.anchor.getZ()+.5,.15,true);
                if(Math.abs(mob.getX()-r.anchor.getX()-.5)<.18
                        &&Math.abs(mob.getZ()-r.anchor.getZ()-.5)<.18
                        &&mob.getY()>=r.topY()+.055){
                    release(mob,r,"ASCENDED");
                }
            }
            case STEP_OFF -> {
                moved=steer(level,mob,x,r.topY(),z,.14,true);
                if(Math.abs(mob.getX()-x)<.12
                        &&Math.abs(mob.getZ()-z)<.12)
                    r.phase=Phase.RAPPEL;
            }
            case RETREAT -> {
                // Climbing failed: slide BACK down the original clear
                // column. Keep the line attached throughout the descent.
                if(Math.abs(mob.getX()-x)>.07
                        ||Math.abs(mob.getZ()-z)>.07)
                    moved=steer(level,mob,x,mob.getY(),z,.14,true);
                else if(mob.getY()>r.landing.getY()+.035)
                    moved=steer(level,mob,x,r.landing.getY(),z,.14,true);
                else if(r.safeStart!=null){
                    r.phase=Phase.RETURN_TO_START;
                    r.stalledTicks=0;
                }else if(solidTop(level,r.landing.below())
                        &&headroom(level,r.landing)
                        &&level.noCollision(mob,mob.getBoundingBox()))
                    release(mob,r,"CLIMB RETREATED TO GROUND");
                else moved=false;
            }
            case RETURN_TO_START -> {
                // Failed unsupported swing must return to the SAME
                // physically supported ledge, never detach in open air.
                if(r.safeStart==null||!solidTop(level,r.safeStart.below())){
                    moved=false;
                }else{
                    double sx=r.safeStart.getX()+.5;
                    double sz=r.safeStart.getZ()+.5;
                    if(Math.abs(mob.getX()-sx)<.15
                            &&Math.abs(mob.getZ()-sz)<.15
                            &&mob.onGround()){
                        release(mob,r,"SWING RETREATED TO SAFE LEDGE");
                    }else moved=steer(level,mob,sx,r.safeStart.getY(),
                            sz,.12,false);
                }
            }
            case RETURN_TO_TOP -> {
                // Descending failed: ascend the same rope to slightly
                // ABOVE the upper ledge, then step back on its solid top.
                // Do not release while suspended over empty air.
                if(Math.abs(mob.getX()-x)>.07
                        ||Math.abs(mob.getZ()-z)>.07)
                    moved=steer(level,mob,x,mob.getY(),z,.14,true);
                else if(mob.getY()<r.topY()+.19)
                    moved=steer(level,mob,x,r.topY()+.22,z,.14,true);
                else{
                    r.phase=Phase.RETURN_ON_LEDGE;
                    r.stalledTicks=0;
                }
            }
            case RETURN_ON_LEDGE -> {
                moved=steer(level,mob,r.anchor.getX()+.5,
                        r.topY()+.12,r.anchor.getZ()+.5,.13,true);
                if(Math.abs(mob.getX()-r.anchor.getX()-.5)<.17
                        &&Math.abs(mob.getZ()-r.anchor.getZ()-.5)<.17
                        &&mob.getY()>=r.topY()+.055
                        &&solidTop(level,r.anchor))
                    release(mob,r,"RAPPEL RETREATED TO UPPER LEDGE");
            }
            case RAPPEL -> {
                if(mob.getY()<=r.landing.getY()+.055){
                    if(r.landing.getX()!=r.column.getX()
                            ||r.landing.getZ()!=r.column.getZ()){
                        r.phase=Phase.STEP_TO_LOWER_LEDGE;
                        r.stalledTicks=0;
                    }else if(solidTop(level,r.landing.below())
                            &&headroom(level,r.landing)
                            &&Math.abs(mob.getY()-r.landing.getY())<.065){
                        release(mob,r,"RAPPELLED TO FLOOR");
                    }else{
                        // Never detach until ground under the full golem
                        // is confirmed; an attached rope is the safety line.
                        moved=false;
                    }
                }else{
                    moved=steer(level,mob,x,r.landing.getY(),z,.14,true);
                }
            }
            case STEP_TO_LOWER_LEDGE -> {
                // Shift from the hanging line onto real supported terrain,
                // then and ONLY then release the hook.
                double lx=r.landing.getX()+.5;
                double lz=r.landing.getZ()+.5;
                if(Math.abs(mob.getX()-lx)<.14
                        &&Math.abs(mob.getZ()-lz)<.14
                        &&Math.abs(mob.getY()-r.landing.getY())<.065
                        &&solidTop(level,r.landing.below())
                        &&headroom(level,r.landing)){
                    release(mob,r,"RAPPELLED ONTO LOWER SHELF");
                }else moved=steer(level,mob,lx,r.landing.getY(),
                        lz,.12,false);
            }
            default -> {}
        }
        // A return must keep the rope until safe ground is reached.
        // If the original route is newly obstructed, never detach midair:
        // record the obstruction explicitly instead of silently freezing.
        if(r.phase==Phase.RETREAT||r.phase==Phase.RETURN_TO_START
                ||r.phase==Phase.RETURN_TO_TOP
                ||r.phase==Phase.RETURN_ON_LEDGE){
            r.recoveryTicks++;
            if(r.recoveryTicks==TRAVEL_TIMEOUT)
                System.out.println("[GRAPPLE] RECOVERY BLOCKED (still attached)"
                        +" phase="+r.phase+" anchor="+r.anchor
                        +" feet="+mob.position());
        }
        return true;
    }
}
