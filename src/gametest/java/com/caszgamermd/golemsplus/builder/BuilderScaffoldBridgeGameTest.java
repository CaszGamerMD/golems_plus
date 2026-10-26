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
    /** Requesting one platform at a time must not trigger an entire
     * depot roundtrip for every suspended bridge cell. Batch pickup still
     * debits the actual chest and requires a physically nearby worker.
     */
    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=80,skyAccess=true)
    public void fetchesBatchOfRealScaffoldingAtDepot(GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        helper.setBlock(3,1,3,Blocks.STONE);
        helper.setBlock(4,1,3,Blocks.STONE);
        helper.setBlock(3,2,3,WorksiteContent.BUILDER_TABLE);
        helper.setBlock(4,2,3,Blocks.CHEST);
        BlockPos planner=helper.absolutePos(new BlockPos(3,2,3));
        BlockPos depot=helper.absolutePos(new BlockPos(4,2,3));
        if(!(level.getBlockEntity(planner) instanceof WorkerStationBlockEntity table)
                ||!(level.getBlockEntity(depot) instanceof net.minecraft.world.Container chest)){
            helper.fail("Batch scaffold trial has no real Planner / chest");
            return;
        }
        var owner=helper.makeMockServerPlayerInLevel();
        if(!table.claimIfUnowned(owner.getUUID())
                ||!table.setPlacedBuilderPlan(owner.getUUID(),
                        "placed-batch-transfer-test.litematic","Batch inventory test",
                        planner,0,0,1,0)
                ||!table.setBuilderMaterialStorage(owner.getUUID(),depot)){
            helper.fail("Batch scaffold trial could not configure depot");
            return;
        }
        chest.setItem(0,new ItemStack(Items.SCAFFOLDING,40));
        BuilderGolem worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        worker.setPos(depot.getX()+.5,depot.getY(),depot.getZ()+1.5);
        worker.setPersistenceRequired();
        level.addFreshEntity(worker);
        var outcome=BuilderSupplyInventory.loadScaffold(level,worker,table,1);
        int carried=worker.carriedCount(Items.SCAFFOLDING);
        int remaining=chest.getItem(0).getCount();
        if(outcome!=BuilderSupplyInventory.ScaffoldLoad.READY
                ||carried!=32||remaining!=8||carried+remaining!=40){
            helper.fail("Scaffold pickup violated real-item batching: "
                    +outcome+" carried="+carried+" chest="+remaining);
            return;
        }
        helper.succeed();
    }
    /** Prove an actual Golem buys/builds an outside tower, climbs it,
     * and places two real horizontal scaffolds across the reserved gap.
     * No 3,537-block Dome workload or teammate doorway traffic involved.
     */
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=1100,skyAccess=true)
    public void constructsOutsideTowerAndBridgesTwoClearCells(
            GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        for(int x=3;x<=25;x++)for(int z=3;z<=23;z++){
            helper.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=8;y++)
                helper.setBlock(x,y,z,Blocks.AIR);
        }
        helper.setBlock(3,2,4,WorksiteContent.BUILDER_TABLE);
        BlockPos planner=helper.absolutePos(new BlockPos(3,2,4));
        if(!(level.getBlockEntity(planner)
                instanceof WorkerStationBlockEntity table)){
            helper.fail("Two-gap test has no Planner");return;
        }
        var owner=helper.makeMockServerPlayerInLevel();
        if(!table.claimIfUnowned(owner.getUUID())){
            helper.fail("Two-gap Planner permission denied");return;
        }
        java.util.List<BuilderConstructionPlan.Step> plan=
                new java.util.ArrayList<>();
        for(int x=10;x<=14;x++)
            for(int z=8;z<=12;z++){
                BlockPos cell=helper.absolutePos(new BlockPos(x,2,z));
                plan.add(new BuilderConstructionPlan.Step(
                        cell.getX(),cell.getY(),cell.getZ(),
                        "minecraft:stone","minecraft:stone"));
            }
        BlockPos goal=helper.absolutePos(new BlockPos(12,6,10));
        plan.add(new BuilderConstructionPlan.Step(
                goal.getX(),goal.getY(),goal.getZ(),
                "minecraft:stone","minecraft:stone"));
        var shell=BuilderScaffoldShell.fromSteps(plan);
        BlockPos base=shell.towers().stream()
                .filter(t->t.side().equals("X-")).findFirst()
                .orElseThrow().base();
        BlockPos expected=helper.absolutePos(new BlockPos(7,2,10));
        if(!base.equals(expected)||!shell.towerHasTwoBlockGap(base)){
            helper.fail("Expected third-lane tower two blocks outside model: "
                    +base+" expected "+expected);return;
        }
        BuilderGolem worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        worker.setPersistenceRequired();
        worker.setPos(base.getX()+.5,base.getY(),base.getZ()+.5);
        worker.setItem(0,new ItemStack(Items.SCAFFOLDING,48));
        level.addFreshEntity(worker);
        if(!table.registerBuilderWorker(worker.getUUID())){
            helper.fail("Two-gap Builder registration failed");return;
        }
        final Runnable simulation=BuilderChunkTickets.holdGameTestSimulation(level,
                worker.blockPosition(),goal);
        final int[] stage={0};
        final long[] stageStart={helper.getTick()};
        helper.onEachTick(()->{
            if(!worker.isAlive()){
                helper.fail("Builder died constructing two-gap tower");return;
            }
            if(helper.getTick()%5!=0)return;
            if(helper.getTick()-stageStart[0]>350){
                helper.fail("Two-gap trial stalled stage="+stage[0]
                        +" position="+worker.blockPosition()
                        +" climb="+BuilderScaffolding.debugClimb(worker)
                        +" bridge="+BuilderScaffoldBridge.debug(worker)
                        +" tracked="+table.scaffoldPositions(worker.getUUID()).size());
                return;
            }
            if(stage[0]==0){
                if(worker.getY()>=goal.getY()-.22
                        &&table.sharedScaffoldPositions().contains(
                                new BlockPos(base.getX(),goal.getY()-1,
                                        base.getZ()))){
                    stage[0]=1;stageStart[0]=helper.getTick();
                    return;
                }
                var result=BuilderScaffolding.reach(level,worker,table,
                        owner,goal,plan);
                if(result==BuilderScaffolding.Result.NO_SAFE_TOWER
                        ||result==BuilderScaffolding.Result.NEED_MATERIALS)
                    helper.fail("Cannot build remote two-gap tower: "+result
                            +" from "+worker.blockPosition());
                return;
            }
            BlockPos first=helper.absolutePos(new BlockPos(8,5,10));
            BlockPos second=helper.absolutePos(new BlockPos(9,5,10));
            var built=table.sharedScaffoldPositions();
            if(built.contains(first)&&built.contains(second)
                    &&level.getBlockState(first).is(Blocks.SCAFFOLDING)
                    &&level.getBlockState(second).is(Blocks.SCAFFOLDING)
                    &&worker.distanceToSqr(goal.getX()+.5,goal.getY(),
                            goal.getZ()+.5)<=12.5){
                simulation.run();
                helper.succeed();
                return;
            }
            var result=BuilderScaffoldBridge.reach(level,worker,table,
                    owner,goal,plan);
            if(result==BuilderScaffolding.Result.NO_SAFE_TOWER
                    ||result==BuilderScaffolding.Result.NEED_MATERIALS)
                helper.fail("Cannot physically bridge two reserved air cells: "
                        +result+" stage="+stage[0]
                        +" bridge="+BuilderScaffoldBridge.debug(worker));
        });
    }
    /** A virtual flight edge climbs ONE block but always lands on a
     * physically placed, registered scaffold. Test actual entity motion;
     * no instant position changes or invisible floor are permitted.
     */
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=300,skyAccess=true)
    public void fliesOnlyOverBuiltScaffoldToOneLevelHigher(GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        for(int x=2;x<=12;x++)for(int z=2;z<=10;z++){
            helper.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=7;y++)helper.setBlock(x,y,z,Blocks.AIR);
        }
        helper.setBlock(2,2,2,WorksiteContent.BUILDER_TABLE);
        BlockPos home=helper.absolutePos(new BlockPos(2,2,2));
        if(!(level.getBlockEntity(home) instanceof WorkerStationBlockEntity table)){
            helper.fail("Ghost-flight test missing Planner");return;
        }
        var owner=helper.makeMockServerPlayerInLevel();
        if(!table.claimIfUnowned(owner.getUUID())){
            helper.fail("Ghost-flight Planner owner missing");return;
        }
        BlockPos anchor=helper.absolutePos(new BlockPos(6,3,6));
        BlockPos landing=helper.absolutePos(new BlockPos(7,4,6));
        BuilderGolem worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        worker.setPersistenceRequired();
        worker.setPos(anchor.getX()+.5,anchor.getY()+1,anchor.getZ()+.5);
        if(!table.registerBuilderWorker(worker.getUUID())){
            helper.fail("Ghost-flight worker registration failed");return;
        }
        for(int y=2;y<=3;y++){
            BlockPos piece=helper.absolutePos(new BlockPos(6,y,6));
            level.setBlock(piece,Blocks.SCAFFOLDING.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.ScaffoldingBlock.DISTANCE,0),3);
            if(!table.trackScaffold(worker.getUUID(),piece)){
                helper.fail("Untracked departure scaffold");return;
            }
        }
        for(int y=2;y<=4;y++){
            BlockPos piece=helper.absolutePos(new BlockPos(7,y,6));
            level.setBlock(piece,Blocks.SCAFFOLDING.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.ScaffoldingBlock.DISTANCE,0),3);
            if(!table.trackScaffold(worker.getUUID(),piece)){
                helper.fail("Untracked arrival scaffold");return;
            }
        }
        // Finish constructing the grounded columns before entity spawning
        // so the test cannot accidentally conflate idle scaffold cleanup
        // with the actual guided one-block ascent.
        boolean beforeBlock=level.getBlockState(landing).is(Blocks.SCAFFOLDING);
        boolean beforeOwned=table.sharedScaffoldPositions().contains(landing);
        level.addFreshEntity(worker);
        if(!BuilderScaffoldBridge.sidestep(level,worker,table,landing.above())){
            helper.fail("Ghost-flight refused supported ascent: state="
                    +level.getBlockState(landing)+" owned="
                    +table.sharedScaffoldPositions().contains(landing)
                    +" loaded="+level.hasChunkAt(landing)
                    +" beforeSpawn="+beforeBlock+"/"+beforeOwned
                    +" rangeSq="+worker.distanceToSqr(landing.getX()+.5,
                            landing.getY()+1,landing.getZ()+.5));
            return;
        }
        final Runnable simulation=BuilderChunkTickets.holdGameTestSimulation(level,
                anchor.above(),landing.above());
        final boolean[] rose={false};
        helper.onEachTick(()->{
            if(!worker.isAlive()){
                helper.fail("Ghost-flight Builder died");return;
            }
            if(worker.getY()>anchor.getY()+1.35)rose[0]=true;
            if(worker.distanceToSqr(landing.getX()+.5,
                    landing.getY()+1,landing.getZ()+.5)<.10
                    &&!BuilderScaffoldBridge.moving(worker)){
                if(!rose[0]||!level.getBlockState(landing).is(Blocks.SCAFFOLDING)){
                    helper.fail("Flight did not traverse real supported scaffolding");
                    return;
                }
                simulation.run();
                helper.succeed();
                return;
            }
            if(helper.getTick()>200){
                simulation.run();
                helper.fail("Ghost-flight scaffold transition stalled at "
                        +worker.blockPosition()+" bridge="
                        +BuilderScaffoldBridge.debug(worker));
            }
        });
    }

    /** The GameTest must reject bridges that only LOOK built but whose
     * unsupported spans have distance seven and will fall on block tick.
     */
    private static String unstableBridge(ServerLevel world,
            java.util.Collection<BlockPos> owned){
        for(BlockPos at:owned){
            if(!world.hasChunkAt(at)||!world.hasChunkAt(at.below()))
                return "unloaded scaffold at "+at.toShortString();
            var state=world.getBlockState(at);
            if(!state.is(Blocks.SCAFFOLDING))
                return "tracked scaffold missing at "+at.toShortString();
            BlockPos under=at.below();
            var below=world.getBlockState(under);
            boolean verticalSupport=below.is(Blocks.SCAFFOLDING)
                    ||below.isFaceSturdy(world,under,
                            net.minecraft.core.Direction.UP);
            if(!verticalSupport&&state.getValue(
                    net.minecraft.world.level.block.ScaffoldingBlock.DISTANCE)>=7)
                return "unsupported span at "+at.toShortString();
        }
        return null;
    }
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
        final Runnable simulation=BuilderChunkTickets.holdGameTestSimulation(level,
                worker.blockPosition(),helper.absolutePos(new BlockPos(8,6,3)));
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
                    String unstable=unstableBridge(level,segments);
                    if(unstable!=null){
                        helper.fail("Bridge crossed but physics support failed: "+unstable);
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
                simulation.run();
                helper.succeed();
                return;
            }
            if(!BuilderScaffoldBridge.returnToAnchor(level,worker,table))
                helper.fail("Lost physical return route to tower");
        });
    }
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=1400,skyAccess=true)
    public void extendsLongDetourOnRealGroundedPiers(GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        // Fabric's test world can contain residual barrier cells from
        // the structure boundary (observed in the 20-block test at
        // x=8,y=3,z=2). Explicitly clear only this controlled arena,
        // then create the intentional overhang obstruction below.
        for(int x=8;x<=27;x++)for(int z=8;z<=16;z++){
            helper.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=6;y++)
                helper.setBlock(x,y,z,Blocks.AIR);
        }
        helper.setBlock(9,2,9,WorksiteContent.BUILDER_TABLE);
        BlockPos home=helper.absolutePos(new BlockPos(9,2,9));
        if(!(level.getBlockEntity(home) instanceof WorkerStationBlockEntity table)){
            helper.fail("Long bridge has no Planner");return;
        }
        var owner=helper.makeMockServerPlayerInLevel();
        if(!table.claimIfUnowned(owner.getUUID())){
            helper.fail("Long bridge has no owner");return;
        }
        BlockPos anchor=helper.absolutePos(new BlockPos(10,3,11));
        BuilderGolem worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        worker.setPersistenceRequired(); // keep focused physics tests deterministic
        worker.setPos(anchor.getX()+.5,anchor.getY()+1,anchor.getZ()+.5);
        worker.setItem(0,new ItemStack(Items.SCAFFOLDING,32));
        level.addFreshEntity(worker);
        if(!table.registerBuilderWorker(worker.getUUID())){
            helper.fail("Cannot register long bridge worker");return;
        }
        final Runnable simulation=BuilderChunkTickets.holdGameTestSimulation(level,
                worker.blockPosition(),helper.absolutePos(new BlockPos(22,4,11)));
        // The scaffold MUST be directly below the spawned worker.
        // Previously this fixture registered a tower at local (2,3),
        // while its worker actually started at local (10,11).
        // That made the test fail for an unrelated missing anchor.
        for(int y=2;y<=3;y++){
            BlockPos p=helper.absolutePos(new BlockPos(10,y,11));
            level.setBlock(p,Blocks.SCAFFOLDING.defaultBlockState(),3);
            if(!table.trackScaffold(worker.getUUID(),p)){
                helper.fail("Long bridge could not register its real tower");
                return;
            }
        }
        for(int x=11;x<=16;x++)helper.setBlock(x,3,11,Blocks.STONE);
        BlockPos goal=helper.absolutePos(new BlockPos(22,4,11));
        var plan=List.of(new BuilderConstructionPlan.Step(
                goal.getX(),goal.getY(),goal.getZ(),
                "minecraft:stone","minecraft:stone"));
        // Diagnose the exact failed bridge node instead of the generic
        // GameTest 1400-tick timeout. Building piers counts as progress;
        // physically moving around the obstruction counts as progress.
        final net.minecraft.world.phys.Vec3[] lastPosition={worker.position()};
        final long[] lastProgress={helper.getTick()};
        final int[] lastOwned={table.sharedScaffoldPositions().size()};
        helper.onEachTick(()->{
            if(!worker.isAlive()){
                helper.fail("Long bridge worker disappeared at "
                        +worker.blockPosition()+" fall="+worker.fallDistance
                        +" step="+BuilderScaffoldBridge.debug(worker));return;
            }
            if(helper.getTick()%5!=0)return;
            // A loaded GameTest structure is not necessarily entity ticking:
            // if tickCount does not advance, any physics/path "test" is fake.
            if(helper.getTick()>90&&worker.tickCount<15){
                helper.fail("No physical entity simulation for scaffold Builder: "
                        +"workerTicks="+worker.tickCount+" gameTicks="+helper.getTick());
                return;
            }
            int ownedNow=table.sharedScaffoldPositions().size();
            if(worker.position().distanceToSqr(lastPosition[0])>.20
                    ||ownedNow>lastOwned[0]){
                lastPosition[0]=worker.position();
                lastOwned[0]=ownedNow;
                lastProgress[0]=helper.getTick();
            }
            if(helper.getTick()-lastProgress[0]>230){
                helper.fail("Long bridge physically stalled at "
                        +worker.blockPosition()+" feetY="+worker.getY()
                        +" owned="+ownedNow+" bridge="
                        +BuilderScaffoldBridge.debug(worker)
                        +" climb="+BuilderScaffolding.debugClimb(worker)
                        +" nav="+com.caszgamermd.golemsplus.worker.WorkerNavigation
                                .debugStatus(worker)+" workerTicks="+worker.tickCount);
                return;
            }
            if(worker.distanceToSqr(goal.getX()+.5,goal.getY()+.5,
                    goal.getZ()+.5)<=12.5){
                String unstable=unstableBridge(level,
                        table.sharedScaffoldPositions());
                if(unstable!=null){
                    helper.fail("Long bridge is physically unstable: "+unstable);
                    return;
                }
                int posts=0;
                for(BlockPos p:table.scaffoldPositions(worker.getUUID()))
                    if(p.getY()==anchor.getY()-1&&p.getX()!=anchor.getX())
                        posts++;
                if(posts==0)
                    helper.fail("Long unsupported bridge missed its footing posts");
                else {
                    simulation.run();
                    helper.succeed();
                }
                return;
            }
            var state=BuilderScaffoldBridge.reach(level,worker,table,owner,
                    goal,plan);
            if(state==BuilderScaffolding.Result.NO_SAFE_TOWER
                    ||state==BuilderScaffolding.Result.NEED_MATERIALS){
                StringBuilder probe=new StringBuilder();
                for(int x=11;x<=20;x++){
                    BlockPos at=helper.absolutePos(new BlockPos(x,3,10));
                    probe.append(" x").append(x).append(":")
                            .append(level.getBlockState(at))
                            .append("/floor=").append(level.getBlockState(at.below().below()))
                            .append("/permission=").append(level.mayInteract(owner,at));
                }
                helper.fail("Long bridge denied physical detour: "+state
                        +" "+BuilderScaffoldBridge.debug(worker)
                        +" probe="+probe+" at "+worker.blockPosition());
            }
        });
    }

}
