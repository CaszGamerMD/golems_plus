package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/** Physical Fabric 26.2 regression: a 0.65-wide Builder cannot place a
 * block until its center is at least 0.8 blocks outside the nearest
 * edge of that future cube. Use actual velocity and real collisions.
 */
public final class BuilderPlacementClearanceGameTest {
    @GameTest(maxTicks=260,skyAccess=true)
    public void physicallyStepsAtLeastPointEightFromOwnPlacementEdge(
            GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        helper.setBlock(0,2,0,WorksiteContent.BUILDER_TABLE);
        BlockPos planner=helper.absolutePos(new BlockPos(0,2,0));
        if(!(level.getBlockEntity(planner) instanceof WorkerStationBlockEntity table)){
            helper.fail("Missing actual physical Planner");return;
        }
        BlockPos target=helper.absolutePos(new BlockPos(3,2,3));
        BuilderGolem builder=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        // On the adjacent tile but its real shoulder overlaps the future
        // target cell, as happened repeatedly in the dome log.
        builder.setPos(target.getX()+1.04,target.getY(),target.getZ()+.5);
        level.addFreshEntity(builder);
        if(!table.registerBuilderWorker(builder.getUUID())){
            helper.fail("Could not register real Builder");return;
        }
        final boolean[] sawBlock={false};
        final double[] previousX={builder.getX()};
        helper.onEachTick(()->{
            if(!builder.isAlive()){
                helper.fail("Real Builder died during sidestep");return;
            }
            if(Math.abs(builder.getX()-previousX[0])>.29){
                helper.fail("Discontinuous horizontal sidestep (teleport?) dx="
                        +(builder.getX()-previousX[0]));return;
            }
            previousX[0]=builder.getX();
            if(builder.blockPosition().getY()!=target.getY()){
                helper.fail("Builder left the actual ground layer");return;
            }
            if(helper.getTick()%5!=0)return;
            boolean ready=BuilderPlacementSpace.ready(level,builder,table,target);
            if(!ready){sawBlock[0]=true;return;}
            double edgeGap=builder.getX()-(target.getX()+1.0);
            if(!sawBlock[0]||edgeGap<.80-.015){
                helper.fail("Placement was permitted too close: edgeGap="
                        +edgeGap+" builder="+builder.position());return;
            }
            if(!level.setBlock(target,Blocks.IRON_BLOCK.defaultBlockState(),3)){
                helper.fail("Real target refused placement");return;
            }
            if(!builder.getBoundingBox().intersects(
                    new net.minecraft.world.phys.AABB(target).inflate(.1,0,.1))){
                System.out.println("[CLEARANCE] PASS physical center-to-edge="
                        +edgeGap+" (>0.8), no teleport or self collision, tick="
                        +helper.getTick());
                helper.succeed();
            }else helper.fail("Builder remains inside the placed solid block");
        });
    }
}
