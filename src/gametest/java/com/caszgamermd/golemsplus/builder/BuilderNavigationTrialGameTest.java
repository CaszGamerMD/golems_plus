package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.pathfinder.Path;

/**
 * Real-server navigation trials; these check entity motion, not source text.
 * Each trial operates inside the default 8x8 GameTest arena.
 */
public final class BuilderNavigationTrialGameTest {
    private static BuilderGolem spawn(GameTestHelper helper,BlockPos local) {
        ServerLevel world=helper.getLevel();
        BuilderGolem worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        BlockPos spawn=helper.absolutePos(local);
        worker.setPos(spawn.getX()+.5,spawn.getY(),spawn.getZ()+.5);
        world.addFreshEntity(worker);
        return worker;
    }

    private static void floor(GameTestHelper helper) {
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
    }

    private static boolean reached(BuilderGolem golem,BlockPos target) {
        double dx=golem.getX()-(target.getX()+.5);
        double dz=golem.getZ()-(target.getZ()+.5);
        return dx*dx+dz*dz<.45
                &&Math.abs(golem.getY()-target.getY())<.25
                &&golem.onGround();
    }

    @GameTest(maxTicks=750,skyAccess=true)
    public void builderTakesRealDetourAroundSolidWall(GameTestHelper helper) {
        floor(helper);
        // Two-high wall with genuine passages at z=1 and z=5.
        // The direct route through z=3 is impossible.
        for(int y=2;y<=3;y++)for(int z=2;z<=4;z++)
            helper.setBlock(3,y,z,Blocks.COBBLESTONE);
        BlockPos destination=helper.absolutePos(new BlockPos(6,2,3));
        BuilderGolem worker=spawn(helper,new BlockPos(1,2,3));
        Path nativePath=worker.getNavigation().createPath(destination,0);
        if(nativePath==null||!nativePath.canReach()){
            helper.fail("Vanilla navigation failed to find the real wall detour");
            return;
        }
        final double[] lastX={worker.getX()},lastZ={worker.getZ()};
        final int[] stationary={0};
        final boolean[] detoured={false};
        helper.onEachTick(()->{
            if(!worker.isAlive()){
                helper.fail("Builder vanished on wall detour");
                return;
            }
            if(Math.abs(worker.getZ()-(helper.absolutePos(
                    new BlockPos(0,2,3)).getZ()+.5))>.6)
                detoured[0]=true;
            if(worker.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(
                    helper.absolutePos(new BlockPos(3,2,2)),
                    helper.absolutePos(new BlockPos(4,4,5))))){
                helper.fail("Builder entered wall collision while detouring");
                return;
            }
            if(reached(worker,destination)&&detoured[0]){
                helper.succeed();
                return;
            }
            if(Math.abs(worker.getX()-lastX[0])+
                    Math.abs(worker.getZ()-lastZ[0])>.08){
                stationary[0]=0;
                lastX[0]=worker.getX();lastZ[0]=worker.getZ();
            }else stationary[0]++;
            if(stationary[0]>140){
                helper.fail("Builder stuck in detour pos="+worker.position()
                        +" nativeNodes="+nativePath.getNodeCount()
                        +" nav="+WorkerNavigation.debugStatus(worker));
                return;
            }
            if(helper.getTick()%5==0)
                WorkerNavigation.routeToExact(helper.getLevel(),worker,destination,1.0);
        });
    }

    @GameTest(maxTicks=750,skyAccess=true)
    public void builderReplansAfterNewBlockCutsItsRoute(GameTestHelper helper) {
        floor(helper);
        BlockPos destination=helper.absolutePos(new BlockPos(6,2,3));
        BuilderGolem worker=spawn(helper,new BlockPos(1,2,3));
        final boolean[] obstructed={false},detoured={false};
        final double[] lastX={worker.getX()},lastZ={worker.getZ()};
        final int[] stationary={0};
        helper.onEachTick(()->{
            if(!worker.isAlive()){
                helper.fail("Builder vanished during live reroute");
                return;
            }
            if(!obstructed[0]&&helper.getTick()>=5){
                BlockPos block=helper.absolutePos(new BlockPos(3,2,3));
                helper.setBlock(3,2,3,Blocks.COBBLESTONE);
                helper.setBlock(3,3,3,Blocks.COBBLESTONE);
                obstructed[0]=true;
                if(worker.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(block))){
                    helper.fail("Trial wall placed into moving builder");
                    return;
                }
            }
            if(obstructed[0]&&Math.abs(worker.getZ()-
                    (destination.getZ()+.5))>.55)
                detoured[0]=true;
            if(obstructed[0]&&reached(worker,destination)){
                if(!detoured[0])helper.fail("Builder arrived without clearing the new wall");
                else helper.succeed();
                return;
            }
            if(Math.abs(worker.getX()-lastX[0])+
                    Math.abs(worker.getZ()-lastZ[0])>.08){
                stationary[0]=0;
                lastX[0]=worker.getX();lastZ[0]=worker.getZ();
            }else stationary[0]++;
            if(stationary[0]>140){
                helper.fail("Builder stuck on dynamic reroute pos="+worker.position()
                        +" nav="+WorkerNavigation.debugStatus(worker));
                return;
            }
            if(helper.getTick()%5==0)
                WorkerNavigation.routeToExact(helper.getLevel(),worker,destination,1.0);
        });
    }
}
