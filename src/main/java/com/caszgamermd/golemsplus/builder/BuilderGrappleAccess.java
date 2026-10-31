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
import net.minecraft.world.level.block.state.BlockState;
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
        WALK_TO_LINE, WALK_TO_EDGE, CLIMB, STEP_ON_TOP, STEP_OFF,
        RAPPEL, RETREAT, RETURN_TO_TOP, RETURN_ON_LEDGE
    }
    /** Max ticks without ACTUAL progress, not just a move intent. */
    static final int STUCK_TICKS=45;
    // Abandon a stationary, unhooked attachment approach after five seconds.
    // The timer resets on real ground movement; a long but moving approach
    // is never punished for distance alone.
    private static final int TRAVEL_TIMEOUT=100;
    private static final class Rope {
        final BlockPos anchor,column,landing,goal;
        final Direction side;
        final boolean down;
        final long started;
        Phase phase;
        int stalledTicks;
        int recoveryTicks;
        double lastX=Double.NaN,lastY=Double.NaN,lastZ=Double.NaN;
        Phase lastPhase;
        long lastWalkProgress;
        double walkX=Double.NaN,walkZ=Double.NaN;
        double supportY=Double.NaN,upperX,upperZ;
        Rope(BlockPos anchor,Direction side,BlockPos landing,BlockPos goal,
                boolean down,long time) {
            this.anchor=anchor.immutable();
            this.side=side;
            this.column=anchor.relative(side);
            this.landing=landing.immutable();
            this.goal=goal.immutable();
            this.down=down;
            this.started=time;
            this.lastWalkProgress=time;
            this.phase=down?Phase.WALK_TO_EDGE:Phase.WALK_TO_LINE;
            upperX=anchor.getX()+.5;upperZ=anchor.getZ()+.5;
        }
        double topY(){return Double.isFinite(supportY)?supportY:anchor.getY()+1.0;}
    }
    private static final Map<BuilderGolem,Rope> active=new WeakHashMap<>();
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
        if(continueLocalDrop(level,mob,goal))return true;
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
    /** A normal descent can temporarily gain height on a staircase detour.
     * Keep that physical route until its actual landing, not merely until
     * the worker changes Y. Otherwise return-to-stock hooks continually
     * interrupt it and cycle between the same two ledges.
     */
    private static boolean continueLocalDrop(ServerLevel level,
            BuilderGolem mob,BlockPos goal){
        NormalDrop hold=normalDrops.get(mob);
        if(hold==null)return false;
        if(level.getGameTime()>hold.until()||!hold.goal().equals(goal)
                ||mob.onGround()&&mob.blockPosition().equals(hold.landing())){
            normalDrops.remove(mob);
            return false;
        }
        if(!WorkerNavigation.canStandOn(level,mob,hold.landing())){
            normalDrops.remove(mob);
            return false;
        }
        if(!mob.onGround()||WorkerNavigation.routeToExact(
                level,mob,hold.landing(),1.0))return true;
        normalDrops.remove(mob);
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
        active.remove(mob);
        BuilderPlacementSpace.forgetApproach(mob);
        mob.setNoGravity(false);
        mob.setDeltaMovement(0,0,0);
        WorkerNavigation.stop(mob);
        mob.getMoveControl().setWantedPosition(mob.getX(),mob.getY(),mob.getZ(),0);
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
    private static boolean solidTop(ServerLevel world,BlockPos p){
        return world.hasChunkAt(p)
                &&world.getBlockState(p).isFaceSturdy(world,p,Direction.UP);
    }
    private static boolean anchorPresent(ServerLevel level,Rope rope){
        if(!Double.isFinite(rope.supportY))return solidTop(level,rope.anchor);
        var shape=level.getBlockState(rope.anchor).getCollisionShape(level,rope.anchor);
        for(AABB box:shape.toAabbs())
            if(Math.abs(box.maxY+rope.anchor.getY()-rope.supportY)<.05
                    &&box.move(rope.anchor).intersects(new AABB(
                        rope.upperX-.325,rope.supportY-.04,rope.upperZ-.325,
                        rope.upperX+.325,rope.supportY+.001,rope.upperZ+.325)))return true;
        return false;
    }
    /** Connected wall/fence shapes can change as neighboring dome cells
     * are placed. The hook remains attached to the SAME real block if
     * its new collision top is at most half a block from the original
     * attachment and still touches the original upper footing. Never
     * substitute a remote block or an empty-air attachment. */
    private static boolean refreshPartialAnchor(ServerLevel level,Rope rope){
        if(!Double.isFinite(rope.supportY))return false;
        var shape=level.getBlockState(rope.anchor).getCollisionShape(
                level,rope.anchor);
        for(AABB box:shape.toAabbs()){
            double top=rope.anchor.getY()+box.maxY;
            if(Math.abs(top-rope.supportY)>.55)continue;
            var physical=box.move(rope.anchor);
            if(physical.maxX<=rope.upperX-.325
                    ||physical.minX>=rope.upperX+.325
                    ||physical.maxZ<=rope.upperZ-.325
                    ||physical.minZ>=rope.upperZ+.325)continue;
            if(!lineClear(level,rope.column,rope.landing.getY(),
                    (int)Math.ceil(top)))continue;
            System.out.println("[GRAPPLE] RETAINED REAL SHAPED ANCHOR "
                    +rope.anchor.toShortString()+" collision top "
                    +rope.supportY+" -> "+top);
            rope.supportY=top;
            return true;
        }
        return false;
    }
    /** Releasing a changed hook is safe ONLY if the real mob is already
     * touching another solid collision surface under its soles. A mob
     * hanging in air stays secured; it is never teleported or dropped. */
    private static boolean physicallyStandingWithoutRope(ServerLevel level,
            BuilderGolem worker){
        AABB box=worker.getBoundingBox();
        AABB soles=new AABB(box.minX+.055,box.minY-.08,
                box.minZ+.055,box.maxX-.055,
                box.minY+.005,box.maxZ-.055);
        return level.noCollision(worker,box)
                &&level.getBlockCollisions(worker,soles).iterator().hasNext();
    }
    /** Emergency descent from an actual grounded partial-block perch.
     * A centered standing tile need not exist on a fence rail. Attach only
     * to collision geometry touching the soles, then sweep the entire body
     * to an adjacent clear rope column before committing to that face.
     */
    private static Rope chooseSupportedDescent(ServerLevel level,
            BuilderGolem worker,BlockPos goal){
        if(!worker.onGround())return null;
        double y=worker.getY();
        AABB body=worker.getBoundingBox();
        AABB soles=new AABB(body.minX,y-.04,body.minZ,body.maxX,y+.001,body.maxZ);
        List<Rope> candidates=new ArrayList<>();
        BlockPos feet=worker.blockPosition();
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)
            for(int dy=-2;dy<=0;dy++){
                BlockPos anchor=feet.offset(dx,dy,dz);
                if(!level.hasChunkAt(anchor))continue;
                boolean contact=false;
                for(AABB box:level.getBlockState(anchor)
                        .getCollisionShape(level,anchor).toAabbs())
                    if(Math.abs(box.maxY+anchor.getY()-y)<.05
                            &&box.move(anchor).intersects(soles)){contact=true;break;}
                if(!contact)continue;
                for(Direction side:Direction.Plane.HORIZONTAL){
                    if(rejected(level,worker,anchor,side))continue;
                    BlockPos column=anchor.relative(side);
                    double mx=column.getX()+.5-worker.getX();
                    double mz=column.getZ()+.5-worker.getZ();
                    boolean clear=true;
                    int samples=Math.max(8,(int)Math.ceil(Math.hypot(mx,mz)/.08));
                    for(int i=1;i<=samples;i++)
                        if(!level.noCollision(worker,body.move(mx*i/samples,0,mz*i/samples))){
                            clear=false;break;
                        }
                    if(!clear)continue;
                    for(int ly=(int)Math.floor(y)-1;y-ly<=MAX_REACH;ly--){
                        BlockPos landing=column.atY(ly);
                        if(!headroom(level,landing))break;
                        if(!solidTop(level,landing.below()))continue;
                        if(!lineClear(level,column,ly,(int)Math.ceil(y)))break;
                        Rope rope=new Rope(anchor,side,landing,goal,true,level.getGameTime());
                        rope.supportY=y;rope.upperX=worker.getX();rope.upperZ=worker.getZ();
                        rope.phase=Phase.STEP_OFF;
                        candidates.add(rope);break;
                    }
                }
            }
        candidates.sort(Comparator.comparingDouble(r->r.landing.distSqr(goal)));
        return candidates.isEmpty()?null:candidates.get(0);
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
        return solidAnchors(world,goal,4);
    }
    private static List<BlockPos> solidAnchors(ServerLevel world,
            BlockPos goal,int radius){
        List<BlockPos> out=new ArrayList<>();
        for(int dx=-radius;dx<=radius;dx++)for(int dz=-radius;dz<=radius;dz++){
            BlockPos at=goal.offset(dx,-1,dz);
            if(dx*dx+dz*dz>radius*radius||!solidTop(world,at)
                    ||!headroom(world,at.above()))continue;
            out.add(at);
        }
        out.sort(Comparator.comparingInt(p->p.distManhattan(goal)));
        return out;
    }
    /** A hook always anchors to the solid surface BELOW the landing feet.
     * The line must be on an open cardinal face and must reach an actual
     * walkable entry cell underneath it.
     */
    private static Rope chooseAscent(ServerLevel level,BuilderGolem worker,
            BlockPos goal){
        return chooseAscent(level,worker,goal,unused->true);
    }
    private static Rope chooseAscent(ServerLevel level,BuilderGolem worker,
            BlockPos goal,java.util.function.Predicate<BlockPos> usableLanding){
        return chooseAscent(level,worker,goal,usableLanding,4);
    }
    private static Rope chooseAscent(ServerLevel level,BuilderGolem worker,
            BlockPos goal,java.util.function.Predicate<BlockPos> usableLanding,
            int radius){
        List<Rope> candidates=new ArrayList<>();
        Map<BlockPos,Boolean> usable=new java.util.HashMap<>();
        for(BlockPos anchor:solidAnchors(level,goal,radius)){
            for(Direction side:Direction.Plane.HORIZONTAL){
                if(rejected(level,worker,anchor,side))continue;
                BlockPos entry=ropeEntryFloor(level,anchor,side);
                if(entry==null||!usable.computeIfAbsent(
                        anchor.above(),usableLanding::test))continue;
                candidates.add(new Rope(anchor,side,entry,goal,false,
                        level.getGameTime()));
            }
        }
        candidates.sort(Comparator.comparingDouble(r->
                r.landing.distSqr(worker.blockPosition())
                +r.anchor.distSqr(goal)*2.0));
        List<BlockPos> entries=new ArrayList<>();
        for(Rope r:candidates)if(!entries.contains(r.landing))entries.add(r.landing);
        // The entry may be BELOW the worker's current feet after a
        // normal walking detour. One trained multi-goal path chooses a
        // genuinely executable entry; no fixed-Y/native-only assumption.
        BlockPos selected=WorkerNavigation.selectExecutableStand(level,worker,entries);
        if(selected==null)return null;
        for(Rope r:candidates)if(r.landing.equals(selected))return r;
        return null;
    }
    /** First real floor below a clear rope column. A lower floor cannot
     * be used through the first obstruction. Each actual rope stays at
     * most 20 blocks long, independently of the worker's initial height.
     */
    private static BlockPos ropeEntryFloor(ServerLevel level,BlockPos anchor,
            Direction side){
        int top=anchor.getY()+1;
        BlockPos column=anchor.relative(side);
        if(!headroom(level,column.atY(top)))return null;
        for(int y=top-1;y>=top-MAX_REACH;y--){
            BlockPos at=column.atY(y);
            if(!headroom(level,at))return null;
            if(solidTop(level,at.below()))return top-y>=2?at:null;
        }
        return null;
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
                        BlockPos landing=column.atY(landingY);
                        if(!empty(level,landing)||!empty(level,landing.above()))
                            break; // real wall, never descend through it
                        if(!solidTop(level,landing.below()))continue;
                        if(!lineClear(level,column,landingY,topY))break;
                        candidates.add(new Rope(anchor,side,landing,goal,
                                true,level.getGameTime()));
                        break; // first safe lower floor on this line
                    }
                }
            }
        candidates.sort(Comparator.comparingDouble(r->
                r.anchor.above().distSqr(feet)*1.25
                +r.landing.distSqr(goal)*.7));
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
        // A short native path is NOT proof of executable trained movement,
        // and native failure is not proof that a detour or one-block jump
        // is impossible. Preflight all five poses in one bounded trained
        // search, then execute the exact selected stance physically.
        BlockPos chosen=WorkerNavigation.selectExecutableStand(level,mob,choices);
        return chosen!=null&&WorkerNavigation.routeToExact(level,mob,chosen,1.0);
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

    /** A construction ascent must end on terrain connected to a SAFE work
     * pose. A sturdy anchor alone can be an isolated pedestal, or put the
     * worker too close to its target to satisfy the 0.8m placement margin.
     */
    private static Rope chooseWorkAscent(ServerLevel level,BuilderGolem worker,
            BlockPos target){
        List<BlockPos> poses=new ArrayList<>();
        for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++)
            for(int dy:new int[]{0,1}){
                BlockPos at=target.offset(dx,dy,dz);
                double ex=Math.max(0,Math.abs(dx)-.5);
                double ez=Math.max(0,Math.abs(dz)-.5);
                if(Math.hypot(ex,ez)<BuilderPlacementSpace.MIN_EDGE_CLEARANCE
                        ||!WorkerNavigation.canStandOn(level,worker,at)
                        ||!BuilderConstructionPlanner.safeStand(
                                level,worker,target,at))continue;
                poses.add(at);
            }
        if(poses.isEmpty())return null;
        // Walking first; then a direct rope; then up to four real ropes.
        // Every prospective landing must connect onward to a safe work
        // pose through grounded walking and validated upward rope segments.
        for(int dy:new int[]{0,1}){
            Rope route=chooseAscent(level,worker,target.above(dy),
                    landing->WorkerNavigation.hasGroundRouteFrom(
                            level,worker,landing,poses));
            if(route!=null)return route;
        }
        // Expand walking components only for the staged fallback. Most
        // ordinary climbs need a short goal-directed path, not a flood
        // of the entire connected structure and surrounding ground.
        ClimbPreview preview=new ClimbPreview(level,worker,target,poses);
        // Access is allowed to walk around a floor to a remote ledge.
        // This larger search radius never changes actual placement reach.
        for(int dy:new int[]{0,1}){
            Rope remote=chooseAscent(level,worker,target.above(dy),preview::canWork,16);
            if(remote!=null)return remote;
        }
        int startY=worker.blockPosition().getY();
        for(int y=Math.min(target.getY()-2,startY+MAX_REACH);y>=startY+2;y--){
            Rope first=chooseAscent(level,worker,target.atY(y),
                    landing->preview.continues(landing,3),16);
            if(first!=null)return first;
        }
        return null;
    }

    private record ClimbKey(BlockPos landing,int remaining){}
    private record WalkComponent(BlockPos root,java.util.Set<BlockPos> cells){}
    /** Read-only, current-world bounded access graph. Connected walking
     * areas are shared across anchor probes, with a checked reverse route
     * before reusing one from another start (never assume directed grid
     * edges are reversible). No cache survives this planning invocation.
     */
    private static final class ClimbPreview {
        final ServerLevel level;
        final BuilderGolem worker;
        final BlockPos target;
        final List<BlockPos> poses;
        final List<WalkComponent> components=new ArrayList<>();
        final Map<BlockPos,java.util.Set<BlockPos>> walks=new java.util.HashMap<>();
        final Map<Integer,List<BlockPos>> anchors=new java.util.HashMap<>();
        final Map<BlockPos,Boolean> work=new java.util.HashMap<>();
        final Map<ClimbKey,Boolean> onward=new java.util.HashMap<>();
        ClimbPreview(ServerLevel level,BuilderGolem worker,BlockPos target,
                List<BlockPos> poses){
            this.level=level;this.worker=worker;this.target=target;this.poses=poses;
        }
        java.util.Set<BlockPos> walking(BlockPos from){
            var cached=walks.get(from);
            if(cached!=null)return cached;
            for(WalkComponent c:components){
                if(c.cells().contains(from)
                        &&WorkerNavigation.hasGroundRouteFrom(
                                level,worker,from,List.of(c.root()))){
                    walks.put(from,c.cells());return c.cells();
                }
            }
            var cells=WorkerNavigation.groundReachableFrom(level,worker,from);
            walks.put(from,cells);components.add(new WalkComponent(from,cells));
            return cells;
        }
        boolean canWork(BlockPos from){
            return work.computeIfAbsent(from,at->{
                var cells=walking(at);
                return poses.stream().anyMatch(cells::contains);
            });
        }
        List<BlockPos> anchors(int feetY){
            return anchors.computeIfAbsent(feetY,y->solidAnchors(level,target.atY(y),16));
        }
        boolean ropeEntry(BlockPos from,BlockPos anchor,
                java.util.Set<BlockPos> walking){
            for(Direction side:Direction.Plane.HORIZONTAL){
                if(rejected(level,worker,anchor,side))continue;
                BlockPos entry=ropeEntryFloor(level,anchor,side);
                if(entry!=null&&walking.contains(entry))return true;
            }
            return false;
        }
        boolean continues(BlockPos from,int remaining){
            ClimbKey key=new ClimbKey(from,remaining);
            Boolean known=onward.get(key);
            if(known!=null)return known;
            boolean found=search(from,remaining);
            onward.put(key,found);return found;
        }
        boolean search(BlockPos from,int remaining){
            if(canWork(from))return true;
            if(remaining<=0)return false;
            var cells=walking(from);
            for(int dy:new int[]{0,1})
                for(BlockPos anchor:anchors(target.getY()+dy))
                    if(ropeEntry(from,anchor,cells)&&canWork(anchor.above()))return true;
            if(remaining==1)return false;
            for(int y=Math.min(target.getY()-2,from.getY()+MAX_REACH);
                    y>=from.getY()+2;y--)
                for(BlockPos anchor:anchors(y))
                    if(ropeEntry(from,anchor,cells)
                            &&continues(anchor.above(),remaining-1))return true;
            return false;
        }
    }

    public static boolean canPlanWorkAscent(ServerLevel level,
            BuilderGolem worker,BlockPos target){
        return hasHook(worker)&&!active.containsKey(worker)
                &&chooseWorkAscent(level,worker,target)!=null;
    }
    /** An A* preview is not a successful movement command. A rope must
     * never become active unless its first real walking leg was accepted;
     * otherwise it monopolizes the Builder for the attachment timeout and
     * repeats an unreachable roof-rim entry on each assignment.
     */
    private static boolean startAscent(ServerLevel level,BuilderGolem worker,
            Rope route){
        if(!WorkerNavigation.routeToExact(level,worker,route.landing,1.0)){
            reject(level,worker,route); // try a different physical face next time
            return false;
        }
        active.put(worker,route);
        return true;
    }

    public static Result reachWork(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,BlockPos target){
        if(active.containsKey(worker)||!worker.onGround())return Result.MOVING;
        // Probe actual work poses before equipment. Never use an adjacent
        // target tile as evidence of valid work access.
        BlockPos pose=BuilderPlacementSpace.approachWithRules(level,worker,
                target,at->BuilderConstructionPlanner.safeStand(
                        level,worker,target,at));
        if(pose!=null&&WorkerNavigation.routeToExact(level,worker,pose,1.0))
            return Result.MOVING;
        if(!hasHook(worker)){
            return switch(BuilderSupplyInventory.loadGrapple(level,worker,table)){
                case READY,MOVING->Result.MOVING;
                case MISSING->Result.NEED_HOOK;
            };
        }
        Rope route=chooseWorkAscent(level,worker,target);
        if(route==null||!startAscent(level,worker,route))
            return Result.NO_SAFE_ANCHOR;
        return Result.MOVING;
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
        if(route==null||!startAscent(level,worker,route))
            return Result.NO_SAFE_ANCHOR;
        return Result.MOVING;
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
        if(continueLocalDrop(level,worker,goal))return true;
        int diff=worker.blockPosition().getY()-goal.getY();
        if(diff<1)return false; // ordinary depot pickup must still run
        // Let gravity finish a dismount before making another route
        // decision. A valid ground route may temporarily RISE around an
        // obstruction; its new Y is not a reason to interrupt it with a
        // hook back down to the same tile.
        if(!worker.onGround())return true;
        if(WorkerNavigation.routeTo(level,worker,goal,1.0)
                ||WorkerNavigation.hasPlannedGroundRoute(worker,goal))return true;
        // One-block drops are normal ground navigation first. Do not
        // grapple for a step that the trained pathfinder can traverse.
        if(diff==1){
            if(tryGroundRoute(level,worker,goal,false))
                return false; // caller may continue ordinary A* to OnSite
            if(tryLocalOneBlockDrop(level,worker,goal))
                return true; // existing normal A* owns the intermediate step
        }
        if(!hasHook(worker)){
            var load=BuilderSupplyInventory.loadGrapple(level,worker,table);
            return load!=BuilderSupplyInventory.LoadStatus.MISSING;
        }
        // A grounded fence/slab perch can be half a block above the
        // nominal ledge node. A geometric walk to a nearby full ledge can
        // repeatedly fail to center there; attach to actual sole contact
        // first in this fractional-height recovery case.
        Rope route=Math.abs(worker.getY()-Math.rint(worker.getY()))>.05
                ?chooseSupportedDescent(level,worker,goal):null;
        if(route==null)route=chooseDescent(level,worker,goal);
        if(route==null)route=chooseSupportedDescent(level,worker,goal);
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
        if(r.phase==Phase.WALK_TO_LINE||r.phase==Phase.WALK_TO_EDGE){
            if(!Double.isFinite(r.walkX)
                    ||Math.pow(mob.getX()-r.walkX,2)+Math.pow(mob.getZ()-r.walkZ,2)>.04){
                r.walkX=mob.getX();r.walkZ=mob.getZ();
                r.lastWalkProgress=level.getGameTime();
            }
        }
        if((r.phase==Phase.WALK_TO_LINE||r.phase==Phase.WALK_TO_EDGE)
                &&(level.getGameTime()-r.lastWalkProgress>=TRAVEL_TIMEOUT
                    ||level.getGameTime()-r.started>=2400)
                &&mob.onGround()){
            reject(level,mob,r);
            active.remove(mob);
            WorkerNavigation.stop(mob);
            System.out.println("[GRAPPLE] ATTACHMENT ROUTE TIMED OUT; "
                    +"trying alternate anchor after grounded abort "+r.anchor
                    +" entry="+r.landing+" feet="+mob.position()
                    +" "+WorkerNavigation.diagnoseExecutableStand(level,mob,List.of(r.landing)));
            return false;
        }
        if(r.phase==Phase.WALK_TO_EDGE){
            BlockPos edge=r.anchor.above();
            if(mob.blockPosition().getY()!=edge.getY()
                    ||Math.abs(mob.getX()-edge.getX()-.5)>.27
                    ||Math.abs(mob.getZ()-edge.getZ()-.5)>.27){
                if(mob.getNavigation().isDone())
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
        if(!anchorPresent(level,r)
                &&!refreshPartialAnchor(level,r)){
            if(physicallyStandingWithoutRope(level,mob)){
                // A real roof surface has caught the Builder while the
                // old wall top changed. Release the obsolete hook so
                // normal grounded work can resume on that same support.
                reject(level,mob,r);
                release(mob,r,"CHANGED ANCHOR, PHYSICALLY SUPPORTED");
                return false;
            }
            // Still suspended without a trustworthy anchor or floor:
            // never fabricate a safe dismount or let gravity drop it.
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
        if((r.phase==Phase.CLIMB||r.phase==Phase.STEP_ON_TOP
                ||r.phase==Phase.STEP_OFF||r.phase==Phase.RAPPEL)
                &&r.stalledTicks>=STUCK_TICKS)
            beginRecovery(level,mob,r);
        // Native walking acceleration must not push against rope velocity
        // or pull a newly dismounted worker back toward its lower entry.
        mob.getMoveControl().setWantedPosition(mob.getX(),mob.getY(),mob.getZ(),0);
        mob.setNoGravity(true);
        mob.setShiftKeyDown(false);
        emitLine(level,r,mob);
        double x=r.column.getX()+.5,z=r.column.getZ()+.5;
        boolean moved=true;
        switch(r.phase){
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
                else if(solidTop(level,r.landing.below())
                        &&headroom(level,r.landing)
                        &&level.noCollision(mob,mob.getBoundingBox()))
                    release(mob,r,"CLIMB RETREATED TO GROUND");
                else moved=false;
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
                moved=steer(level,mob,r.upperX,
                        r.topY()+.12,r.upperZ,.13,true);
                if(Math.abs(mob.getX()-r.upperX)<.07
                        &&Math.abs(mob.getZ()-r.upperZ)<.07
                        &&mob.getY()>=r.topY()+.055
                        &&anchorPresent(level,r))
                    release(mob,r,"RAPPEL RETREATED TO UPPER LEDGE");
            }
            case RAPPEL -> {
                if(mob.getY()<=r.landing.getY()+.055){
                    release(mob,r,"RAPPELLED");
                }else{
                    moved=steer(level,mob,x,r.landing.getY(),z,.14,true);
                }
            }
            default -> {}
        }
        // A return must keep the rope until safe ground is reached.
        // If the original route is newly obstructed, never detach midair:
        // record the obstruction explicitly instead of silently freezing.
        if(r.phase==Phase.RETREAT||r.phase==Phase.RETURN_TO_TOP
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
