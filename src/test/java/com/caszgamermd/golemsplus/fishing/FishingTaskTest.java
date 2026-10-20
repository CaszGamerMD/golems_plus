package com.caszgamermd.golemsplus.fishing;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FishingTaskTest {
    @Test void requiresEquipmentAndRealHookEvents() {
        FishingTask task = new FishingTask();
        assertFalse(task.castRequested());
        task.updateEquipment(true);
        assertEquals(FishingTask.Stage.NEED_WATER, task.stage());
        task.updateWater(true);
        assertTrue(task.castRequested());
        task.hookSpawned();
        task.updateEquipment(true); // polling does not restart the cast
        assertEquals(FishingTask.Stage.WAIT_BITE, task.stage());
        assertThrows(IllegalStateException.class, task::catchCollected);
        task.biteDetected();
        task.catchCollected();
        assertEquals(FishingTask.Stage.DEPOSIT, task.stage());
        task.deposited();
        assertEquals(FishingTask.Stage.READY, task.stage());
        task.updateStorage(false);
        assertEquals(FishingTask.Stage.STORAGE_FULL, task.stage());
        task.pause();
        task.resume();
        assertEquals(FishingTask.Stage.STORAGE_FULL, task.stage());
    }
}
