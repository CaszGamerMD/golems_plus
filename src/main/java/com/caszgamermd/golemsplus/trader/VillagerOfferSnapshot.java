package com.caszgamermd.golemsplus.trader;

import java.util.UUID;

/**
 * Immutable read-only view of an actual villager's current merchant offer.
 * Values must be refreshed immediately before a real server-side trade.
 * Both costs are the villager's effective prices, not a static recipe list.
 */
public record VillagerOfferSnapshot(UUID villager, int offerIndex,
                                    StackCost primary, StackCost secondary,
                                    StackCost received, int uses, int maxUses,
                                    boolean canTrade) {
    public record StackCost(String itemId, int count) {
        public StackCost {
            if (itemId == null || itemId.isBlank() || count < 1)
                throw new IllegalArgumentException("A positive item amount is required");
        }
    }

    public VillagerOfferSnapshot {
        if (villager == null || offerIndex < 0 || primary == null || received == null
                || uses < 0 || maxUses <= 0 || uses > maxUses)
            throw new IllegalArgumentException("Invalid villager offer");
    }
    public int remainingUses() { return canTrade ? maxUses - uses : 0; }
}
