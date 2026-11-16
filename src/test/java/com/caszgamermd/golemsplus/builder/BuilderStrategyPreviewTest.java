package com.caszgamermd.golemsplus.builder;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuilderStrategyPreviewTest {
    private BuilderConstructionPlan.Step cell(int x,int y,int z,String item){
        return new BuilderConstructionPlan.Step(x,y,z,item,item);
    }
    @Test void stripsKeepColumnSupportAndBatchIndices(){
        var steps=List.of(cell(0,0,0,"minecraft:stone"),cell(9,0,0,"minecraft:stone"),
                cell(0,1,0,"minecraft:stone"),cell(9,1,0,"minecraft:stone"));
        var indices=List.of(0,1,2,3);
        var y=BuilderStrategyPreview.analyze(steps,indices,BuilderStrategyPreview.Axis.Y,2);
        var x=BuilderStrategyPreview.analyze(steps,indices,BuilderStrategyPreview.Axis.X,2);
        assertEquals(28,y.coordinateTravel());
        assertEquals(12,x.coordinateTravel());
        assertEquals(0,x.lowerSupportLate());
        assertEquals(List.of(0,2),x.current().indices());
        assertEquals(List.of(1,3),x.next().indices());
        assertEquals(2,x.next().materials().get("minecraft:stone"));
        assertEquals(1,x.batches64());
        assertThrows(IllegalArgumentException.class,()->BuilderStrategyPreview.analyze(steps,List.of(0,0),BuilderStrategyPreview.Axis.X,2));
    }
    @Test void reusableSuppliesAndCrossZoneDependenciesAreExplicit(){
        var steps=List.of(cell(0,0,0,"minecraft:stone"),
                cell(0,1,0,"minecraft:stone"),
                new BuilderConstructionPlan.Step(0,2,0,"minecraft:water[level=0]",BuilderWaterPass.WATER_BUCKET),
                new BuilderConstructionPlan.Step(0,3,0,"minecraft:water[level=0]",BuilderWaterPass.WATER_BUCKET));
        var r=BuilderStrategyPreview.analyze(steps,List.of(1,2,3),BuilderStrategyPreview.Axis.Z,3);
        assertEquals(1,r.crossZoneLowerSupport());
        assertEquals(1,r.current().materials().get(BuilderWaterPass.WATER_BUCKET));
        assertTrue(r.next().indices().isEmpty());
    }
}
