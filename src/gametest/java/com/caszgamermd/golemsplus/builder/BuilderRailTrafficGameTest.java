package com.caszgamermd.golemsplus.builder;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/** A rail is walk-through geometry, not a newly placed full stone cube.
 * Golems are allowed to work within its empty collision volume; solid
 * blocks and active fluids still need full body clearance. */
public final class BuilderRailTrafficGameTest {
    @GameTest(maxTicks=70,skyAccess=true)
    public void narrowLedgeAllowsNonCollidingRailWithoutFictionalExit(
            GameTestHelper h){
        var level=h.getLevel();
        BlockPos target=h.absolutePos(new BlockPos(4,2,4));
        BlockPos stance=h.absolutePos(new BlockPos(5,2,4));
        h.setBlock(4,1,4,Blocks.STONE);
        h.setBlock(5,1,4,Blocks.STONE);
        var worker=new BuilderGolem(
                com.caszgamermd.golemsplus.world.WorksiteContent.BUILDER_GOLEM,
                level);
        worker.setPos(stance.getX()+.5,stance.getY(),stance.getZ()+.5);
        level.addFreshEntity(worker);
        if(!BuilderConstructionPlanner.safeStandForStep(
                level,worker,target,stance,
                "minecraft:powered_rail[powered=false,shape=east_west,waterlogged=false]")){
            h.fail("Walk-through rail cannot be worked from physical narrow ledge");
            return;
        }
        boolean ordinary=BuilderConstructionPlanner.safeStand(
                level,worker,target,stance);
        boolean solid=BuilderConstructionPlanner.safeStandForStep(
                level,worker,target,stance,"minecraft:smooth_stone");
        if(solid!=ordinary){
            h.fail("Solid cube used a different escape rule from ordinary "
                    +"construction work");return;
        }
        System.out.println("[BUILDER RAIL STANCE PASS] grounded rail access "
                +"allowed; solid cube still requires a safe physical exit");
        h.succeed();
    }

