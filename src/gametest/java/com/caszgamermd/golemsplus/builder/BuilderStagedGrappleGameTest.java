package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorksiteContent;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/** Nested platforms block a direct rope but allow two physical climbs. */
public final class BuilderStagedGrappleGameTest {
    @GameTest(maxTicks=700,skyAccess=true)
    public void reachesSafeWorkPoseViaRealIntermediateLedge(GameTestHelper h){
        var world=h.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            h.setBlock(x,1,z,Blocks.STONE);
        for(int x=2;x<=6;x++)for(int z=1;z<=5;z++)
            h.setBlock(x,3,z,Blocks.STONE);
        for(int x=3;x<=5;x++)for(int z=2;z<=4;z++)
            h.setBlock(x,5,z,Blocks.STONE);
        h.setBlock(0,2,0,WorksiteContent.BUILDER_TABLE);
        var table=(WorkerStationBlockEntity)world.getBlockEntity(
                h.absolutePos(new BlockPos(0,2,0)));
        BlockPos start=h.absolutePos(new BlockPos(7,2,3));
        BlockPos target=h.absolutePos(new BlockPos(6,6,3));
        var mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        mob.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        mob.setItem(0,new ItemStack(WorksiteContent.GRAPPLE_HOOK));
        world.addFreshEntity(mob);
        final boolean[] sawIntermediate={false};
        final double[] previousY={mob.getY()};
        h.onEachTick(()->{
            if(previousY[0]-mob.getY()>.65||!mob.isAlive()){
                h.fail("Staged climb lost controlled physical movement");return;
            }
            previousY[0]=mob.getY();
            if(mob.onGround()&&mob.blockPosition().getY()==start.getY()+2
                    &&!BuilderGrappleAccess.active(mob))sawIntermediate[0]=true;
            if(h.getTick()>=10&&h.getTick()%5==0){
                var result=BuilderGrappleAccess.reachWork(world,mob,table,target);
                if(result==BuilderGrappleAccess.Result.NO_SAFE_ANCHOR
                        ||result==BuilderGrappleAccess.Result.NEED_HOOK){
                    h.fail("Validated two-stage access rejected "+result
                            +" feet="+mob.position());return;
                }
            }
            if(mob.onGround()&&!BuilderGrappleAccess.active(mob)
                    &&BuilderPlacementSpace.horizontalClear(target,mob)
                    &&BuilderConstructionPlanner.safeStand(
                            world,mob,target,mob.blockPosition())){
                if(!sawIntermediate[0]
                        ||mob.carriedCount(WorksiteContent.GRAPPLE_HOOK)!=1){
                    h.fail("Must use intermediate ledge and retain reusable hook");return;
                }
                System.out.println("[STAGED GRAPPLE] PASS two real climbs to safe work pose "
                        +"tick="+h.getTick());
                h.succeed();return;
            }
            if(h.getTick()>620)h.fail("Staged grapple stalled "+mob.position()
                    +" "+BuilderGrappleAccess.debug(mob));
        });
    }
}
