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

/** A REAL Builder physically creates an inventory-paid scaffold shaft,
 * climbs every level without jumping, and descends the same shaft before
 * safely stepping onto solid ground. No simulated coordinates or teleport.
 */
public final class BuilderSafeScaffoldGameTest {
    @GameTest(maxTicks=1100,skyAccess=true)
    public void builderBuildsClimbsAndDescendsItsOwnTower(GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        helper.setBlock(0,2,0,WorksiteContent.BUILDER_TABLE);
        BlockPos home=helper.absolutePos(new BlockPos(0,2,0));
        if(!(level.getBlockEntity(home) instanceof WorkerStationBlockEntity table)){
            helper.fail("No real Builder Planner block entity");return;
        }
        ServerPlayer owner=helper.makeMockServerPlayerInLevel();
        if(!table.claimIfUnowned(owner.getUUID())){
            helper.fail("Owner cannot claim Builder Planner");return;
        }
        BuilderGolem builder=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        BlockPos spawn=helper.absolutePos(new BlockPos(3,2,3));
        builder.setPos(spawn.getX()+.5,spawn.getY(),spawn.getZ()+.5);
        builder.setItem(0,new ItemStack(Items.SCAFFOLDING,32));
        level.addFreshEntity(builder);
        if(!table.registerBuilderWorker(builder.getUUID())){
            helper.fail("Cannot register real Builder");return;
        }
        BlockPos work=helper.absolutePos(new BlockPos(5,7,4));
        int before=builder.carriedCount(Items.SCAFFOLDING);
        int[] state={0};
        double[] highest={-Double.MAX_VALUE};
        long[] began={helper.getTick()};
        helper.onEachTick(()->{
            if(!builder.isAlive()){
                helper.fail("Builder entity disappeared");return;
            }
            if(helper.getTick()-began[0]>350){
                helper.fail("Scaffold stage timeout phase="+state[0]
                        +" pos="+builder.position()
                        +" climb="+BuilderScaffolding.debugClimb(builder)
                        +" placed="+table.scaffoldPositions(builder.getUUID()).size());
                return;
            }
            if(state[0]==0){
                if(helper.getTick()%5==0){
                    BuilderScaffolding.Result next=BuilderScaffolding.reach(
                            level,builder,table,owner,work,List.of());
                    if(next==BuilderScaffolding.Result.NO_SAFE_TOWER
                            ||next==BuilderScaffolding.Result.NEED_MATERIALS){
                        helper.fail("Rejected real safe scaffold: "+next
                                +" pos="+builder.position()
                                +" standSpawn="+com.caszgamermd.golemsplus.worker.WorkerNavigation
                                    .canStandOn(level,builder,spawn)
                                +" reachSpawn="+com.caszgamermd.golemsplus.worker.WorkerNavigation
                                    .canReachExact(level,builder,spawn)
                                +" baseSupport="+level.getBlockState(spawn.below())
                                +" exitStand="+com.caszgamermd.golemsplus.worker.WorkerNavigation
                                    .canStandOn(level,builder,spawn.east())
                                +" reachExit="+com.caszgamermd.golemsplus.worker.WorkerNavigation
                                    .canReachExact(level,builder,spawn.east())
                                +" planY="+work.getY());return;
                    }
                }
                highest[0]=Math.max(highest[0],builder.getY());
                if(builder.getY()>=work.getY()-.12
                        &&table.scaffoldPositions(builder.getUUID()).size()>=4){
                    state[0]=1;began[0]=helper.getTick();
                }
            }else if(state[0]==1){
                if(helper.getTick()%5==0){
                    if(!BuilderScaffolding.descendForTravel(level,builder,table)){
                        helper.fail("Builder has no physically safe descent route");return;
                    }
                }
                if(builder.onGround()
                        &&Math.abs(builder.getY()-spawn.getY())<.25
                        &&table.scaffoldPositions(builder.getUUID()).stream()
                            .noneMatch(p->p.getX()==builder.blockPosition().getX()
                                    &&p.getZ()==builder.blockPosition().getZ())){
                    int placed=before-builder.carriedCount(Items.SCAFFOLDING);
                    if(placed<4||highest[0]<work.getY()-.16){
                        helper.fail("Scaffold completion lied about physical blocks: "+
                                placed+" top="+highest[0]);return;
                    }
                    System.out.println("[SCAFFOLD ACCESS] PASS real placed="+placed
                            +" ascent topY="+highest[0]
                            +" descent feet="+builder.position()
                            +" ticks="+helper.getTick());
                    helper.succeed();
                }
            }
        });
    }

