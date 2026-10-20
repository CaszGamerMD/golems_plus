package com.caszgamermd.golemsplus.trader;

import java.util.UUID;

/**
 * Worker lifecycle: a quote is not a completed transaction.
 * Only a successful server-side villager trade can call confirmTrade().
 * The cargo still must be physically returned to the owner's depot.
 */
public final class VillagerTradeTask {
    public enum Stage {
        SCANNING, TRAVELING, RECHECKING_OFFER, TRADING, RETURNING,
        DELIVERING, WAITING_FOR_RESTOCK, WAITING_FOR_SUPPLIES, PAUSED, COMPLETE
    }
    private final UUID orderId;
    private final int wantedCount;
    private int delivered;
    private int inTransit;
    private Stage stage = Stage.SCANNING;
    private VillagerTradePlanner.Quote selected;

    public VillagerTradeTask(VillagerTradeRequest request) {
        orderId = request.orderId();
        wantedCount = request.wantedCount();
    }
    public Stage stage() { return stage; }
    public int delivered() { return delivered; }
    public int outstanding() { return Math.max(0, wantedCount - delivered); }
    public int inTransit() { return inTransit; }
    public VillagerTradePlanner.Quote selected() { return selected; }

    public void select(VillagerTradePlanner.Quote quote) {
        require(Stage.SCANNING);
        if (quote == null || !quote.order().equals(orderId))
            throw new IllegalArgumentException("Quote belongs to a different job");
        selected = quote;
        stage = Stage.TRAVELING;
    }
    public void arrivedAtVillager() { require(Stage.TRAVELING); stage = Stage.RECHECKING_OFFER; }
    public void offerRevalidated() { require(Stage.RECHECKING_OFFER); stage = Stage.TRADING; }

    public void confirmTrade(int actualGoods) {
        require(Stage.TRADING);
        if (actualGoods <= 0 || selected == null || actualGoods > selected.goodsCount()
                || (long) inTransit + actualGoods > Integer.MAX_VALUE)
            throw new IllegalArgumentException("Invalid merchant confirmation");
        inTransit += actualGoods;
        stage = Stage.RETURNING;
    }
    public void arrivedHome() { require(Stage.RETURNING); stage = Stage.DELIVERING; }
    public void deposited(int actualGoods) {
        require(Stage.DELIVERING);
        if (actualGoods <= 0 || actualGoods > inTransit)
            throw new IllegalArgumentException("Cannot deliver imaginary items");
        inTransit -= actualGoods;
        delivered = Math.addExact(delivered, actualGoods);
        if (inTransit == 0) {
            selected = null;
            stage = outstanding() == 0 ? Stage.COMPLETE : Stage.SCANNING;
        }
    }
    public void quoteExpired() {
        require(Stage.RECHECKING_OFFER);
        selected = null;
        stage = Stage.WAITING_FOR_RESTOCK;
    }
    public void shortage() {
        if (stage != Stage.SCANNING && stage != Stage.RECHECKING_OFFER)
            throw new IllegalStateException("Cannot lose reserved cargo");
        selected = null;
        stage = Stage.WAITING_FOR_SUPPLIES;
    }
    public void retry() {
        if (stage != Stage.WAITING_FOR_RESTOCK && stage != Stage.WAITING_FOR_SUPPLIES)
            throw new IllegalStateException("Not waiting");
        stage = Stage.SCANNING;
    }
    // Pause is only valid before pickup; transit cargo requires persistence
    // and a separate physical return/recovery system.
    public void pause() {
        if (stage != Stage.SCANNING && stage != Stage.WAITING_FOR_RESTOCK
                && stage != Stage.WAITING_FOR_SUPPLIES)
            throw new IllegalStateException("Cargo or path is active");
        stage = Stage.PAUSED;
    }
    public void resume() { require(Stage.PAUSED); stage = Stage.SCANNING; }
    private void require(Stage expected) {
        if (stage != expected) throw new IllegalStateException("Expected " + expected + ", found " + stage);
    }
}
