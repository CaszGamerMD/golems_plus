package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/** A Dome-like ledge: real floor -> fence top (+0.5) -> real floor. */
public final class BuilderFenceStepGameTest {
    @GameTest(maxTicks=350,skyAccess=true)
    public void physicallyStepsAcrossHalfBlockFenceRise(GameTestHelper h){
        var world=h.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            h.setBlock(x,1,z,Blocks.STONE);
        for(int x=0;x<8;x++){
            h.setBlock(x,2,3,Blocks.STONE);
            for(int y=2;y<=5;y++){
                h.setBlock(x,y,2,Blocks.STONE);
                h.setBlock(x,y,4,Blocks.STONE);
            }
        }
        h.setBlock(3,2,3,Blocks.SPRUCE_FENCE);
        BlockPos start=h.absolutePos(new BlockPos(1,3,3));
        BlockPos goal=h.absolutePos(new BlockPos(6,3,3));
        var mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        mob.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        world.addFreshEntity(mob);
        final boolean[] crossedFence={false};
        h.onEachTick(()->{
            if(!mob.isAlive()||mob.isNoGravity()){
                h.fail("Walking test lost normal physical movement");return;
            }
            if(mob.blockPosition().getX()==start.getX()+2
                    &&mob.getY()>start.getY()+.45)crossedFence[0]=true;
            if(h.getTick()>=10&&h.getTick()%5==0)
                WorkerNavigation.routeToExact(world,mob,goal,1.0);
            if(mob.onGround()&&mob.distanceToSqr(goal.getX()+.5,
                    goal.getY(),goal.getZ()+.5)<.16){
                if(!crossedFence[0]){
                    h.fail("Worker bypassed constrained fence ledge");return;
                }
                System.out.println("[FENCE STEP] PASS physical half-block rise, "
                        +"descent and grounded arrival tick="+h.getTick());
                h.succeed();return;
            }
            if(h.getTick()>280)
                h.fail("Half-block fence traverse stalled: "+mob.position()
                        +" "+WorkerNavigation.debugStatus(mob));
        });
    }
}
