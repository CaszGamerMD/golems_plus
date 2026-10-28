package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorksiteContent;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/** Real 26.2 server Builder: no teleport, no scaffold, real collision,
 * reusable paid inventory hook, ascent -> ledge -> safe rappel.
 */
public final class BuilderGrappleGameTest {
    @GameTest(maxTicks=700,skyAccess=true)
    public void realBuilderClimbsAndRappelsWithoutScaffolds(
            GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        helper.setBlock(0,2,0,WorksiteContent.BUILDER_TABLE);
        BlockPos home=helper.absolutePos(new BlockPos(0,2,0));
        if(!(level.getBlockEntity(home) instanceof WorkerStationBlockEntity table)){
            helper.fail("Missing planner");return;
        }
        BlockPos anchor=helper.absolutePos(new BlockPos(4,6,3));
        BlockPos ground=helper.absolutePos(new BlockPos(5,2,3));
        BlockPos top=anchor.above();
        helper.setBlock(4,6,3,Blocks.PURPLE_WOOL);
        helper.setBlock(5,1,3,Blocks.LIME_WOOL);
        BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        mob.setPos(ground.getX()+.5,ground.getY(),ground.getZ()+.5);
        mob.setItem(0,new ItemStack(WorksiteContent.GRAPPLE_HOOK));
        level.addFreshEntity(mob);
        final int[] phase={0};
        final long[] since={helper.getTick()};
        final double[] previousY={mob.getY()};
        helper.onEachTick(()->{
            long now=helper.getTick();
            double drop=previousY[0]-mob.getY();
            previousY[0]=mob.getY();
            if(!mob.isAlive()){
                helper.fail("Real Builder disappeared");return;
            }
            if(phase[0]==2&&drop>.6){
                helper.fail("Uncontrolled rappel drop="+drop
                        +" state="+BuilderGrappleAccess.debug(mob));return;
            }
            if(now-since[0]>260){
                helper.fail("Grapple stuck phase="+phase[0]+" pos="
                        +mob.position()+" onGround="+mob.onGround()
                        +" state="+BuilderGrappleAccess.debug(mob)
                        +" velocity="+mob.getDeltaMovement());
                return;
            }
            if(phase[0]==0){
                if(now%5!=0)return;
                var access=BuilderGrappleAccess.reach(level,mob,table,top);
                if(access==BuilderGrappleAccess.Result.NEED_HOOK
                        ||access==BuilderGrappleAccess.Result.NO_SAFE_ANCHOR){
                    helper.fail("Valid vertical anchor rejected "+access);return;
                }
                if(mob.blockPosition().getY()==top.getY()
                        &&mob.onGround()
                        &&!BuilderGrappleAccess.active(mob)){
                    if(mob.carriedCount(WorksiteContent.GRAPPLE_HOOK)!=1){
                        helper.fail("Grapple consumed unexpectedly");return;
                    }
                    if(!level.getBlockState(anchor).is(Blocks.PURPLE_WOOL)){
                        helper.fail("Grapple changed anchor block");return;
                    }
                    phase[0]=1;since[0]=now;
                    System.out.println("[GRAPPLE TEST] real ascent PASSED tick="+now);
                }
            }else if(phase[0]==1){
                if(!BuilderGrappleAccess.rappelToward(level,mob,table,ground)){
                    helper.fail("Valid safe rappel rejected");return;
                }
                phase[0]=2;since[0]=now;
            }else{
                if(mob.onGround()
                        &&mob.blockPosition().getY()==ground.getY()
                        &&!BuilderGrappleAccess.active(mob)){
                    if(mob.carriedCount(WorksiteContent.GRAPPLE_HOOK)!=1
                            ||!level.getBlockState(anchor).is(Blocks.PURPLE_WOOL)){
                        helper.fail("Rappel consumed tool or changed construction");
                        return;
                    }
                    System.out.println("[GRAPPLE TEST] PASS real hook up/down "
                            +"no scaffolds, feet="+mob.position()+" ticks="+now);
                    helper.succeed();
                }
            }
        });
    }

    @GameTest(maxTicks=80,skyAccess=true)
    public void rejectsAnchorMoreThanTwentyBlocksAboveStart(
            GameTestHelper helper){
        ServerLevel world=helper.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        helper.setBlock(0,2,0,WorksiteContent.BUILDER_TABLE);
        var table=(WorkerStationBlockEntity)world.getBlockEntity(
                helper.absolutePos(new BlockPos(0,2,0)));
        BlockPos anchor=helper.absolutePos(new BlockPos(4,23,3));
        helper.setBlock(4,23,3,Blocks.PURPLE_WOOL);
        BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        BlockPos start=helper.absolutePos(new BlockPos(5,2,3));
        mob.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        mob.setItem(0,new ItemStack(WorksiteContent.GRAPPLE_HOOK));
        world.addFreshEntity(mob);
        var result=BuilderGrappleAccess.reach(world,mob,table,anchor.above());
        if(result!=BuilderGrappleAccess.Result.NO_SAFE_ANCHOR){
            helper.fail("Accepted grapple anchor "+(anchor.getY()+1-start.getY())
                    +" blocks above current feet: "+result);
            return;
        }
        helper.succeed();
    }
}
