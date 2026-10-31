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
    @GameTest(maxTicks=400,skyAccess=true)
    public void offCenterGateOccupantCanExitTowardLowerDepot(GameTestHelper h){
        var world=h.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)h.setBlock(x,1,z,Blocks.STONE);
        for(int x=2;x<=4;x++)for(int z=1;z<=5;z++)h.setBlock(x,2,z,Blocks.STONE);
        h.setBlock(3,3,3,Blocks.OAK_FENCE_GATE.defaultBlockState()
                .setValue(FenceGateBlock.FACING,Direction.EAST)
                .setValue(FenceGateBlock.OPEN,true));
        BlockPos gate=h.absolutePos(new BlockPos(3,3,3));
        BlockPos goal=h.absolutePos(new BlockPos(0,2,3));
        var mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        mob.setPos(gate.getX()+.979,gate.getY(),gate.getZ()+.982);
        world.addFreshEntity(mob);
        h.onEachTick(()->{
            if(h.getTick()>=10&&h.getTick()%5==0)WorkerNavigation.routeTo(world,mob,goal,1.0);
            if(mob.onGround()&&mob.distanceToSqr(goal.getX()+.5,goal.getY(),goal.getZ()+.5)<1){
                h.succeed();return;
            }
            if(h.getTick()>350)h.fail("Off-center gate occupant stalled "+mob.position()
                    +" "+WorkerNavigation.debugStatus(mob));
        });
    }
    private static void runCrossing(GameTestHelper helper,boolean gate){
        ServerLevel world=helper.getLevel();
        for(int x=0;x<=7;x++)for(int z=0;z<=7;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        for(int z=0;z<=7;z++)for(int y=2;y<=4;y++)
            helper.setBlock(3,y,z,Blocks.COBBLESTONE);
        // The default server GameTest arena is eight blocks across;
        // DO NOT extend this route into the invisible barrier perimeter.
        // An enclosed single-width opening bordered by real walls.
        helper.setBlock(3,2,3,Blocks.AIR);
        helper.setBlock(3,3,3,Blocks.AIR);
        if(gate){
            BlockState block=Blocks.OAK_FENCE_GATE.defaultBlockState()
                    .setValue(FenceGateBlock.FACING,Direction.EAST)
                    .setValue(FenceGateBlock.OPEN,false);
            helper.setBlock(3,2,3,block);
        }else{
            BlockState door=Blocks.OAK_DOOR.defaultBlockState()
                    .setValue(DoorBlock.FACING,Direction.EAST)
                    .setValue(DoorBlock.OPEN,false);
            helper.setBlock(3,2,3,door.setValue(DoorBlock.HALF,
                    DoubleBlockHalf.LOWER));
            helper.setBlock(3,3,3,door.setValue(DoorBlock.HALF,
                    DoubleBlockHalf.UPPER));
        }
        BlockPos door=helper.absolutePos(new BlockPos(3,2,3));
        BlockPos target=helper.absolutePos(new BlockPos(6,2,3));
        BlockPos start=helper.absolutePos(new BlockPos(1,2,3));
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
                        +" bounds="+worker.getBoundingBox()
                        +" width="+worker.getBbWidth()
                        +" velocity="+worker.getDeltaMovement()
                        +" grounded="+worker.onGround()
                        +" aheadCollisionFree="+world.noCollision(worker,
                                worker.getBoundingBox().move(.16,0,0))
                        +" blockCollisions="+world.getBlockCollisions(worker,
                                worker.getBoundingBox().move(.16,0,0))
                                .iterator().hasNext()
                        +" nextBlock="+world.getBlockState(
                                worker.blockPosition().relative(Direction.EAST))
                        +" nav="+WorkerNavigation.debugStatus(worker));
                return;
            }
            if(helper.getTick()%5==0)
                WorkerNavigation.routeTo(world,worker,target,1.0);
        });
    }
}
