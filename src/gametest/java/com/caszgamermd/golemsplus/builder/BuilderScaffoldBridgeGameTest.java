package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** The real lateral scaffold movement controller must find a turning route
 * around a stone overhang, place paid scaffold segments, and walk across
 * those segments instead of treating the obstacle as a second tower.
 */
public final class BuilderScaffoldBridgeGameTest {
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=1000,skyAccess=true)
    public void turnsAroundOverhangAndReturnsToItsTower(GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        for(int x=0;x<=12;x++)for(int z=0;z<=9;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        helper.setBlock(1,2,1,WorksiteContent.BUILDER_TABLE);
        BlockPos home=helper.absolutePos(new BlockPos(1,2,1));
        if(!(level.getBlockEntity(home) instanceof WorkerStationBlockEntity table)){
            helper.fail("Bridge test missing Builder Planner");return;
        }
        var owner=helper.makeMockServerPlayerInLevel();
        var id=owner.getUUID();
        if(!table.claimIfUnowned(id)){helper.fail("No owner");return;}
        BuilderGolem worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        BlockPos anchor=helper.absolutePos(new BlockPos(2,5,3));
        worker.setPos(anchor.getX()+.5,anchor.getY()+1,anchor.getZ()+.5);
        worker.setItem(0,new ItemStack(Items.SCAFFOLDING,16));
        level.addFreshEntity(worker);
        if(!table.registerBuilderWorker(worker.getUUID())){
            helper.fail("Unable to register Bridge Builder");return;
        }
        for(int y=2;y<=5;y++){
            BlockPos column=helper.absolutePos(new BlockPos(2,y,3));
            level.setBlock(column,Blocks.SCAFFOLDING.defaultBlockState(),3);
            if(!table.trackScaffold(worker.getUUID(),column)){
                helper.fail("Unable to track paid scaffold tower");return;
            }
        }
        // Direct approach is blocked, but a route via z=2 can turn toward
        // the target without touching either part of the stone obstruction.
        helper.setBlock(3,5,3,Blocks.STONE);
        helper.setBlock(4,5,3,Blocks.STONE);
        helper.setBlock(5,5,3,Blocks.STONE);
        BlockPos goal=helper.absolutePos(new BlockPos(8,6,3));
        var plan=List.of(new BuilderConstructionPlan.Step(
                goal.getX(),goal.getY(),goal.getZ(),
                "minecraft:stone","minecraft:stone"));
        final boolean[] returning={false};
        helper.onEachTick(()->{
            if(!worker.isAlive()){
                helper.fail("Bridge worker died");return;
            }
            if(helper.getTick()%5!=0)return;
            if(!returning[0]){
                if(worker.distanceToSqr(goal.getX()+.5,goal.getY()+.5,
                        goal.getZ()+.5)<=12.5){
                    var segments=table.scaffoldPositions(worker.getUUID());
                    if(segments.size()<7){
                        helper.fail("Reached overhang without a physical scaffold bridge");
                        return;
                    }
                    if(!level.getBlockState(helper.absolutePos(
                            new BlockPos(3,5,3))).is(Blocks.STONE)){
                        helper.fail("Bridge replaced obstacle");return;
                    }
                    returning[0]=true;
                }else{
                    var result=BuilderScaffoldBridge.reach(level,worker,table,
                            owner,goal,plan);
                    if(result==BuilderScaffolding.Result.NO_SAFE_TOWER
                            ||result==BuilderScaffolding.Result.NEED_MATERIALS){
                        helper.fail("Could not route around ledge: "+result
                                +" at "+worker.blockPosition());return;
                    }
                }
                return;
            }
            if(worker.distanceToSqr(anchor.getX()+.5,anchor.getY()+1,
                    anchor.getZ()+.5)<.25){
                helper.succeed();
                return;
            }
            if(!BuilderScaffoldBridge.returnToAnchor(level,worker,table))
                helper.fail("Lost physical return route to tower");
        });
    }
}
