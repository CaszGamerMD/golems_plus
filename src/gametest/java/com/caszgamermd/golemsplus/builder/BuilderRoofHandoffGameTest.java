package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/** Original roof geometry and measured stalled feet; prefab is fixture only. */
public final class BuilderRoofHandoffGameTest {
    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=2200,skyAccess=true)
    public void northRoofWorkerReachesUpperWorkPose(GameTestHelper h){run(h,false);}
    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=2200,skyAccess=true)
    public void southRoofWorkerReachesUpperWorkPose(GameTestHelper h){run(h,true);}
    private static void run(GameTestHelper h,boolean south){
        var world=h.getLevel();
        for(int x=4;x<=42;x++)for(int z=4;z<=42;z++)h.setBlock(x,1,z,Blocks.STONE);
        BlockPos origin=h.absolutePos(new BlockPos(8,2,8));
        world.getChunkSource().addTicketWithRadius(net.minecraft.server.level.TicketType.FORCED,
                new net.minecraft.world.level.ChunkPos(origin.getX()>>4,origin.getZ()>>4),4);
        try{
            var schematic=LitematicFileReader.read(BuilderDomeFixture.unpack());
            for(var block:schematic.blocks()){
                if(block.y()>=30||BuilderVegetation.ignoreSchematicState(block.state()))continue;
                var resolved=BuilderBlockResolver.resolve(block.state(),BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
                if(resolved!=null)world.setBlock(origin.offset(block.x(),block.y(),block.z()),resolved.state(),2);
            }
        }catch(Exception e){h.fail("Could not prepare roof fixture "+e);return;}
        var mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        mob.setPos(origin.getX()+(south?9.604:8.342),origin.getY()+26,
                origin.getZ()+(south?24.445:11.602));
        mob.setItem(0,new ItemStack(WorksiteContent.GRAPPLE_HOOK));
        world.addFreshEntity(mob);
        BlockPos goal=origin.offset(15,30,south?24:11);
        h.onEachTick(()->{
            if(!mob.isAlive()||mob.getY()<origin.getY()+24){
                h.fail("Roof handoff fell off the supported roof "+mob.position());return;
            }
            if(h.getTick()>=10&&h.getTick()%5==0)
                BuilderGrappleAccess.reachWork(world,mob,null,goal);
            if(mob.onGround()&&!BuilderGrappleAccess.active(mob)
                    &&BuilderConstructionPlanner.safeStand(world,mob,goal,mob.blockPosition())
                    &&BuilderPlacementSpace.horizontalClear(goal,mob)
                    &&BuilderPlacementSpace.placementHeight(goal,mob)){
                h.succeed();return;
            }
            if(h.getTick()>2100)h.fail("Roof handoff stalled at "+mob.position()
                    +" "+WorkerNavigation.debugStatus(mob)+" "+BuilderGrappleAccess.debug(mob));
        });
    }
}
