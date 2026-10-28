package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/** Real 26.2 entity test for a work approach on a raised Dome-like rim
 * and for precise final centering inside the selected standing cell.
 */
public final class BuilderWorkAccessGameTest {
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