    /** A completed elevated stone ledge may be a *reachable* starting
     * point for a new paid shaft. Lower candidates at this X/Z are solid
     * architecture, not air where scaffolding can replace player blocks.
     */
    @GameTest(maxTicks=140,skyAccess=true)
    public void elevatedFinishedRoofCanAnchorNewScaffoldBridge(
            GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        for(int x=0;x<9;x++)for(int z=0;z<8;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        // Existing platform begins one block above the Builder's feet.
        // Approaching the raised ledge is a legal real one-block step.
        for(int x=2;x<=7;x++)for(int z=2;z<=5;z++)
            helper.setBlock(x,2,z,Blocks.STONE);
        helper.setBlock(0,2,0,WorksiteContent.BUILDER_TABLE);
        BlockPos home=helper.absolutePos(new BlockPos(0,2,0));
        if(!(level.getBlockEntity(home) instanceof WorkerStationBlockEntity table)){
            helper.fail("Missing Planner");return;
        }
        ServerPlayer owner=helper.makeMockServerPlayerInLevel();
        if(!table.claimIfUnowned(owner.getUUID())){
            helper.fail("Could not claim Planner");return;
        }
        BuilderGolem worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        BlockPos start=helper.absolutePos(new BlockPos(1,2,3));
        BlockPos target=helper.absolutePos(new BlockPos(5,6,3));
        worker.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        level.addFreshEntity(worker);
        helper.onEachTick(()->{
            if(helper.getTick()!=18)return;
            BlockPos raised=helper.absolutePos(new BlockPos(2,3,3));
            if(!com.caszgamermd.golemsplus.worker.WorkerNavigation.canStandOn(
                    level,worker,raised)){
                helper.fail("Completed roof cannot support elevated entry");return;
            }
            if(!BuilderScaffoldPlanA.canPlan(level,worker,table,target,
                    java.util.List.of())){
                helper.fail("No paid tower planned from reachable upper support: "
                        +BuilderScaffoldPlanA.diagnoseAccess(level,worker,table,
                                target,java.util.List.of()));return;
            }
            System.out.println("[ELEVATED ANCHOR] PASS on already-built raised"
                    +" support without overwriting architecture");
            helper.succeed();
        });
    }

    /** A one-block gap to an upper supported work cell needs ONE
     * genuine cantilevered scaffold, not an unrelated three-away tower.
     */
    @GameTest(maxTicks=900,skyAccess=true)
    public void physicallyBridgesOneCellGapAndReturnsSafely(
            GameTestHelper helper){
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
            helper.fail("Could not claim Builder Planner");return;
        }
        BlockPos tower=helper.absolutePos(new BlockPos(2,2,3));
        BlockPos target=helper.absolutePos(new BlockPos(4,6,3));
        // An ordinary one-cell bridge test is distinct from a floating
        // placement PLATFORM. Give this target a real connected side so
        // the bridge still finishes at its original adjacent cell.
        level.setBlock(target.below(),Blocks.STONE.defaultBlockState(),3);
        BuilderGolem worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        worker.setPos(tower.getX()+.5,tower.getY(),tower.getZ()+.5);
        worker.setItem(0,new ItemStack(Items.SCAFFOLDING,32));
        level.addFreshEntity(worker);
        if(!table.registerBuilderWorker(worker.getUUID())){
            helper.fail("Worker registration failed");return;
        }
        int[] phase={0};
        long[] changed={helper.getTick()};
        helper.onEachTick(()->{
            if(helper.getTick()-changed[0]>300){
                helper.fail("One-cell bridge stalled at "+worker.position()
                        +" "+BuilderScaffoldPlanA.status(worker));return;
            }
            if(phase[0]==0){
                if(helper.getTick()%5!=0)return;
                var state=BuilderScaffoldPlanA.reach(
                        level,worker,table,owner,target,List.of());
                if(state==BuilderScaffoldPlanA.Result.NO_SAFE_ROUTE
                        ||state==BuilderScaffoldPlanA.Result.NEED_MATERIALS){
                    helper.fail("One-span bridge rejected: "+state
                            +" "+BuilderScaffoldPlanA.status(worker));return;
                }
                if(state!=BuilderScaffoldPlanA.Result.READY)return;
                BlockPos bridge=tower.east().atY(target.getY()-1);
                if(worker.blockPosition().getY()!=target.getY()
                        ||!level.getBlockState(bridge).is(Blocks.SCAFFOLDING)
                        ||table.scaffoldPositions(worker.getUUID()).stream()
                                .filter(p->p.getX()==bridge.getX()
                                        &&p.getZ()==bridge.getZ()).count()!=1){
                    helper.fail("One-cell bridge not physically proven: "
                            +worker.position()+" "+BuilderScaffoldPlanA.status(worker));
                    return;
                }
                phase[0]=1;changed[0]=helper.getTick();
            }else if(phase[0]==1){
                if(helper.getTick()%5==0)
                    BuilderScaffoldPlanA.returnToGround(level,worker,table);
                if(!BuilderScaffoldPlanA.hasPlan(worker)
                        &&worker.onGround()
                        &&Math.abs(worker.getY()-tower.getY())<.26){
                    System.out.println("[ONE CELL BRIDGE] PASS paid scaffold"
                            +" and real descent back to ground");
                    helper.succeed();
                }
            }
        });
    }

