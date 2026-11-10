package com.caszgamermd.golemsplus.builder;

import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Multi-cell Minecraft items cost ONE inventory item and ONE job.
 * Every companion remains a verified real-world placement, not ignored. */
final class BuilderMultiblockTest {
    @BeforeAll static void bootstrap(){
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }
    private static LitematicFileReader.Block block(
            int x,int y,int z,String state){
        return new LitematicFileReader.Block(x,y,z,state);
    }
    private static BuilderConstructionPlan.Result plan(
            List<LitematicFileReader.Block> cells,BuildSite site){
        return BuilderConstructionPlan.create(
                new LitematicFileReader.Schematic("Grouped blocks",cells,Map.of()),
                site,false);
    }
    private static final String DOOR_LOW=
            "minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false]";
    private static final String DOOR_HIGH=
            "minecraft:oak_door[facing=north,half=upper,hinge=left,open=false,powered=false]";
    private static final String BED_FOOT=
            "minecraft:red_bed[facing=east,occupied=false,part=foot]";
    private static final String BED_HEAD=
            "minecraft:red_bed[facing=east,occupied=false,part=head]";
    private static final String FLOWER_LOW="minecraft:sunflower[half=lower]";
    private static final String FLOWER_HIGH="minecraft:sunflower[half=upper]";

    @Test void doorBedAndPlantEachBecomeOnePaidStep(){
        var result=plan(List.of(
                block(0,1,0,DOOR_LOW),block(0,2,0,DOOR_HIGH),
                block(2,1,2,BED_FOOT),block(3,1,2,BED_HEAD),
                block(5,1,2,FLOWER_LOW),block(5,2,2,FLOWER_HIGH)),
                new BuildSite(100,65,200,BuildSite.Rotation.NONE,
                        BuildSite.Mirror.NONE));
        assertEquals(3,result.steps().size(),"six occupied cells are three real items");
        assertTrue(result.unsupported().isEmpty(),result.unsupported().toString());
        assertEquals(Map.of("minecraft:oak_door",1L,"minecraft:red_bed",1L,
                "minecraft:sunflower",1L),result.required());
        assertEquals(result.required(),
                BuilderMaterialDemand.requiredMaterials(result.steps()));
        assertEquals(result.required(),
                BuilderMaterialDemand.nextWindow(result.steps(),0,i->false,8));
        assertEquals(new BuilderConstructionPlan.Companion(
                100,67,200,DOOR_HIGH),result.steps().get(0).companion());
        assertEquals(new BuilderConstructionPlan.Companion(
                103,66,202,BED_HEAD),result.steps().get(1).companion());
        assertEquals(new BuilderConstructionPlan.Companion(
                105,67,202,FLOWER_HIGH),result.steps().get(2).companion());
    }
    @Test void rotationMovesBothBedCellsAsOneLogicalObject(){
        var result=plan(List.of(
                block(2,4,3,BED_FOOT),block(3,4,3,BED_HEAD)),
                new BuildSite(10,64,20,BuildSite.Rotation.CLOCKWISE_90,
                        BuildSite.Mirror.FLIP_X));
        assertEquals(1,result.steps().size());
        var primary=result.steps().get(0);
        assertEquals(7,primary.x());assertEquals(68,primary.y());
        assertEquals(18,primary.z());
        assertEquals(7,primary.companion().x());
        assertEquals(68,primary.companion().y());
        assertEquals(17,primary.companion().z());
        assertEquals("minecraft:red_bed",primary.requiredItem());
    }
    @Test void loneOrMismatchedPartsCannotGenerateFreeBlocks(){
        var orphan=plan(List.of(block(1,3,1,DOOR_HIGH)),
                new BuildSite(0,65,0,BuildSite.Rotation.NONE,
                        BuildSite.Mirror.NONE));
        assertEquals(1,orphan.steps().size());
        assertNull(orphan.steps().get(0).requiredItem());
        assertTrue(orphan.steps().get(0).encodedState()
                .startsWith("golems_plus:unpaired_multiblock|"));
        assertFalse(orphan.unsupported().isEmpty());
        var wrong=plan(List.of(block(1,3,1,DOOR_LOW),
                block(1,4,1,"minecraft:birch_door[facing=north,half=upper,hinge=left,open=false,powered=false]")),
                new BuildSite(0,65,0,BuildSite.Rotation.NONE,
                        BuildSite.Mirror.NONE));
        assertEquals(2,wrong.steps().size());
        assertTrue(wrong.steps().stream().allMatch(s->s.companion()==null));
        assertTrue(wrong.unsupported().size()>=1);
    }
    @Test void secondaryPartsCannotMatchUnrelatedFacingOrHinges(){
        assertFalse(BuilderMultiblock.matchingPair(DOOR_LOW,
                "minecraft:oak_door[facing=south,half=upper,hinge=left,open=false,powered=false]"));
        assertFalse(BuilderMultiblock.matchingPair(DOOR_LOW,
                "minecraft:oak_door[facing=north,half=upper,hinge=right,open=false,powered=false]"));
        assertFalse(BuilderMultiblock.matchingPair(BED_FOOT,
                "minecraft:red_bed[facing=west,occupied=false,part=head]"));
    }
}
