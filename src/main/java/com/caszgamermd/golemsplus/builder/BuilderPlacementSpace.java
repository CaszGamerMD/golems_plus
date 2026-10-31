package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * A Builder must not place a solid schematic cell through its own body or
 * another worker. Nearby workers step off the target first, with their
 * construction/courier task temporarily held so its normal navigation does
 * not immediately send them back into the block about to be placed.
 *
 * No teleportation and no fake blocks: the location must be navigable with
 * actual ground and free collision. If someone cannot safely vacate, the
 * original step stays claimed and its materials are not consumed.
 */
public final class BuilderPlacementSpace {
    private BuilderPlacementSpace(){}
    private record Yield(BlockPos target,BlockPos destination,
            long until,boolean placingWorker,boolean precision){}
    private static final Map<BuilderGolem,Yield> yielding=new WeakHashMap<>();
    // A work target must keep the same approach position across successive
    // 12-tick updates. Recomputing the nearest spot from a moving golem
    // makes it switch sides every few steps and continually resets A*.
    private record Approach(BlockPos target,BlockPos destination,long until){}
    private record Rejected(BlockPos target,BlockPos destination,long until){}
    private static final Map<BuilderGolem,Rejected> rejected=new WeakHashMap<>();
    private static final Map<BuilderGolem,Approach> workApproaches=
            new WeakHashMap<>();

    private static AABB volume(BlockPos pos){
        // Horizontal breathing room prevents a Golem's shoulder/edge
        // clipping into a new full block. Do not inflate vertically: an
        // entity whose feet are already on TOP of the completed surface
        // does not obstruct that lower block.
        return new AABB(pos).inflate(.10,0,.10);
    }

