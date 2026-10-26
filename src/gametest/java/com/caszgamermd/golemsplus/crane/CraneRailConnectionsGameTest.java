package com.caszgamermd.golemsplus.crane;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Actual Minecraft blockstate, supported-face and physical route test.
 * Rails must form 4-way networks on horizontal AND vertical surfaces,
 * refresh when a neighbor changes, and join safely around a floor/wall bend.
 */
public final class CraneRailConnectionsGameTest {
    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=400)
    public void redstoneLikeCrossesWallsAndCornerClimbs(GameTestHelper h){
        var world=h.getLevel();
        for(int x=6;x<=22;x++)for(int z=6;z<=22;z++)
            h.setBlock(x,1,z,Blocks.STONE);
        BlockState floor=CraneContent.RAIL.defaultBlockState()
                .setValue(CraneRailBlock.FACING,Direction.UP);
        BlockPos center=h.absolutePos(new BlockPos(10,2,10));
        world.setBlock(center,floor,3);
        for(Direction d:new Direction[]{Direction.NORTH,Direction.SOUTH,
                Direction.EAST,Direction.WEST})
            world.setBlock(center.relative(d),floor,3);
        BlockState hub=world.getBlockState(center);
        if(CraneRailBlock.connections(hub)!=4
                ||!hub.getValue(CraneRailBlock.NORTH)
                ||!hub.getValue(CraneRailBlock.EAST)
                ||!hub.getValue(CraneRailBlock.SOUTH)
                ||!hub.getValue(CraneRailBlock.WEST))
            throw new IllegalStateException("Floor cross isn't linked in all four directions: "+hub);

        // Sturdy vertical wall backing. An EAST-facing rail is attached
        // on its east side and can connect UP/DOWN/NORTH/SOUTH, not EAST/WEST.
        for(int y=3;y<=9;y++)for(int z=9;z<=11;z++)
            h.setBlock(14,y,z,Blocks.STONE);
        BlockState wall=CraneContent.RAIL.defaultBlockState()
                .setValue(CraneRailBlock.FACING,Direction.EAST);
        BlockPos middle=h.absolutePos(new BlockPos(15,6,10));
        world.setBlock(middle,wall,3);
        for(Direction d:new Direction[]{Direction.UP,Direction.DOWN,
                Direction.NORTH,Direction.SOUTH})
            world.setBlock(middle.relative(d),wall,3);
        BlockState cross=world.getBlockState(middle);
        if(CraneRailBlock.connections(cross)!=4
                ||!cross.getValue(CraneRailBlock.UP)
                ||!cross.getValue(CraneRailBlock.DOWN)
                ||!cross.getValue(CraneRailBlock.NORTH)
                ||!cross.getValue(CraneRailBlock.SOUTH))
            throw new IllegalStateException("Wall cross isn't 4-way: "+cross);
        world.setBlock(middle.relative(Direction.NORTH),Blocks.AIR.defaultBlockState(),3);
        if(CraneRailBlock.connections(world.getBlockState(middle))!=3)
            throw new IllegalStateException("Wall branch didn't disconnect when broken");

        // Floor X+ at y2 turns into wall X- at y3, then runs upwards.
        BlockPos ground=h.absolutePos(new BlockPos(16,2,10));
        BlockPos foot=h.absolutePos(new BlockPos(15,3,10));
        world.setBlock(ground,floor,3);
        for(int y=3;y<=6;y++)
            world.setBlock(h.absolutePos(new BlockPos(15,y,10)),wall,3);
        if(!CraneRailBlock.linked(world,ground,foot)
                ||!CraneRailBlock.linked(world,foot,ground)
                ||!world.getBlockState(ground).getValue(CraneRailBlock.WEST)
                ||!world.getBlockState(foot).getValue(CraneRailBlock.DOWN))
            throw new IllegalStateException("Floor-to-wall corner failed to form a reciprocal bend");
        var goal=h.absolutePos(new BlockPos(15,6,10));
        var cells=CranePath.find(CraneMovement.cell(ground),
                p->p.equals(CraneMovement.cell(goal)),
                p->p.distance(CraneMovement.cell(goal)),
                p->world.getBlockState(CraneMovement.pos(p)).is(CraneContent.RAIL),
                (a,b)->CraneRailBlock.linked(world,CraneMovement.pos(a),CraneMovement.pos(b)),
                120,p->CraneRailBlock.linkedNeighbors(world,CraneMovement.pos(p))
                        .stream().map(CraneMovement::cell).toList());
        if(cells==null||cells.size()!=4)
            throw new IllegalStateException("Crane rail planner failed to climb wall: "+cells);
        Vec3 chassisPoint=CraneMovement.anchorPoint(world,ground);
        var chassis=CraneMovement.chassis(chassisPoint);
        if(Math.abs(chassis.getXsize()-2)>1e-6
                ||Math.abs(chassis.getYsize()-2)>1e-6
                ||Math.abs(chassis.getZsize()-2)>1e-6)
            throw new IllegalStateException("Crane chassis not 2x2x2: "+chassis);
        System.out.println("[CRANE RAILS] PASS 4-way floor, 4-way wall, "
                +"dynamic disconnect and physically connected floor-to-wall climb");
        h.succeed();
    }
}
