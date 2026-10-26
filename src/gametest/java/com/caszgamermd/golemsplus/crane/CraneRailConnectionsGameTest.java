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
    @GameTest(structure="golems_plus_gametest:dome_arena",padding=64,maxTicks=400)
    public void redstoneLikeCrossesWallsAndCornerClimbs(GameTestHelper h){
        var world=h.getLevel();
        // Dedicated GameTest arenas can contain residual test boundary
        // collision cells even though the source structure looks empty.
        // Clear this controlled fixture before asserting real crane pose.
        for(int x=5;x<=23;x++)for(int z=5;z<=23;z++){
            h.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=12;y++)h.setBlock(x,y,z,Blocks.AIR);
        }
        BlockState floor=CraneContent.RAIL.defaultBlockState()
                .setValue(CraneRailBlock.FACING,Direction.UP);
        BlockPos center=h.absolutePos(new BlockPos(10,2,10));
        world.setBlock(center,floor,3);
        for(Direction d:new Direction[]{Direction.NORTH,Direction.SOUTH,
                Direction.EAST,Direction.WEST})
            world.setBlock(center.relative(d),floor,3);
        BlockState hub=world.getBlockState(center);
        if(CraneRailBlock.connections(world,center)!=4
                ||!CraneRailBlock.hasConnection(world,center,Direction.NORTH)
                ||!CraneRailBlock.hasConnection(world,center,Direction.EAST)
                ||!CraneRailBlock.hasConnection(world,center,Direction.SOUTH)
                ||!CraneRailBlock.hasConnection(world,center,Direction.WEST))
            throw new IllegalStateException("Floor cross isn't linked in all four directions: "+hub);

        // Sturdy vertical wall backing. An EAST-facing rail is attached
        // on its east side and can connect UP/DOWN/NORTH/SOUTH, not EAST/WEST.
        for(int y=3;y<=9;y++)for(int z=9;z<=11;z++)
            h.setBlock(14,y,z,Blocks.STONE);
        BlockState wall=CraneContent.RAIL.defaultBlockState()
                .setValue(CraneRailBlock.FACING,Direction.EAST)
                .setValue(CraneRailBlock.FACE_UP,false)
                .setValue(CraneRailBlock.FACE_EAST,true);
        BlockPos middle=h.absolutePos(new BlockPos(15,6,10));
        world.setBlock(middle,wall,3);
        for(Direction d:new Direction[]{Direction.UP,Direction.DOWN,
                Direction.NORTH,Direction.SOUTH})
            world.setBlock(middle.relative(d),wall,3);
        BlockState cross=world.getBlockState(middle);
        if(CraneRailBlock.connections(world,middle)!=4
                ||!CraneRailBlock.hasConnection(world,middle,Direction.UP)
                ||!CraneRailBlock.hasConnection(world,middle,Direction.DOWN)
                ||!CraneRailBlock.hasConnection(world,middle,Direction.NORTH)
                ||!CraneRailBlock.hasConnection(world,middle,Direction.SOUTH))
            throw new IllegalStateException("Wall cross isn't 4-way: "+cross);
        world.setBlock(middle.relative(Direction.NORTH),Blocks.AIR.defaultBlockState(),3);
        if(CraneRailBlock.connections(world,middle)!=3)
            throw new IllegalStateException("Wall branch didn't disconnect when broken");

        // Add another rail to a different face in the SAME BLOCK CELL.
        // Removing only one backing wall must preserve the other rail.
        BlockPos multiface=h.absolutePos(new BlockPos(20,4,20));
        world.setBlock(multiface.below(),Blocks.STONE.defaultBlockState(),3);
        world.setBlock(multiface.west(),Blocks.STONE.defaultBlockState(),3);
        var faceUp=CraneContent.RAIL.defaultBlockState();
        var faceUpAndEast=faceUp.setValue(CraneRailBlock.FACE_EAST,true);
        world.setBlock(multiface,faceUpAndEast,3);
        if(!CraneRailBlock.attached(world.getBlockState(multiface),Direction.UP)
                ||!CraneRailBlock.attached(world.getBlockState(multiface),Direction.EAST)
                ||CraneRailBlock.faceCount(world.getBlockState(multiface))!=2)
            throw new IllegalStateException("A cell cannot have multiple mounted rail faces");
        world.setBlock(multiface.west(),Blocks.AIR.defaultBlockState(),3);
        if(!CraneRailBlock.attached(world.getBlockState(multiface),Direction.UP)
                ||CraneRailBlock.attached(world.getBlockState(multiface),Direction.EAST))
            throw new IllegalStateException("Removing one support destroyed another mounted face");
        // Floor X+ at y2 turns into wall X- at y3, then runs upwards.
        BlockPos ground=h.absolutePos(new BlockPos(16,2,10));
        BlockPos foot=h.absolutePos(new BlockPos(15,3,10));
        world.setBlock(ground,floor,3);
        for(int y=3;y<=6;y++)
            world.setBlock(h.absolutePos(new BlockPos(15,y,10)),wall,3);
        if(!CraneRailBlock.linked(world,ground,foot)
                ||!CraneRailBlock.linked(world,foot,ground)
                ||!CraneRailBlock.hasConnection(world,ground,Direction.WEST)
                ||!CraneRailBlock.hasConnection(world,foot,Direction.DOWN))
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
        // The graph alone is not enough: a full TWO-BLOCK cubic chassis,
        // boom and builder basket must physically clear every step of the
        // grounded-to-vertical route. The basket stays outboard from the
        // backing wall, with no teleport or collision exemptions.
        var crane=new BuildingCrane(CraneContent.CRANE,world);
        Vec3 previousAnchor=CraneMovement.anchorPoint(world,ground);
        Vec3 offset=new Vec3(0,0,3);
        Vec3 previousLoad=previousAnchor.add(offset);
        if(!CraneMovement.pose(world,crane,previousLoad,previousAnchor))
            throw new IllegalStateException("2x2 chassis clips floor rail"
                    +" anchor="+previousAnchor
                    +" cabin="+CraneMovement.clear(world,crane,
                            CraneMovement.chassis(previousAnchor))
                    +" basket="+CraneMovement.clear(world,crane,
                            CraneMovement.load(crane,previousLoad))
                    +" rail="+world.getBlockState(ground)
                    +" center="+world.getBlockState(center));
        for(var cell:cells){
            Vec3 nextAnchor=CraneMovement.anchorPoint(world,CraneMovement.pos(cell));
            Vec3 nextLoad=nextAnchor.add(offset);
            if(!CraneMovement.swept(world,crane,previousLoad,nextLoad,
                    previousAnchor,nextAnchor))
                throw new IllegalStateException("2x2 chassis clips floor/wall rail bend at "
                        +CraneMovement.pos(cell)+" start="+previousAnchor
                        +" next="+nextAnchor);
            previousAnchor=nextAnchor;
            previousLoad=nextLoad;
        }
        Vec3 chassisPoint=CraneMovement.anchorPoint(world,ground);
        var chassis=CraneMovement.chassis(chassisPoint);
        if(Math.abs(chassis.getXsize()-2)>1e-6
                ||Math.abs(chassis.getYsize()-2)>1e-6
                ||Math.abs(chassis.getZsize()-2)>1e-6)
            throw new IllegalStateException("Crane chassis not 2x2x2: "+chassis);
        System.out.println("[CRANE RAILS] PASS 4-way floor, 4-way wall, "
                +"dynamic disconnect and collision-safe 2x2 chassis floor-to-wall climb");
        h.succeed();
    }
}
