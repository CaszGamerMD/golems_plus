package com.caszgamermd.golemsplus.warehouse;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WarehouseLinkValidatorTest {
    private static final String SCANNER_DIM =
            "ResourceKey[minecraft:dimension / minecraft:overworld]";
    private static final String CANONICAL_DIM = "minecraft:overworld";

    @Test void verifiesLoadedWarehouseAndNormalizesSavedDimension() {
        var result = WarehouseLinkValidator.validate(SCANNER_DIM, SCANNER_DIM,
                CANONICAL_DIM, true, 24, 65, -30, true, true);
        assertTrue(result.successful());
        assertTrue(result.computerVerified());
        assertEquals(new WarehouseAssignment(CANONICAL_DIM, 24, 65, -30),
                result.assignment());
    }

    @Test void keepsRemoteScannerLinksWithoutLoadingDistantChunks() {
        var result = WarehouseLinkValidator.validate(SCANNER_DIM, SCANNER_DIM,
                CANONICAL_DIM, true, -1200, 75, 300, false, false);
        assertTrue(result.successful());
        assertFalse(result.computerVerified());
        assertEquals(-1200, result.assignment().x());
    }

    @Test void rejectsUnlinkedScannerEvenAtCoordinateZero() {
        var result = WarehouseLinkValidator.validate("", SCANNER_DIM,
                CANONICAL_DIM, false, 0, 0, 0, false, false);
        assertEquals(WarehouseLinkValidator.Status.NOT_LINKED, result.status());
        assertNull(result.assignment());
        var incomplete = WarehouseLinkValidator.validate(SCANNER_DIM, SCANNER_DIM,
                CANONICAL_DIM, false, 0, 0, 0, true, true);
        assertEquals(WarehouseLinkValidator.Status.NOT_LINKED, incomplete.status());
    }

    @Test void rejectsWrongDimensionOrMissingComputerInLoadedChunk() {
        var otherDimension = WarehouseLinkValidator.validate(
                "ResourceKey[minecraft:dimension / minecraft:the_nether]",
                SCANNER_DIM, CANONICAL_DIM, true, 10, 70, 10, false, false);
        assertEquals(WarehouseLinkValidator.Status.WRONG_DIMENSION,
                otherDimension.status());
        assertNull(otherDimension.assignment());

        var missingComputer = WarehouseLinkValidator.validate(SCANNER_DIM,
                SCANNER_DIM, CANONICAL_DIM, true, 10, 70, 10, true, false);
        assertEquals(WarehouseLinkValidator.Status.COMPUTER_MISSING,
                missingComputer.status());
        assertNull(missingComputer.assignment());
    }
}