    @GameTest(maxTicks=100,skyAccess=true)
    public void plannedFutureWallsCannotSealGroundExit(GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        BlockPos base=helper.absolutePos(new BlockPos(3,2,3));
        BuilderGolem builder=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        builder.setPos(base.getX()+.5,base.getY(),base.getZ()+.5);
        level.addFreshEntity(builder);
        if(BuilderScaffolding.safeGroundExit(level,builder,base,
                java.util.Set.of())==null){
            helper.fail("Open ground exit was wrongly rejected");return;
        }
        var future=new java.util.HashSet<BlockPos>();
        for(net.minecraft.core.Direction side:net.minecraft.core.Direction.Plane.HORIZONTAL){
            BlockPos foot=base.relative(side);
            future.add(foot);
            future.add(foot.above());
        }
        if(BuilderScaffolding.safeGroundExit(level,builder,base,future)!=null){
            helper.fail("Builder approved tower whose future walls seal all exits");
            return;
        }
        future.remove(base.east());
        future.remove(base.east().above());
        BlockPos allowed=BuilderScaffolding.safeGroundExit(level,builder,base,future);
        if(allowed==null||!allowed.equals(base.east())){
            helper.fail("Planner did not choose its sole surviving east exit: "+allowed);
            return;
        }
        helper.succeed();
    }

    @GameTest(maxTicks=100,skyAccess=true)
    public void neverBuildsTowerWithoutGroundDismount(GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        helper.setBlock(3,1,3,Blocks.STONE);
        helper.setBlock(0,2,0,WorksiteContent.BUILDER_TABLE);
        BlockPos home=helper.absolutePos(new BlockPos(0,2,0));
        if(!(level.getBlockEntity(home) instanceof WorkerStationBlockEntity table)){
            helper.fail("No Planner");return;
        }
        ServerPlayer owner=helper.makeMockServerPlayerInLevel();
        table.claimIfUnowned(owner.getUUID());
        BuilderGolem builder=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        BlockPos pos=helper.absolutePos(new BlockPos(3,2,3));
        builder.setPos(pos.getX()+.5,pos.getY(),pos.getZ()+.5);
        builder.setItem(0,new ItemStack(Items.SCAFFOLDING,32));
        level.addFreshEntity(builder);
        table.registerBuilderWorker(builder.getUUID());
        var result=BuilderScaffolding.reach(level,builder,table,owner,
                helper.absolutePos(new BlockPos(4,7,3)),List.of());
        if(result!=BuilderScaffolding.Result.NO_SAFE_TOWER
                ||!table.scaffoldPositions(builder.getUUID()).isEmpty()){
            helper.fail("Unsupported unsafe shaft was approved: "+result);
        }else helper.succeed();
    }