    // Clearance is measured from the Golem's CENTER to the closest edge
    // of the *future* target block in X/Z, not from block-center to
    // block-center. A 0.65-wide Builder at x=targetMaxX+.80 keeps its full
    // shoulder outside the new cube. Tile centers only 0.5 from the
    // nearest edge are NOT sufficient.
    static final double MIN_EDGE_CLEARANCE=.80;
    private static double edgeDistance(BlockPos block,double x,double z){
        double dx=Math.max(0,Math.max(block.getX()-x,x-(block.getX()+1.0)));
        double dz=Math.max(0,Math.max(block.getZ()-z,z-(block.getZ()+1.0)));
        return Math.hypot(dx,dz);
    }
    /** A Builder with a three-block reach can place an overhead block
     * from grounded footing one or two blocks below it, provided the
     * separate exact reach, line-of-sight and escape checks also pass.
     * This is ordinary hand placement, not a flying work stance: it
     * cannot license an out-of-reach target or unlock a schematic layer.
     */
    public static boolean placementHeight(BlockPos target,BuilderGolem worker){
        int feetY=worker.blockPosition().getY();
        return feetY==target.getY()
                ||worker.onGround()&&!worker.isNoGravity()
                    &&(feetY==target.getY()+1
                        ||feetY==target.getY()-1
                        ||feetY==target.getY()-2);
    }
    /** The same horizontal margin applies when placing DOWN one block.
     * Being vertically above an AIR target is NOT an exemption: otherwise
     * a Builder could keep its feet directly over the future block.
     */
    public static boolean horizontalClear(BlockPos target,BuilderGolem worker){
        return edgeDistance(target,worker.getX(),worker.getZ())
                >=MIN_EDGE_CLEARANCE-.015;
    }
    /** A wall/fence cap can support the Builder at an ACTUAL Y+.5
     * elevation even though Minecraft has no integer-centered A* node
     * there. Prove continuous block collision clearance and a real
     * under-foot contact surface for each fractional retreat sample.
     * This is read-only and grants no movement or placement by itself.
     */
    private static boolean supportedFractional(ServerLevel level,
            BuilderGolem worker,double dx,double dz){
        AABB body=worker.getBoundingBox().move(dx,0,dz);
        if(!level.noCollision(worker,body))return false;
        AABB footing=new AABB(body.minX+.055,body.minY-.075,
                body.minZ+.055,body.maxX-.055,
                body.minY+.006,body.maxZ-.055);
        return level.getBlockCollisions(worker,footing).iterator().hasNext();
    }
    private static boolean fractionalCapStart(ServerLevel level,
            BuilderGolem worker,BlockPos target){
        int feetBlockY=worker.blockPosition().getY();
        return worker.onGround()&&!worker.isNoGravity()
                // A real narrow roof top supports the feet at Y+.5,
                // not at any integer-centered A* stance. The last dome
                // column is a sea lantern with TWO fences directly above:
                // the same safely grounded cap stance must be allowed to
                // reach those next two schematic layers without flying.
                &&target.getY()>=feetBlockY
                &&target.getY()<=feetBlockY+2
                &&Math.abs(worker.getY()-feetBlockY-.5)<.13
                &&level.getBlockState(target).isAir()
                &&worker.distanceToSqr(target.getX()+.5,
                        target.getY()+.5,target.getZ()+.5)<14
                &&supportedFractional(level,worker,0,0);
    }
    /** The current REAL half-height cap pose has full hand clearance,
     * visible target and a supported physical retreat AFTER placement.
     * No fictional tile-center exit is required for an entity already
     * standing safely on its narrow wall-top collision geometry.
     */
    public static boolean safeFractionalCapPose(ServerLevel level,
            BuilderGolem worker,BlockPos target){
        if(!fractionalCapStart(level,worker,target)
                ||!horizontalClear(target,worker)
                ||worker.getBoundingBox().intersects(volume(target))
                ||!BuilderConstructionPlanner.lineOfSightFrom(level,worker,
                        new net.minecraft.world.phys.Vec3(worker.getX(),
                                worker.getEyeY(),worker.getZ()),target))return false;
        double fromX=worker.getX()-target.getX()-.5;
        double fromZ=worker.getZ()-target.getZ()-.5;
        double magnitude=Math.hypot(fromX,fromZ);
        return magnitude>.01&&supportedFractional(level,worker,
                .07*fromX/magnitude,.07*fromZ/magnitude);
    }
    /** Before choosing an otherwise impossible high cap, look ahead at
     * the SAME real physics-controlled sidestep used for teammate yields.
     * Require every 6cm segment to retain actual support and clearance,
     * finish with >=.8m edge margin, and keep an additional safe exit.
     */
    public static boolean canRetreatToFractionalCap(ServerLevel level,
            BuilderGolem worker,BlockPos target){
        if(!fractionalCapStart(level,worker,target)
                ||horizontalClear(target,worker))return false;
        double x=worker.getX(),z=worker.getZ();
        double fromX=x-target.getX()-.5,fromZ=z-target.getZ()-.5;
        double[][] axes=Math.abs(fromX)>=Math.abs(fromZ)
                ?new double[][]{{Math.copySign(1,fromX),0},
                        {0,Math.copySign(1,fromZ)}}
                :new double[][]{{0,Math.copySign(1,fromZ)},
                        {Math.copySign(1,fromX),0}};
        for(double[] axis:axes){
            if(axis[0]==0&&Math.abs(fromZ)<.01
                    ||axis[1]==0&&Math.abs(fromX)<.01)continue;
            for(int n=1;n<=12;n++){
                double dx=axis[0]*n*.06,dz=axis[1]*n*.06;
                if(!supportedFractional(level,worker,dx,dz))break;
                if(edgeDistance(target,x+dx,z+dz)
                        <MIN_EDGE_CLEARANCE-.015)continue;
                if(worker.getBoundingBox().move(dx,0,dz)
                            .intersects(volume(target))
                        ||!supportedFractional(level,worker,
                                dx+axis[0]*.07,dz+axis[1]*.07))break;
                var eye=new net.minecraft.world.phys.Vec3(x+dx,
                        worker.getEyeY(),z+dz);
                if(BuilderConstructionPlanner.lineOfSightFrom(
                        level,worker,eye,target))return true;
            }
        }
        return false;
    }
    /** Start the EXISTING real, inventory-free precision sidestep while
     * this worker is still too near the cap. The per-entity controller
     * moves at most 11.5cm per tick and stops at the safe margin.
     */
    public static boolean stepBackFromFractionalCap(ServerLevel level,
            BuilderGolem worker,WorkerStationBlockEntity table,BlockPos target){
        if(!canRetreatToFractionalCap(level,worker,target))return false;
        ready(level,worker,table,target);
        Yield hold=yielding.get(worker);
        return hold!=null&&hold.target().equals(target)&&hold.precision();
    }
    private static boolean occupied(BlockPos target,BuilderGolem worker){
        if(!worker.isAlive())return false;
        boolean overlapsHeight=worker.getBoundingBox().maxY>target.getY()
                &&worker.getBoundingBox().minY<target.getY()+1;
        boolean aboveWork=worker.blockPosition().getY()==target.getY()+1;
        return overlapsHeight && worker.getBoundingBox().intersects(volume(target))
                ||(overlapsHeight||aboveWork)&&!horizontalClear(target,worker);
    }
    /** Choose a REAL per-tick lateral movement that clears the target.
     * Try more than the nearest face: a nearby slab, wall or teammate
     * may block the obvious side even though another side is available.
     * Keep exact physical collision and ground support for every choice.
     */
    private static double[] precisionStep(ServerLevel level,BuilderGolem mob,
            BlockPos target){
        // Even when a Builder's feet are ONE BELOW the placement Y,
        // its 1.62-high head may intersect a solid block being placed
        // by another Builder. It must be able to step sideways too.
        if(!mob.onGround()
                ||!(placementHeight(target,mob)
                    ||mob.blockPosition().getY()==target.getY()-1
                        &&mob.getBoundingBox().maxY>target.getY()))
            return null;
        double cx=target.getX()+.5,cz=target.getZ()+.5;
        double x=mob.getX(),z=mob.getZ();
        double[][] candidates={
                {x>=cx?1:-1,0},{0,z>=cz?1:-1},
                {x>=cx?-1:1,0},{0,z>=cz?-1:1}
        };
        // The near-side movement is preferred, but any cardinal path
        // that increases separation is valid when it has real footing.
        double bestScore=Double.POSITIVE_INFINITY;
        double[] best=null;
        BlockPos oldFoot=mob.blockPosition();
        for(double[] direction:candidates){
            double vx=direction[0]*.115,vz=direction[1]*.115;
            double newX=x+vx,newZ=z+vz;
            double oldGap=edgeDistance(target,x,z);
            if(oldGap>.001&&edgeDistance(target,newX,newZ)<=oldGap+.0001)
                continue; // a local sidestep must actually gain clearance
            BlockPos foot=BlockPos.containing(newX,mob.getY(),newZ);
            if(!level.hasChunkAt(foot)||!level.hasChunkAt(foot.below()))
                continue;
            // On the same block we already have true physical footing.
            // On another tile, check for a real block or partial-block
            // collision surface below rather than requiring a FULL
            // face-sturdy block; that incorrectly rejected slabs.
            if(!foot.equals(oldFoot)
                    &&level.getBlockState(foot.below())
                        .getCollisionShape(level,foot.below()).isEmpty()
                    &&level.getBlockState(foot)
                        .getCollisionShape(level,foot).isEmpty())
                continue;
            if(!level.noCollision(mob,mob.getBoundingBox().move(vx,0,vz)))
                continue;
            double desiredX=direction[0]>0?target.getX()+1.84:target.getX()-.84;
            double desiredZ=direction[1]>0?target.getZ()+1.84:target.getZ()-.84;
            double distance=direction[0]!=0
                    ?Math.abs(desiredX-newX):Math.abs(desiredZ-newZ);
            double score=distance;
            if(direction[0]!=0&&direction[0]*(x-cx)<0
                    ||direction[1]!=0&&direction[1]*(z-cz)<0)
                score+=2.0;
            if(score<bestScore){bestScore=score;best=new double[]{vx,vz};}
        }
        return best;
    }
    private static boolean canPrecisionStep(ServerLevel level,BuilderGolem mob,
            BlockPos target){
        return precisionStep(level,mob,target)!=null;
    }
    /** This phase operates every real entity tick before Minecraft physics.
     * No snaps, flight, teleportation or unsafe instant block placements.
     */
    public static boolean tickPrecisionYield(ServerLevel level,BuilderGolem mob){
        Yield hold=yielding.get(mob);
        if(hold==null||!hold.precision()||!occupied(hold.target(),mob))return false;
        double[] step=precisionStep(level,mob,hold.target());
        if(step==null)return false;
        WorkerNavigation.stop(mob);
        mob.getMoveControl().setWantedPosition(mob.getX(),mob.getY(),mob.getZ(),0);
        var velocity=mob.getDeltaMovement();
        mob.setDeltaMovement(step[0],Math.min(0,velocity.y),step[1]);
        return true;
    }

