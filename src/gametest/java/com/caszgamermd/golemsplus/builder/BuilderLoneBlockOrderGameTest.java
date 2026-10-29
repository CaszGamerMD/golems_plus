package com.caszgamermd.golemsplus.builder;

import java.util.LinkedHashMap;
import java.util.Map;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/** Minecraft server-world regression for preventing lonely floating blocks.
 * No simulated entity is required: actual block states and face-adjacency
 * are checked without waiting for an entity-simulation ticket.
 */
public final class BuilderLoneBlockOrderGameTest {
    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=300,
            skyAccess=true)
    public void mineLeafBeforeItsOnlySupport(GameTestHelper h){
        ServerLevel level=h.getLevel();
        for(int x=7;x<=35;x++)for(int z=8;z<=21;z++)
            for(int y=3;y<=11;y++)
                h.setBlock(x,y,z,Blocks.AIR);
        Map<BlockPos,BlockState> plan=new LinkedHashMap<>();
        // Roof beam: x=9 -- x=10 -- x=11 (unsupported leaf).
        BlockPos a=place(h,level,plan,9,7,10,true);
        BlockPos support=place(h,level,plan,10,7,10,true);
        BlockPos leaf=place(h,level,plan,11,7,10,true);
        require(h,leaf.equals(BuilderDemolitionOrder.loneBlockFirst(
                level,plan,support)),"side leaf must precede center support");
        require(h,BuilderDemolitionOrder.loneBlockFirst(
                level,plan,leaf)==null,"leaf itself must be removable");
        require(h,!BuilderDemolitionOrder.floatingAlone(level,leaf),
                "leaf is attached until support is removed");

        // A second true connection (not just a diagonal) means the
        // neighbor cannot be made lonely by removing center.
        place(h,level,plan,15,7,10,true);
        BlockPos supported=place(h,level,plan,16,7,10,true);
        place(h,level,plan,17,7,10,true);
        place(h,level,plan,18,7,10,true);
        require(h,BuilderDemolitionOrder.loneBlockFirst(
                level,plan,supported)==null,
                "leaf with other adjacent support should not block mining");

        // Vanilla roof finial above supporting roof block.
        place(h,level,plan,22,6,10,true);
        BlockPos below=place(h,level,plan,22,7,10,true);
        BlockPos above=place(h,level,plan,22,8,10,true);
        require(h,above.equals(BuilderDemolitionOrder.loneBlockFirst(
                level,plan,below)),"upper finial must be mined first");

        // Lower leaf requires an explicit exception to highest-Y-only
        // demolition. This is exactly where top-down scanning alone can
        // strand an unsupported lower block.
        place(h,level,plan,28,8,10,true);
        BlockPos highSupport=place(h,level,plan,29,8,10,true);
        BlockPos lowerLeaf=place(h,level,plan,29,7,10,true);
        require(h,lowerLeaf.equals(BuilderDemolitionOrder.loneBlockFirst(
                level,plan,highSupport)),
                "lower lone leaf must precede its higher supporting block");

        // A real-world but NON-schematic neighboring block is still
        // support. The planner must not treat terrain as air.
        place(h,level,plan,9,7,16,true);
        BlockPos normal=place(h,level,plan,10,7,16,true);
        place(h,level,plan,11,7,16,true);
        place(h,level,plan,12,7,16,false);
        require(h,BuilderDemolitionOrder.loneBlockFirst(
                level,plan,normal)==null,
                "existing world support outside plan must count");

        // An already isolated two-block fragment is inherently a cycle;
        // break its upper block before lower, avoiding infinite deferral.
        BlockPos pairLow=place(h,level,plan,18,7,16,true);
        BlockPos pairHigh=place(h,level,plan,18,8,16,true);
        require(h,BuilderDemolitionOrder.loneBlockFirst(
                level,plan,pairHigh)==null,
                "upper block of unavoidable 2-block pair must proceed");
        require(h,pairHigh.equals(BuilderDemolitionOrder.loneBlockFirst(
                level,plan,pairLow)),
                "lower block of pair must wait for upper");

        // Two different lone neighbors of a connected support are both
        // removed before that support. No dependency is discarded.
        place(h,level,plan,26,7,18,true);
        BlockPos center=place(h,level,plan,27,7,18,true);
        BlockPos north=place(h,level,plan,27,7,17,true);
        BlockPos south=place(h,level,plan,27,7,19,true);
        BlockPos first=BuilderDemolitionOrder.loneBlockFirst(
                level,plan,center);
        require(h,north.equals(first)||south.equals(first),
                "first dangling block should be prioritized");
        level.setBlock(first,Blocks.AIR.defaultBlockState(),3);
        plan.remove(first);
        BlockPos second=BuilderDemolitionOrder.loneBlockFirst(
                level,plan,center);
        require(h,(first.equals(north)?south:north).equals(second),
                "remaining lone neighbor still precedes support");

        // Demonstrate why the preview matters: removing center in the
        // test fixture would isolate its sole remaining leaf.
        level.setBlock(center,Blocks.AIR.defaultBlockState(),3);
        require(h,BuilderDemolitionOrder.floatingAlone(level,second),
                "last leaf really would have been left floating alone");
        System.out.println("[LONE BLOCK ORDER] PASS horizontal, vertical,"
                +" lower-Y dependency, external support, cycle,"
                +" multiple leaves, real floating-block counterexample");
        h.succeed();
    }
    private static BlockPos place(GameTestHelper h,ServerLevel level,
            Map<BlockPos,BlockState> plan,int x,int y,int z,boolean include){
        h.setBlock(x,y,z,Blocks.STONE);
        BlockPos p=h.absolutePos(new BlockPos(x,y,z));
        if(include)plan.put(p,level.getBlockState(p));
        return p;
    }
    private static void require(GameTestHelper h,boolean condition,String reason){
        if(!condition)h.fail("Lone-block order: "+reason);
    }
}
