package com.caszgamermd.golemsplus.builder;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuilderMaterialPlanTest {
    @Test void decodesAcrossLongBoundary() {
        int n = 28;
        long[] packed = new long[2];
        int[] expected = new int[n];
        for (int i = 0; i < n; i++) {
            expected[i] = i % 5;
            int bit = i * 3, word = bit >>> 6, shift = bit & 63;
            packed[word] |= ((long) expected[i]) << shift;
            if (shift + 3 > 64) packed[word + 1] |= (long) expected[i] >>> (64 - shift);
        }
        assertArrayEquals(expected, LitematicPaletteDecoder.decode(packed, 5, n));
        assertThrows(IllegalArgumentException.class,
                () -> LitematicPaletteDecoder.decode(new long[0], 5, n));
    }
    @Test void countsOnlyRealMaterials() {
        List<BuilderMaterialPlan.PaletteState> palette = List.of(
                new BuilderMaterialPlan.PaletteState("minecraft:air", null),
                new BuilderMaterialPlan.PaletteState("minecraft:oak_planks", "minecraft:oak_planks"),
                new BuilderMaterialPlan.PaletteState("mod:machine[facing=east]", null));
        long[] packed = { (1L) | (2L << 4) | (1L << 6) };
        var plan = BuilderMaterialPlan.plan(2, 1, 2, palette, packed);
        assertEquals(3, plan.placements().size());
        assertEquals(2L, plan.materials().get("minecraft:oak_planks"));
        assertTrue(plan.unresolvedStates().contains("mod:machine[facing=east]"));
        assertFalse(plan.canRequestSupplies());
        assertEquals(0, plan.placements().get(0).x());
        assertEquals(1, plan.placements().get(2).z());
    }
}
