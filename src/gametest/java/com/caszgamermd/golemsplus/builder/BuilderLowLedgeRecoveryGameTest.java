package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * A stranded Builder at the middle of a three-wide completed deck must
 * physically walk across the deck to a real one-block lower landing.
 * No teleport, no unstored scaffolding, no noclip and no player movement.
 */
public final class BuilderLowLedgeRecoveryGameTest {
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=420,skyAccess=true)
    public void exitsRaisedDeckAndReachesOneBlockLowerFloor(GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        for(int x=5;x<=16;x++)for(int z=5;z<=16;z++){
            helper.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=6;y++)helper.setBlock(x,y,z,Blocks.AIR);
        }
        // A 3x3 elevated island: there is no safe one-block drop immediately
        // beside the middle worker, only beyond the raised platform's edge.
        for(int x=7;x<=9;x++)for(int z=7;z<=9;z++)
            helper.setBlock(x,2,z,Blocks.STONE);
        BlockPos start=helper.absolutePos(new BlockPos(8,3,8));
        BlockPos depot=helper.absolutePos(new BlockPos(11,2,8));
        BuilderGolem worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        worker.setPersistenceRequired();
        worker.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        level.addFreshEntity(worker);
        Runnable simulation=BuilderChunkTickets.holdGameTestSimulation(level,start,depot);
        final boolean[] started={false};
        final long[] startedAt={helper.getTick()};
        helper.onEachTick(()->{
            if(!worker.isAlive()){
                simulation.run();
                helper.fail("Raised-deck Builder died before a physical descent");
                return;
            }
            if(helper.getTick()%5!=0)return;
            if(helper.getTick()>80&&worker.tickCount<15){
                simulation.run();
                helper.fail("Raised-deck Builder chunk is not entity-simulated: "
                        +worker.tickCount+" ticks");
                return;
            }
            double horizontal=Math.pow(worker.getX()-depot.getX()-.5,2)
                    +Math.pow(worker.getZ()-depot.getZ()-.5,2);
            if(started[0]&&worker.getY()<=depot.getY()+.24
                    &&horizontal<=4.0&&worker.onGround()){
                simulation.run();
                helper.succeed();
                return;
            }
            if(!started[0]&&worker.onGround()){
                started[0]=BuilderTeamGroundTraffic.recover(level,worker,depot);
                if(started[0])startedAt[0]=helper.getTick();
            }
            if(helper.getTick()-startedAt[0]>190){
                simulation.run();
                helper.fail("Raised-deck recovery stalled at "+worker.blockPosition()
                        +" y="+worker.getY()+" workerTicks="+worker.tickCount
                        +" started="+started[0]+" ground="+worker.onGround());
            }
        });
    }
}
