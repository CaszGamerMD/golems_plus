package com.caszgamermd.golemsplus.builder;

import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class BuilderWorkSpatialIndexTest {
    private static BuilderConstructionPlan.Step step(int x,int y,int z){
        return new BuilderConstructionPlan.Step(x,y,z,"stone","stone");
    }

    @Test void findsOnlyAssignedNearbyCellsOnRequestedLayer(){
        var steps=List.of(
                step(0,64,0),step(1,64,0),step(2,64,0),
                step(1,65,0),step(1,64,1));
        var index=BuilderWorkSpatialIndex.create(steps);
        assertEquals(List.of(2,4),index.nearby(
                new BlockPos(0,64,0),64,2,Set.of(2,4),32));
    }

    @Test void localResultsAreNearestFirstAndBounded(){
        var steps=List.of(step(3,64,0),step(1,64,0),step(0,64,2),
                step(0,64,1),step(0,64,3));
        var index=BuilderWorkSpatialIndex.create(steps);
        assertEquals(List.of(3,1,2),index.nearby(
                new BlockPos(0,64,0),64,3,Set.of(0,1,2,3,4),3));
    }

    @Test void emptyOrOutOfRangeNeighborhoodDoesNotScanTheWholePlan(){
        var steps=List.of(step(40,64,40));
        var index=BuilderWorkSpatialIndex.create(steps);
        assertTrue(index.nearby(new BlockPos(0,64,0),64,4,
                Set.of(0),32).isEmpty());
        assertTrue(index.nearby(new BlockPos(0,64,0),64,4,
                Set.of(),32).isEmpty());
    }
}
