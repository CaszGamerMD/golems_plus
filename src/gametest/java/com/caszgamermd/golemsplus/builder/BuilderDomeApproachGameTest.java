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
    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=700,skyAccess=true)
    public void courtyardWorkerWalksToWestRopeEntry(GameTestHelper h){run(h,false);}
    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=700,skyAccess=true)
    public void courtyardWorkerWalksToEastRopeEntry(GameTestHelper h){run(h,true);}
    private void run(GameTestHelper h,boolean east){
        var world=h.getLevel();
        for(int x=4;x<=42;x++)for(int z=4;z<=42;z++)h.setBlock(x,1,z,Blocks.STONE);
        BlockPos origin=h.absolutePos(new BlockPos(8,2,8));
        try{
            var schematic=LitematicFileReader.read(BuilderDomeFixture.unpack());
            for(var block:schematic.blocks()){
                if(block.y()>=24||BuilderVegetation.ignoreSchematicState(block.state()))continue;
                var resolved=BuilderBlockResolver.resolve(block.state(),BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
                if(resolved!=null)world.setBlock(origin.offset(block.x(),block.y(),block.z()),resolved.state(),2);
            }
        }catch(Exception e){h.fail("Could not prepare original geometry "+e);return;}
        var mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        mob.setPos(origin.getX()+16.711,origin.getY()+1,origin.getZ()+4.526);
        world.addFreshEntity(mob);
        BlockPos goal=origin.offset(east?33:-2,0,21);
        h.onEachTick(()->{
            if(h.getTick()>=10&&h.getTick()%5==0)WorkerNavigation.routeToExact(world,mob,goal,1.0);
            if(mob.onGround()&&mob.distanceToSqr(goal.getX()+.5,goal.getY(),goal.getZ()+.5)<.08*.08){
                System.out.println("[DOME APPROACH] PASS east="+east+" tick="+h.getTick());
                h.succeed();return;
            }
            if(h.getTick()>620)h.fail("Dome approach east="+east+" at="+mob.position()
                    +" "+WorkerNavigation.debugStatus(mob)+" "
                    +WorkerNavigation.diagnoseExecutableStand(world,mob,List.of(goal)));
        });
    }
}
