package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/** A real body supported at a platform corner, with its tile center over air. */
public final class BuilderCornerRejoinGameTest {
    @GameTest(maxTicks=220,skyAccess=true)
    public void exactWorkRouteRejoinsCornerSupport(GameTestHelper h){runCase(h,true);}
    @GameTest(maxTicks=220,skyAccess=true)
    public void ordinaryReturnRouteRejoinsCornerSupport(GameTestHelper h){runCase(h,false);}
    private static void runCase(GameTestHelper h,boolean exact){
        var world=h.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            h.setBlock(x,0,z,Blocks.STONE);
        for(int x=1;x<=4;x++)for(int z=1;z<=4;z++)
            h.setBlock(x,2,z,Blocks.STONE);
        BlockPos corner=h.absolutePos(new BlockPos(5,3,5));
        BlockPos goal=h.absolutePos(new BlockPos(2,3,2));
        var mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        mob.setPos(corner.getX()+.12,corner.getY(),corner.getZ()+.12);
        world.addFreshEntity(mob);
        final boolean[] checked={false};
        h.onEachTick(()->{
            if(Math.abs(mob.getY()-corner.getY())>.04||mob.isNoGravity()){
                h.fail("Rejoin must stay on the real platform without falling/flying: "
                        +mob.position()+" grounded="+mob.onGround());return;
            }
            if(h.getTick()<10)return;
            if(!checked[0]){
                if(!mob.onGround()||WorkerNavigation.canStandOn(world,mob,mob.blockPosition())){
                    h.fail("Fixture must be grounded with unsupported tile center");return;
                }
                if(WorkerNavigation.selectExecutableStand(world,mob,List.of(goal))==null){
                    h.fail("Trained preview rejected supported corner re-entry");return;
                }
                checked[0]=true;
            }
            if(h.getTick()%5==0){
                if(exact)WorkerNavigation.routeToExact(world,mob,goal,1.0);
                else WorkerNavigation.routeTo(world,mob,goal,1.0);
            }
            if(mob.onGround()&&mob.distanceToSqr(goal.getX()+.5,
                    goal.getY(),goal.getZ()+.5)<.16){
                System.out.println("[CORNER REJOIN] PASS "+(exact?"exact":"ordinary")
                        +" grounded re-entry tick="+h.getTick());
                h.succeed();return;
            }
            if(h.getTick()>180)h.fail("Corner re-entry stalled "+mob.position()
                    +" "+WorkerNavigation.debugStatus(mob));
        });
    }
}
