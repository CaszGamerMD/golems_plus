package com.caszgamermd.golemsplus.trader;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Owner-approved goods and maximum total spend for a single order.
 * Every payment item requires an explicit spending limit, so the golem
 * cannot burn through emeralds or other valuables on unapproved trades.
 * Empty allowedVillagers means all villagers within the ASSIGNED area.
 */
public record VillagerTradeRequest(UUID owner, UUID orderId, String wantedItem,
                                  int wantedCount, Set<UUID> allowedVillagers,
                                  Map<String, Integer> totalSpendLimits) {
    public VillagerTradeRequest {
        if (owner == null || orderId == null || wantedItem == null
                || wantedItem.isBlank() || wantedCount < 1)
            throw new IllegalArgumentException("Invalid trade request");
        allowedVillagers = Set.copyOf(allowedVillagers);
        totalSpendLimits = Map.copyOf(totalSpendLimits);
        if (totalSpendLimits.isEmpty() || totalSpendLimits.entrySet().stream()
                .anyMatch(e -> e.getKey() == null || e.getKey().isBlank()
                        || e.getValue() == null || e.getValue() < 1))
            throw new IllegalArgumentException("Every payment needs a positive budget");
    }
    public boolean permits(UUID villagerId) {
        return allowedVillagers.isEmpty() || allowedVillagers.contains(villagerId);
    }
}
