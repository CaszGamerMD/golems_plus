package com.caszgamermd.golemsplus.builder;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuilderSiteClearanceTest {
    @Test void clearsExplicitAirPositionsAndKeepsSolidBlueprintCells(){
        var solid=new LitematicFileReader.Block(0,2,0,"minecraft:stone");
        var air=new LitematicFileReader.Block(1,0,0,"minecraft:air");
        var schematic=new LitematicFileReader.Schematic("Test",
                List.of(solid),Map.of("minecraft:stone",1L),List.of(air));
        var site=new BuildSite(10,64,20,
                BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
        var targets=BuilderSiteClearance.targets(schematic,site);
        assertEquals(2,targets.size());
        assertEquals("minecraft:air",targets.get(0).state());
        assertEquals(11,targets.get(0).pos().getX());
        assertEquals(64,targets.get(0).pos().getY());
        assertEquals("minecraft:stone",targets.get(1).state());
        assertEquals(66,targets.get(1).pos().getY());
    }
    @Test void rotatedAndMirroredClearingPositionsMatchBuildTransform(){
        var block=new LitematicFileReader.Block(2,1,3,"minecraft:air");
        var schematic=new LitematicFileReader.Schematic("Rotated",
                List.of(),Map.of(),List.of(block));
        var site=new BuildSite(40,70,80,
                BuildSite.Rotation.CLOCKWISE_90,BuildSite.Mirror.FLIP_X);
        var actual=BuilderSiteClearance.targets(schematic,site).getFirst().pos();
        var expected=site.locate(new BuilderMaterialPlan.Placement(
                block.x(),block.y(),block.z(),block.state(),null));
        assertEquals(expected.x(),actual.getX());
        assertEquals(expected.y(),actual.getY());
        assertEquals(expected.z(),actual.getZ());
    }
    @Test void overlappingSolidPlacementOverridesAirForSafety(){
        var air=new LitematicFileReader.Block(0,0,0,"minecraft:air");
        var solid=new LitematicFileReader.Block(0,0,0,"minecraft:stone");
        var schematic=new LitematicFileReader.Schematic("Overlap",
                List.of(solid),Map.of("minecraft:stone",1L),List.of(air));
        var site=new BuildSite(0,64,0,
                BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
        var targets=BuilderSiteClearance.targets(schematic,site);
        assertEquals(1,targets.size());
        assertEquals("minecraft:stone",targets.getFirst().state());
    }
}
