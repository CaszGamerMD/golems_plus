package com.caszgamermd.golemsplus.builder;

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

/** Two real Builders independently access connected same-Y roof blocks by
 * paying for scaffolds, standing two cells clear and returning safely.
 * No flying, block-center shortcut, prebuilt scaffold or creative placing.
 */
public final class BuilderScaffoldConnectedWorkGameTest {
    @GameTest(maxTicks=1100,skyAccess=true)
    public void twoBuildersScaffoldAroundConnectedRoofAndReturn(GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        helper.setBlock(0,2,0,WorksiteContent.BUILDER_TABLE);
        BlockPos home=helper.absolutePos(new BlockPos(0,2,0));
        if(!(level.getBlockEntity(home) instanceof WorkerStationBlockEntity table)){
            helper.fail("Missing Builder Planner");return;
        }
        ServerPlayer owner=helper.makeMockServerPlayerInLevel();
        if(!table.claimIfUnowned(owner.getUUID())){
            helper.fail("Planner owner not registered");return;
        }
        // Both target cells are connected to real pre-existing stone, so
        // the isolated-air special case must NOT be required.
        helper.setBlock(5,6,3,Blocks.STONE);
        helper.setBlock(5,6,7,Blocks.STONE);
        BlockPos[] towers={helper.absolutePos(new BlockPos(2,2,2)),
                helper.absolutePos(new BlockPos(2,2,6))};
        BlockPos[] targets={helper.absolutePos(new BlockPos(5,6,2)),
                helper.absolutePos(new BlockPos(5,6,6))};
        BuilderGolem[] builders=new BuilderGolem[2];
        int[] startStock=new int[2];
        int[] phases=new int[2];
        long[] progress={helper.getTick(),helper.getTick()};
        for(int i=0;i<2;i++){
            var golem=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
            golem.setPos(towers[i].getX()+.5,towers[i].getY(),
                    towers[i].getZ()+.5);
            golem.setItem(0,new ItemStack(Items.SCAFFOLDING,32));
            level.addFreshEntity(golem);
            if(!table.registerBuilderWorker(golem.getUUID())){
                helper.fail("Second Builder was not registered");return;
            }
            builders[i]=golem;
            startStock[i]=golem.carriedCount(Items.SCAFFOLDING);
            if(BuilderScaffoldPlanA.needsTemporaryWorkPlatform(level,targets[i])){
                helper.fail("Test target should be connected to roof stone");return;
            }
        }
        helper.onEachTick(()->{
            for(int i=0;i<builders.length;i++){
                BuilderGolem mob=builders[i];
                if(!mob.isAlive()){
                    helper.fail("Scaffolding Builder died #"+i);return;
                }
                if(helper.getTick()-progress[i]>350){
                    helper.fail("Connected roof scaffolding stalled #"+i
                            +" phase="+phases[i]+" feet="+mob.position()
                            +" "+BuilderScaffoldPlanA.status(mob)
                            +" climb="+BuilderScaffolding.debugClimb(mob));
                    return;
                }
                if(phases[i]==0){
                    if(helper.getTick()%5!=0)continue;
                    var result=BuilderScaffoldPlanA.reach(level,mob,table,
                            owner,targets[i],List.of());
                    if(result==BuilderScaffoldPlanA.Result.NO_SAFE_ROUTE
                            ||result==BuilderScaffoldPlanA.Result.NEED_MATERIALS){
                        helper.fail("Connected roof refused scaffold #"+i
                                +" result="+result+" "+BuilderScaffoldPlanA.status(mob));
                        return;
                    }
                    if(result!=BuilderScaffoldPlanA.Result.READY)continue;
                    if(!BuilderScaffoldPlanA.safeTemporaryWorkPose(
                            level,mob,table,targets[i])
                            ||!BuilderPlacementSpace.horizontalClear(targets[i],mob)
                            ||mob.blockPosition().getY()!=targets[i].getY()
                            ||startStock[i]-mob.carriedCount(Items.SCAFFOLDING)<5
                            ||!level.getBlockState(targets[i]).isAir()){
                        helper.fail("Unsafe/unpaid connected-roof work pose #"+i
                                +" at "+mob.position()+" plan="
                                +BuilderScaffoldPlanA.status(mob));
                        return;
                    }
                    phases[i]=1;progress[i]=helper.getTick();
                }else if(phases[i]==1){
                    if(helper.getTick()%5==0)
                        BuilderScaffoldPlanA.returnToGround(level,mob,table);
                    if(!BuilderScaffoldPlanA.hasPlan(mob)
                            &&mob.onGround()
                            &&Math.abs(mob.getY()-towers[i].getY())<.3){
                        phases[i]=2;progress[i]=helper.getTick();
                    }
                }
            }
            if(phases[0]==2&&phases[1]==2)helper.succeed();
        });
    }
}
