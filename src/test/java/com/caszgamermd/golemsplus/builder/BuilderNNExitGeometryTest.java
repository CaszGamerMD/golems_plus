package com.caszgamermd.golemsplus.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** No world edits or arbitrary pass-through: exact future voxel shape. */
class BuilderNNExitGeometryTest {
    private static final BlockPos AT=new BlockPos(2,1,3);

    @Test void upperSpaceOverRealBottomSlabIsNotAnImaginaryFullCube(){
        AABB body=new AABB(2.2,1.66,3.2,2.5,1.95,3.5);
        assertTrue(BuilderNNExitGeometry.intersects(body,
                BuilderNNExitGeometry.futureCollision(EmptyBlockGetter.INSTANCE,
                        AT,Blocks.STONE.defaultBlockState())));
        assertFalse(BuilderNNExitGeometry.intersects(body,
                BuilderNNExitGeometry.futureCollision(EmptyBlockGetter.INSTANCE,
                        AT,Blocks.STONE_SLAB.defaultBlockState())));
    }
    @Test void wallPostStillBlocksTheCenterButNotTheEmptyCorner(){
        var future=BuilderNNExitGeometry.futureCollision(EmptyBlockGetter.INSTANCE,
                AT,Blocks.COBBLESTONE_WALL.defaultBlockState());
        assertTrue(BuilderNNExitGeometry.intersects(
                new AABB(2.4,1.25,3.4,2.6,1.6,3.6),future));
        assertFalse(BuilderNNExitGeometry.intersects(
                new AABB(2.02,1.25,3.02,2.15,1.6,3.15),future));
    }
    @Test void airNeverCreatesAProspectiveSolidCube(){
        assertFalse(BuilderNNExitGeometry.intersects(new AABB(2,1,3,3,2,4),
                BuilderNNExitGeometry.futureCollision(EmptyBlockGetter.INSTANCE,
                        AT,Blocks.AIR.defaultBlockState())));
    }
    @Test void unknownFutureRemainsConservative(){
        assertTrue(BuilderNNExitGeometry.intersects(
                new AABB(2.4,1.6,3.4,2.7,1.8,3.7),
                BuilderNNExitGeometry.futureCollision(EmptyBlockGetter.INSTANCE,AT,null)));
    }
}
