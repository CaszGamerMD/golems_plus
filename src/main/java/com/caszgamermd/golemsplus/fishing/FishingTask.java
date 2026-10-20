package com.caszgamermd.golemsplus.fishing;

/**
 * A fishing worker's observable lifecycle. Only game-side hook events can
 * advance a cast; this state machine never fabricates loot or rod durability.
 */
public final class FishingTask {
    public enum Stage { NEED_ROD, NEED_WATER, READY, CAST, WAIT_BITE, REEL, DEPOSIT, STORAGE_FULL, PAUSED }
    private Stage stage = Stage.NEED_ROD;
    private boolean hasRod;
    private boolean validWater;
    private boolean hasStorage = true;
    private boolean paused;

    public Stage stage() { return stage; }
    public void updateEquipment(boolean rodPresent) { hasRod = rodPresent; reevaluate(); }
    public void updateWater(boolean waterVerified) { validWater = waterVerified; reevaluate(); }
    public void updateStorage(boolean freeSpace) { hasStorage = freeSpace; reevaluate(); }
    public void pause() { paused = true; stage = Stage.PAUSED; }
    public void resume() { paused = false; reevaluate(); }

    public boolean castRequested() {
        if (stage != Stage.READY) return false;
        stage = Stage.CAST;
        return true;
    }
    public void hookSpawned() {
        if (stage != Stage.CAST) throw new IllegalStateException("No active cast");
        stage = Stage.WAIT_BITE;
    }
    public void biteDetected() {
        if (stage != Stage.WAIT_BITE) throw new IllegalStateException("No bite to reel");
        stage = Stage.REEL;
    }
    public void catchCollected() {
        if (stage != Stage.REEL) throw new IllegalStateException("No retrieved catch");
        stage = Stage.DEPOSIT;
    }
    public void deposited() {
        if (stage != Stage.DEPOSIT) throw new IllegalStateException("No catch to deposit");
        stage = Stage.READY;
        reevaluate();
    }
    /** Hook removal, unload or interrupted cast aborts without inventing a catch. */
    public void interrupted() {
        stage = Stage.READY;
        reevaluate();
    }

    private void reevaluate() {
        if (paused) { stage = Stage.PAUSED; return; }
        // During a cast, repeated environment checks must not reset the task.
        if ((stage == Stage.CAST || stage == Stage.WAIT_BITE
                || stage == Stage.REEL || stage == Stage.DEPOSIT)
                && hasRod && validWater && hasStorage) return;
        if (!hasRod) stage = Stage.NEED_ROD;
        else if (!validWater) stage = Stage.NEED_WATER;
        else if (!hasStorage) stage = Stage.STORAGE_FULL;
        else stage = Stage.READY;
    }
}
