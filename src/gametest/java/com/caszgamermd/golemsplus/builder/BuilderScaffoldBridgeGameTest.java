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

/** Physical trial for generalized horizontal scaffold planning:
 * a real Builder starts on its verified vertical ladder, pays for an
 * aerial footbridge across a gap, walks it WITHOUT jumping, reverses
 * the same placed bridge, descends, and dismounts on real stone.
 */
public final class BuilderScaffoldBridgeGameTest {
    @GameTest(maxTicks=1300,skyAccess=true)
    public void buildsCrossesRetracesAndDescendsBridge(GameTestHelper helper){
        run(helper,false);
    }
    @GameTest(maxTicks=1300,skyAccess=true)
    public void buildsTurningBridgeAroundSolidObstacle(GameTestHelper helper){
        run(helper,true);
    }

    private static void run(GameTestHelper helper,boolean obstructed){
        ServerLevel level=helper.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            if(x<2||x>5||z!=3)helper.setBlock(x,1,z,Blocks.STONE);
        // Obstructing an aerial cell forces a corner; the valid answer
        // is NOT a memorized straight bridge from the other test.
        if(obstructed)helper.setBlock(2,6,3,Blocks.COBBLESTONE);
        helper.setBlock(0,2,0,WorksiteContent.BUILDER_TABLE);
        BlockPos home=helper.absolutePos(new BlockPos(0,2,0));
        if(!(level.getBlockEntity(home) instanceof WorkerStationBlockEntity table)){
            helper.fail("No Builder Table");return;
        }
        ServerPlayer owner=helper.makeMockServerPlayerInLevel();
        table.claimIfUnowned(owner.getUUID());
        BlockPos base=helper.absolutePos(new BlockPos(1,2,3));
        for(int y=2;y<=6;y++)
            helper.setBlock(1,y,3,Blocks.SCAFFOLDING);
        BuilderGolem builder=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        BlockPos top=helper.absolutePos(new BlockPos(1,7,3));
        builder.setPos(top.getX()+.5,top.getY(),top.getZ()+.5);
        builder.setItem(0,new ItemStack(Items.SCAFFOLDING,24));
        level.addFreshEntity(builder);
        if(!table.registerBuilderWorker(builder.getUUID())){
            helper.fail("Builder cannot own scaffold");return;
        }
        for(int y=2;y<=6;y++){
            if(!table.trackScaffold(builder.getUUID(),
                    helper.absolutePos(new BlockPos(1,y,3)))){
                helper.fail("Cannot claim physically built tower");return;
            }
        }
        BlockPos goal=helper.absolutePos(new BlockPos(7,7,3));
        int[] stage={0};
        int initial=builder.carriedCount(Items.SCAFFOLDING);
        long[] last={helper.getTick()};
        double[] highest={builder.getY()};
        helper.onEachTick(()->{
            if(!builder.isAlive()){
                helper.fail("Real Builder died");return;
            }
            highest[0]=Math.max(highest[0],builder.getY());
            if(helper.getTick()-last[0]>350){
                helper.fail("Bridge stuck stage="+stage[0]
                        +" pos="+builder.position()
                        +" nav="+com.caszgamermd.golemsplus.worker.WorkerNavigation
                            .debugStatus(builder)
                        +" scaffold="+BuilderScaffolding.debugClimb(builder)
                        +" count="+table.scaffoldPositions(builder.getUUID()).size());
                return;
            }
            if(stage[0]==0){
                if(helper.getTick()%5==0){
                    var result=BuilderScaffolding.bridgeTowardWork(level,
                            builder,table,owner,goal,List.of());
                    if(result==BuilderScaffolding.Result.NO_SAFE_TOWER
                            ||result==BuilderScaffolding.Result.NEED_MATERIALS){
                        helper.fail("Bridge rejected physical route "+result
                                +" feet="+builder.position());
                        return;
                    }
                }
                // A Builder standing on vanilla scaffolding can have
                // onGround=false while its feet are still physically
                // supported at the correct top elevation. Require real
                // tracked scaffold directly underfoot instead of trusting
                // vanilla's full-block ground flag for the turning bridge.
                BlockPos underFeet=builder.blockPosition().below();
                boolean supported=builder.onGround()
                        ||level.getBlockState(underFeet).is(Blocks.SCAFFOLDING)
                          &&table.scaffoldPositions(builder.getUUID())
                                .contains(underFeet);
                boolean reached=builder.distanceToSqr(
                        goal.getX()+.5,goal.getY()+.5,goal.getZ()+.5)<=10.05
                        &&Math.abs(builder.getY()-goal.getY())<.3
                        &&supported;
                if(reached){
                    int built=table.scaffoldPositions(builder.getUUID()).size()-5;
                    if(built<2||builder.carriedCount(Items.SCAFFOLDING)>=initial){
                        helper.fail("Crossed without real bridge purchases; built="+built);
                        return;
                    }
                    stage[0]=1;last[0]=helper.getTick();
                }
            }else if(stage[0]==1){
                if(helper.getTick()%5==0)
                    BuilderScaffolding.descendForTravel(level,builder,table);
                BlockPos under=builder.blockPosition().below();
                if(builder.onGround()
                        &&Math.abs(builder.getY()-base.getY())<.28
                        &&level.getBlockState(under).is(Blocks.STONE)){
                    var metrics=BuilderScaffoldTrialStats.snapshot(builder);
                    if(metrics.plans()<1||metrics.scaffoldPlaced()<2
                            ||metrics.retreatSteps()<2||metrics.landed()<1
                            ||obstructed&&metrics.turns()<1){
                        helper.fail("Scaffold physically finished but training metrics "+
                                "were lost: "+metrics);
                        return;
                    }
                    System.out.println("[SCAFFOLD BRIDGE] PASS placed="+
                            (table.scaffoldPositions(builder.getUUID()).size()-5)
                            +" returned-ground="+builder.position()
                            +" tick="+helper.getTick()
                            +" courseMetrics="+metrics);
                    helper.succeed();
                }
            }
        });
    }
}
