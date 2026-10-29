package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/** Real collision-world previews: anchors must lead to useful work terrain. */
public final class BuilderGrappleWorkPoseGameTest {
    private static BuilderGolem worker(GameTestHelper h){
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            h.setBlock(x,1,z,Blocks.STONE);
        var mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,h.getLevel());
        BlockPos start=h.absolutePos(new BlockPos(5,2,3));
        mob.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        mob.setItem(0,new ItemStack(WorksiteContent.GRAPPLE_HOOK));
        h.getLevel().addFreshEntity(mob);
        return mob;
    }
    @GameTest(maxTicks=60,skyAccess=true)
    public void isolatedAnchorIsNotConstructionAccess(GameTestHelper h){
        var mob=worker(h);
        h.setBlock(3,5,3,Blocks.STONE);
        BlockPos target=h.absolutePos(new BlockPos(4,6,3));
        h.onEachTick(()->{
            if(h.getTick()!=16)return;
            if(!BuilderGrappleAccess.canPlanAscent(h.getLevel(),mob,target)){
                h.fail("Fixture must have a physically valid generic anchor");return;
            }
            if(BuilderGrappleAccess.canPlanWorkAscent(h.getLevel(),mob,target)){
                h.fail("Isolated anchor has no legal work stance or exit");return;
            }
            if(BuilderGrappleAccess.active(mob)||mob.isNoGravity()){
                h.fail("Preview changed actual movement");return;
            }
            h.succeed();
        });
    }
    @GameTest(maxTicks=60,skyAccess=true)
    public void connectedPlatformProvidesConstructionAccess(GameTestHelper h){
        var mob=worker(h);
        for(int x=1;x<=3;x++)for(int z=2;z<=4;z++)
            h.setBlock(x,5,z,Blocks.STONE);
        BlockPos target=h.absolutePos(new BlockPos(4,6,3));
        h.onEachTick(()->{
            if(h.getTick()!=16)return;
            if(!BuilderGrappleAccess.canPlanWorkAscent(h.getLevel(),mob,target)){
                h.fail("Connected platform has safe work poses and real rope entry");return;
            }
            if(BuilderGrappleAccess.active(mob)||mob.isNoGravity()){
                h.fail("Preview changed actual movement");return;
            }
            h.succeed();
        });
    }
}
