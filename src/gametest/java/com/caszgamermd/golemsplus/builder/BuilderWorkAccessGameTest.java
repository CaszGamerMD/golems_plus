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
    /** Reproduces the dome's four-level work handoff: the block is high,
     * but a winding real-ground climb ends at a valid placement stance.
     * Do not require a hook or scaffold for this reachable roof tile.
     */
    @GameTest(maxTicks=560,skyAccess=true)
    public void builderWalksFourLevelRiseToWorkPoseWithoutTools(
            GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        for(int x=0;x<12;x++)for(int z=0;z<8;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        for(int x=2;x<=4;x++)
            helper.setBlock(x,x,3,Blocks.STONE);
        for(int x=5;x<=9;x++)
            helper.setBlock(x,5,3,Blocks.STONE);
        helper.setBlock(0,2,0,WorksiteContent.BUILDER_TABLE);
        BlockPos station=helper.absolutePos(new BlockPos(0,2,0));
        if(!(level.getBlockEntity(station) instanceof WorkerStationBlockEntity table)){
            helper.fail("Missing real Builder station");return;
        }
        BlockPos target=helper.absolutePos(new BlockPos(9,6,3));
        BlockPos start=helper.absolutePos(new BlockPos(1,2,3));
        BuilderGolem worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        worker.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        level.addFreshEntity(worker);
        boolean[] traveled={false};
        helper.onEachTick(()->{
            if(!worker.isAlive()||worker.isNoGravity()){
                helper.fail("Builder left physical ground movement");return;
            }
            if(helper.getTick()>=15&&helper.getTick()%5==0&&worker.onGround())
                traveled[0]|=BuilderBuildService.walkToOrdinaryWorkPose(
                        level,worker,table,target);
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
                        +" route="+WorkerNavigation.debugStatus(worker));
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
