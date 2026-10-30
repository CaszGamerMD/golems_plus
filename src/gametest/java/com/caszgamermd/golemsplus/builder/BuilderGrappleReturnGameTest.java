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
            if(tick>=10&&tick%5==0){
                // The real A* and collision state need the golem to
                // finish its initial spawn / ground contact ticks first.
                boolean active=BuilderGrappleAccess.rappelToward(
                        level,mob,table,depot);
                if(!active&&!sawRappel[0]&&tick>=100){
                    helper.fail("Cannot find a physical walkable rooftop rim");
                    return;
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
    @GameTest(maxTicks=500,skyAccess=true)
    public void walkingDetourMayRiseBeforeDescendingToDepot(GameTestHelper h){
        var world=h.getLevel();
        for(int x=0;x<8;x++){
            for(int z=0;z<8;z++)h.setBlock(x,0,z,Blocks.STONE);
            for(int y=1;y<=6;y++){
                h.setBlock(x,y,2,Blocks.STONE);
                h.setBlock(x,y,4,Blocks.STONE);
            }
        }
        for(int x=0;x<=1;x++)h.setBlock(x,2,3,Blocks.STONE);
        for(int x=2;x<=3;x++)h.setBlock(x,3,3,Blocks.STONE);
        h.setBlock(4,2,3,Blocks.STONE);
        for(int x=5;x<=7;x++)h.setBlock(x,1,3,Blocks.STONE);
        h.setBlock(0,1,7,WorksiteContent.BUILDER_TABLE);
        var table=(WorkerStationBlockEntity)world.getBlockEntity(
                h.absolutePos(new BlockPos(0,1,7)));
        BlockPos start=h.absolutePos(new BlockPos(1,3,3));
        BlockPos depot=h.absolutePos(new BlockPos(6,2,3));
        var mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        mob.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        mob.setItem(0,new ItemStack(WorksiteContent.GRAPPLE_HOOK));
        world.addFreshEntity(mob);
        final boolean[] climbed={false};
        h.onEachTick(()->{
            if(BuilderGrappleAccess.active(mob)){
                h.fail("Valid walking detour was interrupted by a rappel");return;
            }
            if(mob.getY()>start.getY()+.85)climbed[0]=true;
            if(h.getTick()>=10&&h.getTick()%5==0)
                BuilderGrappleAccess.rappelToward(world,mob,table,depot);
            if(mob.onGround()&&mob.distanceToSqr(depot.getX()+.5,
                    depot.getY(),depot.getZ()+.5)<.2){
                if(!climbed[0]){
                    h.fail("Fixture did not exercise the uphill detour");return;
                }
                System.out.println("[GROUND RETURN] PASS rose then descended "
                        +"without hook interruption tick="+h.getTick());
                h.succeed();return;
            }
            if(h.getTick()>420)h.fail("Ground return detour stalled "+mob.position()
                    +" "+com.caszgamermd.golemsplus.worker.WorkerNavigation.debugStatus(mob));
        });
    }
}
