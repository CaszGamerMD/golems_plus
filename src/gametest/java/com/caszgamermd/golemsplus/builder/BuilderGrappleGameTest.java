package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorksiteContent;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/** Real 26.2 server Builder: no teleport, no scaffold, real collision,
 * reusable paid inventory hook, ascent -> ledge -> safe rappel.
 */
public final class BuilderGrappleGameTest {
    // In the 26.2 registry, dyed wool variants are resolved by ID instead
    // of depending on removed old Blocks.<COLOR>_WOOL Java constants.
    private static final net.minecraft.world.level.block.Block PURPLE=
            BuiltInRegistries.BLOCK.getValue(Identifier.tryParse("minecraft:purple_wool"));
    private static final net.minecraft.world.level.block.Block LIME=
            BuiltInRegistries.BLOCK.getValue(Identifier.tryParse("minecraft:lime_wool"));
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
        helper.setBlock(4,6,3,PURPLE);
        helper.setBlock(5,1,3,LIME);
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
                    if(!level.getBlockState(anchor).is(PURPLE)){
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
                            ||!level.getBlockState(anchor).is(PURPLE)){
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
        helper.setBlock(4,23,3,PURPLE);
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
    @GameTest(maxTicks=900,skyAccess=true)
    public void climbsExactlyTwentyBlocksThenRappelsSafely(
            GameTestHelper helper){
        ServerLevel world=helper.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        helper.setBlock(0,2,0,WorksiteContent.BUILDER_TABLE);
        var table=(WorkerStationBlockEntity)world.getBlockEntity(
                helper.absolutePos(new BlockPos(0,2,0)));
        BlockPos floor=helper.absolutePos(new BlockPos(5,2,3));
        BlockPos anchor=helper.absolutePos(new BlockPos(4,21,3));
        BlockPos top=anchor.above();
        if(top.getY()-floor.getY()!=20){
            helper.fail("Fixture is not exactly a 20-block climb");return;
        }
        helper.setBlock(4,21,3,PURPLE);
        helper.setBlock(5,1,3,LIME);
        BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        mob.setPos(floor.getX()+.5,floor.getY(),floor.getZ()+.5);
        mob.setItem(0,new ItemStack(WorksiteContent.GRAPPLE_HOOK));
        world.addFreshEntity(mob);
        final int[] phase={0};
        final long[] since={helper.getTick()};
        final double[] lastY={mob.getY()};
        helper.onEachTick(()->{
            long now=helper.getTick();
            double drop=lastY[0]-mob.getY();
            lastY[0]=mob.getY();
            if(!mob.isAlive()||drop>.65){
                helper.fail("20-block line dropped Builder "+drop);return;
            }
            if(now-since[0]>370){
                helper.fail("20-block "+phase[0]+" traversal stalled at "
                        +mob.position()+" "+BuilderGrappleAccess.debug(mob));
                return;
            }
            if(phase[0]==0){
                if(now%5==0){
                    var result=BuilderGrappleAccess.reach(world,mob,table,top);
                    if(result==BuilderGrappleAccess.Result.NO_SAFE_ANCHOR
                            ||result==BuilderGrappleAccess.Result.NEED_HOOK){
                        helper.fail("Denied valid 20-block grapple "+result);
                        return;
                    }
                }
                if(mob.onGround()&&!BuilderGrappleAccess.active(mob)
                        &&mob.blockPosition().getY()==top.getY()){
                    phase[0]=1;since[0]=now;
                    System.out.println("[GRAPPLE LIMIT] reached full 20 blocks tick="+now);
                }
            }else if(phase[0]==1){
                if(!BuilderGrappleAccess.rappelToward(world,mob,table,floor)){
                    helper.fail("Failed full 20-block rappel");return;
                }
                phase[0]=2;since[0]=now;
            }else if(mob.onGround()
                    &&mob.blockPosition().getY()==floor.getY()
                    &&!BuilderGrappleAccess.active(mob)){
                if(mob.carriedCount(WorksiteContent.GRAPPLE_HOOK)!=1
                        ||!world.getBlockState(anchor).is(PURPLE)){
                    helper.fail("Full-range traverse damaged item or anchor");
                    return;
                }
                System.out.println("[GRAPPLE LIMIT] PASS 20 up / 20 down tick="+now);
                helper.succeed();
            }
        });
    }


    @GameTest(maxTicks=80,skyAccess=true)
    public void missingRooftopHookCannotRecursivelyRequestItself(
            GameTestHelper helper){
        ServerLevel world=helper.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        helper.setBlock(0,2,0,WorksiteContent.BUILDER_TABLE);
        helper.setBlock(1,2,0,Blocks.CHEST);
        helper.setBlock(3,6,3,Blocks.STONE);
        BlockPos planner=helper.absolutePos(new BlockPos(0,2,0));
        BlockPos depot=helper.absolutePos(new BlockPos(1,2,0));
        var table=(WorkerStationBlockEntity)world.getBlockEntity(planner);
        var owner=helper.makeMockServerPlayerInLevel();
        if(!table.claimIfUnowned(owner.getUUID())
                ||!table.setPlacedBuilderPlan(owner.getUUID(),
                        "placed-missing-hook-test.litematic","Test",
                        planner,0,0,1,0)
                ||!table.setBuilderMaterialStorage(owner.getUUID(),depot)){
            helper.fail("Cannot stage real OnSite chest test");return;
        }
        if(!(world.getBlockEntity(depot) instanceof net.minecraft.world.Container chest)){
            helper.fail("Missing OnSite chest");return;
        }
        chest.setItem(0,new ItemStack(WorksiteContent.GRAPPLE_HOOK));
        BuilderGolem mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        BlockPos roof=helper.absolutePos(new BlockPos(3,7,3));
        mob.setPos(roof.getX()+.5,roof.getY(),roof.getZ()+.5);
        world.addFreshEntity(mob);
        // In the original bug, rappelToward -> loadGrapple ->
        // rappelForTravel -> rappelToward recursed to StackOverflowError
        // when equipment existed in the chest but not on the golem.
        helper.onEachTick(()->{
            if(helper.getTick()<10)return;
            if(helper.getTick()%5!=0)return;
            BuilderGrappleAccess.rappelToward(world,mob,table,depot);
            if(mob.carriedCount(WorksiteContent.GRAPPLE_HOOK)!=0){
                helper.fail("Remote hook acquired without visiting OnSite");
                return;
            }
            if(BuilderGrappleAccess.active(mob)){
                helper.fail("Started a rappel without carrying any hook");
                return;
            }
            if(helper.getTick()>=30){
                System.out.println("[GRAPPLE SAFETY] PASS missing hook on roof "
                        +"did not recurse, teleport item or create a rope");
                helper.succeed();
            }
        });
    }
}
