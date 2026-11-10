package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Construction-aware frontier selection, not a replacement for trained A*.
 *
 * A claimed block must have an actual, reachable grounded work pose with
 * safe headroom, 0.8m horizontal margin, clear line of sight, and a
 * walkable escape after the proposed block is placed. A real anchored hook
 * may provide access to a higher pose, but never invents walking or flight.
 *
 * The planner respects the persistent strict shared Y-layer gate. Scanning
 * never completes, reassigns or buys a block: the Planner BE owns claims and
 * accounting and the actual Builder still uses vanilla physical movement.
 */
public final class BuilderConstructionPlanner {
    public record Choice(int index, BlockPos stance, boolean needsGrapple,
            boolean directSky, int examined){
        public boolean alreadyBuilt(){return stance==null&&!needsGrapple;}
    }
    private BuilderConstructionPlanner(){}
    private static final int MAX_EXAMINED=BuilderFrontierTuning.SCAN_LIMIT;
    private static final int MAX_FEASIBLE=BuilderFrontierTuning.FEASIBLE_LIMIT;
    /** A worker must EVENTUALLY inspect the whole current layer, not
     * repeatedly inspect the same nearest 32 inaccessible cells. This
     * is search progress (not a saved route) and resets on a Y change.
     */
    private record SearchWindow(int layer,int offset,int pending,
            int examined,int feasible,int chosen,int startOffset,
            int connected,int chosenContact){}
    public static String scanStatus(BuilderGolem worker){
        SearchWindow w=windows.get(worker);
        return w==null?"not yet scanned":
                "Y="+w.layer()+" pending="+w.pending()
                +" from="+w.startOffset()+" checked="+w.examined()
                +" possible="+w.feasible()+" connected="+w.connected()
                +" chosenTouch="+w.chosenContact()
                +" lastChosen="+w.chosen()+" nextOffset="+w.offset();
    }
    private static final Map<BuilderGolem,SearchWindow> windows=
            new WeakHashMap<>();
    /** Last REAL placement by this worker, not its last path request.
     * A coherent run follows the previous placed cell rather than
     * jumping between isolated schematic strips. Never saved as a
     * claim, route or source of material: only a soft ordering hint.
     */
    private static final Map<BuilderGolem,BlockPos> lastPlaced=
            new WeakHashMap<>();
    static void noteCompleted(BuilderGolem worker,BlockPos target){
        lastPlaced.put(worker,target.immutable());
        // Re-evaluate the new edge after each confirmed world placement.
        // Keep the rotating search window while merely trying (and
        // failing) approaches, so a blocked strip cannot starve others.
        windows.remove(worker);
    }
    private static final int REACH_SQUARED=14;

    /** Ray from a real Builder's eye at the planned stance to the block
     * center or face centers. Solid walls are never transparent just because A* could
     * reach a nearby standing tile. The target block itself may be
     * incorrect non-air and is allowed to be the final hit.
     */
    static boolean lineOfSight(ServerLevel world,BuilderGolem builder,
            BlockPos stance,BlockPos target){
        Vec3 eye=new Vec3(stance.getX()+.5,
                stance.getY()+builder.getEyeHeight(),stance.getZ()+.5);
        return lineOfSightFrom(world,builder,eye,target);
    }
    /** A wall-top Builder may be grounded at an actual half-block
     * elevation and off-center X/Z. Its real eyes, not a fictional
     * integer tile center, determine whether the cap is visible. */
    static boolean lineOfSightFrom(ServerLevel world,BuilderGolem builder,
            Vec3 eye,BlockPos target){
        Vec3 center=Vec3.atCenterOf(target);
        // Inset face samples remain inside the target cell. This permits
        // visible floor gaps without allowing rays through adjacent walls.
        Vec3[] samples={center,center.add(0,.499,0),center.add(0,-.499,0),
                center.add(.499,0,0),center.add(-.499,0,0),
                center.add(0,0,.499),center.add(0,0,-.499)};
        for(Vec3 sample:samples){
            var hit=world.clip(new ClipContext(eye,sample,
                    ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,builder));
            if(hit.getType()==HitResult.Type.MISS
                    ||hit.getType()==HitResult.Type.BLOCK
                        &&((net.minecraft.world.phys.BlockHitResult)hit)
                            .getBlockPos().equals(target))return true;
        }
        return false;
    }

