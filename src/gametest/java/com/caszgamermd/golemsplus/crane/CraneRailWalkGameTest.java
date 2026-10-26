package com.caszgamermd.golemsplus.crane;

import com.caszgamermd.golemsplus.builder.BuilderGolem;
import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/** Physical collision test: workers must cross surface rail tracks. */
public final class CraneRailWalkGameTest {
    @GameTest(maxTicks=450,skyAccess=true)
    public void builderCrossesVineRailWithoutJumpingOrCircling(GameTestHelper h){
        var level=h.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++){
            h.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=5;y++)h.setBlock(x,y,z,Blocks.AIR);
        }
        for(int x=2;x<=5;x++)for(int z=2;z<=4;z++)
            h.setBlock(x,2,z,CraneContent.RAIL);
        var golem=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        BlockPos start=h.absolutePos(new BlockPos(1,2,3));
        BlockPos target=h.absolutePos(new BlockPos(6,2,3));
        golem.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        level.addFreshEntity(golem);
        h.onEachTick(()->{
            if(!golem.isAlive()){h.fail("Builder disappeared");return;}
            if(golem.distanceToSqr(target.getX()+.5,target.getY(),
                    target.getZ()+.5)<.45
                    &&golem.onGround()){
                h.succeed();return;
            }
            if(h.getTick()%5==0)
                WorkerNavigation.routeToExact(level,golem,target,1.0);
            if(h.getTick()>330)
                h.fail("Rail still blocks golem, goal="+target+
                        " feet="+golem.blockPosition()+
                        " nav="+WorkerNavigation.debugStatus(golem));
        });
    }
}