    @GameTest(maxTicks=115,skyAccess=true)
    public void blockingBuilderMakesPhysicalPrecisionSidestep(GameTestHelper h){
        var level=h.getLevel();
        for(int x=1;x<=8;x++)for(int z=1;z<=8;z++)
            h.setBlock(x,1,z,Blocks.STONE);
        h.setBlock(1,2,1,
                com.caszgamermd.golemsplus.world.WorksiteContent.BUILDER_TABLE);
        var planner=h.absolutePos(new BlockPos(1,2,1));
        if(!(level.getBlockEntity(planner) instanceof
                com.caszgamermd.golemsplus.world.WorkerStationBlockEntity table)){
            h.fail("No Builder table for physical occupancy test");return;
        }
        var owner=h.makeMockServerPlayerInLevel();
        // The mock GameTest player normally spawns near the template
        // center, exactly where this Builder is supposed to step aside.
        // Move the observer away so that the test probes a genuine
        // single-Builder traffic problem, not an immovable human body.
        BlockPos observer=h.absolutePos(new BlockPos(1,2,7));
        owner.setPos(observer.getX()+.5,observer.getY(),
                observer.getZ()+.5);
        if(!table.claimIfUnowned(owner.getUUID())
                ||!table.setCrewTarget(owner.getUUID(),1)){
            h.fail("Could not prepare Builder crew");return;
        }
        BlockPos target=h.absolutePos(new BlockPos(4,2,4));
        var worker=new BuilderGolem(
                com.caszgamermd.golemsplus.world.WorksiteContent.BUILDER_GOLEM,
                level);
        worker.setPos(target.getX()+.5,target.getY(),target.getZ()+.5);
        level.addFreshEntity(worker);
        if(!table.registerBuilderWorker(worker.getUUID())){
            h.fail("Could not enlist physical blocked Builder");return;
        }
        final boolean[] requested={false};
        final double[] oldX={worker.getX()},oldZ={worker.getZ()};
        h.onEachTick(()->{
            if(!worker.isAlive()){h.fail("Evacuating Builder died");return;}
            if(h.getTick()==7){
                if(BuilderPlacementSpace.ready(level,worker,table,target)){
                    h.fail("Solid target incorrectly ignored a blocking Builder");
                    return;
                }
                requested[0]=true;
            }
            if(!requested[0])return;
            // Live builders retry their placement checks every five ticks.
            // The first spawn tick can precede confirmed on-ground
            // contact; an isolated one-shot ready() request is not the
            // actual work-loop behavior.
            if(h.getTick()>=10&&h.getTick()%5==0
                    &&!BuilderPlacementSpace.horizontalClear(target,worker))
                BuilderPlacementSpace.ready(level,worker,table,target);
            if(h.getTick()==8||h.getTick()==15){
                System.out.println("[BUILDER YIELD DIAGNOSTIC] tick="
                        +h.getTick()+" ground="+worker.onGround()
                        +" pos="+worker.position()
                        +" route="+BuilderPlacementSpace.debugYield(level,worker)
                        +" blockers="+BuilderPlacementSpace.debugOccupants(
                                level,table,target)
                        +" stepXFree="+level.noCollision(worker,
                                worker.getBoundingBox().move(.115,0,0))
                        +" stepXBlocks="+level.getBlockCollisions(worker,
                                worker.getBoundingBox().move(.115,0,0))
                                .iterator().hasNext()
                        +" stepZFree="+level.noCollision(worker,
                                worker.getBoundingBox().move(0,0,.115))
                        +" nearbyEntities="+level.getEntities(
                                worker,worker.getBoundingBox().inflate(1,1,1))
                                .size());
            }
            // This is a genuine physical .115-block-per-tick sidestep,
            // NOT a snap/teleport or a bogus route to the same tile.
            double displacement=Math.hypot(worker.getX()-oldX[0],
                    worker.getZ()-oldZ[0]);
            oldX[0]=worker.getX();oldZ[0]=worker.getZ();
            if(displacement>.40){
                h.fail("Occupancy escape jumped instead of walking: "
                        +displacement);return;
            }
            if(h.getTick()>12
                    &&BuilderPlacementSpace.ready(level,worker,table,target)){
                if(!BuilderPlacementSpace.horizontalClear(target,worker)){
                    h.fail("Escaped Builder still overlaps placement margin");
                    return;
                }
                System.out.println("[BUILDER YIELD PASS] occupying Builder "
                        +"physically vacated solid placement cell");
                h.succeed();
            }
            if(h.getTick()>=90)
                h.fail("Builder could not vacate solid placement via "
                        +"physical precision sidestep: "+worker.position()
                        +" ground="+worker.onGround()
                        +" yield="+BuilderPlacementSpace.debugYield(level,worker)
                        +" occupants="+BuilderPlacementSpace.debugOccupants(
                                level,table,target));
        });
    }

    @GameTest(maxTicks=40,skyAccess=true)
    public void teammateOnUpperLedgeDoesNotBlockPlacementBelow(GameTestHelper h){
        var level=h.getLevel();
        h.setBlock(1,2,1,
                com.caszgamermd.golemsplus.world.WorksiteContent.BUILDER_TABLE);
        h.setBlock(3,2,4,Blocks.STONE);
        var table=(com.caszgamermd.golemsplus.world.WorkerStationBlockEntity)
                level.getBlockEntity(h.absolutePos(new BlockPos(1,2,1)));
        var owner=h.makeMockServerPlayerInLevel();
        BlockPos observer=h.absolutePos(new BlockPos(1,2,7));
        owner.setPos(observer.getX()+.5,observer.getY(),observer.getZ()+.5);
        BlockPos target=h.absolutePos(new BlockPos(4,2,4));
        var teammate=new BuilderGolem(
                com.caszgamermd.golemsplus.world.WorksiteContent.BUILDER_GOLEM,level);
        teammate.setPos(target.getX()-.5,target.getY()+1,target.getZ()+.5);
        level.addFreshEntity(teammate);
        if(!table.claimIfUnowned(owner.getUUID())
                ||!table.registerBuilderWorker(teammate.getUUID())){
            h.fail("Cannot register teammate on upper ledge");
            return;
        }
        var placing=new BuilderGolem(
                com.caszgamermd.golemsplus.world.WorksiteContent.BUILDER_GOLEM,level);
        h.onEachTick(()->{
            if(h.getTick()!=10)return;
            if(!teammate.onGround()||BuilderPlacementSpace.horizontalClear(target,teammate)){
                h.fail("Teammate did not settle within the horizontal clearance margin");
                return;
            }
            if(!BuilderPlacementSpace.ready(level,placing,table,target)){
                h.fail("A vertically clear teammate blocked the lower placement");
                return;
            }
            if(BuilderPlacementSpace.ready(level,teammate,table,target)){
                h.fail("The placing Builder lost its downward clearance check");
                return;
            }
            h.succeed();
        });
    }

