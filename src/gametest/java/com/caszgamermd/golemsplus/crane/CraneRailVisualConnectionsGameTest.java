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
        // FACE_SOUTH means the supporting wall is NORTH of the rail
        // block, therefore the rail must be in the NORTHERN 2/16 of
        // its own cell. The visible wall model already uses that edge.
        if(bounds.minZ<-.01||bounds.maxZ>.15){
            h.fail("South-attached rail outline must be on north edge "+bounds);
            return;
        }
        // Independent regression for east/west wall normals: their
        // existing model looks right, but their selection outline was
        // mirrored across the whole block cell.
        BlockPos eastSupport=h.absolutePos(new BlockPos(2,3,5));
        BlockPos eastRail=eastSupport.east();
        world.setBlock(eastSupport,Blocks.STONE.defaultBlockState(),3);
        var east=CraneContent.RAIL.defaultBlockState()
                .setValue(CraneRailBlock.FACING,Direction.EAST)
                .setValue(CraneRailBlock.FACE_UP,false)
                .setValue(CraneRailBlock.FACE_EAST,true);
        world.setBlock(eastRail,east,3);
        var eastShape=world.getBlockState(eastRail).getShape(world,eastRail,
                net.minecraft.world.phys.shapes.CollisionContext.empty()).bounds();
        if(eastShape.minX<-.01||eastShape.maxX>.15){
            h.fail("East-attached rail outline on wrong X side "+eastShape);
            return;
        }
        BlockPos westSupport=h.absolutePos(new BlockPos(6,3,5));
        BlockPos westRail=westSupport.west();
        world.setBlock(westSupport,Blocks.STONE.defaultBlockState(),3);
        var west=CraneContent.RAIL.defaultBlockState()
                .setValue(CraneRailBlock.FACING,Direction.WEST)
                .setValue(CraneRailBlock.FACE_UP,false)
                .setValue(CraneRailBlock.FACE_WEST,true);
        world.setBlock(westRail,west,3);
        var westShape=world.getBlockState(westRail).getShape(world,westRail,
                net.minecraft.world.phys.shapes.CollisionContext.empty()).bounds();
        if(westShape.minX<.85||westShape.maxX>1.01){
            h.fail("West-attached rail outline on wrong X side "+westShape);
            return;
        }
        h.succeed();
    }
}
