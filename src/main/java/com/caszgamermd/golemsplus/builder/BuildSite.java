package com.caszgamermd.golemsplus.builder;

import java.util.ArrayList;
import java.util.List;

/**
 * Player-approved world anchor plus horizontal transformation. This only
 * computes coordinates; it never places or destroys blocks.
 */
public record BuildSite(int anchorX, int anchorY, int anchorZ, Rotation rotation,
                        Mirror mirror) {
    public enum Rotation { NONE, CLOCKWISE_90, CLOCKWISE_180, COUNTERCLOCKWISE_90 }
    public enum Mirror { NONE, FLIP_X, FLIP_Z }
    public record WorldPlacement(int x, int y, int z, String blockState, String item) {}

    public BuildSite {
        if (rotation == null || mirror == null)
            throw new IllegalArgumentException("Missing site orientation");
    }

    public WorldPlacement locate(BuilderMaterialPlan.Placement block) {
        int x = block.x();
        int z = block.z();
        if (mirror == Mirror.FLIP_X) x = Math.negateExact(x);
        if (mirror == Mirror.FLIP_Z) z = Math.negateExact(z);
        int rx, rz;
        switch (rotation) {
            case NONE -> { rx = x; rz = z; }
            case CLOCKWISE_90 -> { rx = Math.negateExact(z); rz = x; }
            case CLOCKWISE_180 -> { rx = Math.negateExact(x); rz = Math.negateExact(z); }
            case COUNTERCLOCKWISE_90 -> { rx = z; rz = Math.negateExact(x); }
            default -> throw new IllegalStateException("Unknown rotation");
        }
        return new WorldPlacement(Math.addExact(anchorX, rx),
                Math.addExact(anchorY, block.y()), Math.addExact(anchorZ, rz),
                block.blockState(), block.requiredItem());
    }

    public List<WorldPlacement> preview(BuilderMaterialPlan.Result plan) {
        List<WorldPlacement> result = new ArrayList<>(plan.placements().size());
        for (var placement : plan.placements()) result.add(locate(placement));
        return List.copyOf(result);
    }
}
