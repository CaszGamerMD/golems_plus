package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/** Real 26.2 entity test for a work approach on a raised Dome-like rim
 * and for precise final centering inside the selected standing cell.
 */
public final class BuilderWorkAccessGameTest {
    /** A fence's narrow post may leave the body collision-free while
     * there is only 10cm of space to squeeze past it. A Builder needs
     * a further 12cm work-pose margin, without banning ordinary footing
     * a full tile away or falsely treating the fence as a solid cube.
     */
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=100,skyAccess=true)
    public void fenceCollisionNeedsWorkPoseBreathingRoom(
            GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        helper.setBlock(6,1,6,Blocks.STONE);
        helper.setBlock(7,2,6,Blocks.BIRCH_FENCE);
        BlockPos footing=helper.absolutePos(new BlockPos(6,2,6));
        BuilderGolem close=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        close.setPos(footing.getX()+.95,footing.getY(),footing.getZ()+.5);
        level.addFreshEntity(close);
        if(level.getBlockCollisions(close,close.getBoundingBox())
                    .iterator().hasNext()){
            helper.fail("Test stance must NOT intersect the fence itself");return;
        }
        if(BuilderConstructionPlanner.fenceWallClearance(
                level,close.getBoundingBox())){
            helper.fail("Builder accepted 10cm fence clearance as a work pose");
            return;
        }
        BuilderGolem farther=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        farther.setPos(footing.getX()+.70,footing.getY(),footing.getZ()+.5);
        level.addFreshEntity(farther);
        if(!BuilderConstructionPlanner.fenceWallClearance(
                level,farther.getBoundingBox())){
            helper.fail("Builder rejected safely offset fence work pose");return;
        }
        System.out.println("[FENCE WORK MARGIN] PASS collision-free 10cm gap"
                +" rejected, safer offset retained");
        helper.succeed();
    }

    /** Four obstructed cardinal shaft lines do not rule out a safe
     * inventory-paid scaffold reached from a diagonal roof approach.
     * The tower still needs real floor, an exit and a supported bend.
     */
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=1100,skyAccess=true)
    public void diagonalScaffoldEntryWorksAroundRoofWalls(
            GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        for(int x=4;x<=42;x++)for(int z=4;z<=42;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        BlockPos target=helper.absolutePos(new BlockPos(18,6,18));
        level.getChunkSource().addTicketWithRadius(
                net.minecraft.server.level.TicketType.FORCED,
                new net.minecraft.world.level.ChunkPos(
                        target.getX()>>4,target.getZ()>>4),4);
        for(net.minecraft.core.Direction direction:
                net.minecraft.core.Direction.Plane.HORIZONTAL)
            for(int distance=3;distance<=8;distance++)
                for(int y=2;y<=5;y++){
                    BlockPos p=target.relative(direction,distance)
                            .atY(helper.absolutePos(new BlockPos(0,y,0)).getY());
                    level.setBlock(p,Blocks.STONE.defaultBlockState(),2);
                }
        helper.setBlock(4,2,7,WorksiteContent.BUILDER_TABLE);
        BlockPos planner=helper.absolutePos(new BlockPos(4,2,7));
        if(!(level.getBlockEntity(planner)
                instanceof WorkerStationBlockEntity table)){
            helper.fail("No diagonal scaffold Planner");return;
        }
        BuilderGolem worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        BlockPos start=helper.absolutePos(new BlockPos(5,2,5));
        worker.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        worker.setItem(0,new net.minecraft.world.item.ItemStack(
                net.minecraft.world.item.Items.SCAFFOLDING,64));
        level.addFreshEntity(worker);
        var owner=helper.makeMockServerPlayerInLevel();
        if(!table.claimIfUnowned(owner.getUUID())
                ||!table.setCrewTarget(owner.getUUID(),1)
                ||!table.registerBuilderWorker(worker.getUUID())){
            helper.fail("Cannot register diagonal scaffold Builder");return;
        }
        var plan=java.util.List.of(new BuilderConstructionPlan.Step(
                target.getX(),target.getY(),target.getZ(),
                "minecraft:stone","minecraft:stone"));
        final boolean[] planned={false};
        final BuilderScaffoldPlanA.Result[] last={null};
        helper.onEachTick(()->{
            if(helper.getTick()<20||helper.getTick()%5!=0)return;
            if(worker.onGround()&&!planned[0])planned[0]=
                    BuilderScaffoldPlanA.canPlanWorkPose(
                            level,worker,table,target,plan);
            last[0]=BuilderScaffoldPlanA.reachWorkPose(
                    level,worker,table,owner,target,plan);
            if(planned[0]&&last[0]==BuilderScaffoldPlanA.Result.READY
                    &&BuilderScaffoldPlanA.safeTemporaryWorkPose(
                            level,worker,table,target)
                    &&worker.carriedCount(
                            net.minecraft.world.item.Items.SCAFFOLDING)<64
                    &&!table.scaffoldPositions(worker.getUUID()).isEmpty()){
                System.out.println("[DIAGONAL SCAFFOLD] PASS physically "
                        +"climbed and paid for the supported bent bridge "
                        +"in "+helper.getTick()+" ticks");
                helper.succeed();return;
            }
            if(helper.getTick()>=1020)
                helper.fail("Could plan but not physically finish diagonal "
                        +"paid scaffold around blocked cardinal lines: "
                        +"route="+last[0]+" feet="+worker.position()
                        +" plan="+BuilderScaffoldPlanA.status(worker)
                        +" climb="+BuilderScaffolding.debugClimb(worker)
                        +" paid="+table.scaffoldPositions(
                                worker.getUUID()).size()+" access="
                        +BuilderScaffoldPlanA.diagnoseAccess(
                                level,worker,table,target,plan));
        });
    }

    /** Full-dome main stalled at 3469/3493 with a floating cobblestone
     * at (18,31,17). A neighboring finished cell is not a floor. From
     * the worker's recorded lower-roof footing, a paid scaffold bridge
     * must be considered rather than cycling a theoretical walking pose.
     */
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=2700,skyAccess=true)
    public void floatingY31CobbleGetsPhysicalScaffoldWorkPose(
            GameTestHelper helper){
        lateRoofSupportAccess(helper,false);
    }

    /** The adjacent mossy cobblestone sits on a REAL birch fence at
     * (19,30,17). It needs no replacement support: approach from a
     * safe lateral stance with the full fence collision shape checked.
     */
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=2700,skyAccess=true)
    public void fenceSupportedY31CobbleKeepsBodyClear(
            GameTestHelper helper){
        lateRoofSupportAccess(helper,true);
    }

    private static void lateRoofSupportAccess(GameTestHelper helper,
            boolean fenceBelow){
        ServerLevel level=helper.getLevel();
        for(int x=4;x<=42;x++)for(int z=4;z<=42;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        BlockPos origin=helper.absolutePos(new BlockPos(8,2,8));
        level.getChunkSource().addTicketWithRadius(
                net.minecraft.server.level.TicketType.FORCED,
                new net.minecraft.world.level.ChunkPos(
                        origin.getX()>>4,origin.getZ()>>4),4);
        final java.util.List<BuilderConstructionPlan.Step> steps;
        try{
            var schematic=LitematicFileReader.read(BuilderDomeFixture.unpack());
            steps=BuilderConstructionPlan.create(schematic,
                    new BuildSite(origin.getX(),origin.getY(),origin.getZ(),
                            BuildSite.Rotation.NONE,BuildSite.Mirror.NONE),true)
                    .steps();
            for(var cell:schematic.blocks()){
                if(cell.y()>31||BuilderVegetation.ignoreSchematicState(cell.state())
                        ||cell.y()==31&&cell.z()==17
                            &&(cell.x()==18||cell.x()==19))continue;
                var state=BuilderBlockResolver.resolve(cell.state(),
                        BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
                if(state!=null)level.setBlock(origin.offset(
                        cell.x(),cell.y(),cell.z()),state.state(),2);
            }
        }catch(Exception e){helper.fail("Cannot reconstruct Y31 supports "+e);return;}
        BlockPos target=origin.offset(fenceBelow?19:18,31,17);
        if(fenceBelow?level.getBlockState(target.below()).getBlock()
                        !=Blocks.BIRCH_FENCE
                :!level.getBlockState(target.below()).isAir()){
            helper.fail("Wrong physical Y31 support for "+target
                    +": "+level.getBlockState(target.below()));return;
        }
        if(BuilderScaffoldPlanA.needsTemporaryWorkPlatform(level,target)
                ==fenceBelow){
            helper.fail("Scaffold hint must distinguish AIR from fence support");
            return;
        }
        helper.setBlock(4,2,7,WorksiteContent.BUILDER_TABLE);
        BlockPos station=helper.absolutePos(new BlockPos(4,2,7));
        if(!(level.getBlockEntity(station)
                instanceof WorkerStationBlockEntity table)){
            helper.fail("Missing Y31 roof work station");return;
        }
        BuilderGolem worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        worker.setPos(origin.getX()+(fenceBelow?19.498:19.944),
                origin.getY()+27,
                origin.getZ()+(fenceBelow?9.813:24.906));
        worker.setItem(0,new net.minecraft.world.item.ItemStack(
                net.minecraft.world.item.Items.SCAFFOLDING,64));
        level.addFreshEntity(worker);
        var owner=helper.makeMockServerPlayerInLevel();
        if(!table.claimIfUnowned(owner.getUUID())
                ||!table.setCrewTarget(owner.getUUID(),1)
                ||!table.registerBuilderWorker(worker.getUUID())){
            helper.fail("Cannot register Y31 Builder");return;
        }
        final BuilderScaffoldPlanA.Result[] attempt={null};
        helper.onEachTick(()->{
            if(!worker.isAlive()||worker.isNoGravity()){
                helper.fail("Y31 Builder left natural movement");return;
            }
            boolean direct=worker.onGround()
                    &&BuilderPlacementSpace.placementHeight(target,worker)
                    &&BuilderPlacementSpace.horizontalClear(target,worker)
                    &&worker.distanceToSqr(target.getX()+.5,
                            target.getY()+.5,target.getZ()+.5)<14
                    &&BuilderConstructionPlanner.safeStand(level,worker,
                            target,worker.blockPosition());
            boolean paid=BuilderScaffoldPlanA.safeTemporaryWorkPose(
                    level,worker,table,target);
            if(direct||paid){
                if(fenceBelow){
                    BlockPos support=target.below();
                    var shape=level.getBlockState(support)
                            .getCollisionShape(level,support);
                    for(var local:shape.toAabbs())
                        if(local.move(support.getX(),support.getY(),
                                support.getZ()).intersects(worker.getBoundingBox())){
                            helper.fail("Y31 worker too close to fence collision");
                            return;
                        }
                }
                System.out.println("[Y31 SUPPORT ACCESS] PASS "
                        +(fenceBelow?"fence supported":"floating")
                        +" using "+(paid?"paid scaffold":"real footing")
                        +" after "+helper.getTick()+" ticks");
                helper.succeed();return;
            }
            if((worker.onGround()||BuilderScaffoldPlanA.hasPlan(worker))
                    &&helper.getTick()>12&&helper.getTick()%5==0){
                if(!BuilderScaffoldPlanA.hasPlan(worker)
                        &&(fenceBelow||helper.getTick()<120)){
                    BlockPos walk=BuilderPlacementSpace.approachWithRules(
                            level,worker,target,
                            at->BuilderConstructionPlanner.safeStand(
                                    level,worker,target,at));
                    if(walk!=null&&WorkerNavigation.routeToExact(
                            level,worker,walk,1.0))return;
                }
                attempt[0]=BuilderScaffoldPlanA.reachWorkPose(
                        level,worker,table,owner,target,steps);
            }
            if(helper.getTick()>2550)
                helper.fail("Y31 "+(fenceBelow?"fence":"floating")
                        +" work pose stalled from real roof feet="+worker.position()
                        +" attempt="+attempt[0]
                        +" plan="+BuilderScaffoldPlanA.status(worker)
                        +" access="+BuilderScaffoldPlanA.diagnoseAccess(
                                level,worker,table,target,steps));
        });
    }

    /** The measured 3420/3493 dome has no legal grounded work stance
     * around its eight Y30 holes. Rebuild the exact roof snapshot and
     * verify a physically climbable, inventory-paid temporary work pose,
     * never a fake walking route or creative block placement.
     */
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=1800,skyAccess=true)
    public void northRoofScaffoldReachesMeasuredFinalGap(GameTestHelper helper){
        measuredRoofScaffoldGap(helper,"north",15,30,10,
                8.34569378,26,11.51642730);
    }
    /** The southern Y30 holes are part of the SAME saved 3420-block
     * snapshot. Start from Morticia's measured position and use real
     * physics, not a fabricated stand at roof level.
     */
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=1800,skyAccess=true)
    public void southRoofScaffoldReachesMeasuredFinalGap(GameTestHelper helper){
        measuredRoofScaffoldGap(helper,"south",15,30,22,
                7.69798012,26.16610926,24.80114409);
    }
    /** At 3470/3493 the roof's Y31 center cap is the sole remaining
     * cell on that layer. Reconstruct its exact complete lower roof and
     * start Mason Max at his measured Y29 stance. The Builder must find
     * a physically reachable pose, never a fictitious three-tile Y-2
     * work reach that fails the final real distance check.
     */
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=1900,skyAccess=true)
    public void centralRoofCapUsesReachablePoseOrPaidScaffold(
            GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        for(int x=4;x<=42;x++)for(int z=4;z<=42;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        BlockPos origin=helper.absolutePos(new BlockPos(8,2,8));
        level.getChunkSource().addTicketWithRadius(
                net.minecraft.server.level.TicketType.FORCED,
                new net.minecraft.world.level.ChunkPos(
                        origin.getX()>>4,origin.getZ()>>4),4);
        final java.util.List<BuilderConstructionPlan.Step> steps;
        try{
            var schematic=LitematicFileReader.read(BuilderDomeFixture.unpack());
            steps=BuilderConstructionPlan.create(schematic,
                    new BuildSite(origin.getX(),origin.getY(),origin.getZ(),
                            BuildSite.Rotation.NONE,BuildSite.Mirror.NONE),true)
                    .steps();
            for(var b:schematic.blocks()){
                if(b.y()>31||BuilderVegetation.ignoreSchematicState(b.state())
                        ||b.x()==15&&b.y()==31&&b.z()==16)continue;
                var state=BuilderBlockResolver.resolve(b.state(),
                        BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
                if(state!=null)level.setBlock(origin.offset(
                        b.x(),b.y(),b.z()),state.state(),2);
            }
        }catch(Exception e){helper.fail("Cannot reconstruct center cap "+e);return;}
        helper.setBlock(4,2,7,WorksiteContent.BUILDER_TABLE);
        BlockPos station=helper.absolutePos(new BlockPos(4,2,7));
        if(!(level.getBlockEntity(station)
                instanceof WorkerStationBlockEntity table)){
            helper.fail("Missing cap work station");return;
        }
        BlockPos target=origin.offset(15,31,16);
        BuilderGolem worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        worker.setPos(origin.getX()+17.406,origin.getY()+29,
                origin.getZ()+14.036);
        worker.setItem(0,new net.minecraft.world.item.ItemStack(
                net.minecraft.world.item.Items.SCAFFOLDING,64));
        level.addFreshEntity(worker);
        var owner=helper.makeMockServerPlayerInLevel();
        if(!table.claimIfUnowned(owner.getUUID())
                ||!table.setCrewTarget(owner.getUUID(),1)
                ||!table.registerBuilderWorker(worker.getUUID())){
            helper.fail("Cannot register center-cap Builder");return;
        }
        final BuilderScaffoldPlanA.Result[] result={null};
        helper.onEachTick(()->{
            if(!worker.isAlive()||worker.isNoGravity()){
                helper.fail("Center-cap Builder left physical movement");return;
            }
            boolean grounded=worker.onGround();
            boolean direct=grounded
                    &&BuilderPlacementSpace.placementHeight(target,worker)
                    &&BuilderPlacementSpace.horizontalClear(target,worker)
                    &&worker.distanceToSqr(target.getX()+.5,
                            target.getY()+.5,target.getZ()+.5)<14
                    &&BuilderConstructionPlanner.safeStand(
                            level,worker,target,worker.blockPosition());
            boolean scaffold=BuilderScaffoldPlanA.safeTemporaryWorkPose(
                    level,worker,table,target);
            if(direct||scaffold){
                System.out.println("[CAP FINAL REACH] PASS "
                        +(direct?"ordinary grounded reach":"paid scaffold")
                        +" at Y31 after "+helper.getTick()+" ticks"
                        +" feet="+worker.position());
                helper.succeed();return;
            }
            if((grounded||BuilderScaffoldPlanA.hasPlan(worker))
                    &&helper.getTick()>=20&&helper.getTick()%5==0){
                if(!BuilderScaffoldPlanA.hasPlan(worker)){
                    BlockPos walk=BuilderPlacementSpace.approachWithRules(
                            level,worker,target,
                            at->BuilderConstructionPlanner.safeStand(
                                    level,worker,target,at));
                    if(walk!=null&&WorkerNavigation.routeToExact(
                            level,worker,walk,1.0))return;
                }
                result[0]=BuilderScaffoldPlanA.reachWorkPose(
                        level,worker,table,owner,target,steps);
            }
            if(helper.getTick()>1770)
                helper.fail("Y31 center cap stalled feet="+worker.position()
                        +" grounded="+grounded
                        +" bodyInsideRoof="+!level.noCollision(
                                worker,worker.getBoundingBox())
                        +" result="+result[0]
                        +" plan="+BuilderScaffoldPlanA.status(worker)
                        +" nav="+WorkerNavigation.debugStatus(worker)
                        +" approach="+BuilderPlacementSpace.diagnoseApproach(
                                level,worker,target,
                                at->BuilderConstructionPlanner.safeStand(
                                        level,worker,target,at)));
        });
    }
    /** The 3475-block run stopped on four narrow Y33 wall/fence caps.
     * Start from an ACTUAL grounded foothold on the already completed
     * Y32 roof, several blocks away. The worker must physically step
     * back, walk around the roof or climb its own paid scaffold before
     * being considered within placement reach of the first fence cap.
     */
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=2400,skyAccess=true)
    public void finalFenceCapReachesFromGroundedRoofLaunch(GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        for(int x=4;x<=42;x++)for(int z=4;z<=42;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        BlockPos origin=helper.absolutePos(new BlockPos(8,2,8));
        level.getChunkSource().addTicketWithRadius(
                net.minecraft.server.level.TicketType.FORCED,
                new net.minecraft.world.level.ChunkPos(
                        origin.getX()>>4,origin.getZ()>>4),4);
        final java.util.List<BuilderConstructionPlan.Step> steps;
        try{
            var schematic=LitematicFileReader.read(BuilderDomeFixture.unpack());
            steps=BuilderConstructionPlan.create(schematic,
                    new BuildSite(origin.getX(),origin.getY(),origin.getZ(),
                            BuildSite.Rotation.NONE,BuildSite.Mirror.NONE),true)
                    .steps();
            for(var b:schematic.blocks()){
                if(b.y()>32||BuilderVegetation.ignoreSchematicState(b.state()))
                    continue;
                var state=BuilderBlockResolver.resolve(b.state(),
                        BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
                if(state!=null)level.setBlock(origin.offset(
                        b.x(),b.y(),b.z()),state.state(),2);
            }
        }catch(Exception e){helper.fail("Cannot reconstruct last roof "+e);return;}
        helper.setBlock(4,2,7,WorksiteContent.BUILDER_TABLE);
        BlockPos station=helper.absolutePos(new BlockPos(4,2,7));
        if(!(level.getBlockEntity(station)
                instanceof WorkerStationBlockEntity table)){
            helper.fail("No high-roof scaffold Planner");return;
        }
        BlockPos target=origin.offset(14,33,16);
        BuilderGolem worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        BlockPos launch=null;
        double best=Double.POSITIVE_INFINITY;
        for(int dx=-8;dx<=8;dx++)for(int dz=-8;dz<=8;dz++)
            for(int y=30;y<=32;y++){
                BlockPos p=target.offset(dx,y-33,dz);
                int horizontal=dx*dx+dz*dz;
                if(horizontal<16||horizontal>64
                        ||!WorkerNavigation.canStandOn(level,worker,p))continue;
                double floor=WorkerNavigation.surveyFloorHeight(level,p);
                if(!Double.isFinite(floor)||Math.abs(floor-p.getY())>.15)
                    continue;
                double score=Math.abs(horizontal-25)*.05
                        +Math.abs(y-31)*2.0;
                if(score<best){best=score;launch=p;}
            }
        if(launch==null){
            helper.fail("No real Y30-32 standing tile near last roof cap");return;
        }
        worker.setPos(launch.getX()+.5,launch.getY(),launch.getZ()+.5);
        worker.setItem(0,new net.minecraft.world.item.ItemStack(
                net.minecraft.world.item.Items.SCAFFOLDING,32));
        level.addFreshEntity(worker);
        var owner=helper.makeMockServerPlayerInLevel();
        if(!table.claimIfUnowned(owner.getUUID())
                ||!table.setCrewTarget(owner.getUUID(),1)
                ||!table.registerBuilderWorker(worker.getUUID())){
            helper.fail("Cannot register Y33 cap Builder");return;
        }
        final BlockPos originalLaunch=launch;
        final BuilderScaffoldPlanA.Result[] result={null};
        helper.onEachTick(()->{
            if(!worker.isAlive()||worker.isNoGravity()){
                helper.fail("Y33 cap worker lost natural physics");return;
            }
            boolean direct=worker.onGround()
                    &&BuilderPlacementSpace.placementHeight(target,worker)
                    &&BuilderPlacementSpace.horizontalClear(target,worker)
                    &&worker.distanceToSqr(target.getX()+.5,
                            target.getY()+.5,target.getZ()+.5)<14
                    &&BuilderConstructionPlanner.safeStand(
                            level,worker,target,worker.blockPosition());
            boolean paid=BuilderScaffoldPlanA.safeTemporaryWorkPose(
                    level,worker,table,target);
            if(direct||paid){
                System.out.println("[Y33 FENCE CAP] PASS "
                        +(paid?"paid scaffold":"ordinary safe footing")
                        +" launch="+originalLaunch.subtract(origin)
                        +" final="+worker.blockPosition().subtract(origin)
                        +" after "+helper.getTick()+" ticks");
                helper.succeed();return;
            }
            if((worker.onGround()||BuilderScaffoldPlanA.hasPlan(worker))
                    &&helper.getTick()>12
                    &&helper.getTick()%5==0){
                if(!BuilderScaffoldPlanA.hasPlan(worker)){
                    BlockPos walk=BuilderPlacementSpace.approachWithRules(
                            level,worker,target,
                            at->BuilderConstructionPlanner.safeStand(
                                    level,worker,target,at));
                    if(walk!=null&&WorkerNavigation.routeToExact(
                            level,worker,walk,1.0))return;
                }
                result[0]=BuilderScaffoldPlanA.reachWorkPose(
                        level,worker,table,owner,target,steps);
            }
            if(helper.getTick()>2260)
                helper.fail("Real Y33 cap approach stalled launch="
                        +originalLaunch.subtract(origin)
                        +" final="+worker.position()
                        +" paid="+table.scaffoldPositions(worker.getUUID()).size()
                        +" result="+result[0]
                        +" plan="+BuilderScaffoldPlanA.status(worker)
                        +" nav="+WorkerNavigation.debugStatus(worker));
        });
    }
    /** Reproduce the repeatedly measured Y21 north-rim deadlock from
     * two full-dome runs. All lower layers are exact and the fourteen
     * remaining Y21 positions match the CI snapshot. Start on the real
     * offset glass ledge at (12.424,21,4.605), not a fabricated tile
     * center, and require an executable safe placement pose.
     */
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=2200,skyAccess=true)
    public void northRimFenceRetreatsToWorkPose(GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        for(int x=4;x<=42;x++)for(int z=4;z<=42;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        BlockPos origin=helper.absolutePos(new BlockPos(8,2,8));
        level.getChunkSource().addTicketWithRadius(
                net.minecraft.server.level.TicketType.FORCED,
                new net.minecraft.world.level.ChunkPos(
                        origin.getX()>>4,origin.getZ()>>4),4);
        int[][] missing={{14,3},{12,5},{13,5},{14,5},{11,6},
                {9,7},{5,10},{4,12},{4,13},{3,15},{1,16},
                {11,28},{12,29},{13,29}};
        final java.util.List<BuilderConstructionPlan.Step> steps;
        try{
            var schematic=LitematicFileReader.read(BuilderDomeFixture.unpack());
            steps=BuilderConstructionPlan.create(schematic,
                    new BuildSite(origin.getX(),origin.getY(),origin.getZ(),
                            BuildSite.Rotation.NONE,BuildSite.Mirror.NONE),true)
                    .steps();
            for(var block:schematic.blocks()){
                if(block.y()>21||BuilderVegetation.ignoreSchematicState(
                        block.state()))continue;
                boolean remain=false;
                if(block.y()==21)
                    for(int[] gap:missing)
                        if(block.x()==gap[0]&&block.z()==gap[1]){
                            remain=true;break;
                        }
                if(remain)continue;
                var state=BuilderBlockResolver.resolve(block.state(),
                        BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
                if(state!=null)level.setBlock(origin.offset(
                        block.x(),block.y(),block.z()),state.state(),2);
            }
        }catch(Exception e){helper.fail("Cannot reconstruct Y21 ledge "+e);return;}
        helper.setBlock(4,2,7,WorksiteContent.BUILDER_TABLE);
        BlockPos station=helper.absolutePos(new BlockPos(4,2,7));
        if(!(level.getBlockEntity(station)
                instanceof WorkerStationBlockEntity table)){
            helper.fail("No Y21 north rim Planner");return;
        }
        BlockPos target=origin.offset(14,21,3);
        BuilderGolem worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        worker.setPos(origin.getX()+12.424,origin.getY()+21,
                origin.getZ()+4.605);
        worker.setItem(0,new net.minecraft.world.item.ItemStack(
                net.minecraft.world.item.Items.SCAFFOLDING,64));
        level.addFreshEntity(worker);
        var owner=helper.makeMockServerPlayerInLevel();
        if(!table.claimIfUnowned(owner.getUUID())
                ||!table.setCrewTarget(owner.getUUID(),1)
                ||!table.registerBuilderWorker(worker.getUUID())){
            helper.fail("Cannot register Y21 rim worker");return;
        }
        final BuilderScaffoldPlanA.Result[] result={null};
        helper.onEachTick(()->{
            if(!worker.isAlive()||worker.isNoGravity()){
                helper.fail("Y21 rim worker lost physical motion");return;
            }
            boolean direct=worker.onGround()
                    &&BuilderPlacementSpace.placementHeight(target,worker)
                    &&BuilderPlacementSpace.horizontalClear(target,worker)
                    &&worker.distanceToSqr(target.getX()+.5,
                            target.getY()+.5,target.getZ()+.5)<14
                    &&BuilderConstructionPlanner.safeStand(
                            level,worker,target,worker.blockPosition());
            if(direct||BuilderScaffoldPlanA.safeTemporaryWorkPose(
                    level,worker,table,target)){
                System.out.println("[Y21 RIM FENCE] PASS from offset footing"
                        +" now="+worker.position()
                        +" ticks="+helper.getTick());
                helper.succeed();return;
            }
            if((worker.onGround()||BuilderScaffoldPlanA.hasPlan(worker))
                    &&helper.getTick()>12&&helper.getTick()%5==0){
                if(!BuilderScaffoldPlanA.hasPlan(worker)){
                    BlockPos walk=BuilderPlacementSpace.approachWithRules(
                            level,worker,target,
                            at->BuilderConstructionPlanner.safeStand(
                                    level,worker,target,at));
                    if(walk!=null&&WorkerNavigation.routeToExact(
                            level,worker,walk,1.0))return;
                }
                result[0]=BuilderScaffoldPlanA.reachWorkPose(
                        level,worker,table,owner,target,steps);
            }
            if(helper.getTick()>2070)
                helper.fail("Y21 offset fence could not step back/scaffold"
                        +" feet="+worker.position()+" result="+result[0]
                        +" plan="+BuilderScaffoldPlanA.status(worker)
                        +" nav="+WorkerNavigation.debugStatus(worker)
                        +" approach="+BuilderPlacementSpace.diagnoseApproach(
                                level,worker,target,
                                at->BuilderConstructionPlanner.safeStand(
                                        level,worker,target,at)));
        });
    }
    /** 3490/3493 in the full run left this one Y35 sea lantern and
     * two consecutive birch fences directly above it.
     * Sawyer was physically grounded at (16.497,35.500,17.233), on a
     * narrow cap with no safe INTEGER-centered stance anywhere nearby.
     * Prove the existing per-tick 11.5cm step can retreat just beyond
     * the .8m placement margin while retaining true wall-top footing,
     * including for an overhead target on the NEXT schematic layer.
     */
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=700,skyAccess=true)
    public void finalSeaLanternUsesSupportedFractionalStepBack(
            GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        for(int x=4;x<=42;x++)for(int z=4;z<=42;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        BlockPos origin=helper.absolutePos(new BlockPos(8,2,8));
        level.getChunkSource().addTicketWithRadius(
                net.minecraft.server.level.TicketType.FORCED,
                new net.minecraft.world.level.ChunkPos(
                        origin.getX()>>4,origin.getZ()>>4),4);
        try{
            var schematic=LitematicFileReader.read(BuilderDomeFixture.unpack());
            for(var block:schematic.blocks()){
                if(block.y()>35||BuilderVegetation.ignoreSchematicState(
                        block.state())
                        ||block.x()==15&&block.y()==35&&block.z()==17)
                    continue;
                var state=BuilderBlockResolver.resolve(block.state(),
                        BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
                if(state!=null)level.setBlock(origin.offset(
                        block.x(),block.y(),block.z()),state.state(),2);
            }
        }catch(Exception e){helper.fail("Cannot reconstruct last lantern "+e);return;}
        helper.setBlock(4,2,7,WorksiteContent.BUILDER_TABLE);
        BlockPos station=helper.absolutePos(new BlockPos(4,2,7));
        if(!(level.getBlockEntity(station)
                instanceof WorkerStationBlockEntity table)){
            helper.fail("No Y35 cap Planner");return;
        }
        BlockPos target=origin.offset(15,35,17);
        BlockPos upperFence=target.above();
        BlockPos topFence=upperFence.above();
        int[] stage={0};
        BuilderGolem worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        worker.setPos(origin.getX()+16.497,origin.getY()+35.5,
                origin.getZ()+17.233);
        worker.setItem(0,new net.minecraft.world.item.ItemStack(
                net.minecraft.world.item.Items.SEA_LANTERN,1));
        level.addFreshEntity(worker);
        var owner=helper.makeMockServerPlayerInLevel();
        if(!table.claimIfUnowned(owner.getUUID())
                ||!table.setCrewTarget(owner.getUUID(),1)
                ||!table.registerBuilderWorker(worker.getUUID())){
            helper.fail("Cannot register final cap worker");return;
        }
        helper.onEachTick(()->{
            if(!worker.isAlive()||worker.isNoGravity()){
                helper.fail("Final cap worker left natural physics");return;
            }
            BlockPos activeTarget=stage[0]==0?target
                    :stage[0]==1?upperFence:topFence;
            if(BuilderPlacementSpace.safeFractionalCapPose(
                    level,worker,activeTarget)){
                if(stage[0]==0){
                    // Change only the reconstructed fixture: prove that
                    // after the real lantern occupies its place, the
                    // grounded cap stance can still reach the Y36 fence.
                    level.setBlock(target,Blocks.SEA_LANTERN.defaultBlockState(),3);
                    stage[0]=1;
                    return;
                }
                if(stage[0]==1){
                    level.setBlock(upperFence,
                            Blocks.BIRCH_FENCE.defaultBlockState(),3);
                    stage[0]=2;
                    return;
                }
                System.out.println("[Y35/Y37 CAP STEP BACK] PASS physical feet="
                        +worker.position()+" after "+helper.getTick()
                        +" ticks; lantern and lower fence placed,"
                        +" final upper fence reachable");
                helper.succeed();return;
            }
            if(helper.getTick()>=12&&helper.getTick()%5==0)
                BuilderPlacementSpace.stepBackFromFractionalCap(
                        level,worker,table,activeTarget);
            if(helper.getTick()>620)
                helper.fail("Y35/Y37 wall-top sidestep stalled stage="
                        +stage[0]+" feet="
                        +worker.position()+" grounded="+worker.onGround()
                        +" corners="+WorkerNavigation.supportedHitboxCorners(
                                level,worker)
                        +" clear="+BuilderPlacementSpace.horizontalClear(
                                activeTarget,worker)
                        +" retreat="+BuilderPlacementSpace
                                .canRetreatToFractionalCap(level,worker,activeTarget));
        });
    }
    private static void measuredRoofScaffoldGap(GameTestHelper helper,
            String region,int targetX,int targetY,int targetZ,
            double startX,double startY,double startZ){
        ServerLevel level=helper.getLevel();
        for(int x=4;x<=42;x++)for(int z=4;z<=42;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        BlockPos origin=helper.absolutePos(new BlockPos(8,2,8));
        level.getChunkSource().addTicketWithRadius(
                net.minecraft.server.level.TicketType.FORCED,
                new net.minecraft.world.level.ChunkPos(
                        origin.getX()>>4,origin.getZ()>>4),4);
        final java.util.List<BuilderConstructionPlan.Step> steps;
        try{
            var schematic=LitematicFileReader.read(BuilderDomeFixture.unpack());
            steps=BuilderConstructionPlan.create(schematic,
                    new BuildSite(origin.getX(),origin.getY(),origin.getZ(),
                            BuildSite.Rotation.NONE,BuildSite.Mirror.NONE),true)
                    .steps();
            for(var b:schematic.blocks()){
                if(b.y()>30||BuilderVegetation.ignoreSchematicState(b.state()))
                    continue;
                boolean north=b.x()==15&&b.z()>=9&&b.z()<=12;
                boolean south=b.x()==15&&b.z()>=22&&b.z()<=24
                        ||b.x()==20&&b.z()==22;
                if(b.y()==30&&(north||south))continue;
                var state=BuilderBlockResolver.resolve(
                        b.state(),BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
                if(state!=null)level.setBlock(origin.offset(
                        b.x(),b.y(),b.z()),state.state(),2);
            }
        }catch(Exception e){helper.fail("Could not reconstruct saved roof "+e);return;}
        helper.setBlock(4,2,7,WorksiteContent.BUILDER_TABLE);
        BlockPos station=helper.absolutePos(new BlockPos(4,2,7));
        if(!(level.getBlockEntity(station) instanceof WorkerStationBlockEntity table)){
            helper.fail("Missing work station for original roof");return;
        }
        BlockPos target=origin.offset(targetX,targetY,targetZ);
        if(!level.getBlockState(target).isAir()){
            helper.fail("Live roof target must remain unbuilt");return;
        }
        BuilderGolem worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        worker.setPos(origin.getX()+startX,origin.getY()+startY,
                origin.getZ()+startZ);
        worker.setItem(0,new net.minecraft.world.item.ItemStack(
                net.minecraft.world.item.Items.SCAFFOLDING,64));
        level.addFreshEntity(worker);
        var owner=helper.makeMockServerPlayerInLevel();
        if(!table.claimIfUnowned(owner.getUUID())
                ||!table.setCrewTarget(owner.getUUID(),1)
                ||!table.registerBuilderWorker(worker.getUUID())){
            helper.fail("Cannot register paid roof scaffold worker");return;
        }
        final BuilderScaffoldPlanA.Result[] last={null};
        helper.onEachTick(()->{
            if(!worker.isAlive()||worker.isNoGravity()){
                helper.fail("Original north roof worker lost physical motion");return;
            }
            if(helper.getTick()>=20&&helper.getTick()%5==0)
                last[0]=BuilderScaffoldPlanA.reachWorkPose(
                        level,worker,table,owner,target,steps);
            if(helper.getTick()%150==0)
                System.out.println("[ROOF SIDE CLIMB] region="+region
                        +" tick="+helper.getTick()
                        +" y="+(worker.getY()-origin.getY())
                        +" grounded="+worker.onGround()
                        +" vy="+worker.getDeltaMovement().y
                        +" session="+BuilderScaffolding.debugClimb(worker)
                        +" paid="+table.scaffoldPositions(worker.getUUID()).size()
                        +" plan="+BuilderScaffoldPlanA.status(worker));
            if(last[0]==BuilderScaffoldPlanA.Result.READY
                    &&BuilderScaffoldPlanA.safeTemporaryWorkPose(
                            level,worker,table,target)
                    &&BuilderPlacementSpace.horizontalClear(target,worker)
                    &&worker.distanceToSqr(target.getX()+.5,
                            target.getY()+.5,target.getZ()+.5)<14
                    &&!table.scaffoldPositions(worker.getUUID()).isEmpty()
                    &&worker.carriedCount(net.minecraft.world.item.Items.SCAFFOLDING)<64){
                System.out.println("[ROOF FINAL GAP] PASS inventory-paid physical "
                        +"scaffold at measured "+region+" Y30 gap in "
                        +helper.getTick()+" ticks");
                helper.succeed();return;
            }
            if(helper.getTick()>1670)
                helper.fail(region+" final gap scaffold stalled: feet="
                        +worker.position()+" result="+last[0]
                        +" bodyInsideRoof="+!level.noCollision(worker,
                                worker.getBoundingBox())
                        +" status="+BuilderScaffoldPlanA.status(worker)
                        +" climb="+BuilderScaffolding.debugClimb(worker)
                        +" paid="+table.scaffoldPositions(worker.getUUID()).size()
                        +" nav="+WorkerNavigation.debugStatus(worker)
                        +" access="+BuilderScaffoldPlanA.diagnoseAccess(
                                level,worker,table,target,steps));
        });
    }
    /** Reproduces the dome's four-level work handoff: the block is high,
     * but a winding real-ground climb ends at a valid placement stance.
     * Do not require a hook or scaffold for this reachable roof tile.
     */
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=560,skyAccess=true)
    public void builderWalksFourLevelRiseToWorkPoseWithoutTools(
            GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        // A ten-block slope does not fit the default eight-block GameTest
        // room: its invisible boundary would falsely reject real routes.
        for(int x=0;x<12;x++)for(int z=0;z<8;z++)
            for(int y=2;y<=8;y++)helper.setBlock(x,y,z,Blocks.AIR);
        for(int x=0;x<12;x++)for(int z=0;z<8;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        // Real half-height stair steps avoid a contrived sequence of
        // four adjacent full-block jumps. This is the same supported
        // half-slab terrain as the upper Dome's rising roof edges.
        helper.setBlock(2,2,3,Blocks.STONE_SLAB);
        helper.setBlock(3,2,3,Blocks.STONE);
        helper.setBlock(4,3,3,Blocks.STONE_SLAB);
        helper.setBlock(5,3,3,Blocks.STONE);
        helper.setBlock(6,4,3,Blocks.STONE_SLAB);
        helper.setBlock(7,4,3,Blocks.STONE);
        helper.setBlock(8,5,3,Blocks.STONE_SLAB);
        for(int x=9;x<=11;x++)
            helper.setBlock(x,5,3,Blocks.STONE);
        helper.setBlock(0,2,0,WorksiteContent.BUILDER_TABLE);
        BlockPos station=helper.absolutePos(new BlockPos(0,2,0));
        if(!(level.getBlockEntity(station) instanceof WorkerStationBlockEntity table)){
            helper.fail("Missing real Builder station");return;
        }
        BlockPos target=helper.absolutePos(new BlockPos(11,6,3));
        BlockPos start=helper.absolutePos(new BlockPos(1,2,3));
        BuilderGolem worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        worker.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        level.addFreshEntity(worker);
        boolean[] traveled={false};
        helper.onEachTick(()->{
            if(!worker.isAlive()||worker.isNoGravity()){
                helper.fail("Builder left physical ground movement");return;
            }
            if(helper.getTick()>=15&&helper.getTick()%5==0&&worker.onGround()){
                BlockPos approach=BuilderPlacementSpace.approachWithRules(
                        level,worker,target,
                        at->BuilderConstructionPlanner.safeStand(
                                level,worker,target,at));
                if(approach!=null)
                    traveled[0]|=WorkerNavigation.routeToExact(
                            level,worker,approach,1.0);
            }
            if(worker.onGround()&&worker.getY()>=target.getY()-.05
                    &&worker.distanceToSqr(target.getX()+.5,
                            target.getY()+.5,target.getZ()+.5)<14
                    &&BuilderPlacementSpace.horizontalClear(target,worker)
                    &&BuilderConstructionPlanner.safeStand(
                            level,worker,target,worker.blockPosition())){
                if(!traveled[0]){
                    helper.fail("High roof stance reached without trained approach");return;
                }
                System.out.println("[WORK ACCESS] PASS walk-first four-level real roof in "
                        +helper.getTick()+" ticks, no hook or scaffold");
                helper.succeed();return;
            }
            if(helper.getTick()>470)
                helper.fail("Four-level roof walk stalled feet="+worker.position()
                        +" selected="+BuilderPlacementSpace.approachWithRules(
                                level,worker,target,
                                p->BuilderConstructionPlanner.safeStand(
                                        level,worker,target,p))
                        +" candidate="+helper.absolutePos(new BlockPos(9,6,3))
                        +" route="+WorkerNavigation.debugStatus(worker)
                        +" stance="+BuilderPlacementSpace.diagnoseApproach(
                                level,worker,target,
                                p->BuilderConstructionPlanner.safeStand(
                                        level,worker,target,p))
                        +" trained="+WorkerNavigation.diagnoseExecutableStand(
                                level,worker,java.util.List.of(
                                        helper.absolutePos(new BlockPos(9,6,3)))));
        });
    }
    @GameTest(maxTicks=450,skyAccess=true)
    public void builderCentersWithinItsCurrentWorkTile(GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        BlockPos target=helper.absolutePos(new BlockPos(3,2,3));
        BuilderGolem worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        worker.setPos(target.getX()+.06,target.getY(),target.getZ()+.09);
        level.addFreshEntity(worker);
        helper.onEachTick(()->{
            if(helper.getTick()%5==0)
                WorkerNavigation.routeToExact(level,worker,target,1.0);
            double dx=worker.getX()-target.getX()-.5;
            double dz=worker.getZ()-target.getZ()-.5;
            if(dx*dx+dz*dz<.12*.12&&worker.onGround()){
                System.out.println("[BUILDER ACCESS] centered real worker ticks="+helper.getTick());
                helper.succeed();return;
            }
            if(helper.getTick()>160)
                helper.fail("In-cell real movement didn't reach center: pos="+
                        worker.position()+" "+WorkerNavigation.debugStatus(worker));
        });
    }
    @GameTest(maxTicks=550,skyAccess=true)
    public void builderSelectsActualUpperRimStandingFeet(GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        // Solid raised walkway, and a future target just beyond its edge.
        // The walkable FEET level is y=4, never y=3 inside the stone.
        for(int x=1;x<=4;x++)for(int z=2;z<=4;z++)
            helper.setBlock(x,3,z,Blocks.STONE);
        BlockPos target=helper.absolutePos(new BlockPos(5,4,3));
        BlockPos spawn=helper.absolutePos(new BlockPos(1,4,3));
        BuilderGolem worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        worker.setPos(spawn.getX()+.5,spawn.getY(),spawn.getZ()+.5);
        level.addFreshEntity(worker);
        final BlockPos[] chosen={null};
        helper.onEachTick(()->{
            if(helper.getTick()%5==0){
                BlockPos selected=BuilderPlacementSpace.approach(level,worker,target);
                if(selected!=null){
                    if(selected.distSqr(target)>=14
                            ||!WorkerNavigation.canStandOn(level,worker,selected)){
                        helper.fail("Unsafe elevated work stance "+selected);
                        return;
                    }
                    chosen[0]=selected;
                    WorkerNavigation.routeToExact(level,worker,selected,1.0);
                }
            }
            if(chosen[0]!=null&&worker.onGround()
                    &&worker.distanceToSqr(target.getX()+.5,
                            target.getY()+.5,target.getZ()+.5)<=10){
                System.out.println("[BUILDER ACCESS] upper-rim stance real worker ticks="+
                        helper.getTick()+" target="+target+" approach="+chosen[0]);
                helper.succeed();return;
            }
            if(helper.getTick()>250)
                helper.fail("Builder never reached upper-rim work position: target="+
                        target+" chosen="+chosen[0]+" pos="+worker.position()
                        +" nav="+WorkerNavigation.debugStatus(worker));
        });
    }
}
