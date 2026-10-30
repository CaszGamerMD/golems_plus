package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/** Navigation-only reproduction using the original dome's existing geometry.
 * Prefab blocks are a test fixture; movement still uses the real controller.
 */
public final class BuilderDomeApproachGameTest {
    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=3200,skyAccess=true)
    public void courtyardWorkerWalksToOutsideRopeEntries(GameTestHelper h){run(h,true);}
    private void run(GameTestHelper h,boolean east){
        var world=h.getLevel();
        for(int x=4;x<=42;x++)for(int z=4;z<=42;z++)h.setBlock(x,1,z,Blocks.STONE);
        BlockPos origin=h.absolutePos(new BlockPos(8,2,8));
        // Full-size navigation fixtures need entity simulation beyond
        // the small default test room; the production dome gets these
        // tickets from its active real Planner.
        world.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.FORCED,
                new net.minecraft.world.level.ChunkPos(origin.getX()>>4,origin.getZ()>>4),4);
        try{
            var schematic=LitematicFileReader.read(BuilderDomeFixture.unpack());
            for(var block:schematic.blocks()){
                if(block.y()>=24||BuilderVegetation.ignoreSchematicState(block.state()))continue;
                var resolved=BuilderBlockResolver.resolve(block.state(),BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
                if(resolved!=null)world.setBlock(origin.offset(block.x(),block.y(),block.z()),resolved.state(),2);
            }
        }catch(Exception e){h.fail("Could not prepare original geometry "+e);return;}
        BuilderGolem[] current={new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world)};
        current[0].setPos(origin.getX()+16.711,origin.getY()+1,origin.getZ()+4.526);
        world.addFreshEntity(current[0]);
        int[] phase={0};long[] started={0};
        h.onEachTick(()->{
            var mob=current[0];
            BlockPos goal=phase[0]==2?origin.offset(15,24,27):origin.offset(phase[0]==1?-2:33,0,21);
            if(h.getTick()>=10&&h.getTick()%5==0){
                if(phase[0]==2)BuilderGrappleAccess.reachWork(world,mob,null,goal);
                else WorkerNavigation.routeToExact(world,mob,goal,1.0);
            }
            if(mob.onGround()&&!BuilderGrappleAccess.active(mob)
                    &&(phase[0]==2?BuilderPlacementSpace.placementHeight(goal,mob)
                        &&BuilderPlacementSpace.horizontalClear(goal,mob)
                        &&mob.distanceToSqr(goal.getX()+.5,goal.getY()+.5,goal.getZ()+.5)<14:
                        mob.distanceToSqr(goal.getX()+.5,goal.getY(),goal.getZ()+.5)<.08*.08)){
                System.out.println("[DOME APPROACH] PASS phase="+phase[0]+" tick="+h.getTick());
                if(phase[0]==2){h.succeed();return;}
                phase[0]++;started[0]=h.getTick();
                current[0]=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
                current[0].setPos(origin.getX()+16.711,origin.getY()+1,origin.getZ()+4.526);
                current[0].setItem(0,new net.minecraft.world.item.ItemStack(WorksiteContent.GRAPPLE_HOOK));
                world.addFreshEntity(current[0]);return;
            }
            if(h.getTick()-started[0]>1000)h.fail("Dome approach phase="+phase[0]+" at="+mob.position()
                    +" entityTicks="+mob.tickCount+" "+WorkerNavigation.debugStatus(mob)+" "
                    +WorkerNavigation.diagnoseExecutableStand(world,mob,List.of(goal)));
        });
    }
}
