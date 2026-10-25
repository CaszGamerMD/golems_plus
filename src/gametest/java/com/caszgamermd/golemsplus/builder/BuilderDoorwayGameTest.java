package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/** Tests the same real-world single-block framed entrance as the Dome.
 * A grant in the door queue is not enough: the worker must physically
 * walk THROUGH and finish outside, having entered the doorway footprint.
 */
public final class BuilderDoorwayGameTest {
    @GameTest(maxTicks=700, skyAccess=true)
    public void builderCrossesFramedOakDoor(GameTestHelper helper){
        runCrossing(helper,false);
    }
    @GameTest(maxTicks=700, skyAccess=true)
    public void builderCrossesFramedFenceGate(GameTestHelper helper){
        runCrossing(helper,true);
    }
    private static void runCrossing(GameTestHelper helper,boolean gate){
        ServerLevel world=helper.getLevel();
        for(int x=0;x<=12;x++)for(int z=0;z<=8;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        for(int z=0;z<=8;z++)for(int y=2;y<=4;y++)
            helper.setBlock(6,y,z,Blocks.COBBLESTONE);
        // An enclosed single-width opening bordered by real walls.
        helper.setBlock(6,2,4,Blocks.AIR);
        helper.setBlock(6,3,4,Blocks.AIR);
        if(gate){
            BlockState block=Blocks.OAK_FENCE_GATE.defaultBlockState()
                    .setValue(FenceGateBlock.FACING,Direction.EAST)
                    .setValue(FenceGateBlock.OPEN,false);
            helper.setBlock(6,2,4,block);
        }else{
            BlockState door=Blocks.OAK_DOOR.defaultBlockState()
                    .setValue(DoorBlock.FACING,Direction.EAST)
                    .setValue(DoorBlock.OPEN,false);
            helper.setBlock(6,2,4,door.setValue(DoorBlock.HALF,
                    DoubleBlockHalf.LOWER));
            helper.setBlock(6,3,4,door.setValue(DoorBlock.HALF,
                    DoubleBlockHalf.UPPER));
        }
        BlockPos door=helper.absolutePos(new BlockPos(6,2,4));
        BlockPos target=helper.absolutePos(new BlockPos(10,2,4));
        BlockPos start=helper.absolutePos(new BlockPos(3,2,4));
        BuilderGolem worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        worker.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        world.addFreshEntity(worker);
        final boolean[] entered={false};
        final int[] noProgress={0};
        final double[] lastX={worker.getX()};
        helper.onEachTick(()->{
            if(!worker.isAlive()){helper.fail("Builder disappeared at doorway");return;}
            if(worker.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(door)))
                entered[0]=true;
            if(Math.abs(worker.getX()-lastX[0])>.10){
                noProgress[0]=0;lastX[0]=worker.getX();
            }else noProgress[0]++;
            if(worker.distanceToSqr(target.getX()+.5,target.getY(),
                    target.getZ()+.5)<.9&&entered[0]&&worker.onGround()){
                helper.succeed();return;
            }
            if(noProgress[0]>150){
                helper.fail("Builder centered/stationary in "+(gate?"gate":"door")
                        +" pos="+worker.position()+" entered="+entered[0]
                        +" door="+world.getBlockState(door)
                        +" nav="+WorkerNavigation.debugStatus(worker));
                return;
            }
            if(helper.getTick()%5==0)
                WorkerNavigation.routeTo(world,worker,target,1.0);
        });
    }
}
