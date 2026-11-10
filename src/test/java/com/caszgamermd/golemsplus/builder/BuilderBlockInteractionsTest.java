package com.caszgamermd.golemsplus.builder;

import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import net.minecraft.server.Bootstrap;
import net.minecraft.SharedConstants;
import static org.junit.jupiter.api.Assertions.*;

/** Manual schematic settings must use real clicks on already-paid blocks.
 * Facing, mounting, halves, and waterlogging never become free changes. */
final class BuilderBlockInteractionsTest {
    @BeforeAll static void bootstrapMinecraftRegistries(){
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }
    private static BlockState state(String encoded){
        var result=BuilderBlockResolver.resolve(encoded,
                BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
        assertNotNull(result,"Cannot resolve "+encoded);
        return result.state();
    }

    @Test void leverRequiresARealClickButKeepsItsMounting(){
        var target=state("minecraft:lever[face=floor,facing=north,powered=true]");
        var neutral=BuilderBlockInteractions.placementState(target);
        assertEquals(state("minecraft:lever[face=floor,facing=north,powered=false]"),neutral);
        assertTrue(BuilderBlockInteractions.needsInteraction(neutral,target));
        assertFalse(BuilderBlockInteractions.needsInteraction(
                state("minecraft:lever[face=wall,facing=north,powered=false]"),target));
    }

    @Test void repeaterAndComparatorModesAreManualNotBlockVariants(){
        var repeater=state("minecraft:repeater[delay=4,facing=north,locked=false,powered=false]");
        var defaultRepeater=BuilderBlockInteractions.placementState(repeater);
        assertEquals(state("minecraft:repeater[delay=1,facing=north,locked=false,powered=false]"),
                defaultRepeater);
        assertTrue(BuilderBlockInteractions.needsInteraction(defaultRepeater,repeater));
        assertFalse(BuilderBlockInteractions.needsInteraction(
                state("minecraft:repeater[delay=1,facing=south,locked=false,powered=false]"),
                repeater));

        var comparator=state("minecraft:comparator[facing=north,mode=subtract,powered=false]");
        var normal=BuilderBlockInteractions.placementState(comparator);
        assertEquals(state("minecraft:comparator[facing=north,mode=compare,powered=false]"),
                normal);
        assertTrue(BuilderBlockInteractions.needsInteraction(normal,comparator));
        // Powered is live world logic; mode is still an exact manual setting.
        assertTrue(BuilderPlacementState.matches(
                state("minecraft:comparator[facing=north,mode=subtract,powered=true]"),
                comparator));
        assertFalse(BuilderPlacementState.matches(normal,comparator));
    }

    @Test void trapdoorsAndFenceGatesClickWithoutLosingOrientation(){
        var trapdoor=state("minecraft:oak_trapdoor[facing=north,half=bottom,open=true,powered=false,waterlogged=false]");
        var closed=BuilderBlockInteractions.placementState(trapdoor);
        assertTrue(BuilderBlockInteractions.needsInteraction(closed,trapdoor));
        assertFalse(BuilderBlockInteractions.needsInteraction(
                state("minecraft:oak_trapdoor[facing=south,half=bottom,open=false,powered=false,waterlogged=false]"),
                trapdoor));
        var gate=state("minecraft:oak_fence_gate[facing=east,in_wall=false,open=true,powered=false]");
        assertTrue(BuilderBlockInteractions.needsInteraction(
                BuilderBlockInteractions.placementState(gate),gate));
        var iron=state("minecraft:iron_trapdoor[facing=north,half=bottom,open=true,powered=false,waterlogged=false]");
        assertEquals(iron,BuilderBlockInteractions.placementState(iron));
        assertFalse(BuilderBlockInteractions.needsInteraction(
                state("minecraft:iron_trapdoor[facing=north,half=bottom,open=false,powered=false,waterlogged=false]"),
                iron));
    }

    @Test void nonInteractiveAndMomentaryBlocksAreNotFaked(){
        var stone=state("minecraft:stone");
        assertEquals(stone,BuilderBlockInteractions.placementState(stone));
        var button=state("minecraft:oak_button[face=floor,facing=north,powered=true]");
        assertEquals(button,BuilderBlockInteractions.placementState(button));
    }
}
