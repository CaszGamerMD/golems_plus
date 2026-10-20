package com.caszgamermd.golemsplus.trader;

import java.util.HashMap;
import java.util.Map;

/**
 * Deterministic inventory ledger, independent of Minecraft stacks.
 * Every mutation is first simulated against a snapshot by TradeEngine.
 */
public final class TradeInventory {
    private final int maxTotalItems;
    private Map<String, Integer> contents = new HashMap<>();

    public TradeInventory(int maxTotalItems) {
        if (maxTotalItems < 0) throw new IllegalArgumentException("Negative capacity");
        this.maxTotalItems = maxTotalItems;
    }
    public int count(String item) { return contents.getOrDefault(item, 0); }
    public Map<String, Integer> snapshot() { return Map.copyOf(contents); }
    TradeInventory copy() {
        TradeInventory result = new TradeInventory(maxTotalItems);
        result.contents = new HashMap<>(contents);
        return result;
    }
    void commitFrom(TradeInventory staged) { contents = new HashMap<>(staged.contents); }

    public boolean change(String item, int delta) {
        if (item == null || item.isBlank()) throw new IllegalArgumentException("Missing item");
        long updated = (long) count(item) + delta;
        if (updated < 0 || updated > Integer.MAX_VALUE) return false;
        long total = contents.values().stream().mapToLong(Integer::longValue).sum();
        if (total + delta > maxTotalItems) return false;
        if (updated == 0) contents.remove(item);
        else contents.put(item, (int) updated);
        return true;
    }
}
