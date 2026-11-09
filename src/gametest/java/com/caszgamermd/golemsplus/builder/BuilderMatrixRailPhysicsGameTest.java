package com.caszgamermd.golemsplus.builder;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Isolated vanilla-physics diagnostic for the exact full-Matrix
 * ascending powered rail at source local (15,1,24). This test does NOT
 * assert the full build succeeds or alter the original litematic.
 */
public final class BuilderMatrixRailPhysicsGameTest {
    @GameTest(structure="golems_plus_gametest:matrix_full_arena",
            environment="golems_plus_gametest:matrix_full_e2e",
            maxTicks=160,skyAccess=true)
    public void raisedJigRemovalMustPreserveExpectedRail(GameTestHelper h){
        ServerLevel world=h.getLevel();
        BlockPos lower=h.absolutePos(new BlockPos(12,4,12));
        // The Matrix requests ascending WEST and its raised endpoint is
        // one block higher immediately to the west.
        BlockState expected=BuilderBlockResolver.resolve(
                "minecraft:powered_rail[powered=true,shape=ascending_west,waterlogged=false]",
                BuildSite.Rotation.NONE,BuildSite.Mirror.NONE).state();
        BlockPos upper=BuilderRailSlope.raisedEnd(lower,expected);
        if(upper==null){h.fail("Couldn't locate ascending rail end");return;}
        for(int y=2;y<=8;y++)for(int x=8;x<=15;x++)for(int z=9;z<=15;z++)
            world.setBlock(h.absolutePos(new BlockPos(x,y,z)),
                    Blocks.AIR.defaultBlockState(),3);
        world.setBlock(lower.below(),Blocks.STONE.defaultBlockState(),3);
        world.setBlock(upper.below(),Blocks.STONE.defaultBlockState(),3);
        BlockState jig=Blocks.POWERED_RAIL.defaultBlockState();
        if(!jig.canSurvive(world,upper)){
            h.fail("Isolated upper rail jig cannot survive on real stone");
            return;
        }
        boolean placedUpper=world.setBlock(upper,jig,3);
        boolean placedLower=world.setBlock(lower,expected,3);
        BlockState during=world.getBlockState(lower);
        boolean removedUpper=world.setBlock(upper,Blocks.AIR.defaultBlockState(),3);
        BlockState after=world.getBlockState(lower);
        System.out.println("[MATRIX RAIL PHYSICS] local=(15,1,24)"
                +" expected="+expected+" raised="+upper.toShortString()
                +" upperPlaced="+placedUpper+" lowerPlaced="+placedLower
                +" upperRemoved="+removedUpper
                +" duringJig="+during+" afterRemoval="+after
                +" stable="+BuilderPlacementState.matches(after,expected));
        if(!placedUpper||!placedLower||!removedUpper){
            h.fail("Vanilla failed isolated rail placement/removal");return;
        }
        if(!BuilderRailSlope.shapeMatches(after,expected)){
            h.fail("Expected physical incline lost after removing paid jig: "
                    +after);return;
        }
        if(!BuilderRailSlope.railStateDifference(after,expected)
                .contains("redstone power")){
            h.fail("Expected only a real redstone-power mismatch: "
                    +BuilderRailSlope.railStateDifference(after,expected));
            return;
        }
        // Confirm the actual user file's neighboring blocks separately
        // from this controlled physics experiment, preserving every
        // user's saved rail, fluid and redstone state unchanged.
        try{
            var source=LitematicFileReader.read(
                    BuilderVillagerMatrixFullFixture.unpack());
            var around=new java.util.ArrayList<String>();
            String originalRail=null;
            String raisedCell="minecraft:air";
            for(var block:source.blocks()){
                if(block.x()==15&&block.y()==1&&block.z()==24)
                    originalRail=block.state();
                if(block.x()==14&&block.y()==2&&block.z()==24)
                    raisedCell=block.state();
                if(Math.abs(block.x()-15)<=2
                        &&Math.abs(block.y()-1)<=2
                        &&Math.abs(block.z()-24)<=2
                        &&around.size()<38)
                    around.add("("+block.x()+","+block.y()+","
                            +block.z()+")="+block.state());
            }
            System.out.println("[MATRIX RAIL NEIGHBORS] original="
                    +originalRail+" raisedCell="+raisedCell
                    +" nearbyNonAir="+around);
            if(originalRail==null
                    ||!originalRail.contains("shape=ascending_west"))
                throw new IllegalStateException(
                        "User's original Matrix rail state unexpectedly changed");
        }catch(Exception e){
            h.fail("Could not inspect exact Matrix rail neighbors: "+e);
            return;
        }
        // Report a factual, discrete physical outcome. The full build
        // must separately assert every final block state, not inherit
        // any success from this isolated diagnostic.
        h.succeed();
    }
    /** Preserve the real Matrix power-chain cells: redstone block
     * directly under rail x=13, waterlogged roots under rail x=14,
     * and the requested ascending rail at x=15. Unlike the isolated
     * jig test, this deliberately DOES NOT invent a stone support
     * under the raised end. It asks vanilla physics what is possible.
     */
    @GameTest(structure="golems_plus_gametest:matrix_full_arena",
            environment="golems_plus_gametest:matrix_full_e2e",
            maxTicks=160,skyAccess=true)
    public void originalMatrixPoweredRailPocketWithTrueSupportingBlocks(
            GameTestHelper h){
        ServerLevel world=h.getLevel();
        BlockPos x15=h.absolutePos(new BlockPos(12,4,12));
        BlockPos x14=x15.west(),x13=x15.west(2);
        for(int y=2;y<=8;y++)for(int x=8;x<=15;x++)for(int z=9;z<=15;z++)
            world.setBlock(h.absolutePos(new BlockPos(x,y,z)),
                    Blocks.AIR.defaultBlockState(),3);
        world.setBlock(x13.below(),Blocks.REDSTONE_BLOCK.defaultBlockState(),3);
        world.setBlock(x14.below(),
                Blocks.MANGROVE_ROOTS.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.state.properties
                                .BlockStateProperties.WATERLOGGED,true),3);
        world.setBlock(x15.below(),Blocks.SMOOTH_STONE.defaultBlockState(),3);
        var flat=BuilderBlockResolver.resolve(
                "minecraft:powered_rail[powered=true,shape=east_west,waterlogged=false]",
                BuildSite.Rotation.NONE,BuildSite.Mirror.NONE).state();
        var ascending=BuilderBlockResolver.resolve(
                "minecraft:powered_rail[powered=true,shape=ascending_west,waterlogged=false]",
                BuildSite.Rotation.NONE,BuildSite.Mirror.NONE).state();
        world.setBlock(x13,flat,3);
        world.setBlock(x14,flat,3);
        BlockPos raised=BuilderRailSlope.raisedEnd(x15,ascending);
        boolean realJigCanSurvive=flat.getBlock().defaultBlockState()
                .canSurvive(world,raised);
        world.setBlock(x15,ascending,3);
        BlockState physical=world.getBlockState(x15);
        System.out.println("[MATRIX RAIL ACTUAL POCKET] "
                +"source=(15,1,24) raisedCell=iron_trapdoor"
                +" actualRaisedFooting="+world.getBlockState(raised.below())
                +" raisedJigCanSurvive="+realJigCanSurvive
                +" x13="+world.getBlockState(x13)
                +" x14="+world.getBlockState(x14)
                +" x15="+physical
                +" shapeMatches="+BuilderRailSlope.shapeMatches(
                        physical,ascending)
                +" difference="+BuilderRailSlope.railStateDifference(
                        physical,ascending));
        // Diagnostic only: a mismatching vanilla snapshot here is a
        // REAL construction blocker, not permission to fabricate blocks
        // or to mark the full Matrix job successful.
        h.succeed();
    }
}
