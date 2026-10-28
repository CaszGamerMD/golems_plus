package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Real Fabric 26.2 entity traversal, not a simulated path: Builder
 * constructs the axis-three shaft, pays for two sideways scaffolds,
 * physically walks onto the bridge at work Y, then returns and descends.
 */
public final class BuilderScaffoldPlanAGameTest {
    @GameTest(maxTicks=900,skyAccess=true)
    public void buildsThreeAwayThenBridgesAtExactWorkHeightAndReturns(
            GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        helper.setBlock(0,2,0,WorksiteContent.BUILDER_TABLE);
        BlockPos home=helper.absolutePos(new BlockPos(0,2,0));
        if(!(level.getBlockEntity(home) instanceof WorkerStationBlockEntity table)){
            helper.fail("Missing physical Planner");return;
        }
        ServerPlayer owner=helper.makeMockServerPlayerInLevel();
        if(!table.claimIfUnowned(owner.getUUID())){
            helper.fail("Planner owner not registered");return;
        }
        BlockPos tower=helper.absolutePos(new BlockPos(2,2,3));
        BlockPos target=helper.absolutePos(new BlockPos(5,6,3));
        BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        // This is a direct movement controller test: do not assign a
        // live construction job to a Planner with activeOrder=null,
        // which would simultaneously issue an early-return/cleanup
        // command and fight the exact motion being tested.
        mob.setPos(tower.getX()+.5,tower.getY(),tower.getZ()+.5);
        mob.setItem(0,new ItemStack(Items.SCAFFOLDING,32));
        level.addFreshEntity(mob);
        if(!table.registerBuilderWorker(mob.getUUID())){
            helper.fail("Builder not registered");return;
        }
        final int[] phase={0},initial={mob.carriedCount(Items.SCAFFOLDING)};
        final long[] since={helper.getTick()};
        helper.onEachTick(()->{
            if(!mob.isAlive()){
                helper.fail("Real Builder disappeared");return;
            }
            if(helper.getTick()-since[0]>270){
                helper.fail("Plan A stuck phase="+phase[0]
                        +" position="+mob.position()+" plan="+
                        BuilderScaffoldPlanA.status(mob)
                        +" climb="+BuilderScaffolding.debugClimb(mob)
                        +" nav="+WorkerNavigation.debugStatus(mob)
                        +" placed="+table.scaffoldPositions(mob.getUUID()).size()
                        +" first="+level.getBlockState(tower.east().atY(target.getY()-1))
                        +" second="+level.getBlockState(tower.east(2).atY(target.getY()-1))
                        +" support1="+level.getBlockState(tower.east().atY(target.getY()-2))
                        +" support2="+level.getBlockState(tower.east(2).atY(target.getY()-2))
                        +" below="+level.getBlockState(mob.blockPosition())
                        +" vy="+mob.getDeltaMovement().y);
                return;
            }
            if(phase[0]==0){
                if(helper.getTick()%5!=0)return;
                var state=BuilderScaffoldPlanA.reach(
                        level,mob,table,owner,target,List.of());
                if(state==BuilderScaffoldPlanA.Result.NO_SAFE_ROUTE
                        ||state==BuilderScaffoldPlanA.Result.NEED_MATERIALS){
                    helper.fail("Physical bridge rejected: "+state+
                            " "+BuilderScaffoldPlanA.status(mob));
                    return;
                }
                if(state!=BuilderScaffoldPlanA.Result.READY)return;
                // Must stand at the same Y as the block to place.
                if(mob.blockPosition().getY()!=target.getY()){
                    helper.fail("Wrong work Y: "+mob.position());return;
                }
                BlockPos bridgeA=tower.east().atY(target.getY()-1);
                BlockPos bridgeB=tower.east(2).atY(target.getY()-1);
                if(!level.getBlockState(bridgeA).is(Blocks.SCAFFOLDING)
                        ||!level.getBlockState(bridgeB).is(Blocks.SCAFFOLDING)
                        ||!level.getBlockState(bridgeA.below()).is(Blocks.SCAFFOLDING)
                        ||!level.getBlockState(bridgeB.below()).is(Blocks.SCAFFOLDING)
                        ||level.getBlockState(bridgeA).getValue(
                                net.minecraft.world.level.block.ScaffoldingBlock.BOTTOM)
                        ||level.getBlockState(bridgeB).getValue(
                                net.minecraft.world.level.block.ScaffoldingBlock.BOTTOM)
                        ||!table.scaffoldPositions(mob.getUUID()).contains(bridgeA)
                        ||!table.scaffoldPositions(mob.getUUID()).contains(bridgeB)
                        ||!table.scaffoldPositions(mob.getUUID()).contains(bridgeA.below())
                        ||!table.scaffoldPositions(mob.getUUID()).contains(bridgeB.below())
                        ||Math.abs(mob.getX()-bridgeB.getX()-.5)>.4){
                    helper.fail("Builder didn't physically reach a real paid bridge: "+
                            mob.position()+" "+BuilderScaffoldPlanA.status(mob));
                    return;
                }
                if(initial[0]-mob.carriedCount(Items.SCAFFOLDING)<8){
                    helper.fail("Scaffold items weren't genuinely consumed");return;
                }
                phase[0]=1;since[0]=helper.getTick();
            }else if(phase[0]==1){
                if(helper.getTick()%5==0)
                    BuilderScaffoldPlanA.returnToGround(level,mob,table);
                if(!BuilderScaffoldPlanA.hasPlan(mob)
                        &&mob.onGround()
                        &&Math.abs(mob.getY()-tower.getY())<.25){
                    System.out.println("[PLAN A] PASS real shaft+supported 2-cell bridge"
                            +" feetY="+target.getY()+" safe return tick="+helper.getTick());
                    helper.succeed();
                }
            }
        });
    }
}