    /** Run before ANY ordinary Builder assignment/courier/navigation tick. */
    public static boolean yieldTick(ServerLevel level,BuilderGolem worker){
        Yield hold=yielding.get(worker);
        if(hold==null)return false;
        if(!worker.isAlive()||level.getGameTime()>=hold.until()
                ||!occupied(hold.target(),worker)
                    &&(hold.placingWorker()
                        ||!level.getBlockState(hold.target()).isAir())){
            // Once the placement exists, its physical collision prevents
            // anyone from walking back into the same block. Resume work
            // immediately instead of waiting out the full yield window.
            yielding.remove(worker);
            return false;
        }
        // The last fraction of a block is a continuous physics-controlled
        // precision move; an exact tile-center path would stop too close.
        if(occupied(hold.target(),worker)&&hold.precision()){
            if(canPrecisionStep(level,worker,hold.target()))return true;
            BlockPos destination=escape(level,worker,hold.target());
            if(destination!=null){
                hold=new Yield(hold.target(),destination,hold.until(),hold.placingWorker(),false);
                yielding.put(worker,hold);
            }
        }
        // Otherwise the worker must take a normal collision-safe A* path.
        if(occupied(hold.target(),worker)
                ||worker.distanceToSqr(hold.destination().getX()+.5,
                        hold.destination().getY(),hold.destination().getZ()+.5)>2.25)
            WorkerNavigation.routeToExact(level,worker,hold.destination(),.9);
        else if(!worker.getNavigation().isDone())
            worker.getNavigation().stop();
        return true;
    }

