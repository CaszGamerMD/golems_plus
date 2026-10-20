package com.caszgamermd.golemsplus.trader;

/**
 * Player-selected village search area; not a claim on nearby entities.
 * Search implementation must only scan loaded chunks inside the area.
 */
public record VillageSearchArea(String dimension, int x, int y, int z, int radius) {
    public VillageSearchArea {
        if (dimension == null || dimension.isBlank() || radius < 4 || radius > 128)
            throw new IllegalArgumentException("Invalid village search area");
    }
}
