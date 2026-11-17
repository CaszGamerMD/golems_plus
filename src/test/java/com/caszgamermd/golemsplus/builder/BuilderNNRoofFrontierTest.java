package com.caszgamermd.golemsplus.builder;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BuilderNNRoofFrontierTest {
    @Test void onlyNearbyElevatedUnfinishedLargeLayersQualify(){
        BlockPos from=new BlockPos(100,0,100);
        assertTrue(BuilderNNRoofFrontier.nearElevated(from,new BlockPos(120,12,100),100));
        assertTrue(BuilderNNRoofFrontier.nearElevated(from,new BlockPos(100,12,120),100));
        assertFalse(BuilderNNRoofFrontier.nearElevated(from,new BlockPos(125,12,100),100));
        assertFalse(BuilderNNRoofFrontier.nearElevated(from,new BlockPos(100,12,125),100));
        assertFalse(BuilderNNRoofFrontier.nearElevated(from,new BlockPos(120,1,100),100));
        assertFalse(BuilderNNRoofFrontier.nearElevated(from,new BlockPos(120,65,100),100));
        assertFalse(BuilderNNRoofFrontier.nearElevated(from,new BlockPos(120,12,100),24));
        assertFalse(BuilderNNRoofFrontier.nearElevated(from,new BlockPos(120,-5,100),100));
    }
    @Test void exactBoundaryAndRotationAreSymmetric(){
        var from=new BlockPos(-10,-45,20);
        assertTrue(BuilderNNRoofFrontier.nearElevated(from,from.offset(24,14,0),25));
        assertTrue(BuilderNNRoofFrontier.nearElevated(from,from.offset(0,14,24),25));
        assertTrue(BuilderNNRoofFrontier.nearElevated(from,from.offset(-24,14,0),25));
        assertTrue(BuilderNNRoofFrontier.nearElevated(from,from.offset(0,14,-24),25));
        assertFalse(BuilderNNRoofFrontier.nearElevated(from,from.offset(24,14,1),25));
    }
    @Test void sparseDomeCapMayTrySeparateVerifiedFlightLegs(){
        var from=new BlockPos(100,-55,100);
        var east=from.offset(35,23,0);
        var north=from.offset(0,23,-35);
        assertTrue(BuilderNNRoofFrontier.sparseRoofMultiLegCandidate(from,east,7));
        assertTrue(BuilderNNRoofFrontier.sparseRoofMultiLegCandidate(from,north,7));
        assertTrue(BuilderNNRoofFrontier.sparseRoofMultiLegCandidate(from,
                from.offset(-40,23,0),24));
        assertFalse(BuilderNNRoofFrontier.sparseRoofMultiLegCandidate(from,
                from.offset(41,23,0),7));
        assertFalse(BuilderNNRoofFrontier.sparseRoofMultiLegCandidate(from,east,25));
        assertFalse(BuilderNNRoofFrontier.sparseRoofMultiLegCandidate(from,east,0));
        assertFalse(BuilderNNRoofFrontier.sparseRoofMultiLegCandidate(from,
                from.offset(3,1,0),7));
        assertFalse(BuilderNNRoofFrontier.sparseRoofMultiLegCandidate(from,
                from.offset(3,49,0),7));
    }
}
