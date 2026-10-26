package com.caszgamermd.golemsplus.crane;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;

/** Regression: real rail state must NOT render as an unconditional plus. */
public final class CraneRailVisualConnectionsGameTest {
    @GameTest(maxTicks=180,skyAccess=true)
    public void floorSegmentHasOnlyActualNorthSouthArms(GameTestHelper h){
        var world=h.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++){
            h.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=4;y++)h.setBlock(x,y,z,Blocks.AIR);
        }
        for(int z=2;z<=4;z++)
            h.setBlock(3,2,z,CraneContent.RAIL);
        BlockPos middle=h.absolutePos(new BlockPos(3,2,3));
        var state=world.getBlockState(middle);
        if(!CraneRailBlock.attached(state,Direction.UP)
                ||!state.getValue(CraneRailBlock.LINK_NORTH)
                ||!state.getValue(CraneRailBlock.LINK_SOUTH)
                ||state.getValue(CraneRailBlock.LINK_EAST)
                ||state.getValue(CraneRailBlock.LINK_WEST)
                ||state.getValue(CraneRailBlock.LINK_UP)
                ||state.getValue(CraneRailBlock.LINK_DOWN)){
            h.fail("Straight line renders as 4-way: "+state);return;
        }
        if(!world.getBlockState(middle).getCollisionShape(world,middle).isEmpty()){
            h.fail("Rail still trips Builder golems");return;
        }
        h.succeed();
    }

    @GameTest(maxTicks=180,skyAccess=true)
    public void supportedWallRailOccupiesCorrectWallSide(GameTestHelper h){
        var world=h.getLevel();
        for(int x=0;x<8;x++)for(int z=0;z<8;z++){
            h.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=6;y++)h.setBlock(x,y,z,Blocks.AIR);
        }
        // A northern wall's SOUTH side is at the front of block (4,3,2).
        BlockPos backing=h.absolutePos(new BlockPos(4,3,2));
        BlockPos rail=backing.south();
        world.setBlock(backing,Blocks.STONE.defaultBlockState(),3);
        var state=CraneContent.RAIL.defaultBlockState()
                .setValue(CraneRailBlock.FACING,Direction.SOUTH)
                .setValue(CraneRailBlock.FACE_UP,false)
                .setValue(CraneRailBlock.FACE_SOUTH,true);
        world.setBlock(rail,state,3);
        var actual=world.getBlockState(rail);
        if(!CraneRailBlock.attached(actual,Direction.SOUTH)
                ||CraneRailBlock.attached(actual,Direction.UP)
                ||!actual.canSurvive(world,rail)){
            h.fail("Rail has wrong supported wall face "+actual);return;
        }
        var bounds=actual.getShape(world,rail,
                net.minecraft.world.phys.shapes.CollisionContext.empty()).bounds();
        if(bounds.minZ<.85||bounds.maxZ>1.01){
            h.fail("Rail selection box is on wrong side "+bounds);return;
        }
        h.succeed();
    }
}
