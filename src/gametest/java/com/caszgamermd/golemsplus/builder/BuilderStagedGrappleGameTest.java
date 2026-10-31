package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorksiteContent;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/** Nested platforms block a direct rope but allow two physical climbs. */
public final class BuilderStagedGrappleGameTest {
    @GameTest(maxTicks=700,skyAccess=true)
    public void reachesSafeWorkPoseViaRealIntermediateLedge(GameTestHelper h){
        var world=h.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            h.setBlock(x,1,z,Blocks.STONE);
        for(int x=2;x<=6;x++)for(int z=1;z<=5;z++)
            h.setBlock(x,3,z,Blocks.STONE);
        for(int x=3;x<=5;x++)for(int z=2;z<=4;z++)
            h.setBlock(x,5,z,Blocks.STONE);
        h.setBlock(0,2,0,WorksiteContent.BUILDER_TABLE);
        var table=(WorkerStationBlockEntity)world.getBlockEntity(
                h.absolutePos(new BlockPos(0,2,0)));
        BlockPos start=h.absolutePos(new BlockPos(7,2,3));
        BlockPos target=h.absolutePos(new BlockPos(6,6,3));
        var mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        mob.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        mob.setItem(0,new ItemStack(WorksiteContent.GRAPPLE_HOOK));
        world.addFreshEntity(mob);
        final boolean[] sawIntermediate={false};
        final double[] previousY={mob.getY()};
        h.onEachTick(()->{
            if(previousY[0]-mob.getY()>.65||!mob.isAlive()){
                h.fail("Staged climb lost controlled physical movement");return;
            }
            previousY[0]=mob.getY();
            if(mob.onGround()&&mob.blockPosition().getY()==start.getY()+2
                    &&!BuilderGrappleAccess.active(mob))sawIntermediate[0]=true;
            if(h.getTick()>=10&&h.getTick()%5==0){
                var result=BuilderGrappleAccess.reachWork(world,mob,table,target);
                if(result==BuilderGrappleAccess.Result.NO_SAFE_ANCHOR
                        ||result==BuilderGrappleAccess.Result.NEED_HOOK){
                    h.fail("Validated two-stage access rejected "+result
                            +" feet="+mob.position());return;
                }
            }
            if(mob.onGround()&&!BuilderGrappleAccess.active(mob)
                    &&BuilderPlacementSpace.horizontalClear(target,mob)
                    &&BuilderConstructionPlanner.safeStand(
                            world,mob,target,mob.blockPosition())){
                if(!sawIntermediate[0]
                        ||mob.carriedCount(WorksiteContent.GRAPPLE_HOOK)!=1){
                    h.fail("Must use intermediate ledge and retain reusable hook");return;
                }
                System.out.println("[STAGED GRAPPLE] PASS two real climbs to safe work pose "
                        +"tick="+h.getTick());
                h.succeed();return;
            }
            if(h.getTick()>620)h.fail("Staged grapple stalled "+mob.position()
                    +" "+BuilderGrappleAccess.debug(mob));
        });
    }
    @GameTest(maxTicks=900,skyAccess=true)
    public void previewsThreeClimbsPastTwoBlockingOverhangs(GameTestHelper h){
        var world=h.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            h.setBlock(x,0,z,Blocks.STONE);
        for(int x=1;x<=6;x++)for(int z=0;z<=6;z++)
            h.setBlock(x,2,z,Blocks.STONE);
        for(int x=2;x<=5;x++)for(int z=1;z<=5;z++)
            h.setBlock(x,4,z,Blocks.STONE);
        for(int x=3;x<=4;x++)for(int z=2;z<=4;z++)
            h.setBlock(x,6,z,Blocks.STONE);
        h.setBlock(0,1,7,WorksiteContent.BUILDER_TABLE);
        var table=(WorkerStationBlockEntity)world.getBlockEntity(
                h.absolutePos(new BlockPos(0,1,7)));
        BlockPos start=h.absolutePos(new BlockPos(7,1,3));
        BlockPos target=h.absolutePos(new BlockPos(6,7,3));
        var mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        mob.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        mob.setItem(0,new ItemStack(WorksiteContent.GRAPPLE_HOOK));
        world.addFreshEntity(mob);
        final boolean[] sawLower={false},sawMiddle={false};
        final double[] previousY={mob.getY()};
        h.onEachTick(()->{
            if(previousY[0]-mob.getY()>.65||!mob.isAlive()){
                h.fail("Three-stage climb lost controlled movement");return;
            }
            previousY[0]=mob.getY();
            if(mob.onGround()&&!BuilderGrappleAccess.active(mob)){
                if(mob.blockPosition().getY()==start.getY()+2)sawLower[0]=true;
                if(mob.blockPosition().getY()==start.getY()+4)sawMiddle[0]=true;
            }
            if(h.getTick()>=10&&h.getTick()%5==0){
                var result=BuilderGrappleAccess.reachWork(world,mob,table,target);
                if(result==BuilderGrappleAccess.Result.NO_SAFE_ANCHOR
                        ||result==BuilderGrappleAccess.Result.NEED_HOOK){
                    h.fail("Three-stage route rejected "+result
                            +" feet="+mob.position());return;
                }
            }
            if(mob.onGround()&&!BuilderGrappleAccess.active(mob)
                    &&BuilderPlacementSpace.horizontalClear(target,mob)
                    &&BuilderConstructionPlanner.safeStand(
                            world,mob,target,mob.blockPosition())){
                if(!sawLower[0]||!sawMiddle[0]
                        ||mob.carriedCount(WorksiteContent.GRAPPLE_HOOK)!=1){
                    h.fail("Must use both intermediate ledges and retain hook"
                            +" feet="+mob.position()+" lower="+sawLower[0]
                            +" middle="+sawMiddle[0]+" "
                            +BuilderGrappleAccess.debug(mob));return;
                }
                System.out.println("[STAGED GRAPPLE] PASS three real climbs "
                        +"tick="+h.getTick());
                h.succeed();return;
            }
            if(h.getTick()>820)h.fail("Three-stage grapple stalled "+mob.position()
                    +" "+BuilderGrappleAccess.debug(mob));
        });
    }
    @GameTest(maxTicks=600,skyAccess=true)
    public void walksDownToActualRopeEntryBeforeClimbing(GameTestHelper h){
        var world=h.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            h.setBlock(x,1,z,Blocks.STONE);
        h.setBlock(1,2,3,Blocks.STONE);
        for(int x=4;x<=6;x++)for(int z=2;z<=4;z++)
            h.setBlock(x,4,z,Blocks.STONE);
        h.setBlock(0,2,0,WorksiteContent.BUILDER_TABLE);
        var table=(WorkerStationBlockEntity)world.getBlockEntity(
                h.absolutePos(new BlockPos(0,2,0)));
        BlockPos start=h.absolutePos(new BlockPos(1,3,3));
        BlockPos target=h.absolutePos(new BlockPos(7,5,3));
        var mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        mob.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        mob.setItem(0,new ItemStack(WorksiteContent.GRAPPLE_HOOK));
        world.addFreshEntity(mob);
        final boolean[] sawGroundEntry={false};
        h.onEachTick(()->{
            if(mob.onGround()&&mob.blockPosition().getY()==start.getY()-1)
                sawGroundEntry[0]=true;
            if(h.getTick()>=10&&h.getTick()%5==0){
                var result=BuilderGrappleAccess.reachWork(world,mob,table,target);
                if(result==BuilderGrappleAccess.Result.NO_SAFE_ANCHOR
                        ||result==BuilderGrappleAccess.Result.NEED_HOOK){
                    h.fail("Reachable lower rope entry rejected "+result);return;
                }
            }
            if(mob.onGround()&&!BuilderGrappleAccess.active(mob)
                    &&BuilderPlacementSpace.horizontalClear(target,mob)
                    &&BuilderConstructionPlanner.safeStand(
                            world,mob,target,mob.blockPosition())){
                if(!sawGroundEntry[0]){
                    h.fail("Must physically walk down to the rope entry"
                            +" feet="+mob.position()+" "
                            +BuilderGrappleAccess.debug(mob));return;
                }
                System.out.println("[ROPE ENTRY] PASS walked down then climbed "
                        +"tick="+h.getTick());
                h.succeed();return;
            }
            if(h.getTick()>520)h.fail("Lower rope entry stalled "+mob.position()
                    +" "+BuilderGrappleAccess.debug(mob));
        });
    }
    @GameTest(maxTicks=750,skyAccess=true)
    public void usesRemoteLedgeBeyondTheTargetsWorkRadius(GameTestHelper h){
        var world=h.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            h.setBlock(x,0,z,Blocks.STONE);
        // A two-block-high base cannot be jumped from the ground. Its
        // west rim is the only rope entry; the near-target rims border
        // the test-room boundary. All successful motion stays inside.
        for(int x=2;x<=7;x++)for(int z=0;z<8;z++){
            h.setBlock(x,1,z,Blocks.STONE);
            h.setBlock(x,2,z,Blocks.STONE);
        }
        for(int x=1;x<=6;x++)h.setBlock(x,3,3,Blocks.STONE);
        for(int x=5;x<=6;x++)for(int z=2;z<=4;z++)
            h.setBlock(x,6,z,Blocks.STONE);
        h.setBlock(0,1,7,WorksiteContent.BUILDER_TABLE);
        var table=(WorkerStationBlockEntity)world.getBlockEntity(
                h.absolutePos(new BlockPos(0,1,7)));
        BlockPos start=h.absolutePos(new BlockPos(0,1,3));
        BlockPos target=h.absolutePos(new BlockPos(7,7,3));
        var mob=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        mob.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        mob.setItem(0,new ItemStack(WorksiteContent.GRAPPLE_HOOK));
        world.addFreshEntity(mob);
        final boolean[] remoteLedge={false};
        h.onEachTick(()->{
            if(mob.onGround()&&mob.getY()>start.getY()+1.5
                    &&mob.getX()<target.getX()-4)remoteLedge[0]=true;
            if(h.getTick()>=10&&h.getTick()%5==0){
                var result=BuilderGrappleAccess.reachWork(world,mob,table,target);
                if(result==BuilderGrappleAccess.Result.NO_SAFE_ANCHOR
                        ||result==BuilderGrappleAccess.Result.NEED_HOOK){
                    h.fail("Remote real access ledge rejected "+result
                            +" feet="+mob.position()+" "
                            +BuilderPlacementSpace.diagnoseApproach(
                                    world,mob,target,
                                    at->BuilderConstructionPlanner.safeStand(
                                            world,mob,target,at)));return;
                }
            }
            if(mob.onGround()&&!BuilderGrappleAccess.active(mob)
                    &&BuilderPlacementSpace.horizontalClear(target,mob)
                    &&BuilderConstructionPlanner.safeStand(
                            world,mob,target,mob.blockPosition())){
                if(!remoteLedge[0]){
                    h.fail("Must use access outside the four-block target window");return;
                }
                System.out.println("[REMOTE ACCESS] PASS remote ledge then actual work pose "
                        +"tick="+h.getTick());
                h.succeed();return;
            }
            if(h.getTick()>670)h.fail("Remote access stalled "+mob.position()
                    +" "+BuilderGrappleAccess.debug(mob));
        });
    }
}
