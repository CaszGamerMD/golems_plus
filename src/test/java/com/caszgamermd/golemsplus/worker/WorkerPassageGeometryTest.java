package com.caszgamermd.golemsplus.worker;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

final class WorkerPassageGeometryTest {
    @Test void centeredBodyClearsEitherOpenDoorPanelWithMargin() {
        double panel = 3.0 / 16;
        double min = .5 - WorkerPassageGeometry.HALF_WIDTH;
        double max = .5 + WorkerPassageGeometry.HALF_WIDTH;
        assertTrue(min > panel, "West/north hinge must not intersect body");
        assertTrue(max < 1 - panel, "East/south hinge must not intersect body");
        assertTrue(.5 - .35 < panel, "Previous .70-wide body overlapped the panel");
    }
}
