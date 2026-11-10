package com.caszgamermd.golemsplus.builder;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/** A rail is walk-through geometry, not a newly placed full stone cube.
 * Golems are allowed to work within its empty collision volume; solid
 * blocks and active fluids still need full body clearance. */
public final class BuilderRailTrafficGameTest {
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
                        +"physical precision sidestep: "+worker.position());
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
}