    private static boolean canStand(ServerLevel level,BuilderGolem worker,
            BlockPos at,BlockPos obstructed){
        // An adjacent cardinal tile is safe when the FULL mob bounding
        // box clears the future block (checked below). Rejecting every
        // tile closer than 1.8 forced Builders on narrow upper ledges to
        // search across non-existent two-block-wide platforms forever.
        if(at.closerThan(obstructed,1.0)
                ||!level.hasChunkAt(at)||!level.hasChunkAt(at.above())
                ||!level.hasChunkAt(at.below()))return false;
        BlockState feet=level.getBlockState(at);
        BlockState above=level.getBlockState(at.above());
        BlockState below=level.getBlockState(at.below());
        if(!level.getFluidState(at).isEmpty()
                ||!feet.getCollisionShape(level,at).isEmpty()
                ||!above.getCollisionShape(level,at.above()).isEmpty()
                ||below.getCollisionShape(level,at.below()).isEmpty())
            return false;
        AABB shifted=worker.getBoundingBox().move(
                at.getX()+.5-worker.getX(),at.getY()-worker.getY(),
                at.getZ()+.5-worker.getZ());
        // Never choose the Builder's EXISTING blocked tile as its
        // escape; that silently routes it back to the obstacle forever.
        // Candidate centers must clear 0.8m in X/Z even if the body
        // overlaps from the layer below, not just avoid an AABB contact.
        if(at.equals(worker.blockPosition())
                &&occupied(obstructed,worker))return false;
        if(edgeDistance(obstructed,at.getX()+.5,at.getZ()+.5)
                    <MIN_EDGE_CLEARANCE
                ||shifted.intersects(volume(obstructed))
                ||!level.noCollision(worker,shifted))return false;
        for(var other:yielding.entrySet())
            if(other.getKey()!=worker
                    &&other.getValue().destination().equals(at)
                    &&other.getValue().until()>level.getGameTime())
                return false;
        return true;
    }

