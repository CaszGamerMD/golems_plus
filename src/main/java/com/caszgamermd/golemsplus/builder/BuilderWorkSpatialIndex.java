package com.caszgamermd.golemsplus.builder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntPredicate;
import net.minecraft.core.BlockPos;

/**
 * Immutable coordinate lookup for a compiled Builder schematic.
 *
 * Local follow-up selection must not rescan a worker's entire schematic zone
 * after every placed block. This index makes a bounded same-layer neighborhood
 * lookup proportional to the neighborhood radius, not the job size.
 */
public final class BuilderWorkSpatialIndex {
    private record Candidate(int index, int distanceSquared) {}

    private final Map<BlockPos, Integer> byPosition;

    private BuilderWorkSpatialIndex(Map<BlockPos, Integer> byPosition) {
        this.byPosition = Map.copyOf(byPosition);
    }

    public static BuilderWorkSpatialIndex create(
            List<BuilderConstructionPlan.Step> steps) {
        Map<BlockPos, Integer> positions = new HashMap<>();
        for (int index = 0; index < steps.size(); index++) {
            var step = steps.get(index);
            positions.put(new BlockPos(step.x(), step.y(), step.z()), index);
        }
        return new BuilderWorkSpatialIndex(positions);
    }

    /**
     * Find assigned schematic cells in a horizontal square around focus.
     * The active layer is explicit because a Builder may stand below the
     * layer while starting a new task. Results are nearest-first and bounded.
     * The predicate checks fixed rectangular-zone ownership in constant time.
     */
    public List<Integer> nearby(BlockPos focus, int layerY, int radius,
            IntPredicate allowed, int limit) {
        if (focus == null || allowed == null || radius < 0 || limit <= 0)
            return List.of();

        List<Candidate> found = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                Integer index = byPosition.get(
                        new BlockPos(focus.getX() + dx, layerY, focus.getZ() + dz));
                if (index != null && allowed.test(index))
                    found.add(new Candidate(index, dx * dx + dz * dz));
            }
        }
        found.sort(Comparator.comparingInt(Candidate::distanceSquared)
                .thenComparingInt(Candidate::index));
        if (found.size() > limit) found = found.subList(0, limit);
        return found.stream().map(Candidate::index).toList();
    }
}
