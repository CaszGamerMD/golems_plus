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
        // Begin at the verified solid-roof tower foot. The separate
        // roof-access tests exercise approaching a remote entrance.
        BlockPos start=helper.absolutePos(new BlockPos(3,4,4));
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
        BlockPos goal=helper.absolutePos(new BlockPos(9,7,4));
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
                            +" "+BuilderScaffoldPlanA.status(mob));return;
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

}
