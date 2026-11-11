package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/** Actual entity movement across the lowered collision surfaces used in
 * the Matrix. The route must not require a scaffold or a fake integer Y.
 */
public final class BuilderLoweredFloorNavigationGameTest {
    @GameTest(maxTicks=300,skyAccess=true)
    public void walksOutOfSoulSandAcrossMudToFullBlock(GameTestHelper h){
        var level=h.getLevel();
        for(int x=1;x<=6;x++)
            h.setBlock(x,1,3,x==1||x==3?Blocks.SOUL_SAND:
                    x==2||x==5?Blocks.MUD:Blocks.STONE);
        BlockPos start=h.absolutePos(new BlockPos(1,1,3));
        BlockPos goal=h.absolutePos(new BlockPos(6,2,3));
        var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        worker.setPos(start.getX()+.5,start.getY()+.875,start.getZ()+.5);
        level.addFreshEntity(worker);
        h.onEachTick(()->{
            if(h.getTick()<10)return;
            if(!worker.isAlive()||worker.getY()<start.getY()-.2){
                h.fail("Builder fell from lowered-floor walkway");return;
            }
            if(h.getTick()==10){
                double actual=WorkerNavigation.surveyFloorHeight(level,start);
                if(Math.abs(actual-start.getY()-.875)>.001
                        ||!WorkerNavigation.canStandOn(level,worker,start)){
                    h.fail("Actual soul-sand floor was rejected: "+actual);return;
                }
                if(WorkerNavigation.canStandOn(level,worker,
                        h.absolutePos(new BlockPos(4,1,3)))){
                    h.fail("Full solid cube was admitted as a feet node");return;
                }
            }
            if(h.getTick()%5==0)
                WorkerNavigation.routeToExact(level,worker,goal,1.0);
            if(worker.onGround()&&worker.distanceToSqr(goal.getX()+.5,
                    goal.getY(),goal.getZ()+.5)<.16){
                System.out.println("[LOWERED FLOOR NAV PASS] soul sand/mud "
                        +"to stone at tick="+h.getTick());
                h.succeed();return;
            }
            if(h.getTick()>260)
                h.fail("Lowered-floor route stalled: feet="+worker.position()
                        +" "+WorkerNavigation.debugStatus(worker));
        });
    }

    @GameTest(maxTicks=50,skyAccess=true)
    public void loweredFloorStillRequiresHeadroom(GameTestHelper h){
        var level=h.getLevel();
        h.setBlock(3,1,3,Blocks.SOUL_SAND);
        h.setBlock(3,3,3,Blocks.STONE);
        BlockPos feet=h.absolutePos(new BlockPos(3,1,3));
        var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        if(WorkerNavigation.canStandOn(level,worker,feet)){
            h.fail("Lowered floor bypassed solid head collision");return;
        }
        h.succeed();
    }
}
