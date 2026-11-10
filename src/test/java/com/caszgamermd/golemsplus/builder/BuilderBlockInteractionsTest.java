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

    @Test void energyAndWireConnectionsBeginNeutralUntilWorldUpdates(){
        var wire=state("minecraft:redstone_wire[east=side,north=up,power=12,south=side,west=none]");
        var unpowered=BuilderBlockInteractions.placementState(wire);
        assertEquals(state("minecraft:redstone_wire[east=none,north=none,power=0,south=none,west=none]"),
                unpowered);
        var rail=state("minecraft:powered_rail[powered=true,shape=ascending_west,waterlogged=false]");
        assertEquals(state("minecraft:powered_rail[powered=false,shape=ascending_west,waterlogged=false]"),
                BuilderBlockInteractions.placementState(rail));
        // Don't accidentally reset a rail's precise slope or waterlog.
        assertEquals(rail.getBlock(),BuilderBlockInteractions.placementState(rail).getBlock());
        var comparator=state("minecraft:comparator[facing=north,mode=subtract,powered=true]");
        assertEquals(state("minecraft:comparator[facing=north,mode=compare,powered=false]"),
                BuilderBlockInteractions.placementState(comparator));
    }

    @Test void noteTuningUsesRealClicksWhileInstrumentTracksSupport(){
        var note=state("minecraft:note_block[instrument=harp,note=24,powered=false]");
        var untuned=BuilderBlockInteractions.placementState(note);
        assertEquals(state("minecraft:note_block[instrument=harp,note=0,powered=false]"),
                untuned);
        // The source instrument is not physically selectable; it depends
        // on the block under the placed note block.
        var bassTarget=state("minecraft:note_block[instrument=basedrum,note=24,powered=true]");
        assertEquals(untuned,BuilderBlockInteractions.placementState(bassTarget));
        assertTrue(BuilderBlockInteractions.needsInteraction(untuned,note));
        // Note sounds come from the actual supporting block, not the
        // schematic item; tuning and facing remain exact.
        var onStone=state("minecraft:note_block[instrument=basedrum,note=24,powered=true]");
        assertTrue(BuilderPlacementState.matches(onStone,note));
        assertFalse(BuilderPlacementState.matches(untuned,note));
    }

    @Test void daylightInversionIsManualButPowerComesFromTheSky(){
        var desired=state("minecraft:daylight_detector[inverted=true,power=15]");
        var neutral=BuilderBlockInteractions.placementState(desired);
        assertEquals(state("minecraft:daylight_detector[inverted=false,power=0]"),
                neutral);
        assertTrue(BuilderBlockInteractions.needsInteraction(neutral,desired));
        assertTrue(BuilderPlacementState.matches(
                state("minecraft:daylight_detector[inverted=true,power=0]"),desired));
        assertFalse(BuilderPlacementState.matches(neutral,desired));
    }

    @Test void redstoneOutputsUpdateFromRealNeighborsNotFakeItems(){
        var lamp=state("minecraft:redstone_lamp[lit=true]");
        assertEquals(state("minecraft:redstone_lamp[lit=false]"),
                BuilderBlockInteractions.placementState(lamp));
        assertTrue(BuilderPlacementState.matches(
                state("minecraft:redstone_lamp[lit=false]"),lamp));
        var observer=state("minecraft:observer[facing=north,powered=true]");
        assertTrue(BuilderPlacementState.matches(
                state("minecraft:observer[facing=north,powered=false]"),observer));
        assertFalse(BuilderPlacementState.matches(
                state("minecraft:observer[facing=south,powered=false]"),observer));
        var hook=state("minecraft:tripwire_hook[facing=north,powered=true,attached=true]");
        assertTrue(BuilderPlacementState.matches(
                state("minecraft:tripwire_hook[facing=north,powered=false,attached=false]"),
                hook));
        assertFalse(BuilderPlacementState.matches(
                state("minecraft:tripwire_hook[facing=east,powered=false,attached=false]"),
                hook));
        var freshHook=BuilderBlockInteractions.placementState(hook);
        assertEquals(state("minecraft:tripwire_hook[facing=north,powered=false,attached=false]"),
                freshHook);
        var target=state("minecraft:target[power=11]");
        assertEquals(state("minecraft:target[power=0]"),
                BuilderBlockInteractions.placementState(target));
    }

    @Test void nonInteractiveAndMomentaryBlocksAreNotFaked(){
        var stone=state("minecraft:stone");
        assertEquals(stone,BuilderBlockInteractions.placementState(stone));
        var button=state("minecraft:oak_button[face=floor,facing=north,powered=true]");
        assertEquals(button,BuilderBlockInteractions.placementState(button));
    }
}
