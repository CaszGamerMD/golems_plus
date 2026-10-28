package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorksiteContent;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

/** Real physics: one-block native jump preference and two directions of
 * grapple timeout/retraction. Never simulate by teleporting a Builder.
 */
public final class BuilderGrappleRecoveryGameTest {
    private static void ground(GameTestHelper h){
        for(int x=0;x<8;x++)for(int z=0;z<8;z++)
            h.setBlock(x,1,z,Blocks.STONE);
        h.setBlock(0,2,0,WorksiteContent.BUILDER_TABLE);
    }
    private static BuilderGolem golem(GameTestHelper h,BlockPos feet){
        ServerLevel world=h.getLevel();
        BuilderGolem g=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        g.setPos(feet.getX()+.5,feet.getY(),feet.getZ()+.5);
        g.setItem(0,new ItemStack(WorksiteContent.GRAPPLE_HOOK));
        world.addFreshEntity(g);
        return g;
    }
    @GameTest(maxTicks=320,skyAccess=true)
    public void oneBlockStepTriesNativeJumpBeforeHook(GameTestHelper h){
        ground(h);
        ServerLevel world=h.getLevel();
        h.setBlock(4,2,3,Blocks.STONE);
        BlockPos start=h.absolutePos(new BlockPos(3,2,3));
        BlockPos upper=h.absolutePos(new BlockPos(4,3,3));
        var table=(WorkerStationBlockEntity)world.getBlockEntity(
                h.absolutePos(new BlockPos(0,2,0)));
        BuilderGolem g=golem(h,start);
        final boolean[] began={false};
        h.onEachTick(()->{
            int tick=(int)h.getTick();
            if(tick<10)return;
            if(g.onGround()&&g.blockPosition().getY()==upper.getY()
                    &&!BuilderGrappleAccess.active(g)){
                System.out.println("[GRAPPLE JUMP] PASS 1-block native jump "
                        +"without throwing hook, tick="+tick);
                h.succeed();
                return;
            }
            if(tick%5!=0)return;
            var result=BuilderGrappleAccess.reach(world,g,table,upper);
            if(result==BuilderGrappleAccess.Result.MOVING)began[0]=true;
            if(BuilderGrappleAccess.active(g)){
                h.fail("Used Grapple Hook for reachable one-block native jump");
                return;
            }
            if(tick>=175)h.fail("Native jump not completed: result="+result
                    +" began="+began[0]+" feet="+g.position());
        });
    }

    @GameTest(maxTicks=460,skyAccess=true)
    public void failedAscentReturnsToGroundAndRejectsSameAnchor(
            GameTestHelper h){
        ground(h);
        ServerLevel world=h.getLevel();
        h.setBlock(4,6,3,Blocks.STONE);
        BlockPos start=h.absolutePos(new BlockPos(5,2,3));
        BlockPos anchor=h.absolutePos(new BlockPos(4,6,3));
        BlockPos top=anchor.above();
        var table=(WorkerStationBlockEntity)world.getBlockEntity(
                h.absolutePos(new BlockPos(0,2,0)));
        BuilderGolem g=golem(h,start);
        final boolean[] ascent={false},obstructed={false};
        final double[] oldY={g.getY()};
        h.onEachTick(()->{
            int tick=(int)h.getTick();
            double fall=oldY[0]-g.getY();oldY[0]=g.getY();
            if(!g.isAlive()||fall>.65){
                h.fail("Unsafe fall during climb recovery "+fall);return;
            }
            if(tick<10)return;
            String state=BuilderGrappleAccess.debug(g);
            if(tick%5==0&&!ascent[0]){
                BuilderGrappleAccess.reach(world,g,table,top);
                ascent[0]=BuilderGrappleAccess.active(g);
            }
            if(!obstructed[0]&&state.contains("STEP_ON_TOP")){
                // Block ONLY the upper ledge's destination, not the lower
                // rope column. Returning to ground stays physically safe.
                h.setBlock(4,7,3,Blocks.STONE);
                obstructed[0]=true;
            }
            if(obstructed[0]&&!BuilderGrappleAccess.active(g)
                    &&g.blockPosition().getY()==start.getY()
                    &&g.onGround()){
                var retry=BuilderGrappleAccess.reach(world,g,table,top);
                if(BuilderGrappleAccess.active(g)){
                    h.fail("Immediately reused rejected blocked climb hook");return;
                }
                System.out.println("[GRAPPLE RECOVERY] PASS ascent aborted, "
                        +"returned to ground, rejected blocked hook, retry="+retry);
                h.succeed();return;
            }
            if(tick>=330)h.fail("Ascent never disengaged: state="+state
                    +" feet="+g.position()+" blocked="+obstructed[0]);
        });
    }

