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
    /** The measured 3420/3493 dome has no legal grounded work stance
     * around its eight Y30 holes. Rebuild the exact roof snapshot and
     * verify a physically climbable, inventory-paid temporary work pose,
     * never a fake walking route or creative block placement.
     */
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=1800,skyAccess=true)
    public void northRoofScaffoldReachesMeasuredFinalGap(GameTestHelper helper){
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
        BlockPos target=origin.offset(15,30,10);
        if(!level.getBlockState(target).isAir()
                ||!level.getBlockState(origin.offset(15,30,9)).isAir()){
            helper.fail("Live roof target must remain unbuilt");return;
        }
        BuilderGolem worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        worker.setPos(origin.getX()+8.34569378,origin.getY()+26,
                origin.getZ()+11.51642730);
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
            if(helper.getTick()>=40&&helper.getTick()<=210
                    &&helper.getTick()%10==0){
                var bbox=worker.getBoundingBox();
                BlockPos feet=worker.blockPosition();
                System.out.println("[ROOF JUMP CONTACT] tick="+helper.getTick()
                        +" xyz="+worker.position()
                        +" vel="+worker.getDeltaMovement()
                        +" ground="+worker.onGround()
                        +" roof="+level.getBlockState(feet.above())
                        +" head="+level.getBlockState(feet.above(2))
                        +" riseOpen="+level.noCollision(worker,bbox.move(0,.42,0))
                        +" leftRiseOpen="+level.noCollision(worker,
                                bbox.move(-.12,.42,0))
                        +" session="+BuilderScaffolding.debugClimb(worker));
            }
            if(helper.getTick()%150==0)
                System.out.println("[ROOF SIDE CLIMB] tick="+helper.getTick()
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
                        +"scaffold at measured north Y30 gap in "
                        +helper.getTick()+" ticks");
                helper.succeed();return;
            }
            if(helper.getTick()>1670)
                helper.fail("North final gap scaffold stalled: feet="
                        +worker.position()+" result="+last[0]
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
