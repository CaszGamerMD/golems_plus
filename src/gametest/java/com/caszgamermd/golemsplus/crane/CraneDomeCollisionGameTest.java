package com.caszgamermd.golemsplus.crane;

import com.caszgamermd.golemsplus.builder.BuildSite;
import com.caszgamermd.golemsplus.builder.BuilderBlockResolver;
import com.caszgamermd.golemsplus.builder.BuilderConstructionPlan;
import com.caszgamermd.golemsplus.builder.BuilderDomeFixture;
import com.caszgamermd.golemsplus.builder.LitematicFileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Regression against the user's exact SHA-256 checked Dome.litematic, not an
 * invented sphere or a claim that four builders already finished the build.
 *
 * Exercises the real server's block-collision API with the crane's production
 * swept-boom geometry.  A far-side landing must not tunnel through the Dome.
 */
public final class CraneDomeCollisionGameTest {
    @GameTest(structure="golems_plus_gametest:dome_arena",
            maxTicks=1200, skyAccess=true)
    public void boomRespectsRealDomeAndRequiresReposition(GameTestHelper helper) {
        Path fixture=null;
        try {
            fixture=BuilderDomeFixture.unpack();
            var schematic=LitematicFileReader.read(fixture);
            if(schematic.blocks().size()!=3537)
                throw new IllegalStateException("Wrong Dome schematic block count");
            BlockPos origin=helper.absolutePos(new BlockPos(8,2,8));
            var site=new BuildSite(origin.getX(),origin.getY(),origin.getZ(),
                    BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
            var plan=BuilderConstructionPlan.create(schematic,site,true);
            if(plan.steps().size()!=3537 || !plan.unsupported().isEmpty())
                throw new IllegalStateException("Dome plan mismatch: "+plan.unsupported());

            var world=helper.getLevel();
            // The full 48x48x48 arena includes ground under the chassis,
            // unlike Minecraft's 8x8 GameTest default room.
            for(int x=1;x<=45;x++)for(int z=1;z<=45;z++)
                helper.setBlock(x,1,z,Blocks.STONE);
            int solid=0;
            for(var step:plan.steps()) {
                if(step.ignorePlant())continue;
                var state=BuilderBlockResolver.resolve(step.encodedState(),
                        BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
                if(state==null)continue;
                BlockPos target=new BlockPos(step.x(),step.y(),step.z());
                // No world-neighbor notification; the geometry test needs
                // the *finished* model rather than construction-order physics.
                world.setBlock(target,state.state(),2);
                solid++;
            }
            if(solid<3400)
                throw new IllegalStateException("Dome collision volume incomplete: "+solid);

            var owner=helper.makeMockServerPlayerInLevel();
            BuildingCrane crane=new BuildingCrane(CraneContent.CRANE,world);
            BlockPos westAnchor=helper.absolutePos(new BlockPos(2,2,24));
            Vec3 westPickup=Vec3.atBottomCenterOf(
                    helper.absolutePos(new BlockPos(5,2,24)));
            Vec3 nearY30=Vec3.atBottomCenterOf(
                    helper.absolutePos(new BlockPos(6,32,24)));
            Vec3 nearY38=Vec3.atBottomCenterOf(
                    helper.absolutePos(new BlockPos(6,40,24)));
            Vec3 farY38=Vec3.atBottomCenterOf(
                    helper.absolutePos(new BlockPos(41,40,24)));

            crane.place(owner.getUUID(),westAnchor,westPickup);
            if(!CraneMovement.pose(world,crane,westPickup,crane.anchorPoint()))
                throw new IllegalStateException("Crane cannot pick up beside Dome");
            if(CraneMovement.pose(world,crane,nearY30,crane.anchorPoint()))
                throw new IllegalStateException("30-block boom improperly reaches Y30");

            owner.setItemInHand(InteractionHand.MAIN_HAND,
                    new ItemStack(CraneContent.BOOM,2));
            crane.interact(owner,InteractionHand.MAIN_HAND);
            if(crane.reach()!=60)
                throw new IllegalStateException("First boom extension not consumed");
            if(!CraneMovement.pose(world,crane,nearY30,crane.anchorPoint()))
                throw new IllegalStateException("60-block boom cannot reach near Y30");
            if(!CraneMovement.pose(world,crane,nearY38,crane.anchorPoint()))
                throw new IllegalStateException("60-block boom cannot reach near roof");
            if(CraneMovement.pose(world,crane,farY38,crane.anchorPoint()))
                throw new IllegalStateException("Boom clipped through Dome to far roof");

            crane.interact(owner,InteractionHand.MAIN_HAND);
            if(crane.reach()!=90)
                throw new IllegalStateException("Second boom extension not consumed");
            if(CraneMovement.pose(world,crane,farY38,crane.anchorPoint()))
                throw new IllegalStateException("90-block boom incorrectly ignores Dome walls");

            // Moving the chassis around the Dome should restore a clear
            // approach to the otherwise obstructed east-side target.
            BlockPos eastAnchor=helper.absolutePos(new BlockPos(44,2,24));
            Vec3 eastPickup=Vec3.atBottomCenterOf(
                    helper.absolutePos(new BlockPos(41,2,24)));
            crane.place(owner.getUUID(),eastAnchor,eastPickup);
            if(!CraneMovement.pose(world,crane,farY38,crane.anchorPoint()))
                throw new IllegalStateException("Repositioned east crane still obstructed");

            // A continuous supported detour travels outside the original
            // Dome footprint: west side -> north perimeter -> east side.
            // This specifically tests rail A* against real beam/load
            // swept collisions, not a fictitious straight-line teleport.
            var railState=CraneContent.RAIL.defaultBlockState()
                    .setValue(CraneRailBlock.FACING,Direction.UP);
            for(int z=3;z<=24;z++){
                world.setBlock(helper.absolutePos(new BlockPos(2,2,z)),railState,2);
                world.setBlock(helper.absolutePos(new BlockPos(44,2,z)),railState,2);
            }
            for(int x=2;x<=44;x++)
                world.setBlock(helper.absolutePos(new BlockPos(x,2,3)),railState,2);
            // Keep the basket on the *north* side of the rails. A fixed
            // eastward stow would cross the 48-block arena boundary when
            // the chassis reaches the far east edge.
            Vec3 stowed=CraneMovement.anchorPoint(world,westAnchor)
                    .add(0,0,3);
            crane.place(owner.getUUID(),westAnchor,stowed);
            var detour=CraneMovement.railPath(world,crane,farY38);
            if(detour==null || detour.size()<20)
                throw new IllegalStateException(
                        "Connected outside-Dome crane rails cannot bypass obstruction");
            for(BlockPos rail:detour)
                if(!world.getBlockState(rail).is(CraneContent.RAIL))
                    throw new IllegalStateException("Crane path jumps an unplaced rail: "+rail);
            System.out.println("[DOME CRANE] PASS exact 3537-block Dome; "
                    +"west pickup, 30/60/90 reach, collision-safe roof, "
                    +"east reposition and outside rail detour="+detour.size()
                    +" segments; placed="+solid+" block states");
            helper.succeed();
        } catch(Exception error){
            helper.fail("Exact Dome crane regression failed: "+error);
        } finally {
            if(fixture!=null)try{Files.deleteIfExists(fixture);}
                catch(Exception ignored){}
        }
    }
}