    /** A real Builder can climb from an existing elevated roof and recover
     * only its owned paid scaffolding after physically returning to that roof.
     */
    @SuppressWarnings("removal")
    @GameTest(maxTicks=1600,skyAccess=true)
    public void builderCleansUpOwnedScaffoldsOnBuiltRoof(GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        for(int x=2;x<=6;x++)for(int z=2;z<=6;z++)
            helper.setBlock(x,3,z,Blocks.STONE);
        helper.setBlock(0,2,0,WorksiteContent.BUILDER_TABLE);
        helper.setBlock(1,2,0,Blocks.CHEST);
        BlockPos home=helper.absolutePos(new BlockPos(0,2,0));
        BlockPos storage=helper.absolutePos(new BlockPos(1,2,0));
        if(!(level.getBlockEntity(home) instanceof WorkerStationBlockEntity table)){
            helper.fail("No real Builder Planner on roof test");return;
        }
        ServerPlayer owner=helper.makeMockServerPlayerInLevel();
        // A Planner without a placed job keeps its origin at world zero.
        // Stage a tiny roof job so the real 128-block storage distance
        // validation uses the GameTest's actual local origin.
        if(!table.claimIfUnowned(owner.getUUID())
                ||!table.setPlacedBuilderPlan(owner.getUUID(),
                        "placed-roof-scaffold.litematic","Roof",
                        helper.absolutePos(new BlockPos(3,4,3)),0,0,1,0)
                ||!table.setBuilderMaterialStorage(owner.getUUID(),storage)){
            helper.fail("Could not register owned roof materials");return;
        }
        BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        BlockPos spawn=helper.absolutePos(new BlockPos(3,4,3));
        mob.setPos(spawn.getX()+.5,spawn.getY(),spawn.getZ()+.5);
        mob.setItem(0,new ItemStack(Items.SCAFFOLDING,24));
        level.addFreshEntity(mob);
        if(!table.registerBuilderWorker(mob.getUUID())){
            helper.fail("Cannot register roof Builder");return;
        }
        BlockPos target=helper.absolutePos(new BlockPos(5,7,4));
        int[] phase={0},built={0};
        long[] since={helper.getTick()};
        helper.onEachTick(()->{
            if(!mob.isAlive()){helper.fail("Roof Builder disappeared");return;}
            if(helper.getTick()-since[0]>650){
                helper.fail("Roof scaffold timeout phase="+phase[0]
                        +" feet="+mob.position()
                        +" tower="+BuilderScaffolding.debugClimb(mob)
                        +" owned="+table.scaffoldPositions(mob.getUUID()).size());
                return;
            }
            if(phase[0]==0){
                if(helper.getTick()%5==0){
                    var result=BuilderScaffolding.reach(
                            level,mob,table,owner,target,List.of());
                    if(result==BuilderScaffolding.Result.NO_SAFE_TOWER
                            ||result==BuilderScaffolding.Result.NEED_MATERIALS){
                        helper.fail("Roof scaffold route unavailable: "+result
                                +" at "+mob.position());return;
                    }
                }
                built[0]=Math.max(built[0],
                        table.scaffoldPositions(mob.getUUID()).size());
                if(mob.getY()>=target.getY()-.12&&built[0]>=3){
                    if(!level.getBlockState(spawn.below()).is(Blocks.STONE)){
                        helper.fail("Scaffolding damaged the existing roof");return;
                    }
                    phase[0]=1;since[0]=helper.getTick();
                }
            }else if(phase[0]==1){
                if(helper.getTick()%5==0)
                    BuilderScaffolding.descendForTravel(level,mob,table);
                if(mob.onGround()
                        &&Math.abs(mob.getY()-spawn.getY())<.22
                        &&table.scaffoldPositions(mob.getUUID()).stream()
                          .noneMatch(p->p.getX()==mob.blockPosition().getX()
                                        &&p.getZ()==mob.blockPosition().getZ())){
                    phase[0]=2;since[0]=helper.getTick();
                }
            }else{
                if(helper.getTick()%5==0)
                    BuilderScaffolding.retrieve(level,mob,table,false);
                if(table.scaffoldPositions(mob.getUUID()).isEmpty()){
                    if(built[0]<3||!level.getBlockState(spawn.below()).is(Blocks.STONE)){
                        helper.fail("Roof scaffolding was not built/cleaned");
                        return;
                    }
                    System.out.println("[ROOF SCAFFOLD CLEANUP] PASS physical="
                            +built[0]+" recovered=true roofIntact=true");
                    helper.succeed();
                }
            }
        });
    }


