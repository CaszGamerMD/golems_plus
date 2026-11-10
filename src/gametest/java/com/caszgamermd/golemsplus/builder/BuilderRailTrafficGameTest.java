package com.caszgamermd.golemsplus.builder;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/** A rail is walk-through geometry, not a newly placed full stone cube.
 * Golems are allowed to work within its empty collision volume; solid
 * blocks and active fluids still need full body clearance. */
public final class BuilderRailTrafficGameTest {
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
