package com.caszgamermd.golemsplus.builder;

import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The evolving scaffolding AI learns reusable rules, not map routes. */
final class BuilderScaffoldBridgePlannerTest {
    private static BlockPos p(int x,int z){return new BlockPos(x,10,z);}
    @Test void choosesShortestSupportedWalkwayAndReturnsAllCells(){
        var result=BuilderScaffoldBridgePlanner.search(p(0,0),p(6,0),
                at->Math.abs(at.getX())<9&&Math.abs(at.getZ())<9);
        assertNotNull(result);
        assertEquals(p(0,0),result.anchor());
        assertEquals(3,result.cells().size());
        BlockPos prev=result.anchor();
        for(var current:result.cells()){
            assertEquals(1,prev.distManhattan(current));
            assertEquals(10,current.getY());
            prev=current;
        }
        assertTrue(BuilderScaffoldBridgePlanner.withinPlacementRange(
                result.end(),p(6,0)));
    }
    @Test void reroutesAroundFutureSchematicCells(){
        var excluded=Set.of(p(1,0),p(2,0),p(3,0));
        var result=BuilderScaffoldBridgePlanner.search(p(0,0),p(6,0),
                at->!excluded.contains(at));
        assertNotNull(result);
        assertTrue(result.cells().size()<=6);
        assertTrue(result.cells().stream().noneMatch(excluded::contains));
        for(int i=1;i<result.cells().size();i++)
            assertEquals(1,result.cells().get(i-1).distManhattan(result.cells().get(i)));
    }
    @Test void rejectsUnsupportedSpanOrBlockedWalkway(){
        assertTrue(BuilderScaffoldBridgePlanner.supportedSpan(6));
        assertFalse(BuilderScaffoldBridgePlanner.supportedSpan(7));
        assertNull(BuilderScaffoldBridgePlanner.search(p(0,0),p(12,0),at->true));
        assertNull(BuilderScaffoldBridgePlanner.search(p(0,0),p(6,0),at->false));
    }
    @Test void futureWallsAndHeadroomAreReservedForEveryStep(){
        var cells=BuilderScaffoldBridgePlanner.reserved(java.util.List.of(
                new BuilderConstructionPlan.Step(1,10,2,"minecraft:stone","minecraft:stone"),
                new BuilderConstructionPlan.Step(2,11,2,"minecraft:stone","minecraft:stone"),
                new BuilderConstructionPlan.Step(2,10,4,"minecraft:dandelion",null,true)));
        assertTrue(cells.contains(new BlockPos(1,10,2)));
        assertTrue(cells.contains(new BlockPos(2,11,2)));
        assertFalse(cells.contains(new BlockPos(2,10,4)));
    }
}
