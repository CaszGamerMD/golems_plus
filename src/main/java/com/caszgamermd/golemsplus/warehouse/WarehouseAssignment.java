package com.caszgamermd.golemsplus.warehouse;

/** A player-approved warehouse anchor. Coordinates alone do not grant access. */
public record WarehouseAssignment(String dimension, int x, int y, int z) {
    public WarehouseAssignment {
        if (dimension == null || dimension.isBlank())
            throw new IllegalArgumentException("Warehouse dimension required");
    }
}
