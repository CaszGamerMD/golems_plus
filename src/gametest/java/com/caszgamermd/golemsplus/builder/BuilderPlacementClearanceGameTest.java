package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/** Physical Fabric 26.2 regression: a 0.65-wide Builder cannot place a
 * block until its center is at least 0.8 blocks outside the nearest
 * edge of that future cube. Use actual velocity and real collisions.
 */
public final class BuilderPlacementClearanceGameTest {
    @GameTest(maxTicks=260,skyAccess=true)
    public void physicallyStepsAtLeastPointEightFromOwnPlacementEdge(
            GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        helper.setBlock(0,2,0,WorksiteContent.BUILDER_TABLE);
        BlockPos planner=helper.absolutePos(new BlockPos(0,2,0));
        if(!(level.getBlockEntity(planner) instanceof WorkerStationBlockEntity table)){
            helper.fail("Missing actual physical Planner");return;
        }
        BlockPos target=helper.absolutePos(new BlockPos(3,2,3));
        BuilderGolem builder=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        // On the adjacent tile but its real shoulder overlaps the future
        // target cell, as happened repeatedly in the dome log.
        builder.setPos(target.getX()+1.04,target.getY(),target.getZ()+.5);
        level.addFreshEntity(builder);
        if(!table.registerBuilderWorker(builder.getUUID())){
            helper.fail("Could not register real Builder");return;
        }
        final boolean[] sawBlock={false};
        final double[] previousX={builder.getX()};
        helper.onEachTick(()->{
            if(!builder.isAlive()){
                helper.fail("Real Builder died during sidestep");return;
            }
            if(Math.abs(builder.getX()-previousX[0])>.29){
                helper.fail("Discontinuous horizontal sidestep (teleport?) dx="
                        +(builder.getX()-previousX[0]));return;
            }
            previousX[0]=builder.getX();
            if(builder.blockPosition().getY()!=target.getY()){
                helper.fail("Builder left the actual ground layer");return;
            }
            if(helper.getTick()%5!=0)return;
            boolean ready=BuilderPlacementSpace.ready(level,builder,table,target);
            if(!ready){sawBlock[0]=true;return;}
            double edgeGap=builder.getX()-(target.getX()+1.0);
            if(!sawBlock[0]||edgeGap<.80-.015){
                helper.fail("Placement was permitted too close: edgeGap="
                        +edgeGap+" builder="+builder.position());return;
            }
            if(!level.setBlock(target,Blocks.IRON_BLOCK.defaultBlockState(),3)){
                helper.fail("Real target refused placement");return;
            }
            if(!builder.getBoundingBox().intersects(
                    new net.minecraft.world.phys.AABB(target).inflate(.1,0,.1))){
                System.out.println("[CLEARANCE] PASS physical center-to-edge="
                        +edgeGap+" (>0.8), no teleport or self collision, tick="
                        +helper.getTick());
                helper.succeed();
            }else helper.fail("Builder remains inside the placed solid block");
        });
    }

    @GameTest(maxTicks=230,skyAccess=true)
    public void physicallyClearsHeadWhenWorkingOneLevelBelowNextBlock(
            GameTestHelper helper){
        ServerLevel level=helper.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            helper.setBlock(x,1,z,Blocks.STONE);
        helper.setBlock(0,2,0,WorksiteContent.BUILDER_TABLE);
        var table=(WorkerStationBlockEntity)level.getBlockEntity(
                helper.absolutePos(new BlockPos(0,2,0)));
        BlockPos upperTarget=helper.absolutePos(new BlockPos(3,3,3));
        BuilderGolem worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        // Feet at targetY-1, head protrudes into the future AIR cell.
        worker.setPos(upperTarget.getX()+1.5,
                upperTarget.getY()-1,upperTarget.getZ()+.5);
        level.addFreshEntity(worker);
        if(!table.registerBuilderWorker(worker.getUUID())){
            helper.fail("Worker registration failed");return;
        }
        final boolean[] blocked={false};
        final double[] lastX={worker.getX()};
        helper.onEachTick(()->{
            double delta=Math.abs(worker.getX()-lastX[0]);
            lastX[0]=worker.getX();
            if(delta>.30){
                helper.fail("Worker teleported while avoiding overhead block");
                return;
            }
            if(helper.getTick()<8)return;
            if(!worker.onGround()
                    ||worker.blockPosition().getY()!=upperTarget.getY()-1){
                helper.fail("Worker must stay on the real lower floor");return;
            }
            if(helper.getTick()%5!=0)return;
            boolean ready=BuilderPlacementSpace.ready(
                    level,worker,table,upperTarget);
            if(!ready){blocked[0]=true;return;}
            double gap=worker.getX()-(upperTarget.getX()+1.0);
            if(!blocked[0]||gap<.8-.015){
                helper.fail("Didn't move lower Builder 0.8 away: gap="+gap);
                return;
            }
            if(!level.setBlock(upperTarget,
                    Blocks.IRON_BLOCK.defaultBlockState(),3)){
                helper.fail("World refused overhead block placement");return;
            }
            if(worker.getBoundingBox().intersects(
                    new net.minecraft.world.phys.AABB(upperTarget))){
                helper.fail("Builder's head overlaps the overhead cube");
                return;
            }
            System.out.println("[CLEARANCE BELOW] PASS feet at Y-1, "
                    +"head-clear sidestep to 0.8+ gap="+gap
                    +" with physical ground/collision");
            helper.succeed();
        });
    }
    @GameTest(maxTicks=400,skyAccess=true)
    public void narrowLedgeUsesWalkingEscapeWhenNearSideIsBlocked(GameTestHelper h){
        var world=h.getLevel();
        for(int x=0;x<8;x++)h.setBlock(x,1,3,Blocks.STONE);
        h.setBlock(1,2,3,Blocks.STONE);
        h.setBlock(1,3,3,Blocks.STONE);
        h.setBlock(0,1,0,WorksiteContent.BUILDER_TABLE);
        var table=(WorkerStationBlockEntity)world.getBlockEntity(h.absolutePos(new BlockPos(0,1,0)));
        BlockPos target=h.absolutePos(new BlockPos(3,2,3));
        var mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        mob.setPos(target.getX()-.652,target.getY(),target.getZ()+.854);
        world.addFreshEntity(mob);
        table.registerBuilderWorker(mob.getUUID());
        boolean[] blocked={false};
        h.onEachTick(()->{
            if(h.getTick()<10||h.getTick()%5!=0)return;
            if(!mob.onGround()||mob.blockPosition().getY()!=target.getY()){
                h.fail("Escape left the actual narrow ledge "+mob.position());return;
            }
            if(BuilderPlacementSpace.ready(world,mob,table,target)){
                if(!blocked[0]||!BuilderPlacementSpace.horizontalClear(target,mob)){
                    h.fail("Unsafe clearance accepted");return;
                }
                world.setBlock(target,Blocks.IRON_BLOCK.defaultBlockState(),3);
                h.succeed();return;
            }
            blocked[0]=true;
            if(h.getTick()>350)h.fail("Walking escape stalled "+mob.position()
                    +" "+BuilderPlacementSpace.debugYield(world,mob));
        });
    }

