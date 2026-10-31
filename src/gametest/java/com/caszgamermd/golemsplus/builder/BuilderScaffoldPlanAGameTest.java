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
 * constructs the axis-three shaft, walks onto a paid bridge at work Y,
 * extends separate scaffold support under a floating block, then returns.
 */
public final class BuilderScaffoldPlanAGameTest {
    /** A scaffold tower on the far side of a gap is reachable only by
     * walking WEST away from the target, descending one block, circling
     * SOUTH, and stepping up onto the far ledge. Native exact-tile
     * preflight must not discard this valid trained movement route.
     */
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=1500,skyAccess=true)
    public void reachesElevatedScaffoldViaAwayDownAndUpDetour(
            GameTestHelper h){
        ServerLevel level=h.getLevel();
        h.setBlock(0,2,0,WorksiteContent.BUILDER_TABLE);
        BlockPos home=h.absolutePos(new BlockPos(0,2,0));
        if(!(level.getBlockEntity(home) instanceof WorkerStationBlockEntity table)){
            h.fail("Missing Builder Planner");return;
        }
        ServerPlayer owner=h.makeMockServerPlayerInLevel();
        if(!table.claimIfUnowned(owner.getUUID())){
            h.fail("Planner owner not registered");return;
        }
        BlockPos start=h.absolutePos(new BlockPos(10,10,10));
        BlockPos tower=h.absolutePos(new BlockPos(15,10,10));
        BlockPos target=h.absolutePos(new BlockPos(18,14,10));
        // At the original height only the three western island cells
        // exist. To reach the distant tower the worker must walk away,
        // take the lower southern route, then climb onto the far ledge.
        for(int x=8;x<=10;x++)h.setBlock(x,9,10,Blocks.STONE);
        h.setBlock(8,8,11,Blocks.STONE);
        for(int x=8;x<=13;x++)h.setBlock(x,8,12,Blocks.STONE);
        h.setBlock(14,9,12,Blocks.STONE);
        h.setBlock(14,9,11,Blocks.STONE);
        h.setBlock(14,9,10,Blocks.STONE);
        h.setBlock(15,9,10,Blocks.STONE);
        BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        mob.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        mob.setItem(0,new ItemStack(Items.SCAFFOLDING,40));
        level.addFreshEntity(mob);
        if(!table.registerBuilderWorker(mob.getUUID())){
            h.fail("Could not register roof Builder");return;
        }
        final int[] phase={0};
        final boolean[] wentWest={false},wentDown={false};
        final boolean[] checkedApproach={false};
        h.onEachTick(()->{
            if(!mob.isAlive()||mob.isNoGravity()){
                h.fail("Roof Builder lost real gravity");return;
            }
            if(mob.getX()<start.getX()-.55)wentWest[0]=true;
            if(mob.getY()<start.getY()-.70)wentDown[0]=true;
            if(h.getTick()<15||h.getTick()%5!=0)return;
            if(phase[0]==0){
                // A newly spawned entity can still be settling onto its
                // starting floor on the first sampled tick. The trained
                // approach requires an actual grounded physical start.
                if(!checkedApproach[0]){
                    if(!mob.onGround()){
                        if(h.getTick()>65)h.fail("Roof Builder never settled "
                                +mob.position()+" below="
                                +level.getBlockState(mob.blockPosition().below()));
                        return;
                    }
                    if(!BuilderScaffoldPlanA.canPlan(
                            level,mob,table,target,List.of())){
                        h.fail("Detour tower rejected: "+
                                BuilderScaffoldPlanA.diagnoseAccess(
                                        level,mob,table,target,List.of()));return;
                    }
                    checkedApproach[0]=true;
                }
                var result=BuilderScaffoldPlanA.reach(
                        level,mob,table,owner,target,List.of());
                if(result==BuilderScaffoldPlanA.Result.NO_SAFE_ROUTE
                        ||result==BuilderScaffoldPlanA.Result.NEED_MATERIALS){
                    h.fail("Trained scaffold approach rejected: "+result
                            +" "+BuilderScaffoldPlanA.status(mob));return;
                }
                if(result!=BuilderScaffoldPlanA.Result.READY)return;
                if(!wentWest[0]||!wentDown[0]
                        ||!BuilderScaffoldPlanA.safeTemporaryWorkPose(
                                level,mob,table,target)
                        ||!table.scaffoldPositions(mob.getUUID())
                                .contains(target.below())){
                    h.fail("Scaffold route skipped physical away/down detour "
                            +mob.position()+" "+BuilderScaffoldPlanA.status(mob));
                    return;
                }
                if(!level.setBlock(target,Blocks.STONE.defaultBlockState(),3)){
                    h.fail("Could not place block above detour scaffold");return;
                }
                phase[0]=1;
            }else if(BuilderScaffoldPlanA.returnToGround(level,mob,table)==false
                    &&mob.onGround()&&!BuilderScaffoldPlanA.hasPlan(mob)
                    &&Math.abs(mob.getY()-tower.getY())<.30){
                System.out.println("[SCAFFOLD DETOUR] PASS west/down/up, "
                        +"paid bridge and real return tick="+h.getTick());
                h.succeed();
            }
            if(h.getTick()>1380)
                h.fail("Physical scaffold detour stalled "+mob.position()
                        +" plan="+BuilderScaffoldPlanA.status(mob)
                        +" nav="+WorkerNavigation.debugStatus(mob));
        });
    }

    @GameTest(maxTicks=900,skyAccess=true)
    public void buildsThreeAwayThenBridgesAtExactWorkHeightAndReturns(
            GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        helper.setBlock(0,2,0,WorksiteContent.BUILDER_TABLE);
        helper.setBlock(1,2,0,Blocks.CHEST);
        BlockPos home=helper.absolutePos(new BlockPos(0,2,0));
        if(!(level.getBlockEntity(home) instanceof WorkerStationBlockEntity table)){
            helper.fail("Missing physical Planner");return;
        }
        ServerPlayer owner=helper.makeMockServerPlayerInLevel();
        if(!table.claimIfUnowned(owner.getUUID())
                ||!table.setPlacedBuilderPlan(owner.getUUID(),
                        "placed-scaffold-future-footprint.litematic",
                        "Future footprint physical test",home,0,0,1,0)
                ||!table.setBuilderMaterialStorage(owner.getUUID(),
                        helper.absolutePos(new BlockPos(1,2,0)))){
            helper.fail("Planner owner not registered");return;
        }
        BlockPos tower=helper.absolutePos(new BlockPos(2,2,3));
        BlockPos target=helper.absolutePos(new BlockPos(5,6,3));
        BlockPos bridgeA=tower.east().atY(target.getY()-1);
        BlockPos supportLink=tower.east(2).atY(target.getY()-1);
        // A future schematic may reserve the shaft itself and the air
        // above the standing bridge. Those reservations must not veto a
        // temporary route across the unfinished construction footprint.
        List<BuilderConstructionPlan.Step> future=List.of(
                new BuilderConstructionPlan.Step(tower.getX(),tower.getY()+1,
                        tower.getZ(),"minecraft:stone","minecraft:stone"),
                new BuilderConstructionPlan.Step(bridgeA.getX(),bridgeA.getY(),
                        bridgeA.getZ(),"minecraft:stone","minecraft:stone"),
                new BuilderConstructionPlan.Step(bridgeA.getX(),bridgeA.getY()+1,
                        bridgeA.getZ(),"minecraft:stone","minecraft:stone"),
                new BuilderConstructionPlan.Step(supportLink.getX(),
                        supportLink.getY()+1,supportLink.getZ(),
                        "minecraft:stone","minecraft:stone"));
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
        final double[] priorY={mob.getY()};
        helper.onEachTick(()->{
            // A truly safe return must not hide a four-block fall behind
            // a successful final ground position.
            double fallen=priorY[0]-mob.getY();
            priorY[0]=mob.getY();
            if(phase[0]==1&&fallen>.65){
                helper.fail("Unsafe uncontrolled scaffold drop="+fallen
                        +" from="+(mob.getY()+fallen)+" to="+mob.getY()
                        +" "+BuilderScaffoldPlanA.status(mob));
                return;
            }
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
                        level,mob,table,owner,target,future);
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
                BlockPos supportUnder=target.below();
                if(!level.getBlockState(bridgeA).is(Blocks.SCAFFOLDING)
                        ||!level.getBlockState(supportLink).is(Blocks.SCAFFOLDING)
                        ||!level.getBlockState(supportUnder).is(Blocks.SCAFFOLDING)
                        ||level.getBlockState(bridgeA).getValue(
                                net.minecraft.world.level.block.ScaffoldingBlock.DISTANCE)!=1
                        ||level.getBlockState(supportLink).getValue(
                                net.minecraft.world.level.block.ScaffoldingBlock.DISTANCE)!=2
                        ||level.getBlockState(supportUnder).getValue(
                                net.minecraft.world.level.block.ScaffoldingBlock.DISTANCE)!=3
                        ||!table.scaffoldPositions(mob.getUUID()).contains(bridgeA)
                        ||!table.scaffoldPositions(mob.getUUID()).contains(supportLink)
                        ||!table.scaffoldPositions(mob.getUUID()).contains(supportUnder)
                        ||table.scaffoldPositions(mob.getUUID()).contains(bridgeA.below())
                        ||table.scaffoldPositions(mob.getUUID()).contains(supportLink.below())
                        ||Math.abs(mob.getX()-bridgeA.getX()-.5)>.4
                        ||!BuilderScaffoldPlanA.safeTemporaryWorkPose(
                                level,mob,table,target)){
                    helper.fail("Builder didn't reach a supported floating work pose: "+
                            mob.position()+" "+BuilderScaffoldPlanA.status(mob));
                    return;
                }
                // Four tower cells + one standing bridge + two separate
                // support cells must all come from actual scaffold items.
                if(initial[0]-mob.carriedCount(Items.SCAFFOLDING)<7){
                    helper.fail("Scaffold items weren't genuinely consumed");return;
                }
                if(!BuilderScaffolding.occupiesPendingFootprint(
                        level,mob,table,future,bridgeA.getY())){
                    helper.fail("Temporary bridge didn't overlap reserved future work");
                    return;
                }
                if(!level.setBlock(target,Blocks.STONE.defaultBlockState(),3)
                        ||!level.getBlockState(target).is(Blocks.STONE)){
                    helper.fail("Couldn't place a real block above its owned support");
                    return;
                }
                phase[0]=1;since[0]=helper.getTick();
            }else if(phase[0]==1){
                if(helper.getTick()%5==0)
                    BuilderScaffoldPlanA.returnToGround(level,mob,table);
                if(!BuilderScaffoldPlanA.hasPlan(mob)
                        &&mob.onGround()
                        &&Math.abs(mob.getY()-tower.getY())<.25){
                    phase[0]=2;since[0]=helper.getTick();
                }
            }else if(phase[0]==2){
                if(helper.getTick()%5==0)
                    BuilderScaffolding.retrieve(level,mob,table,false);
                if(!table.scaffoldPositions(mob.getUUID()).isEmpty())return;
                if(!level.getBlockState(bridgeA).isAir()
                        ||!level.getBlockState(supportLink).isAir()
                        ||!level.getBlockState(tower.above()).isAir()
                        ||!level.getBlockState(target).is(Blocks.STONE)
                        ||BuilderScaffolding.occupiesPendingFootprint(
                                level,mob,table,future,bridgeA.getY())){
                    helper.fail("Reclamation failed to free future schematic cells");
                    return;
                }
                if(!level.setBlock(bridgeA,Blocks.STONE.defaultBlockState(),3)
                        ||!level.getBlockState(bridgeA).is(Blocks.STONE)){
                    helper.fail("Future schematic position still obstructed");return;
                }
                System.out.println("[PLAN A] PASS build-anchored scaffold, "
                        +"future-footprint clearance and safe return at tick="
                        +helper.getTick());
                helper.succeed();
            }
        });
    }
}
