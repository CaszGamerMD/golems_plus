package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * Uses real 0.65-wide Builder entities and their server-side placement
 * clearance controller. This is a feet-Y+1 working position, not permission
 * to schedule schematic layer Y+1 before finishing the current layer.
 */
public final class BuilderPlaceOneBelowGameTest {
    @GameTest(maxTicks=120,skyAccess=true)
    public void groundedBuilderPlacesBelowFromPointEightSideMargin(
            GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        helper.setBlock(0,2,0,WorksiteContent.BUILDER_TABLE);
        // Solid shoulder ledge at the target's SAME Y. The Builder
        // stands one block above the AIR target with real ground below.
        helper.setBlock(4,2,3,Blocks.STONE);
        BlockPos target=helper.absolutePos(new BlockPos(3,2,3));
        var table=(WorkerStationBlockEntity)level.getBlockEntity(
                helper.absolutePos(new BlockPos(0,2,0)));
        BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        mob.setPos(target.getX()+1.85,target.getY()+1,
                target.getZ()+.5);
        level.addFreshEntity(mob);
        if(!table.registerBuilderWorker(mob.getUUID())){
            helper.fail("Real Builder registration failed");return;
        }
        helper.onEachTick(()->{
            if(helper.getTick()<6)return;
            if(!mob.onGround()){
                helper.fail("Builder did not stand on real ledge");return;
            }
            if(!BuilderPlacementSpace.placementHeight(target,mob)
                    ||!BuilderPlacementSpace.horizontalClear(target,mob)
                    ||!BuilderPlacementSpace.ready(level,mob,table,target)){
                helper.fail("Rejected legal downward one-block placement");return;
            }
            double gap=mob.getX()-(target.getX()+1.0);
            if(gap<.8-0.015||mob.blockPosition().getY()!=target.getY()+1){
                helper.fail("Wrong feet Y or horizontal gap: "+gap);return;
            }
            if(!level.setBlock(target,Blocks.IRON_BLOCK.defaultBlockState(),3)){
                helper.fail("Actual world block placement failed");return;
            }
            if(mob.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(target))){
                helper.fail("Builder collided with newly placed block");return;
            }
            System.out.println("[BELOW Y] PASS grounded at Y+1 placed at Y; "
                    +"center-to-edge XZ="+gap+" (>=0.8) and real collision clear");
            helper.succeed();
        });
    }

    @GameTest(maxTicks=220,skyAccess=true)
    public void tooCloseOnUpperLedgeMustStepBackBeforePlacingBelow(
            GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        helper.setBlock(0,2,0,WorksiteContent.BUILDER_TABLE);
        helper.setBlock(4,2,3,Blocks.STONE);
        BlockPos target=helper.absolutePos(new BlockPos(3,2,3));
        var table=(WorkerStationBlockEntity)level.getBlockEntity(
                helper.absolutePos(new BlockPos(0,2,0)));
        BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        // 0.60 blocks outside X edge is NOT the required 0.80.
        mob.setPos(target.getX()+1.60,target.getY()+1,target.getZ()+.5);
        level.addFreshEntity(mob);
        if(!table.registerBuilderWorker(mob.getUUID())){
            helper.fail("Real Builder registration failed");return;
        }
        final double[] prevX={mob.getX()};
        final boolean[] refused={false};
        helper.onEachTick(()->{
            double dx=Math.abs(mob.getX()-prevX[0]);
            prevX[0]=mob.getX();
            if(dx>.30){
                helper.fail("Teleport/discontinuous sidestep "+dx);return;
            }
            if(helper.getTick()<6)return;
            if(!mob.onGround()||!BuilderPlacementSpace.placementHeight(target,mob)){
                helper.fail("Worker lost real support under upper ledge");
                return;
            }
            boolean ready=BuilderPlacementSpace.ready(level,mob,table,target);
            double gap=mob.getX()-(target.getX()+1.0);
            if(!ready){
                refused[0]=true;
                if(!level.getBlockState(target).isAir())
                    helper.fail("Target changed before clearance");
                return;
            }
            if(!refused[0]||gap<.8-.015
                    ||!BuilderPlacementSpace.horizontalClear(target,mob)){
                helper.fail("Allowed unsafe below-Y placement at XZ gap="+gap);
                return;
            }
            if(!level.setBlock(target,Blocks.IRON_BLOCK.defaultBlockState(),3)){
                helper.fail("Physical below-Y placement failed");return;
            }
            if(mob.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(target))){
                helper.fail("Collision with completed lower block");return;
            }
            System.out.println("[BELOW Y] PASS refused 0.60 gap; physically "
                    +"sidestepped to "+gap+" before placing at Y-1");
            helper.succeed();
        });
    }

    @GameTest(maxTicks=100,skyAccess=true)
    public void twoBlocksBelowIsNotLegalDirectPlacement(
            GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        helper.setBlock(0,2,0,WorksiteContent.BUILDER_TABLE);
        helper.setBlock(4,3,3,Blocks.STONE);
        BlockPos target=helper.absolutePos(new BlockPos(3,2,3));
        BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        mob.setPos(target.getX()+1.86,target.getY()+2,target.getZ()+.5);
        level.addFreshEntity(mob);
        helper.onEachTick(()->{
            if(helper.getTick()<6)return;
            if(!mob.onGround()){
                helper.fail("Two-Y test fixture has no physical ground");return;
            }
            if(BuilderPlacementSpace.placementHeight(target,mob)){
                helper.fail("Accepted unsupported two-block-down work stance");
                return;
            }
            System.out.println("[BELOW Y] PASS cannot place directly from Y+2");
            helper.succeed();
        });
    }
}
