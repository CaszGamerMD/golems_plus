package com.caszgamermd.golemsplus.builder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Side-effect-free construction estimate. A future NBT adapter converts
 * .litematic regions into the validated inputs of this planner.
 * Material mapping must be supplied by a real block-to-item resolver:
 * block IDs are not always obtainable item IDs (doors, fluids, crops...).
 */
public final class BuilderMaterialPlan {
    public record PaletteState(String blockState, String requiredItem) {
        public PaletteState {
            if (blockState == null || blockState.isBlank())
                throw new IllegalArgumentException("Block state required");
        }
        public boolean isAir() {
            String id = blockState.split("\\[", 2)[0];
            return id.equals("minecraft:air") || id.equals("minecraft:cave_air")
                    || id.equals("minecraft:void_air");
        }
    }
    public record Placement(int x, int y, int z, String blockState, String requiredItem) {}
    public record Result(List<Placement> placements, Map<String, Long> materials,
                         Set<String> unresolvedStates) {
        public Result {
            placements = List.copyOf(placements);
            materials = Map.copyOf(materials);
            unresolvedStates = Set.copyOf(unresolvedStates);
        }
        public boolean canRequestSupplies() { return unresolvedStates.isEmpty(); }
    }

    private BuilderMaterialPlan() {}

    public static Result plan(int sizeX, int sizeY, int sizeZ,
                              List<PaletteState> palette, long[] packedStates) {
        if (sizeX < 1 || sizeY < 1 || sizeZ < 1 ||
                (long) sizeX * sizeY * sizeZ > 16_777_216)
            throw new IllegalArgumentException("Invalid region dimensions");
        if (palette == null || palette.isEmpty())
            throw new IllegalArgumentException("Missing palette");
        int[] indexes = LitematicPaletteDecoder.decode(
                packedStates, palette.size(), sizeX * sizeY * sizeZ);
        List<Placement> placements = new ArrayList<>();
        Map<String, Long> materials = new LinkedHashMap<>();
        Set<String> unresolved = new LinkedHashSet<>();
        for (int i = 0; i < indexes.length; i++) {
            PaletteState entry = palette.get(indexes[i]);
            if (entry.isAir()) continue;
            // Litematica's conventional x-fastest, z-second, y-third order.
            int x = i % sizeX;
            int z = (i / sizeX) % sizeZ;
            int y = i / (sizeX * sizeZ);
            placements.add(new Placement(x, y, z, entry.blockState(), entry.requiredItem()));
            if (entry.requiredItem() == null || entry.requiredItem().isBlank())
                unresolved.add(entry.blockState());
            else
                materials.merge(entry.requiredItem(), 1L, Long::sum);
        }
        return new Result(placements, materials, unresolved);
    }
}