    /** Verify a one-step PHYSICAL exit after the intended block exists.
     * Builders can walk cardinally/diagonally and can climb/descend one
     * block without a hook. A same-Y-only cardinal check wrongly rejected
     * almost every useful dome ledge and pinned entire construction zones.
     *
     * This is only a local exit proof. We never claim this proves every
     * possible maze has a path all the way back to OnSite.
     */
    static boolean exitAfterPlacement(ServerLevel world,BuilderGolem builder,
            BlockPos stance,BlockPos target){
        var future=new net.minecraft.world.phys.AABB(target)
                .inflate(.10,0,.10);
        var base=builder.getBoundingBox().move(
                stance.getX()+.5-builder.getX(),
                stance.getY()-builder.getY(),
                stance.getZ()+.5-builder.getZ());
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++){
            if(dx==0&&dz==0)continue;
            for(int dy:new int[]{0,-1,1}){
                BlockPos dest=stance.offset(dx,dy,dz);
                if(!WorkerNavigation.canStandOn(world,builder,dest))
                    continue;
                // Every candidate uses exactly the real 0.8m clearance,
                // including when its feet stand above or below the new cube.
                // The 0.8 m placement margin applies to the work pose,
                // NOT to a later escape move. A physically collidable
                // 0.65-wide Golem can safely pass beside the new block
                // with less arm/placement clearance. The swept AABB
                // against the FUTURE block below is the safety check.
                boolean free=true;
                for(int i=1;i<=4;i++){
                    double t=i/4.0;
                    // A real jump rises BEFORE moving across the next
                    // block's solid face. A drop steps over the rim and
                    // then lowers. Plain walks interpolate both axes.
                    double horizontal=dy>0?Math.max(0,2*t-1):
                            dy<0?Math.min(1,2*t):t;
                    double rise=dy>0?Math.min(1,2*t):
                            dy<0?-Math.max(0,2*t-1):0;
                    var moved=base.move(dx*horizontal,rise,dz*horizontal);
                    if(moved.intersects(future)
                            ||!world.noCollision(builder,moved)){
                        free=false;break;
                    }
                }
                if(free)return true;
            }
        }
        return false;
    }
    static boolean safeStand(ServerLevel world,BuilderGolem builder,
            BlockPos target,BlockPos stance){
        return stance.distSqr(target)<REACH_SQUARED
                // A target's interaction point is HALF A BLOCK ABOVE
                // its integer Y, whereas the Builder's feet are at the
                // stance Y. From Y-2, a three-tile horizontal offset is
                // sqrt(3^2+2.5^2) away, NOT sqrt(3^2+2^2). The loose
                // integer preview repeatedly chose a Y31 dome-cap pose
                // that actual placement could never reach.
                &&workReachFromFeet(stance,target)<REACH_SQUARED
                &&(stance.getY()==target.getY()
                        ||stance.getY()==target.getY()+1
                        ||stance.getY()==target.getY()-1
                        ||stance.getY()==target.getY()-2)
                &&lineOfSight(world,builder,stance,target)
                &&exitAfterPlacement(world,builder,stance,target);
    }
    /** Waterlogging modifies a PREEXISTING block rather than creating
     * new solid collision. Requiring an exit from a hypothetical fresh
     * solid cube can reject valid elevated finishing poses. Require real
     * footing, 3-block hand reach and unobstructed sight instead.
     * All other work still obeys the normal placement/escape checks.
     */
    static boolean safeStandForStep(ServerLevel world,BuilderGolem builder,
            BlockPos target,BlockPos stance,String encodedState){
        String expectedBlock=BuilderWaterPass.originalState(encodedState)
                .split("\\[",2)[0];
        if(!BuilderWaterPass.isWaterlogPass(encodedState)
                ||!net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(
                        world.getBlockState(target).getBlock())
                        .toString().equals(expectedBlock))
            return safeStand(world,builder,target,stance);
        return !stance.equals(target)
                &&stance.distSqr(target)<REACH_SQUARED
                &&workReachFromFeet(stance,target)<REACH_SQUARED
                &&(stance.getY()==target.getY()
                    ||stance.getY()==target.getY()+1
                    ||stance.getY()==target.getY()-1
                    ||stance.getY()==target.getY()-2)
                &&WorkerNavigation.canStandOn(world,builder,stance)
                &&lineOfSight(world,builder,stance,target);
    }

    static double workReachFromFeet(BlockPos stance,BlockPos target){
        double dx=stance.getX()-target.getX();
        double dz=stance.getZ()-target.getZ();
        double dy=stance.getY()-target.getY()-.5;
        return dx*dx+dy*dy+dz*dz;
    }
    /**
     * Physical contact with a CORRECT, already-built schematic cell is a
     * construction advantage. Favor extending established roof supports
     * and walls before starting isolated islands on the same Y. Nearby
     * terrain or unrelated player blocks do NOT count as completed work.
     *
     * Only used as a score AFTER the real work pose, escape and placement
     * support were checked. This never authorizes a floating block.
     */
    static int connectedPlacementBonus(ServerLevel world,BlockPos target,
            Map<BlockPos,BlockState> schematic){
        int side=0,below=0,above=0;
        for(Direction direction:Direction.values()){
            BlockPos neighbor=target.relative(direction);
            BlockState expected=schematic.get(neighbor);
            if(expected==null||!world.hasChunkAt(neighbor)
                    ||!BuilderPlacementState.matches(
                            world.getBlockState(neighbor),expected))continue;
            if(direction==Direction.DOWN)below++;
            else if(direction==Direction.UP)above++;
            else side++;
        }
        // A built block UNDER the next placement is a particularly useful
        // foothold. Side connections allow outward growth along the roof.
        return Math.min(100,below*75+side*42+above*10);
    }
    /** Horizontal contact matters more than a floor underneath.
     * Almost every upper dome cell already has a lower-layer support;
     * treating that support as equivalent to a completed SAME-Y run
     * caused a field of isolated strips instead of a connected front.
     */
    static int sameLayerConnections(ServerLevel world,BlockPos target,
            Map<BlockPos,BlockState> schematic){
        int count=0;
        for(Direction side:Direction.Plane.HORIZONTAL){
            BlockPos adjacent=target.relative(side);
            BlockState expected=schematic.get(adjacent);
            if(expected!=null&&world.hasChunkAt(adjacent)
                    &&BuilderPlacementState.matches(
                            world.getBlockState(adjacent),expected))
                count++;
        }
        return count;
    }
    /** Building a neighboring cell can close the THIRD or FOURTH
     * side around an unfinished schematic position. Those holes are
     * much harder to access later on a roof. Defer such closure when
     * another physically valid cell exists, but never forbid it: an
     * open-air grapple or paid scaffold may be the only way to finish.
     */
    static int pocketClosureRisk(ServerLevel world,BlockPos target,
            Map<BlockPos,BlockState> schematic){
        int worst=0;
        for(Direction side:Direction.Plane.HORIZONTAL){
            BlockPos neighbor=target.relative(side);
            BlockState expected=schematic.get(neighbor);
            if(expected==null||!world.hasChunkAt(neighbor)
                    ||BuilderPlacementState.matches(
                            world.getBlockState(neighbor),expected))continue;
            int already=sameLayerConnections(world,neighbor,schematic);
            if(already>=2)worst=Math.max(worst,already-1);
        }
        return worst;
    }
    /** 0 = safe connected run; 1 = connected but would narrow a gap;
     * 2 = new island; 3 = would fully enclose an unfinished cell.
     * This is a preference only, AFTER collision and exit validation.
     */
    static int frontierTier(int contacts,int closureRisk){
        return closureRisk>=2?3:contacts>0?(closureRisk>0?1:0):2;
    }
    /** Stable cheap ordering of the entire current Y. This orders
     * *which cells to PROBE*, not which cells may legally be placed.
     * The ordinary physical pose/exit checks still decide feasibility.
     * If all connected probes fail, the rotating scan proceeds to the
     * other islands rather than imposing an impossible build order.
     */
    private static double stepDistanceSquared(BuilderConstructionPlan.Step step,
            BlockPos anchor){
        double dx=(double)step.x()-anchor.getX();
        double dy=(double)step.y()-anchor.getY();
        double dz=(double)step.z()-anchor.getZ();
        return dx*dx+dy*dy+dz*dz;
    }
    static void orderConnectedFront(List<Integer> pending,
            List<BuilderConstructionPlan.Step> steps,
            Map<Integer,Integer> contacts,Map<Integer,Integer> risks,
            BlockPos anchor){
        pending.sort((left,right)->{
            int a=contacts.getOrDefault(left,0);
            int b=contacts.getOrDefault(right,0);
            int ta=frontierTier(a,risks.getOrDefault(left,0));
            int tb=frontierTier(b,risks.getOrDefault(right,0));
            if(ta!=tb)return Integer.compare(ta,tb);
            // Within the same safe frontier tier, travel locality wins.
            // Filling extra contacts is helpful, but making a Builder cross
            // its zone to do so wastes much more walking than extending the
            // nearest safe run after its previous physical placement.
            var x=steps.get(left);
            var y=steps.get(right);
            // No temporary BlockPos allocations during hundreds of sort
            // comparisons at every layer handoff.
            double da=stepDistanceSquared(x,anchor);
            double db=stepDistanceSquared(y,anchor);
            int distance=Double.compare(da,db);
            if(distance!=0)return distance;
            return a!=b?Integer.compare(b,a):Integer.compare(left,right);
        });
    }
    /** Lookahead runs before its current block is placed, so the future
     * neighbor is not yet part of the real built frontier. Count that one
     * expected contact for ordering only; world support, reachability and
     * collision checks still use real blocks, and the claim is rechecked. */
    static int upcomingConnection(int builtContacts,BlockPos candidate,
            BlockPos afterPlacement){
        if(afterPlacement==null||candidate.getY()!=afterPlacement.getY())
            return builtContacts;
        int dx=Math.abs(candidate.getX()-afterPlacement.getX());
        int dz=Math.abs(candidate.getZ()-afterPlacement.getZ());
        return builtContacts+(dx+dz==1?1:0);
    }
    private static final Map<List<BuilderConstructionPlan.Step>,
            Map<BlockPos,BlockState>> schematicStates=new WeakHashMap<>();
    private static Map<BlockPos,BlockState> schematicStates(
            List<BuilderConstructionPlan.Step> steps,
            BuildSite.Rotation rotation,BuildSite.Mirror mirror){
        // Never cache by file path: the same placed schematic has different
        // world coordinates after moving or rotating its placement.
        return schematicStates.computeIfAbsent(steps,unused->{
            Map<BlockPos,BlockState> expected=new java.util.HashMap<>();
            for(var cell:steps){
                if(cell.ignorePlant())continue;
                var state=BuilderBlockResolver.resolve(
                        cell.encodedState(),rotation,mirror);
                if(state!=null)expected.put(
                        new BlockPos(cell.x(),cell.y(),cell.z()),state.state());
            }
            return Map.copyOf(expected);
        });
    }
    private static double score(BuilderGolem worker,BlockPos target,
            BlockPos stance,boolean sky,boolean grapple){
        BlockPos reference=stance==null?target:stance;
        return reference.distSqr(worker.blockPosition())
                +target.distSqr(reference)*BuilderFrontierTuning.TARGET_WEIGHT
                +(sky?0:BuilderFrontierTuning.SKY_PENALTY) // favor open sky
                +(grapple?BuilderFrontierTuning.GRAPPLE_PENALTY:0); // rank valid hook access
    }

    /** Human-readable diagnostic in SCHEMATIC-LOCAL XYZ. This never
     * changes claims or tests artificial standability. It records exact
     * unfinished inputs and their real-world support so failures are
     * actionable rather than "pathfinding failed" with random test XZ.
     */
    public static String blockedSummary(ServerLevel world,
            WorkerStationBlockEntity table,
            List<BuilderConstructionPlan.Step> steps,List<Integer> zone){
        int layer=table.builderActiveLayer(steps);
        BlockPos origin=table.builderOrigin();
        int pending=0;
        List<String> examples=new ArrayList<>();
        for(int idx:zone){
            if(idx<0||idx>=steps.size()
                    ||steps.get(idx).y()!=layer
                    ||table.builderZoneDone(List.of(idx)))continue;
            pending++;
            if(examples.size()>=4)continue;
            var step=steps.get(idx);
            BlockPos p=new BlockPos(step.x(),step.y(),step.z());
            var expected=BuilderBlockResolver.resolve(step.encodedState(),
                    BuildSite.Rotation.values()[table.builderRotation()/90],
                    BuildSite.Mirror.values()[table.builderMirror()]);
            String reason=step.ignorePlant()
                    ?"ignored vegetation: no physical support required":
                    !world.hasChunkAt(p)?"chunk unloaded":
                    expected==null?"unsupported block state":
                    !expected.state().canSurvive(world,p)
                        ?"missing physical support":
                    "no confirmed reachable work stance/exit";
            examples.add("#"+idx+" XYZ="+p.subtract(origin).toShortString()
                    +" state="+step.encodedState()
                    +" reason="+reason);
        }
        return "Y="+(layer-origin.getY())+" pending="+pending
                +" next=["+String.join("; ",examples)+"]";
    }

    /** A Builder with no ordinary work stance must build physical footing
     * under an unsupported placement before trying an optional grapple. */
    static boolean preferScaffoldForUnsupportedWork(
            boolean ordinaryWorkStance,boolean unsupportedBelow){
        return !ordinaryWorkStance&&unsupportedBelow;
    }

    /** Diagnose the exact first supported frontier obstruction with the
     * current world and trained route finder; never move or alter blocks.
     * This is for failure reports, not the 5-tick claim hot path.
     */
    public static String diagnoseBlockedZone(ServerLevel world,
            BuilderGolem worker,WorkerStationBlockEntity table,
            List<BuilderConstructionPlan.Step> steps,List<Integer> zone){
        int y=table.builderActiveLayer(steps);
        for(int idx:zone){
            if(idx<0||idx>=steps.size()||steps.get(idx).y()!=y
                    ||table.builderZoneDone(List.of(idx))
                    ||steps.get(idx).ignorePlant())continue;
            var step=steps.get(idx);
            BlockPos target=new BlockPos(step.x(),step.y(),step.z());
            if(!world.hasChunkAt(target))continue;
            var state=BuilderBlockResolver.resolve(step.encodedState(),
                    BuildSite.Rotation.values()[table.builderRotation()/90],
                    BuildSite.Mirror.values()[table.builderMirror()]);
            if(state==null||!state.state().canSurvive(world,target))
                continue;
            int feasible=0,feet=0,sight=0,escapes=0,routes=0,probes=0;
            for(int dx=-3;dx<=3;dx++)for(int dz=-3;dz<=3;dz++)
                for(int dy:new int[]{0,1,-1,-2}){
                    BlockPos at=target.offset(dx,dy,dz);
                    if(at.equals(target)||at.distSqr(target)>=REACH_SQUARED)
                        continue;
                    double px=at.getX()+.5,pz=at.getZ()+.5;
                    double ex=Math.max(0,Math.max(target.getX()-px,
                            px-target.getX()-1));
                    double ez=Math.max(0,Math.max(target.getZ()-pz,
                            pz-target.getZ()-1));
                    if(Math.hypot(ex,ez)<.8)continue;
                    feasible++;
                    if(!WorkerNavigation.canStandOn(world,worker,at))continue;
                    feet++;
                    if(!lineOfSight(world,worker,at,target))continue;
                    sight++;
                    if(!exitAfterPlacement(world,worker,at,target))continue;
                    escapes++;
                    if(probes++<8&&WorkerNavigation.canReachExact(
                            world,worker,at))routes++;
                }
            return " | firstSupported #"+idx+" localXYZ="
                    +target.subtract(table.builderOrigin()).toShortString()
                    +" poses="+feasible+" standable="+feet+" LOS="+sight
                    +" exits="+escapes+" reachable="+routes
                    +" | "+BuilderPlacementSpace.diagnoseApproach(
                            world,worker,target,
                            at->safeStandForStep(world,worker,target,at,
                                    step.encodedState()));
        }
        return " | no supported pending target on current Y";
    }

    /** Select the most promising same-layer cell in the worker's OWN
     * zone. Readiness is checked against persistent step completion and
     * cooldown state. We look beyond the first blocked strip element;
     * failure is NOT permission to skip the current Y layer.
     */
    public static Choice choose(ServerLevel world,BuilderGolem worker,
            WorkerStationBlockEntity table,
            List<BuilderConstructionPlan.Step> steps,List<Integer> zone){
        return select(world,worker,table,steps,zone,false);
    }
    /** Speculatively evaluate only a small number of candidates while
     * the current A* route is ALREADY moving. This is not a claim,
     * a material reservation or a path command. It does not change
     * the current work-approach cache or the fair full-scan cursor.
     */
    public static Choice chooseLookahead(ServerLevel world,BuilderGolem worker,
            WorkerStationBlockEntity table,
            List<BuilderConstructionPlan.Step> steps,List<Integer> zone){
        return chooseLookahead(world,worker,table,steps,zone,0,8);
    }
    /** A two-probe time slice lets the worker think on several travel
     * ticks instead of blocking a single server tick with eight A* probes.
     * offset is in the ordered eligible work front, not the saved plan.
     */
    public static Choice chooseLookahead(ServerLevel world,BuilderGolem worker,
            WorkerStationBlockEntity table,
            List<BuilderConstructionPlan.Step> steps,List<Integer> zone,
            int offset,int budget){
        return chooseLookahead(world,worker,table,steps,zone,offset,budget,null);
    }
    /** Order speculative next blocks from the placement being finished,
     * not the worker's older completed cell or their moving path position. */
    public static Choice chooseLookahead(ServerLevel world,BuilderGolem worker,
            WorkerStationBlockEntity table,
            List<BuilderConstructionPlan.Step> steps,List<Integer> zone,
            int offset,int budget,BlockPos afterPlacement){
        return select(world,worker,table,steps,zone,true,offset,budget,afterPlacement);
    }
    private static Choice select(ServerLevel world,BuilderGolem worker,
            WorkerStationBlockEntity table,
            List<BuilderConstructionPlan.Step> steps,List<Integer> zone,
            boolean lookahead){
        return select(world,worker,table,steps,zone,lookahead,0,8,null);
    }
    private static Choice select(ServerLevel world,BuilderGolem worker,
            WorkerStationBlockEntity table,
            List<BuilderConstructionPlan.Step> steps,List<Integer> zone,
            boolean lookahead,int lookaheadOffset,int lookaheadBudget,
            BlockPos afterPlacement){
        int y=table.builderActiveLayer(steps);
        if(y==Integer.MAX_VALUE)return null;
        List<Integer> pending=new ArrayList<>();
        for(int idx:zone){
            if(idx<0||idx>=steps.size()
                    ||!table.builderStepInActivePhase(steps,idx)
                    ||!table.builderStepReadyForClaim(idx))continue;
            pending.add(idx);
        }
        // Lookahead needs a small, nearby candidate neighborhood, not a
        // collision/closure-risk sweep of every unplaced block on this Y.
        // The normal full selector retains its rotating whole-layer search.
        if(lookahead&&pending.size()>32){
            BlockPos focus=afterPlacement==null?worker.blockPosition():afterPlacement;
            pending.sort(java.util.Comparator.comparingDouble(
                    idx->stepDistanceSquared(steps.get(idx),focus)));
            pending=new ArrayList<>(pending.subList(0,32));
        }
        // Ignored vegetation is paperwork, not a world placement.
        // Complete these regardless of footing/line of sight. This
        // prevents a full 32-cell blocked strip from starving azaleas
        // and making an irrelevant "missing support" a permanent stop.
        for(int idx:pending)
            if(steps.get(idx).ignorePlant())
                return new Choice(idx,null,false,true,0);
        // Rank the WHOLE current layer cheaply before spending any A*
        // pathfinding probes. Finished horizontal neighbours define a
        // connected work front. The last physically placed block keeps
        // a worker on its current run, rather than hopping to another
        // strip just because the other strip is a metre closer.
        Map<BlockPos,BlockState> builtSchematic=schematicStates(steps,
                BuildSite.Rotation.values()[table.builderRotation()/90],
                BuildSite.Mirror.values()[table.builderMirror()]);
        var contacts=new java.util.HashMap<Integer,Integer>();
        var closureRisks=new java.util.HashMap<Integer,Integer>();
        int connected=0;
        for(int idx:pending){
            var step=steps.get(idx);
            BlockPos target=new BlockPos(step.x(),step.y(),step.z());
            int touch=sameLayerConnections(world,target,builtSchematic);
            if(lookahead)touch=upcomingConnection(touch,target,afterPlacement);
            contacts.put(idx,touch);
            closureRisks.put(idx,pocketClosureRisk(
                    world,target,builtSchematic));
            if(touch>0)connected++;
        }
        BlockPos last=afterPlacement==null?lastPlaced.get(worker):afterPlacement;
        if(last!=null&&last.getY()!=y)last=null;
        BlockPos anchor=last==null?worker.blockPosition():last;
        orderConnectedFront(pending,steps,contacts,closureRisks,anchor);
        Choice best=null;
        int bestTier=Integer.MAX_VALUE;
        int bestContact=0;
        double bestScore=Double.POSITIVE_INFINITY;
        SearchWindow previous=lookahead?null:windows.get(worker);
        int offset=lookahead
                ?(pending.isEmpty()?0:Math.floorMod(lookaheadOffset,pending.size()))
                :previous!=null&&previous.layer()==y?previous.offset():0;
        int examined=0,feasible=0;
        // One speculative slice checks at most two physical approaches.
        // Subsequent work callbacks continue at the next offset while
        // the golem keeps following its existing path.
        int count=Math.min(lookahead?Math.max(0,lookaheadBudget)
                :MAX_EXAMINED,pending.size());
        for(int attempt=0;attempt<count;attempt++){
            int idx=pending.get((offset+attempt)%pending.size());
            examined++;
            var step=steps.get(idx);
            BlockPos target=new BlockPos(step.x(),step.y(),step.z());
            if(!world.hasChunkAt(target))continue;
            // Already matching cells and ignored plants are NO-OP work:
            // the normal build service will confirm them in the world
            // and mark completion without walking or consuming items.
            if(step.ignorePlant()){
                return new Choice(idx,null,false,true,examined);
            }
            var state=BuilderBlockResolver.resolve(step.encodedState(),
                    BuildSite.Rotation.values()[table.builderRotation()/90],
                    BuildSite.Mirror.values()[table.builderMirror()]);
            if(state==null)continue;
            BlockState actual=world.getBlockState(target);
            boolean primaryMatches=BuilderPlacementState.matches(
                    actual,state.state());
            var partner=step.companion();
            boolean companionMatches=true;
            if(partner!=null){
                var pairedState=BuilderBlockResolver.resolve(
                        partner.encodedState(),
                        BuildSite.Rotation.values()[table.builderRotation()/90],
                        BuildSite.Mirror.values()[table.builderMirror()]);
                companionMatches=pairedState!=null
                        &&world.hasChunkAt(BuilderMultiblock.position(partner))
                        &&BuilderPlacementState.matches(
                                world.getBlockState(BuilderMultiblock.position(partner)),
                                pairedState.state());
            }
            // One grouped claim is finished only if BOTH real cells
            // match; a preexisting foot/lower must not hide its partner.
            if(primaryMatches&&companionMatches)
                return new Choice(idx,null,false,true,examined);
            if(!state.state().canSurvive(world,target))continue;
            BlockPos stance=lookahead
                    ?BuilderPlacementSpace.approachForLookahead(
                            world,worker,target,
                            at->safeStandForStep(world,worker,target,at,
                                    step.encodedState()))
                    :BuilderPlacementSpace.approachWithRules(
                            world,worker,target,
                            at->safeStandForStep(world,worker,target,at,
                                    step.encodedState()));
            if(stance==null&&(BuilderPlacementSpace.safeFractionalCapPose(
                    world,worker,target)
                    ||BuilderPlacementSpace.canRetreatToFractionalCap(
                            world,worker,target)))
                // The actual fractional body is already on a real roof
                // support. The five-tick Builder task will execute a
                // collision-checked sidestep before considering placement.
                stance=worker.blockPosition();
            // An elevated unsupported block needs construction footing.
            // Prefer a real, item-paid scaffold platform if one can be
            // built. A grapple is an access tool, not a replacement for a
            // safe pose from which to PLACE the block. If a scaffold
            // cannot be planned, ordinary grapple access stays available.
            boolean unsupportedBelow=world.getBlockState(target.below()).isAir();
            boolean preferPlatform=preferScaffoldForUnsupportedWork(
                    stance!=null,unsupportedBelow);
            boolean scaffold=preferPlatform
                    &&BuilderScaffoldPlanA.canPlanWorkPose(
                            world,worker,table,target,steps);
            boolean grapple=false;
            if(stance==null&&!scaffold
                    &&target.getY()-worker.blockPosition().getY()>0
                    &&worker.carriedCount(WorksiteContent.GRAPPLE_HOOK)>0
                    &&BuilderGrappleAccess.canPlanWorkAscent(world,worker,target))
                grapple=true;
            boolean floating=BuilderScaffoldPlanA
                    .needsTemporaryWorkPlatform(world,target);
            if(stance==null&&!grapple&&!scaffold)
                scaffold=BuilderScaffoldPlanA.canPlanWorkPose(
                        world,worker,table,target,steps)
                        ||(target.getY()-worker.blockPosition().getY()>1
                            &&!floating&&BuilderScaffolding.canPlanAccess(
                                    world,worker,table,target,steps));
            if(stance==null&&!grapple&&!scaffold)continue;
            boolean sky=stance==null
                    ?world.canSeeSky(target.above())
                    :world.canSeeSky(stance.above());
            // Valid same-layer frontier candidates that TOUCH existing
            // completed architecture get scheduled first, making finished
            // roof ledges and columns useful access infrastructure.
            int touch=contacts.getOrDefault(idx,0);
            // A reachable extension of a SAME-Y strip always outranks
            // seeding another island. Only valid, collision-checked work
            // stances enter this comparison. If no connected work pose
            // exists, unconnected cells remain completely eligible.
            int risk=closureRisks.getOrDefault(idx,0);
            int tier=frontierTier(touch,risk);
            double cost=score(worker,target,stance,sky,grapple)
                    // Greedy short-hop routing across the worker's valid,
                    // same-Y frontier, anchored to its last placed block.
                    +target.distSqr(anchor)*BuilderFrontierTuning.LOCALITY_WEIGHT
                    // Connections still break close ties, but no longer
                    // outweigh a long trip across the construction zone.
                    -Math.min(24,connectedPlacementBonus(
                            world,target,builtSchematic))
                    -touch*6+risk*250
                    +(scaffold?BuilderFrontierTuning.GRAPPLE_PENALTY*2:0);
            if(tier<bestTier||tier==bestTier&&cost<bestScore){
                bestTier=tier;
                bestContact=touch;
                bestScore=cost;
                best=new Choice(idx,stance,grapple,sky,examined);
            }
            if(++feasible>=(lookahead?1:MAX_FEASIBLE))break;
        }
        // A fully blocked nearby strip must never monopolize this Y.
        // Subsequent passes explore another bounded window until every
        // pending cell gets its own physical work-position opportunity.
        // A selected job may be deferred when its initially valid approach
        // becomes blocked during the real walk. Retain search progress
        // across each claim so the NEXT claim tries another frontier
        // instead of restarting at the same five nearest cells forever.
        if(!lookahead)
            windows.put(worker,new SearchWindow(y,
                    (offset+Math.max(1,examined))%Math.max(1,pending.size()),
                    pending.size(),examined,feasible,best==null?-1:best.index(),
                    offset,connected,bestContact));
        return best;
    }
}
