package com.caszgamermd.golemsplus.worker;

/** No job is allowed to mutate the world without passing a validation phase. */
public enum WorkerPhase {
    IDLE, PLANNING, WAITING_FOR_SUPPLIES, WAITING_FOR_LOCATION,
    READY, WORKING, DEPOSITING, PAUSED, BLOCKED, COMPLETE
}