    private static BlockPos escape(ServerLevel level,BuilderGolem worker,
            BlockPos target){
        BlockPos origin=worker.blockPosition();
        List<BlockPos> choices=new ArrayList<>();
        // Probe a compact, predictable set of cardinal/diagonal safe
        // standing locations instead of inspecting hundreds of cells on
        // every 12-tick build update (which itself caused visible freezes).
        // Include straight aisle exits. The old corner-only scan tried
        // (radius,radius) diagonals and missed open cardinal paths along a
        // wall, leaving builders stalled despite clear floor two blocks away.
        // Eight directions x three heights x three radii is bounded; only
        // the best candidates below perform expensive navigation probes.
        for(int radius=1;radius<=4;radius++){
            int[][] offsets={
                    {radius,0},{-radius,0},{0,radius},{0,-radius},
                    {radius,radius},{radius,-radius},
                    {-radius,radius},{-radius,-radius}
            };
            for(int[] direction:offsets)
                for(int dy:new int[]{0,1,-1}){
                    BlockPos p=target.offset(direction[0],dy,direction[1]);
                    if(canStand(level,worker,p,target))choices.add(p);
                }
        }
        choices.sort(java.util.Comparator.comparingDouble(p->
                p.distSqr(origin)+Math.abs(p.getY()-origin.getY())*2));
        int attempted=0;
        for(BlockPos p:choices){
            if(++attempted>16)break; // bounded path work; try more than one aisle side
            // Favor a physically reachable tile; don't assume an air
            // candidate is reachable around walls/stairs/racks.
            if(WorkerNavigation.canReachExact(level,worker,p))return p.immutable();
        }
        return null;
    }

