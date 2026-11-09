package com.caszgamermd.golemsplus.builder;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuilderRailSlopeStateTest {
    private static net.minecraft.world.level.block.state.BlockState rail(
            String attributes){
        return BuilderBlockResolver.resolve(
                "minecraft:powered_rail["+attributes+"]",
                BuildSite.Rotation.NONE,BuildSite.Mirror.NONE).state();
    }
    @Test void sameSlopeWithoutPowerIsNotAFailedRailJig(){
        var powered=rail("powered=true,shape=ascending_west,waterlogged=false");
        var powerless=rail("powered=false,shape=ascending_west,waterlogged=false");
        assertTrue(BuilderRailSlope.shapeMatches(powerless,powered));
        assertTrue(BuilderRailSlope.railStateDifference(powerless,powered)
                .contains("redstone power"));
    }
    @Test void realFlatRailStillNeedsAPhysicalSlopeJig(){
        var ascending=rail("powered=false,shape=ascending_west,waterlogged=false");
        var flat=rail("powered=false,shape=east_west,waterlogged=false");
        assertFalse(BuilderRailSlope.shapeMatches(flat,ascending));
        assertTrue(BuilderRailSlope.railStateDifference(flat,ascending)
                .contains("rail shape"));
    }
    @Test void wetRailNeedsFluidNotAnotherShapeJig(){
        var wet=rail("powered=false,shape=ascending_west,waterlogged=true");
        var dry=rail("powered=false,shape=ascending_west,waterlogged=false");
        assertTrue(BuilderRailSlope.shapeMatches(dry,wet));
        assertTrue(BuilderRailSlope.railStateDifference(dry,wet)
                .contains("waterlogging"));
    }
}
