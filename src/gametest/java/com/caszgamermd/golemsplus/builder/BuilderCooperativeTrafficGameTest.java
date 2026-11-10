package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/**
 * Multi-agent physical-footstep benchmark, not a synthetic path-only test.
 * Two real Builders must exchange places in a two-wide corridor using
 * vanilla collision + trained A* + short-horizon teammate forecasts.
 * No teleport after spawn, no scaffold, no hook and no path through wall.
 */
public final class BuilderCooperativeTrafficGameTest {
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=900,skyAccess=true)
    public void opposingBuildersPassThroughTwoWideAisle(GameTestHelper h){
        var level=h.getLevel();
        for(int x=2;x<=12;x++)for(int z=2;z<=8;z++){
            h.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=3;y++)
                h.setBlock(x,y,z,(z==4||z==5)
                        ?Blocks.AIR:Blocks.COBBLESTONE);
        }
        BlockPos[] starts={
            h.absolutePos(new BlockPos(3,2,4)),
            h.absolutePos(new BlockPos(11,2,4))
        };
        BlockPos[] goals={
            h.absolutePos(new BlockPos(11,2,4)),
            h.absolutePos(new BlockPos(3,2,4))
        };
        BuilderGolem[] workers=new BuilderGolem[2];
        boolean[] complete=new boolean[2];
        for(int i=0;i<2;i++){
            BuilderGolem g=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
            g.setPos(starts[i].getX()+.5,starts[i].getY(),
                    starts[i].getZ()+.5);
            level.addFreshEntity(g);workers[i]=g;
        }
        final double[] lastX={workers[0].getX(),workers[1].getX()};
        final double[] lastZ={workers[0].getZ(),workers[1].getZ()};
        final long[] lastActivity={0};
        h.onEachTick(()->{
            int finished=0;
            for(int i=0;i<workers.length;i++){
                BuilderGolem g=workers[i];
                if(!g.isAlive()||g.isNoGravity()||g.getY()<starts[i].getY()-.11){
                    h.fail("Crowd navigation lost physics worker="+i
                            +" at "+g.position()+" start="+starts[i]
                            +" grounded="+g.onGround()
                            +" noGravity="+g.isNoGravity()
                            +" goal="+goals[i]
                            +" route="+WorkerNavigation.debugStatus(g));
                    return;
                }
                if(g.onGround()&&g.distanceToSqr(goals[i].getX()+.5,
                        goals[i].getY(),goals[i].getZ()+.5)<.25)
                    complete[i]=true;
                if(complete[i]){finished++;WorkerNavigation.stop(g);}
                else if(h.getTick()>=10&&h.getTick()%5==0)
                    WorkerNavigation.routeToExact(level,g,goals[i],1.0);
                if(Math.hypot(g.getX()-lastX[i],g.getZ()-lastZ[i])>.08)
                    lastActivity[0]=h.getTick();
                lastX[i]=g.getX();lastZ[i]=g.getZ();
            }
            if(finished==2){
                System.out.println("[COOPERATIVE TRAFFIC PASS] 2/2 "
                        +"opposing Builders completed real aisle exchange at tick="
                        +h.getTick());
                h.succeed();return;
            }
            if(h.getTick()>180&&h.getTick()-lastActivity[0]>140){
                h.fail("Cooperative traffic stalled: "+
                        workers[0].position()+" "+
                        WorkerNavigation.debugStatus(workers[0])+" / "+
                        workers[1].position()+" "+
                        WorkerNavigation.debugStatus(workers[1]));
                return;
            }
            if(h.getTick()>780)
                h.fail("Opposing Builders could not exchange positions: "+
                        WorkerNavigation.debugStatus(workers[0])+" and "+
                        WorkerNavigation.debugStatus(workers[1]));
        });
    }
}
