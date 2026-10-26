package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;

/** Physical 26.2 server regression: two Builders approach head-on.
 * The higher-priority Builder continues while the other waits a RANDOM
 * 1..30 ticks, moves CLOCKWISE right onto actual ground, and allows
 * the first to pass. Neither path may dance forever or teleport.
 */
public final class BuilderClockwiseTrafficGameTest {
    @GameTest(maxTicks=1150,skyAccess=true)
    public void twoBuildersPassClockwiseWithIndependentStagger(GameTestHelper h){
        var level=h.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++){
            h.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=5;y++)h.setBlock(x,y,z,Blocks.AIR);
        }
        var a=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        var b=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        BlockPos left=h.absolutePos(new BlockPos(2,2,3));
        BlockPos right=h.absolutePos(new BlockPos(5,2,3));
        BlockPos goalA=h.absolutePos(new BlockPos(6,2,3));
        BlockPos goalB=h.absolutePos(new BlockPos(1,2,3));
        a.setPos(left.getX()+.5,left.getY(),left.getZ()+.5);
        b.setPos(right.getX()+.5,right.getY(),right.getZ()+.5);
        level.addFreshEntity(a);
        level.addFreshEntity(b);
        final boolean[] reached={false,false};
        h.onEachTick(()->{
            if(!a.isAlive()||!b.isAlive()){
                h.fail("Builder disappeared during clockwise collision test");return;
            }
            BuilderGolem[] workers={a,b};
            BlockPos[] goals={goalA,goalB};
            for(int i=0;i<2;i++){
                BuilderGolem w=workers[i];
                BlockPos dest=goals[i];
                if(!reached[i] && w.onGround()
                        &&w.distanceToSqr(dest.getX()+.5,dest.getY(),
                                dest.getZ()+.5)<.65)
                    reached[i]=true;
                if(!reached[i] &&h.getTick()%12==0
                        &&!BuilderClockwiseTraffic.controlling(w))
                    WorkerNavigation.routeToExact(level,w,dest,1.0);
            }
            if(reached[0]&&reached[1]){
                int yielded=BuilderClockwiseTraffic.completedYields(a)
                        +BuilderClockwiseTraffic.completedYields(b);
                if(yielded<1){
                    h.fail("Workers crossed but never exercised clockwise collision handling");
                    return;
                }
                int delay=BuilderClockwiseTraffic.completedYields(a)>0
                        ?BuilderClockwiseTraffic.lastDelay(a)
                        :BuilderClockwiseTraffic.lastDelay(b);
                if(delay<1||delay>30){
                    h.fail("Clockwise yield did not wait a randomized 1..30 ticks: "
                            +delay);
                    return;
                }
                h.succeed();
            }else if(h.getTick()>960){
                h.fail("Workers still dancing: A="+a.blockPosition()
                        +" "+BuilderClockwiseTraffic.debug(a)
                        +" nav="+WorkerNavigation.debugStatus(a)
                        +" / B="+b.blockPosition()
                        +" "+BuilderClockwiseTraffic.debug(b)
                        +" nav="+WorkerNavigation.debugStatus(b));
            }
        });
    }

    @GameTest(maxTicks=30)
    public void clockwiseOrderAndRandomWaitAreBounded(GameTestHelper h){
        if(BuilderClockwiseTraffic.clockwise(Direction.NORTH)!=Direction.EAST
                ||BuilderClockwiseTraffic.clockwise(Direction.EAST)!=Direction.SOUTH
                ||BuilderClockwiseTraffic.clockwise(Direction.SOUTH)!=Direction.WEST
                ||BuilderClockwiseTraffic.clockwise(Direction.WEST)!=Direction.NORTH){
            h.fail("Builder passing direction is not globally clockwise");return;
        }
        var random=RandomSource.create(9526735L);
        boolean[] seen=new boolean[31];
        int distinct=0;
        for(int n=0;n<350;n++){
            int wait=BuilderClockwiseTraffic.staggerTicks(random);
            if(wait<1||wait>30){h.fail("Invalid 1..30 tick delay "+wait);return;}
            if(!seen[wait]){seen[wait]=true;distinct++;}
        }
        if(distinct<20){h.fail("Unacceptably synchronized random waits");return;}
        h.succeed();
    }
}