    /** A roof that stops at X=4 forces a genuine five-cell
     * cantilever from the roof-supported tower to remote work at X=9.
     * This is a physical Builder, not synthetic path traversal.
     */
    @GameTest(maxTicks=1800,skyAccess=true)
    public void builderExtendsFiveScaffoldCellsFromExistingRoof(GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        for(int x=0;x<12;x++)for(int z=0;z<10;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        for(int x=2;x<=4;x++)for(int z=2;z<=5;z++)
            helper.setBlock(x,3,z,Blocks.STONE);
        helper.setBlock(0,2,0,WorksiteContent.BUILDER_TABLE);
        helper.setBlock(1,2,0,Blocks.CHEST);
        BlockPos home=helper.absolutePos(new BlockPos(0,2,0));
        if(!(level.getBlockEntity(home) instanceof WorkerStationBlockEntity table)){
            helper.fail("No roof bridge Planner");return;
        }
        ServerPlayer owner=helper.makeMockServerPlayerInLevel();
        // Place the test roof's supported starting tower on the
        // actual world axis: GameTest structure rotations must not
        // accidentally move the tower off the prebuilt roof fixture.
        BlockPos goal=helper.absolutePos(new BlockPos(9,7,4));
        BlockPos start=goal.west(6).atY(goal.getY()-3);
        // Keep the original five-span normal-bridge fixture separate
        // from two-cell-clearance floating work-platform behavior.
        level.setBlock(goal.below(),Blocks.STONE.defaultBlockState(),3);
        level.getChunkSource().addTicketWithRadius(
                net.minecraft.server.level.TicketType.FORCED,
                new net.minecraft.world.level.ChunkPos(goal.getX()>>4,
                        goal.getZ()>>4),3);
        level.setBlock(start.below(),Blocks.STONE.defaultBlockState(),3);
        level.setBlock(start.west().below(),Blocks.STONE.defaultBlockState(),3);
        // The dedicated bridge fixture is an OPEN roof gap. Older
        // test-structure snapshots may contain background filler at this
        // elevation; clear that fixture space, never any live build cell.
        for(int n=1;n<=5;n++){
            BlockPos bridge=start.east(n).atY(goal.getY()-1);
            // No second roof support may let the planner select a shorter
            // tower: this fixture explicitly tests the five-cell span.
            level.setBlock(start.east(n).below(),
                    Blocks.AIR.defaultBlockState(),3);
            for(int dy=0;dy<=2;dy++)
                level.setBlock(bridge.above(dy),Blocks.AIR.defaultBlockState(),3);
        }
        if(!table.claimIfUnowned(owner.getUUID())
                ||!table.setPlacedBuilderPlan(owner.getUUID(),
                        "placed-five-span.litematic","Five Span",
                        start,0,0,1,0)
                ||!table.setBuilderMaterialStorage(owner.getUUID(),
                        helper.absolutePos(new BlockPos(1,2,0)))){
            helper.fail("No linked roof bridge material storage");return;
        }
        BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        mob.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        mob.setItem(0,new ItemStack(Items.SCAFFOLDING,32));
        level.addFreshEntity(mob);
        table.registerBuilderWorker(mob.getUUID());
        int[] stage={0},largest={0};
        long[] startTick={helper.getTick()};
        helper.onEachTick(()->{
            if(!mob.isAlive()){helper.fail("Five-span Builder disappeared");return;}
            if(helper.getTick()-startTick[0]>750){
                helper.fail("Five-span stalled phase="+stage[0]
                    +" "+BuilderScaffoldPlanA.status(mob)
                    +" feet="+mob.position()
                    +" tracked="+table.scaffoldPositions(mob.getUUID()).size());
                return;
            }
            if(stage[0]==0){
                // A freshly spawned real entity has not finished settling
                // on the roof at test tick zero; navigation cannot prove a
                // physical first leg until its feet are grounded.
                if(helper.getTick()>=10&&mob.onGround()
                        &&helper.getTick()%5==0){
                    var result=BuilderScaffoldPlanA.reach(
                            level,mob,table,owner,goal,List.of());
                    if(result==BuilderScaffoldPlanA.Result.NO_SAFE_ROUTE
                            ||result==BuilderScaffoldPlanA.Result.NEED_MATERIALS){
                        helper.fail("Five-cell bridge rejected "+result
                            +" feet="+mob.position()
                            +" "+BuilderScaffoldPlanA.status(mob)
                            +" "+BuilderScaffoldPlanA.diagnoseAccess(
                                level,mob,table,goal,List.of()));return;
                    }
                }
                largest[0]=Math.max(largest[0],
                        table.scaffoldPositions(mob.getUUID()).size());
                if(mob.onGround()&&BuilderScaffoldPlanA.hasPlan(mob)
                        &&BuilderScaffoldPlanA.status(mob).contains("PLAN_A READY")
                        &&mob.getY()>=goal.getY()-.15){
                    stage[0]=1;startTick[0]=helper.getTick();
                }
            }else if(stage[0]==1){
                if(helper.getTick()%5==0)
                    BuilderScaffoldPlanA.returnToGround(level,mob,table);
                if(!BuilderScaffoldPlanA.hasPlan(mob)
                        &&mob.onGround()
                        &&Math.abs(mob.getY()-start.getY())<.3){
                    stage[0]=2;startTick[0]=helper.getTick();
                }
            }else{
                if(helper.getTick()%5==0)
                    BuilderScaffolding.retrieve(level,mob,table,false);
                if(table.scaffoldPositions(mob.getUUID()).isEmpty()){
                    if(largest[0]<8
                        ||!level.getBlockState(start.below()).is(Blocks.STONE)){
                        helper.fail("Five-span structure/recovery not verified");
                        return;
                    }
                    System.out.println("[FIVE SPAN ROOF] PASS blocks="+largest[0]
                            +" cleanup=true feet="+mob.position());
                    helper.succeed();
                }
            }
        });
    }

    /**
     * Full blocks need no connected neighbor, but a worker must still
     * stand on something to place a floating stone block. Build only
     * temporary owned scaffold TWO cells from the intended cube, place
     * with real 0.8m clearance, retrace and reclaim every scaffold.
     */
    @GameTest(maxTicks=1200,skyAccess=true)
    public void constructsFloatingDioriteFromPaidTemporaryWorkPlatform(
            GameTestHelper h){
        ServerLevel level=h.getLevel();
        for(int x=0;x<9;x++)for(int z=0;z<8;z++)
            h.setBlock(x,1,z,Blocks.STONE);
        h.setBlock(0,2,0,WorksiteContent.BUILDER_TABLE);
        h.setBlock(1,2,0,Blocks.CHEST);
        BlockPos home=h.absolutePos(new BlockPos(0,2,0));
        if(!(level.getBlockEntity(home) instanceof WorkerStationBlockEntity table)){
            h.fail("No Builder Planner");return;
        }
        ServerPlayer owner=h.makeMockServerPlayerInLevel();
        if(!table.claimIfUnowned(owner.getUUID())
                ||!table.setPlacedBuilderPlan(owner.getUUID(),
                        "placed-floating-platform.litematic",
                        "Floating work platform",home,0,0,1,0)
                ||!table.setBuilderMaterialStorage(owner.getUUID(),
                        h.absolutePos(new BlockPos(1,2,0)))){
            h.fail("Failed to assign temporary scaffold ownership");return;
        }
        BlockPos start=h.absolutePos(new BlockPos(3,2,3));
        BlockPos target=h.absolutePos(new BlockPos(6,6,3));
        BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        mob.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        mob.setItem(0,new ItemStack(Items.SCAFFOLDING,32));
        level.addFreshEntity(mob);
        if(!table.registerBuilderWorker(mob.getUUID())){
            h.fail("Could not register Builder");return;
        }
        int[] phase={0},deployed={0};
        long startTick=h.getTick();
        h.onEachTick(()->{
            if(!mob.isAlive()||mob.isNoGravity()){
                h.fail("Floating-block worker lost real gravity");return;
            }
            if(h.getTick()-startTick>1050){
                h.fail("Floating-block construction stalled at "+mob.position()
                        +" phase="+phase[0]
                        +" "+BuilderScaffoldPlanA.status(mob));return;
            }
            if(phase[0]==0){
                if(h.getTick()<12||!mob.onGround()
                        ||h.getTick()%5!=0)return;
                if(!BuilderScaffoldPlanA.needsTemporaryWorkPlatform(
                                level,target)
                        ||!Blocks.DIORITE.defaultBlockState().canSurvive(
                                level,target)){
                    h.fail("Diorite is not free-floating in Minecraft");return;
                }
                var result=BuilderScaffoldPlanA.reach(
                        level,mob,table,owner,target,List.of());
                if(result==BuilderScaffoldPlanA.Result.NO_SAFE_ROUTE
                        ||result==BuilderScaffoldPlanA.Result.NEED_MATERIALS){
                    h.fail("Temporary construction platform inaccessible "
                            +result+" "+BuilderScaffoldPlanA.diagnoseAccess(
                                    level,mob,table,target,List.of()));return;
                }
                deployed[0]=Math.max(deployed[0],
                        table.scaffoldPositions(mob.getUUID()).size());
                if(result==BuilderScaffoldPlanA.Result.READY){
                    if(!BuilderScaffoldPlanA.safeTemporaryWorkPose(
                            level,mob,table,target)
                            ||!BuilderPlacementSpace.horizontalClear(target,mob)
                            ||!BuilderPlacementSpace.ready(level,mob,table,target)
                            ||!level.getBlockState(target.below()).isAir()){
                        h.fail("No valid real scaffold work pose or floating "
                                +"material area "+mob.position()+" "
                                +BuilderScaffoldPlanA.status(mob));return;
                    }
                    if(!level.setBlock(target,Blocks.DIORITE.defaultBlockState(),3)
                            ||!level.getBlockState(target).is(Blocks.DIORITE)){
                        h.fail("Could not place genuine floating diorite");return;
                    }
                    phase[0]=1;
                }
            }else if(phase[0]==1){
                if(h.getTick()%5==0)
                    BuilderScaffoldPlanA.returnToGround(level,mob,table);
                if(!BuilderScaffoldPlanA.hasPlan(mob)&&mob.onGround()
                        &&Math.abs(mob.getY()-start.getY())<.25)
                    phase[0]=2;
            }else{
                if(h.getTick()%5==0)
                    BuilderScaffolding.retrieve(level,mob,table,false);
                if(table.scaffoldPositions(mob.getUUID()).isEmpty()){
                    if(deployed[0]<2
                            ||!level.getBlockState(target).is(Blocks.DIORITE)
                            ||!level.getBlockState(target.below()).isAir()){
                        h.fail("Floating construction broke dome or lost "
                                +"physical scaffold accounting");return;
                    }
                    System.out.println("[FLOATING BUILD] PASS paid work platform "
                            +"placed unattached diorite and reclaimed "
                            +deployed[0]+" owned scaffolds");
                    h.succeed();
                }
            }
        });
    }

}