    @GameTest(maxTicks=80,skyAccess=true)
    public void railPlacementDoesNotEvacuateAnUnobstructedBuilder(GameTestHelper h){
        var level=h.getLevel();
        for(int x=1;x<=8;x++)for(int z=1;z<=8;z++)
            h.setBlock(x,1,z,Blocks.STONE);
        BlockPos pos=h.absolutePos(new BlockPos(4,2,4));
        var rail=BuilderBlockResolver.resolve(
                "minecraft:powered_rail[powered=false,shape=east_west,waterlogged=false]",
                BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
        if(rail==null){h.fail("Rail resolver returned no physical item");return;}
        if(BuilderPlacementSpace.needsVacating(level,pos,rail.state())
                ||!BuilderPlacementSpace.needsVacating(level,pos,
                        Blocks.SMOOTH_STONE.defaultBlockState())
                ||!BuilderPlacementSpace.needsVacating(level,pos,
                        Blocks.WATER.defaultBlockState())){
            h.fail("Rail/wire versus solid/fluid collision policy is wrong");
            return;
        }
        var worker=new BuilderGolem(
                com.caszgamermd.golemsplus.world.WorksiteContent.BUILDER_GOLEM,
                level);
        worker.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5);
        level.addFreshEntity(worker);
        // A golem standing in the target's empty collision volume is not
        // an obstruction. A null table deliberately proves the old
        // full-cube ready() path was NOT invoked for this rail.
        if(!BuilderPlacementSpace.readyForState(
                level,worker,null,pos,rail.state())){
            h.fail("Non-colliding rail incorrectly requires vacating Builder");
            return;
        }
        if(!level.setBlock(pos,rail.state(),3)
                ||level.getBlockState(pos).getBlock()!=Blocks.POWERED_RAIL){
            h.fail("Physically placed rail was rejected");return;
        }
        var wire=BuilderBlockResolver.resolve(
                "minecraft:redstone_wire[east=none,north=none,power=0,south=none,west=none]",
                BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
        if(wire==null
                ||BuilderPlacementSpace.needsVacating(
                        level,pos.east(2),wire.state())){
            h.fail("Paid redstone wire should be non-colliding");return;
        }
        h.onEachTick(()->{
            if(h.getTick()!=15)return;
            if(!worker.isAlive()
                    ||level.getBlockState(pos).getBlock()!=Blocks.POWERED_RAIL){
                h.fail("Rail was lost after Minecraft neighbor ticks");return;
            }
            System.out.println("[BUILDER RAIL TRAFFIC PASS] walk-through "
                    +"rail and wiring do not trigger phantom full-block clearance");
            h.succeed();
        });
    }
    @GameTest(maxTicks=70,skyAccess=true)
    public void paidSlopeRepairDoesNotRequireTemporaryConnectorSupport(
            GameTestHelper h){
        var world=h.getLevel();
        BlockPos pos=h.absolutePos(new BlockPos(4,2,4));
        h.setBlock(4,1,4,Blocks.STONE);
        h.setBlock(3,1,4,Blocks.STONE);
        h.setBlock(3,2,4,Blocks.POWERED_RAIL);
        h.setBlock(4,2,4,Blocks.POWERED_RAIL);
        var desired=BuilderBlockResolver.resolve(
                "minecraft:powered_rail[powered=false,shape=ascending_west,waterlogged=false]",
                BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
        if(desired==null){h.fail("Could not resolve paid slope");return;}
        if(BuilderRailSlope.hasRaisedEndSupport(world,pos,desired.state())
                ||!BuilderRailSlope.canReshapePaidSlope(world,pos,desired.state())
                ||!BuilderRailSlope.restorePaidSlope(world,pos,desired.state())){
            h.fail("An already present rail could not be corrected without a jig");
            return;
        }
        // A missing rail must retain the ordinary paid placement/support
        // checks. Never classify source water or a foreign block as paid.
        h.setBlock(4,2,4,Blocks.AIR);
        if(BuilderRailSlope.canReshapePaidSlope(world,pos,desired.state())){
            h.fail("Missing rail was accepted as a free paid correction");return;
        }
        h.setBlock(4,2,4,Blocks.STONE);
        if(BuilderRailSlope.canReshapePaidSlope(world,pos,desired.state())){
            h.fail("Foreign block was accepted as a paid rail");return;
        }
        h.succeed();
    }
    @GameTest(maxTicks=70,skyAccess=true)
    public void reachablePaidRailCorrectionRemainsEligibleForAssignment(
            GameTestHelper h){
        var world=h.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            h.setBlock(x,1,z,Blocks.STONE);
        h.setBlock(0,2,0,
                com.caszgamermd.golemsplus.world.WorksiteContent.BUILDER_TABLE);
        h.setBlock(1,2,0,Blocks.CHEST);
        h.setBlock(3,2,4,Blocks.POWERED_RAIL);
        h.setBlock(4,2,4,Blocks.POWERED_RAIL);
        var table=(com.caszgamermd.golemsplus.world.WorkerStationBlockEntity)
                world.getBlockEntity(h.absolutePos(new BlockPos(0,2,0)));
        var owner=h.makeMockServerPlayerInLevel();
        BlockPos observer=h.absolutePos(new BlockPos(0,2,7));
        owner.setPos(observer.getX()+.5,observer.getY(),observer.getZ()+.5);
        var worker=new BuilderGolem(
                com.caszgamermd.golemsplus.world.WorksiteContent.BUILDER_GOLEM,world);
        BlockPos stance=h.absolutePos(new BlockPos(6,2,4));
        worker.setPos(stance.getX()+.5,stance.getY(),stance.getZ()+.5);
        world.addFreshEntity(worker);
        if(!table.claimIfUnowned(owner.getUUID())
                ||!table.setCrewTarget(owner.getUUID(),1)
                ||!table.setPlacedBuilderPlan(owner.getUUID(),
                    "placed-paid-rail-frontier.litematic","Paid rail",table.getBlockPos(),
                    0,0,1,0)
                ||!table.setBuilderMaterialStorage(owner.getUUID(),
                    h.absolutePos(new BlockPos(1,2,0)))
                ||!table.registerBuilderWorker(worker.getUUID())
                ||!table.startPlacedBuilder(owner.getUUID())){
            h.fail("Could not prepare real rail frontier");return;
        }
        BlockPos target=h.absolutePos(new BlockPos(4,2,4));
        var steps=java.util.List.of(new BuilderConstructionPlan.Step(
                target.getX(),target.getY(),target.getZ(),
                "minecraft:powered_rail[powered=false,shape=ascending_west,waterlogged=false]",
                "minecraft:powered_rail"));
        h.onEachTick(()->{
            if(h.getTick()!=16)return;
            var choice=BuilderConstructionPlanner.choose(
                    world,worker,table,steps,java.util.List.of(0));
            if(choice==null||choice.index()!=0||choice.stance()==null){
                h.fail("Reachable paid rail was rejected by frontier: "+choice);
                return;
            }
            h.setBlock(4,2,4,Blocks.AIR);
            if(BuilderConstructionPlanner.choose(world,worker,table,
                    steps,java.util.List.of(0))!=null){
                h.fail("Fresh rail bypassed its missing connector support");return;
            }
            System.out.println("[PAID RAIL FRONTIER PASS] reachable correction "
                    +"selected; fresh unsupported placement still rejected");
            h.succeed();
        });
    }
}
