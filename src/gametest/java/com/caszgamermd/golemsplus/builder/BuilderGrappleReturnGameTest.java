package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorksiteContent;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/** A finished Builder can first WALK to a real nearby rooftop rim and
 * then physically rappel onto actual ground, retaining the reusable hook.
 */
public final class BuilderGrappleReturnGameTest {
    @GameTest(maxTicks=700,skyAccess=true)
    public void idleBuilderWalksToRimAndRappelsToOnSite(
            GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        // Roof platform, not a tower directly beneath the Builder.
        for(int x=2;x<=4;x++)for(int z=2;z<=4;z++)
            helper.setBlock(x,6,z,Blocks.STONE);
        helper.setBlock(0,2,0,WorksiteContent.BUILDER_TABLE);
        BlockPos home=helper.absolutePos(new BlockPos(0,2,0));
        if(!(level.getBlockEntity(home) instanceof WorkerStationBlockEntity table)){
            helper.fail("Missing physical Planner");return;
        }
        BlockPos start=helper.absolutePos(new BlockPos(3,7,3));
        BlockPos depot=helper.absolutePos(new BlockPos(6,2,3));
        BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        mob.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        mob.setItem(0,new ItemStack(WorksiteContent.GRAPPLE_HOOK));
        level.addFreshEntity(mob);
        final boolean[] sawWalk={false},sawRappel={false};
        final double[] previousY={mob.getY()};
        helper.onEachTick(()->{
            long tick=helper.getTick();
            if(!mob.isAlive()){
                helper.fail("Builder disappeared");return;
            }
            if(previousY[0]-mob.getY()>.65){
                helper.fail("Uncontrolled fall from platform "+mob.position());return;
            }
            previousY[0]=mob.getY();
            if(mob.blockPosition().getY()==start.getY()
                    &&(mob.blockPosition().getX()!=start.getX()
                       ||mob.blockPosition().getZ()!=start.getZ()))
                sawWalk[0]=true;
            if(mob.blockPosition().getY()<start.getY()-1)
                sawRappel[0]=true;
            if(tick%5==0){
                boolean active=BuilderGrappleAccess.rappelToward(
                        level,mob,table,depot);
                if(!active&&!sawRappel[0]){
                    helper.fail("Cannot reach nearby ledge with grapple");return;
                }
            }
            if(sawWalk[0]&&sawRappel[0]&&mob.onGround()
                    &&mob.blockPosition().getY()==depot.getY()
                    &&!BuilderGrappleAccess.active(mob)){
                if(mob.carriedCount(WorksiteContent.GRAPPLE_HOOK)!=1){
                    helper.fail("Reusable hook vanished");return;
                }
                System.out.println("[GRAPPLE RETURN] PASS walked to roof edge, "
                        +"then rappelled safely in "+tick+" ticks");
                helper.succeed();
            }
        });
    }
}
