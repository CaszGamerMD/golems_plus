package com.caszgamermd.golemsplus.builder;

import java.util.Map;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LitematicaPlacementBoundsTest {
    enum RequiredEnabled { ANY, PLACEMENT_ENABLED }
    public static final class RegionBox {
        private final BlockPos a,b;
        public RegionBox(BlockPos a,BlockPos b){this.a=a;this.b=b;}
        public BlockPos getPos1(){return a;}
        public BlockPos getPos2(){return b;}
    }
    public static final class ModernPlacement {
        // Matches Litematica 26.2: the enclosing-box UI is hidden,
        // but its actual placement region boxes are still available.
        public RegionBox getEclosingBox(){return null;}
        public Map<String,RegionBox> getSubRegionBoxes(RequiredEnabled enabled){
            if(enabled!=RequiredEnabled.PLACEMENT_ENABLED)
                throw new AssertionError("Must use enabled placement regions");
            return Map.of("regionA",new RegionBox(
                            new BlockPos(20,74,10),new BlockPos(16,70,15)),
                    "regionB",new RegionBox(
                            new BlockPos(-5,68,12),new BlockPos(-2,80,18)));
        }
    }
    public static final class LegacyIntBox {
        public int minX=-2,minY=64,minZ=3,maxX=4,maxY=70,maxZ=9;
    }
    public static final class LegacyPlacement {
        public LegacyIntBox getEnclosingBox(){return new LegacyIntBox();}
    }
    public static final class EmptyModernPlacement {
        public Map<String,RegionBox> getSubRegionBoxes(RequiredEnabled enabled){
            return Map.of();
        }
    }

    @Test void modernEnabledRegionsWorkWithHiddenEnclosingBox()throws Exception{
        var bounds=LitematicaPlacementBounds.resolve(new ModernPlacement());
        assertEquals(-5,bounds.minX());
        assertEquals(68,bounds.minY());
        assertEquals(10,bounds.minZ());
        assertEquals(20,bounds.maxX());
        assertEquals(80,bounds.maxY());
        assertEquals(18,bounds.maxZ());
        assertEquals(26L*13*9,bounds.volume());
    }
    @Test void legacyPublicIntBoundingBoxRemainsSupported()throws Exception{
        var bounds=LitematicaPlacementBounds.resolve(new LegacyPlacement());
        assertEquals(-2,bounds.minX());
        assertEquals(70,bounds.maxY());
        assertEquals(7L*7*7,bounds.volume());
    }
    @Test void refusesEmptyEnabledRegionMap() {
        var e=assertThrows(IllegalArgumentException.class,
                ()->LitematicaPlacementBounds.resolve(new EmptyModernPlacement()));
        assertTrue(e.getMessage().contains("no enabled subregion boxes"));
    }
}