    /** Find an EXACT reachable work position within placement reach.
     * The evacuation method intentionally requires >=1.8 blocks from a
     * target, and the general routeTo() accepts approximate arrival. Neither
     * is valid for construction: stopping two blocks short of a distant
     * "safe" escape cell leaves a Builder idle five blocks from its target.
     * This probe is bounded and cached until target or terrain changes.
     */
    public static BlockPos approach(ServerLevel level,BuilderGolem worker,
            BlockPos target){
        return approachWithHeight(level,worker,target,false,p->true);
    }
    /** Plan B work stance: feet on target Y or one Y ABOVE, with real
     * footing and at least 0.8 horizontal clearance from the new block.
     * This does not authorize claiming any higher schematic Y layer.
     */
    public static BlockPos approachAtTargetY(ServerLevel level,
            BuilderGolem worker,BlockPos target){
        return approachWithHeight(level,worker,target,true,p->true);
    }
    /** Builder construction planner can validate line of sight, immediate
     * post-placement exit and teammate reservations BEFORE committing
     * materials. Never return a cached work stance that violates them.
     */
    public static BlockPos approachWithRules(ServerLevel level,
            BuilderGolem worker,BlockPos target,
            java.util.function.Predicate<BlockPos> safe){
        // A grounded Y-1/Y-2 work stance can be closer and safer than
        // constructing a tower to stand beside an already in-reach block.
        // The safeStand predicate still rejects any unsupported pose.
        return approachWithHeight(level,worker,target,false,safe);
    }
    private static BlockPos approachWithHeight(ServerLevel level,
            BuilderGolem worker,BlockPos target,boolean sameHeight,
            java.util.function.Predicate<BlockPos> safe){
        long now=level.getGameTime();
        Approach cached=workApproaches.get(worker);
        if(cached!=null&&cached.target().equals(target)
                &&(!sameHeight||cached.destination().getY()==target.getY()
                        ||cached.destination().getY()==target.getY()+1)
                &&cached.until()>now
                &&safe.test(cached.destination())
                &&cached.destination().distSqr(target)<14
                &&!(cached.destination().equals(worker.blockPosition())
                    &&worker.distanceToSqr(target.getX()+.5,
                            target.getY()+.5,target.getZ()+.5)>13.0)
                &&WorkerNavigation.canStandOn(level,worker,cached.destination()))
            return cached.destination();
        Rejected bad=rejected.get(worker);
        if(bad!=null&&bad.until()<=now){
            rejected.remove(worker);bad=null;
        }
        List<BlockPos> candidates=new ArrayList<>();
        // Physical work reach is a 14-squared sphere, not a flat
        // 3x3 rectangle. In a layered Dome, useful upper ledges may be
        // two blocks above/below the target even if same-level floor is
        // sealed by completed walls. Do not insist on a jump or teleport.
        for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++)
            for(int dy:sameHeight?new int[]{0,1}:new int[]{0,1,-1,2,-2}){
                if(dx==0&&dz==0&&dy==0)continue;
                BlockPos p=target.offset(dx,dy,dz);
                if(!safe.test(p)
                        ||edgeDistance(target,p.getX()+.5,p.getZ()+.5)
                            <MIN_EDGE_CLEARANCE
                        ||p.distSqr(target)>=14
                        ||p.equals(worker.blockPosition())
                            &&worker.distanceToSqr(target.getX()+.5,
                                target.getY()+.5,target.getZ()+.5)>13.0
                        ||!WorkerNavigation.canStandOn(level,worker,p)
                        ||bad!=null&&bad.target().equals(target)
                            &&bad.destination().equals(p))continue;
                AABB shifted=worker.getBoundingBox().move(
                        p.getX()+.5-worker.getX(),p.getY()-worker.getY(),
                        p.getZ()+.5-worker.getZ());
                // The trained A* permits a path through a tile
                // temporarily occupied by another Builder, and physical
                // crew courtesy resolves traffic during movement.
                // Do not reject the WHOLE work frontier because a teammate
                // is currently standing at a future stance. Reject real
                // blocks, including partial collision shapes, absolutely.
                if(shifted.intersects(volume(target))
                        ||!level.noCollision(worker,shifted)
                            &&level.getBlockCollisions(worker,shifted)
                                    .iterator().hasNext())continue;
                candidates.add(p);
            }
        // Prefer real ground near the worker, but also prefer a work
        // stance that actually faces the target instead of an extremely
        // remote diagonal. A measured reach check always outranks score.
        candidates.sort(java.util.Comparator
                .comparingDouble((BlockPos p)->
                        p.distSqr(worker.blockPosition())*1.3
                        +Math.abs(p.getY()-worker.blockPosition().getY())*4
                        // Builder preference: walk in direct skylight
                        // rather than inside newly enclosed dome pockets.
                        // It is a cost only, NEVER an access prohibition.
                        +(level.canSeeSky(p.above())?0:5.0)
                        +p.distSqr(target)*1.20
                        +Math.pow(Math.max(0,p.distSqr(target)-8),2)*3)
                .thenComparingInt(BlockPos::getX)
                .thenComparingInt(BlockPos::getZ));
        // The feasible set is intrinsically bounded (7x7x2 poses).
        // Cutting off at 40 NEAREST candidates discarded reachable
        // far-side stances when nearby poses were separated by a wall.
        // Never label a frontier unreachable until EVERY physically
        // eligible stance has had its trained A* route checked.
        // Preflight the SAME trained A* used for physical movement
        // against all safe poses at once. Native canReachExact accepted
        // routes that the trained controller could never begin.
        BlockPos selected=WorkerNavigation.selectExecutableStand(
                level,worker,candidates);
        if(selected!=null){
            workApproaches.put(worker,new Approach(target,
                    selected.immutable(),now+75));
            return selected.immutable();
        }
        workApproaches.remove(worker);
        return null;
    }

    /** Read-only comparison to the *actual* work-position preflight.
     * Diagnose separately the standing-pose checks, entity/block collision
     * and trained A* reach. Never claim a step, choose a route or cache
     * a work approach while gathering evidence for a stalled job.
     */
    public static String diagnoseApproach(ServerLevel level,
            BuilderGolem worker,BlockPos target,
            java.util.function.Predicate<BlockPos> safe){
        int safeCount=0,marginCount=0,groundedCount=0;
        int geometryCount=0,blockedGeometry=0,routedCount=0;
        List<BlockPos> executableCandidates=new ArrayList<>();
        Rejected bad=rejected.get(worker);
        boolean liveBad=bad!=null&&bad.until()>level.getGameTime();
        for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++)
            for(int dy:new int[]{0,1,-1,-2}){
                if(dx==0&&dz==0&&dy==0)continue;
                BlockPos at=target.offset(dx,dy,dz);
                if(!safe.test(at))continue;
                safeCount++;
                if(edgeDistance(target,at.getX()+.5,
                        at.getZ()+.5)<MIN_EDGE_CLEARANCE
                        ||at.distSqr(target)>=14)continue;
                marginCount++;
                if(!WorkerNavigation.canStandOn(level,worker,at))continue;
                groundedCount++;
                AABB moved=worker.getBoundingBox().move(
                        at.getX()+.5-worker.getX(),
                        at.getY()-worker.getY(),
                        at.getZ()+.5-worker.getZ());
                if(moved.intersects(volume(target))
                        ||!level.noCollision(worker,moved)
                            &&level.getBlockCollisions(worker,moved)
                                .iterator().hasNext()){
                    blockedGeometry++;continue;
                }
                if(liveBad&&bad.target().equals(target)
                        &&bad.destination().equals(at))continue;
                geometryCount++;
                executableCandidates.add(at);
                if(WorkerNavigation.canReachExact(level,worker,at))
                    routedCount++;
            }
        return "approach safe="+safeCount+" margin="+marginCount
                +" ground="+groundedCount+" bodyBlocked="+blockedGeometry
                +" candidates="+geometryCount+" exactRoutes="+routedCount
                +" trainedExecutable="+(WorkerNavigation.selectExecutableStand(
                    level,worker,executableCandidates)!=null)
                +" trainedReason="+WorkerNavigation.diagnoseExecutableStand(
                    level,worker,executableCandidates)
                +" badPose="+liveBad;
    }

    /** Equipment changed the physical starting point. Forget the old
     * approach without marking its otherwise valid floor tile as bad.
     */
    static void forgetApproach(BuilderGolem worker){
        workApproaches.remove(worker);
    }

    /** The physical position is not fixed forever: a player or teammate
     * can obstruct it after path calculation. Drop ONLY this worker's
     * approach and re-search without disturbing any other Builder.
     */
    public static void invalidateApproach(BuilderGolem worker,BlockPos target){
        Approach approach=workApproaches.get(worker);
        if(approach!=null&&approach.target().equals(target)){
            workApproaches.remove(worker);
            // A moving wall, nearby Builder or scaffold landing can make
            // one stance temporarily inaccessible. Remember THIS failed
            // position, not the target or all floor cells; retry others.
            rejected.put(worker,new Rejected(target,
                    approach.destination(),worker.level().getGameTime()+80));
        }
    }

    /** Release an expired self-placement reservation when the current
     * step is deferred. Only that worker's hold is removed; other crew
     * reservations and pending work remain intact.
     */
    public static void cancelYield(BuilderGolem worker){
        if(yielding.remove(worker)!=null)WorkerNavigation.stop(worker);
    }

    /** Read-only diagnostic for stuck workers. Capture the exact held
     * placement cell and reserved escape, including expiry, without
     * changing a route or claiming a build step.
     */
    public static String debugYield(ServerLevel level,BuilderGolem worker){
        return debugYield(level,worker,BlockPos.ZERO);
    }
    public static String debugYield(ServerLevel level,BuilderGolem worker,
            BlockPos schematicOrigin){
        Yield hold=yielding.get(worker);
        if(hold==null)return "yield=none";
        return "yieldTargetXYZ="+hold.target().subtract(schematicOrigin).toShortString()
                +" escapeXYZ="+hold.destination().subtract(schematicOrigin).toShortString()
                +" expiresIn="+Math.max(0,hold.until()-level.getGameTime())+"t"
                +" stillTooClose="+occupied(hold.target(),worker)
                +" escapeStandable="+WorkerNavigation.canStandOn(
                        level,worker,hold.destination());
    }

    /** Precise occupants of the intended block's collision volume.
     * This is for reporting only, never an alternative movement policy.
     */
    public static String debugOccupants(ServerLevel level,
            WorkerStationBlockEntity table,BlockPos target){
        return debugOccupants(level,table,target,BlockPos.ZERO);
    }
    public static String debugOccupants(ServerLevel level,
            WorkerStationBlockEntity table,BlockPos target,
            BlockPos schematicOrigin){
        List<BuilderGolem> blocking=level.getEntitiesOfClass(
                BuilderGolem.class,volume(target).inflate(1,1,1),
                g->g.isAlive()&&table.builderWorkerIds().contains(g.getUUID())
                        &&occupied(target,g));
        if(blocking.isEmpty())return "none";
        List<String> details=new ArrayList<>();
        for(BuilderGolem g:blocking)
            details.add(g.getName().getString()
                    +" XYZ="+g.blockPosition().subtract(schematicOrigin).toShortString()
                    +" feetXYZ="+String.format(java.util.Locale.ROOT,
                            "(%.2f,%.2f,%.2f)",
                            g.getX()-schematicOrigin.getX(),
                            g.getY()-schematicOrigin.getY(),
                            g.getZ()-schematicOrigin.getZ())
                    +" "+debugYield(level,g,schematicOrigin));
        return String.join("; ",details);
    }

    /** @return true only when the future block volume is clear of workers. */
    public static boolean ready(ServerLevel level,BuilderGolem placing,
            WorkerStationBlockEntity table,BlockPos target){
        AABB area=volume(target);
        List<BuilderGolem> blockers=level.getEntitiesOfClass(
                BuilderGolem.class,area.inflate(1,1,1),
                g->g.isAlive()&&table.builderWorkerIds().contains(g.getUUID())
                        &&occupied(target,g));
        if(blockers.isEmpty()){
            // A player/other mob must also never be suffocated by placement.
            List<LivingEntity> others=level.getEntitiesOfClass(
                    LivingEntity.class,area,
                    g->g.isAlive()&&!(g instanceof BuilderGolem builder
                            &&table.builderWorkerIds().contains(builder.getUUID())));
            if(others.isEmpty())return true;
            table.setProblem("Waiting for entity to vacate construction space "
                    +target.toShortString());
            return false;
        }
        boolean found=true;
        for(BuilderGolem blocker:blockers){
            Yield old=yielding.get(blocker);
            if(old!=null&&old.target().equals(target)
                    &&old.until()>level.getGameTime())continue;
            // When standing adjacent on real ground, do a precise
            // horizontal edge-clearance move instead of targeting a tile
            // center that still intersects the future placement cube.
            boolean precision=canPrecisionStep(level,blocker,target);
            BlockPos destination=precision?blocker.blockPosition():escape(level,blocker,target);
            // An old stand candidate at the AIR target itself would have
            // walked the blocker INTO the block it was supposed to clear.
            if(destination!=null
                    &&destination.equals(target))destination=null;
            if(destination==null){
                found=false;
                continue;
            }
            yielding.put(blocker,new Yield(target,destination,
                    level.getGameTime()+60,blocker==placing,precision));
            WorkerNavigation.routeToExact(level,blocker,destination,.9);
        }
        // An AIR cell is not being mined here. Before placing a new solid
        // schematic block into it, real golems occupying that space must
        // walk away so the new block cannot suffocate them.
        table.setProblem(found
                ?"Waiting for Builders to vacate placement cell "
                        +target.toShortString()
                :"Waiting for safe standing space near "+target.toShortString());
        return false;
    }
}
