package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * Genuine headless Minecraft server test. Unlike source assertions/JUnit:
 * this spawns a real Builder entity in a real block world and verifies
 * that its actual coordinates follow an up -> ring -> down -> ground route.
 *
 * Physical site (local x/y/z):
 *  ground: stone at y=1
 *  scaffold: x=1,z=3,y=2..6
 *  five-by-five elevated stone walkway perimeter at y=6,
 *  x=2..6,z=1..5 (feet travel at y=7).
 */
public final class BuilderScaffoldRingGameTest {
    @GameTest(maxTicks = 1100, skyAccess = true)
    public void climbWalkRingReturnAndDescend(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (int x=0;x<8;x++) for (int z=0;z<8;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        for (int x=2;x<=6;x++) for (int z=1;z<=5;z++)
            if (x==2||x==6||z==1||z==5)
                helper.setBlock(x,6,z,Blocks.STONE);
        for (int y=2;y<=6;y++)
            helper.setBlock(1,y,3,Blocks.SCAFFOLDING);

        BlockPos column=helper.absolutePos(new BlockPos(1,2,3));
        BlockPos groundExit=helper.absolutePos(new BlockPos(0,2,3));
        BlockPos[] lap={
                new BlockPos(2,7,3),new BlockPos(2,7,1),
                new BlockPos(6,7,1),new BlockPos(6,7,5),
                new BlockPos(2,7,5),new BlockPos(2,7,3)
        };

        BuilderGolem builder = new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        BlockPos spawn=helper.absolutePos(new BlockPos(1,2,2));
        builder.setPos(spawn.getX()+.5,spawn.getY(),spawn.getZ()+.5);
        level.addFreshEntity(builder);
        final int[] stage={0},visited={0};
        final long[] since={helper.getTick()};
        BuilderScaffolding.begin(level,builder,column,
                helper.absolutePos(new BlockPos(1,7,3)).getY(),false);

        helper.onEachTick(() -> {
            if (!builder.isAlive()) {
                helper.fail("Builder vanished during scaffold test");
                return;
            }
            long now=helper.getTick();
            if (now-since[0]>170) {
                helper.fail("Builder stalled at stage="+stage[0]+
                        " ring="+visited[0]+
                        " feet="+builder.blockPosition()+
                        " climb="+BuilderScaffolding.debugClimb(builder)+
                        " nav="+WorkerNavigation.debugStatus(builder));
                return;
            }

            if (stage[0]==0) { // Physics-based ascent, not a teleport.
                if (builder.getY()>=helper.absolutePos(
                        new BlockPos(1,7,3)).getY()-.16) {
                    BlockPos platform=helper.absolutePos(new BlockPos(2,7,3));
                    if(!BuilderScaffolding.stepToPlatform(level,builder,platform))
                        helper.fail("Builder at top cannot enter adjacent ring platform");
                    stage[0]=1; since[0]=now;
                }
            }else if (stage[0]==1) { // Physically step onto top walkway.
                BlockPos platform=helper.absolutePos(new BlockPos(2,7,3));
                double dx=builder.getX()-(platform.getX()+.5);
                double dz=builder.getZ()-(platform.getZ()+.5);
                if(dx*dx+dz*dz<.55&&builder.onGround()
                        &&Math.abs(builder.getY()-platform.getY())<.28){
                    stage[0]=2;since[0]=now;
                    visited[0]=1; // the ring entry is first waypoint
                }
            }else if (stage[0]==2) { // Real native navigation around ring.
                if (visited[0]==lap.length) {
                    stage[0]=3;since[0]=now;
                    BuilderScaffolding.begin(level,builder,column,
                            column.getY()+1.0,true);
                    return;
                }
                BlockPos destination=helper.absolutePos(lap[visited[0]]);
                double dx=builder.getX()-(destination.getX()+.5);
                double dz=builder.getZ()-(destination.getZ()+.5);
                if (dx*dx+dz*dz<.50
                        &&Math.abs(builder.getY()-destination.getY())<.38) {
                    visited[0]++;since[0]=now;
                }else{
                    WorkerNavigation.routeToExact(level,builder,destination,1.0);
                }
            }else if(stage[0]==3) { // Re-enter scaffold from ring edge.
                if(builder.getY()<=column.getY()+1.16) {
                    stage[0]=4;since[0]=now;
                    if(!BuilderScaffolding.dismountAtBase(level,builder,column))
                        helper.fail("No physically safe ground landing beside tower");
                }
            }else if(stage[0]==4) { // Feet must land on solid ground.
                double dx=builder.getX()-(groundExit.getX()+.5);
                double dz=builder.getZ()-(groundExit.getZ()+.5);
                if(dx*dx+dz*dz<.55
                        &&Math.abs(builder.getY()-groundExit.getY())<.25
                        &&builder.onGround()){
                    helper.succeed();
                }
            }
        });
    }
}
