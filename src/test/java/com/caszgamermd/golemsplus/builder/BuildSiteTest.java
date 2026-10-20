package com.caszgamermd.golemsplus.builder;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuildSiteTest {
    @Test void rotationMirrorAndAnchor() {
        var p = new BuilderMaterialPlan.Placement(2, 1, 3, "minecraft:stone", "minecraft:stone");
        var site = new BuildSite(100, 64, -20,
                BuildSite.Rotation.CLOCKWISE_90, BuildSite.Mirror.FLIP_X);
        var positioned = site.locate(p);
        assertEquals(97, positioned.x());
        assertEquals(65, positioned.y());
        assertEquals(-22, positioned.z());
        assertEquals("minecraft:stone", positioned.item());
    }
}
