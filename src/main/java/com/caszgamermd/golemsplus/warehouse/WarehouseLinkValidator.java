package com.caszgamermd.golemsplus.warehouse;

/**
 * Checks whether a Caszual Additions scanner's saved link can be copied into
 * a Builder Planning Table. The scanner's dimension uses ResourceKey.toString()
 * in Caszual Additions; the Builder saves the canonical dimension identifier.
 *
 * Coordinates are NOT warehouse authorization. The future warehouse supply
 * adapter must independently check ownership and current computer validity.
 */
public final class WarehouseLinkValidator {
    public enum Status {
        LINKED, NOT_LINKED, WRONG_DIMENSION, COMPUTER_MISSING
    }

    public record Result(Status status, WarehouseAssignment assignment,
                         boolean computerVerified) {
        public boolean successful() { return status == Status.LINKED; }
    }

    private WarehouseLinkValidator() {}

    public static Result validate(String scannerDimension, String scannerWorldDimension,
            String canonicalDimension, boolean hasCoordinates, int x, int y, int z,
            boolean computerLoaded, boolean computerIsRackComputer) {
        if (scannerDimension == null || scannerDimension.isBlank() || !hasCoordinates)
            return new Result(Status.NOT_LINKED, null, false);
        if (!scannerDimension.equals(scannerWorldDimension))
            return new Result(Status.WRONG_DIMENSION, null, false);
        if (computerLoaded && !computerIsRackComputer)
            return new Result(Status.COMPUTER_MISSING, null, false);
        return new Result(Status.LINKED,
                new WarehouseAssignment(canonicalDimension, x, y, z),
                computerLoaded);
    }
}