    @GameTest(maxTicks=500,skyAccess=true)
    public void failedRappelReturnsUpAndTriesDifferentFace(
            GameTestHelper h){
        ground(h);
        ServerLevel world=h.getLevel();
        // Single upper rim; landing floor at Y=2. The rope is beside the
        // stone ledge, and its mid-column is initially empty.
        h.setBlock(4,6,3,Blocks.STONE);
        BlockPos start=h.absolutePos(new BlockPos(4,7,3));
        BlockPos lower=h.absolutePos(new BlockPos(5,2,3));
        var table=(WorkerStationBlockEntity)world.getBlockEntity(
                h.absolutePos(new BlockPos(0,2,0)));
        BuilderGolem g=golem(h,start);
        final boolean[] rappelled={false},blocked={false};
        final double[] oldY={g.getY()};
        h.onEachTick(()->{
            int tick=(int)h.getTick();
            double fall=oldY[0]-g.getY();oldY[0]=g.getY();
            if(!g.isAlive()||fall>.65){
                h.fail("Unsafe fall during rappel recovery "+fall);return;
            }
            if(tick<10)return;
            String state=BuilderGrappleAccess.debug(g);
            if(tick%5==0&&!rappelled[0])
                rappelled[0]=BuilderGrappleAccess.rappelToward(
                        world,g,table,lower);
            if(!blocked[0]&&state.contains("RAPPEL")){
                // The bottom of the rope becomes blocked after attachment,
                // forcing a reverse ascent to the ORIGINAL upper ledge.
                h.setBlock(5,3,3,Blocks.STONE);
                blocked[0]=true;
            }
            if(blocked[0]&&!BuilderGrappleAccess.active(g)
                    &&g.onGround()&&g.blockPosition().getY()==start.getY()){
                // The new attempt must not choose the same failed face.
                boolean next=BuilderGrappleAccess.rappelToward(world,g,table,lower);
                String nextRoute=BuilderGrappleAccess.debug(g);
                if(next&&nextRoute.contains("column="+lower.getX()
                        +", "+(start.getY()-1)+", "+lower.getZ())){
                    h.fail("Retried the obstructed rappel column immediately");
                    return;
                }
                System.out.println("[GRAPPLE RECOVERY] PASS rappel climbed "
                        +"back to upper ledge; new route="+nextRoute);
                h.succeed();return;
            }
            if(tick>=400)h.fail("Rappel never disengaged: state="+state
                    +" feet="+g.position()+" blocked="+blocked[0]);
        });
    }

    @GameTest(maxTicks=240,skyAccess=true)
    public void oneBlockDropWalksWithoutGrappling(
            GameTestHelper h){
        ground(h);
        ServerLevel world=h.getLevel();
        h.setBlock(4,2,3,Blocks.STONE);
        BlockPos start=h.absolutePos(new BlockPos(4,3,3));
        BlockPos groundFoot=h.absolutePos(new BlockPos(5,2,3));
        var table=(WorkerStationBlockEntity)world.getBlockEntity(
                h.absolutePos(new BlockPos(0,2,0)));
        BuilderGolem g=golem(h,start);
        final boolean[] traversed={false};
        h.onEachTick(()->{
            int tick=(int)h.getTick();
            if(tick<10)return;
            if(g.blockPosition().getY()==groundFoot.getY()
                    &&g.onGround()&&!BuilderGrappleAccess.active(g)){
                System.out.println("[GRAPPLE JUMP] PASS normal one-block descent "
                        +"without attaching a rope, tick="+tick);
                h.succeed();
                return;
            }
            if(tick%5==0){
                boolean grapple=BuilderGrappleAccess.rappelToward(
                        world,g,table,groundFoot);
                if(grapple||BuilderGrappleAccess.active(g)){
                    h.fail("Used Grapple Hook for reachable one-block drop");
                    return;
                }
                traversed[0]=true;
            }
            if(tick>=160)h.fail("Normal one-block descent stuck at "
                    +g.position()+" checked="+traversed[0]);
        });
    }

    @GameTest(maxTicks=240,skyAccess=true)
    public void distantDepotDoesNotForceHookForNearbyOneBlockDrop(
            GameTestHelper h){
        ground(h);
        var world=h.getLevel();
        h.setBlock(4,2,3,Blocks.STONE);
        BlockPos start=h.absolutePos(new BlockPos(4,3,3));
        // OnSite's distant coordinates are intentionally outside this
        // small course. The Builder must reach the local floor first,
        // instead of repeatedly hooking the roof for a one-block drop.
        BlockPos farGoal=h.absolutePos(new BlockPos(20,2,20));
        var table=(WorkerStationBlockEntity)world.getBlockEntity(
                h.absolutePos(new BlockPos(0,2,0)));
        BuilderGolem g=golem(h,start);
        final boolean[] moving={false};
        h.onEachTick(()->{
            if(h.getTick()<10)return;
            if(BuilderGrappleAccess.active(g)){
                h.fail("Attached a hook for a supported one-block local drop");
                return;
            }
            if(g.onGround()&&g.blockPosition().getY()==start.getY()-1){
                if(!moving[0]){
                    h.fail("Worker fell down without a planned physical path");
                    return;
                }
                System.out.println("[GRAPPLE RETURN] PASS distant storage "
                        +"uses nearby trained A* one-block drop, not rope, "
                        +"tick="+h.getTick());
                h.succeed();
                return;
            }
            if(h.getTick()%5==0){
                moving[0]=BuilderGrappleAccess.rappelToward(
                        world,g,table,farGoal)||moving[0];
            }
            if(h.getTick()>=170)
                h.fail("Could not walk safely off nearby one-block ledge "
                        +g.position());
        });
    }
}
