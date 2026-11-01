package com.caszgamermd.golemsplus.builder;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The clicked hologram coordinates, NOT the file's region coordinates,
 * are the single source of truth. No origin, signed extents, or placement
 * rotation may be applied after the client captures a snapshot.
 */
final class BuilderPlacementSnapshotTest {
    @Test void worldPositionsRoundTripWithoutDoubleTransform()throws Exception{
        var blocks=List.of(
                new LitematicFileReader.Block(-15,72,-24,
                        "minecraft:dark_oak_sign[rotation=12,waterlogged=false]"),
                new LitematicFileReader.Block(30,54,8,"minecraft:smooth_stone_slab[type=bottom,waterlogged=false]"),
                new LitematicFileReader.Block(30,55,8,"minecraft:stone"));
        var air=List.of(new LitematicFileReader.Block(29,54,8,"minecraft:air"));
        var data=BuilderPlacementSnapshot.encode("Matrix in world",blocks,air);
        var restored=BuilderPlacementSnapshot.decode(data);
        assertEquals("Matrix in world",restored.name());
        assertEquals(blocks,restored.blocks());
        assertEquals(air,restored.airBlocks());
        var plan=BuilderConstructionPlan.create(restored,
                BuilderPlacementSnapshot.worldSpaceSite());
        assertEquals(54,plan.steps().get(0).y());
        assertEquals(72,plan.steps().get(2).y());
        assertEquals(30,plan.steps().get(0).x());
        assertEquals(-15,plan.steps().get(2).x());
    }
    @Test void rejectsBadFormatAndDuplicateCoordinates()throws Exception{
        assertThrows(IOException.class,()->BuilderPlacementSnapshot.decode(
                new byte[]{1,2,3}));
        var same=List.of(new LitematicFileReader.Block(0,70,0,"minecraft:stone"),
                new LitematicFileReader.Block(0,70,0,"minecraft:glass"));
        assertThrows(IOException.class,()->BuilderPlacementSnapshot.decode(
                BuilderPlacementSnapshot.encode("bad",same,List.of())));
    }
    @Test void reconstructsAirCellsAtFinalWorldCoordinates()throws Exception{
        var block=List.of(new LitematicFileReader.Block(7,90,3,"minecraft:stone"));
        var air=List.of(new LitematicFileReader.Block(6,90,3,"minecraft:air"),
                new LitematicFileReader.Block(6,89,3,"minecraft:air"));
        var restored=BuilderPlacementSnapshot.decode(
                BuilderPlacementSnapshot.encode("floating",block,air));
        var clearing=BuilderSiteClearance.targets(restored,
                BuilderPlacementSnapshot.worldSpaceSite());
        assertEquals(3,clearing.size());
        assertEquals(6,clearing.get(0).pos().getX());
        assertEquals(89,clearing.get(0).pos().getY());
        assertEquals(7,clearing.get(2).pos().getX());
    }
}