    /** The placer requests clearance; the recipient performs its own
     * physical movement. The requester never displaces its teammate. */
    @GameTest(maxTicks=280,skyAccess=true)
    public void builderAsksTeammateToMoveBeforePlacing(GameTestHelper h){
        var world=h.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            h.setBlock(x,1,z,Blocks.STONE);
        h.setBlock(0,2,0,WorksiteContent.BUILDER_TABLE);
        var table=(WorkerStationBlockEntity)world.getBlockEntity(
                h.absolutePos(new BlockPos(0,2,0)));
        BlockPos target=h.absolutePos(new BlockPos(3,2,3));
        var blocker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        blocker.setPos(target.getX()+1.04,target.getY(),target.getZ()+.5);
        world.addFreshEntity(blocker);
        var placer=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        placer.setPos(target.getX()+3.5,target.getY(),target.getZ()+.5);
        world.addFreshEntity(placer);
        var owner=h.makeMockServerPlayerInLevel();
        if(!table.claimIfUnowned(owner.getUUID())
                ||!table.setCrewTarget(owner.getUUID(),2)
                ||!table.registerBuilderWorker(blocker.getUUID())
                ||!table.registerBuilderWorker(placer.getUUID())){
            h.fail("Cannot register cooperating builders");return;
        }
        // The real worker tick requires the same persisted home/owner link
        // as a player-created Builder. Without it the test only sends a
        // request; the recipient never executes BuilderBuildService.tick.
        blocker.assign(h.absolutePos(new BlockPos(0,2,0)),owner.getUUID());
        placer.assign(h.absolutePos(new BlockPos(0,2,0)),owner.getUUID());
        // Queue the request before the first idle worker tick. Otherwise
        // the unassigned Builder may independently stroll away before
        // this test checks for a request at tick 10.
        boolean initiallyBlocked=!BuilderPlacementSpace.ready(
                world,placer,table,target);
        if(!initiallyBlocked||!BuilderPlacementSpace.debugYield(world,blocker)
                .contains("moveRequestedBy=")){
            h.fail("The placer did not request its teammate's clearance");return;
        }
        if(BuilderPlacementSpace.answerMoveRequest(world,blocker,false)){
            h.fail("A busy teammate yielded before completing its placement");return;
        }
        final boolean[] asked={true};
        final double[] previousX={blocker.getX()};
        h.onEachTick(()->{
            if(Math.abs(blocker.getX()-previousX[0])>.30){
                h.fail("A move request teleported its recipient");return;
            }
            previousX[0]=blocker.getX();
            if(h.getTick()<10||h.getTick()%5!=0)return;
            boolean clear=BuilderPlacementSpace.ready(
                    world,placer,table,target);
            if(!clear){asked[0]=true;return;}
            if(!asked[0]||!BuilderPlacementSpace.horizontalClear(target,blocker)){
                h.fail("Teammate clearance was granted without a real reply");return;
            }
            System.out.println("[BUILDER COOPERATION] PASS requested move, "
                    +"recipient cleared target by physics in "
                    +h.getTick()+" ticks");
            h.succeed();
        });
    }

}
