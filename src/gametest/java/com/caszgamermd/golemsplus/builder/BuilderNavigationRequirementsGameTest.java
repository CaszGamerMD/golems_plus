package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * Acceptance gates before the next full four-Builder Dome run:
 * - physical one-block ascent AND descent on solid blocks;
 * - recognize and reuse actual tracked scaffolding, not a fake ladder;
 * - physically ascend, step off onto solid construction, return, descend,
 *   and exit onto the ground without teleporting or fabricating scaffolds.
 */
public final class BuilderNavigationRequirementsGameTest {
    @GameTest(maxTicks=1050,skyAccess=true)
    public void walksOneFullBlockUpAndBackDown(GameTestHelper helper){
        ServerLevel world=helper.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++){
            helper.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=5;y++)helper.setBlock(x,y,z,Blocks.AIR);
        }
        // One-block-tall solid step with no stairs, slabs or scaffolding.
        for(int x=3;x<=4;x++)for(int z=2;z<=4;z++)
            helper.setBlock(x,2,z,Blocks.STONE);
        var builder=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        BlockPos spawn=helper.absolutePos(new BlockPos(1,2,3));
        BlockPos high=helper.absolutePos(new BlockPos(4,3,3));
        BlockPos low=helper.absolutePos(new BlockPos(6,2,3));
        builder.setPos(spawn.getX()+.5,spawn.getY(),spawn.getZ()+.5);
        world.addFreshEntity(builder);
        final int[] phase={0};
        final long[] phaseStart={helper.getTick()};
        helper.onEachTick(()->{
            if(!builder.isAlive()){
                helper.fail("One-block movement: real Builder missing");return;
            }
            if(helper.getTick()-phaseStart[0]>340){
                helper.fail("One-block "+(phase[0]==0?"UP":"DOWN")
                        +" stalled feet="+builder.blockPosition()
                        +" nav="+WorkerNavigation.debugStatus(builder));return;
            }
            if(helper.getTick()%3!=0)return;
            BlockPos goal=phase[0]==0?high:low;
            double dx=builder.getX()-goal.getX()-.5;
            double dz=builder.getZ()-goal.getZ()-.5;
            if(dx*dx+dz*dz<.36 && builder.onGround()
                    &&Math.abs(builder.getY()-goal.getY())<.28){
                if(phase[0]==0){
                    phase[0]=1;phaseStart[0]=helper.getTick();
                    WorkerNavigation.stop(builder);
                }else if(world.getBlockState(builder.blockPosition().below())
                        .is(Blocks.STONE))helper.succeed();
            }else WorkerNavigation.routeToExact(world,builder,goal,1.0);
        });
    }

    @GameTest(maxTicks=1350,skyAccess=true)
    public void recognizesSharedScaffoldAndExitsBothSolidPlatforms(
            GameTestHelper helper){
        ServerLevel world=helper.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++){
            helper.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=10;y++)helper.setBlock(x,y,z,Blocks.AIR);
        }
        helper.setBlock(0,2,0,WorksiteContent.BUILDER_TABLE);
        BlockPos planner=helper.absolutePos(new BlockPos(0,2,0));
        if(!(world.getBlockEntity(planner) instanceof WorkerStationBlockEntity table)){
            helper.fail("No navigation requirement Planner");return;
        }
        var owner=helper.makeMockServerPlayerInLevel();
        if(!table.claimIfUnowned(owner.getUUID())){
            helper.fail("Unable to claim requirement Planner");return;
        }
        var builder=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        BlockPos base=helper.absolutePos(new BlockPos(2,2,3));
        BlockPos elevated=helper.absolutePos(new BlockPos(4,7,3));
        builder.setPos(base.getX()+.5,base.getY(),base.getZ()+.5);
        builder.assign(planner,owner.getUUID());
        world.addFreshEntity(builder);
        if(!table.registerBuilderWorker(builder.getUUID())){
            helper.fail("Builder could not register");return;
        }
        for(int y=base.getY();y<=base.getY()+4;y++){
            BlockPos pos=new BlockPos(base.getX(),y,base.getZ());
            if(!world.setBlock(pos,Blocks.SCAFFOLDING.defaultBlockState(),3)
                    ||!table.trackScaffold(builder.getUUID(),pos)){
                helper.fail("Could not place/track existing tower at "+pos);return;
            }
        }
        for(int x=3;x<=6;x++)for(int z=2;z<=4;z++)
            helper.setBlock(x,6,z,Blocks.STONE);
        if(table.sharedScaffoldPositions().size()!=5
                ||!table.sharedScaffoldPositions().contains(base)){
            helper.fail("Builder fails to recognize existing tracked scaffolds");return;
        }
        var work=List.of(new BuilderConstructionPlan.Step(
                elevated.getX(),elevated.getY(),elevated.getZ(),
                "minecraft:stone","minecraft:stone"));
        final int[] phase={0};
        final long[] phaseStart={helper.getTick()};
        final boolean[] climbed={false},landed={false},descended={false};
        helper.onEachTick(()->{
            if(!builder.isAlive()){
                helper.fail("Scaffold navigation: Builder missing");return;
            }
            if(helper.getTick()-phaseStart[0]>340){
                helper.fail("Scaffold navigation stage="+phase[0]
                        +" feet="+builder.blockPosition()
                        +" scaffold="+BuilderScaffolding.debugClimb(builder)
                        +" nav="+WorkerNavigation.debugStatus(builder));return;
            }
            if(helper.getTick()%5!=0)return;
            if(phase[0]==0){
                if(builder.getY()>=elevated.getY()-.17){
                    climbed[0]=true;phase[0]=1;
                    phaseStart[0]=helper.getTick();
                    if(!BuilderScaffolding.stepToPlatform(world,builder,
                            base.east().offset(0,5,0)))
                        helper.fail("Top of recognized scaffold has no solid exit");
                }else{
                    var result=BuilderScaffolding.reach(world,builder,table,
                            owner,elevated,work);
                    if(result==BuilderScaffolding.Result.NO_SAFE_TOWER
                            ||result==BuilderScaffolding.Result.NEED_MATERIALS)
                        helper.fail("Refused already-owned shared tower: "+result);
                }
            }else if(phase[0]==1){
                BlockPos landing=base.east().offset(0,5,0);
                if(builder.onGround()
                        &&Math.abs(builder.getY()-landing.getY())<.28
                        &&builder.distanceToSqr(landing.getX()+.5,
                                landing.getY(),landing.getZ()+.5)<.65){
                    landed[0]=true;phase[0]=2;
                    phaseStart[0]=helper.getTick();
                }
            }else if(phase[0]==2){
                if(BuilderScaffolding.descendForTravel(world,builder,table)){
                    if(builder.getY()<=base.getY()+1.2)
                        descended[0]=true;
                }else if(builder.onGround()
                        &&Math.abs(builder.getY()-base.getY())<.30
                        &&world.getBlockState(builder.blockPosition().below())
                                .is(Blocks.STONE)){
                    if(!climbed[0]||!landed[0]||!descended[0])
                        helper.fail("Incomplete scaffold ascent/landing/return");
                    else if(table.sharedScaffoldPositions().size()!=5)
                        helper.fail("Scaffold traversal duplicated/erased owned tower");
                    else helper.succeed();
                }
            }
        });
    }
}
