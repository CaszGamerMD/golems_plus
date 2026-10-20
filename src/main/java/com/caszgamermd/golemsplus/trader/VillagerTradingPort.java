package com.caszgamermd.golemsplus.trader;

import java.util.List;
import java.util.UUID;

/**
 * Game-side integration contract, with no implementation yet.
 *
 * The future adapter must scan only loaded, reachable, living villagers in
 * an explicitly assigned village, validate owner permissions and the actual
 * merchant offer, consume real items, apply vanilla trading side effects,
 * credit only the actual returned item stacks, and persist trade receipts.
 * NEVER call the pure planner or generic ledger as a substitute for merchant
 * trading. A villager trade may fail because prices, stock, or position moved.
 */
public interface VillagerTradingPort {
    enum ExecutionResult { COMPLETED, OFFER_CHANGED, VILLAGER_GONE,
        RESTOCK_NEEDED, SUPPLIES_MISSING, INVENTORY_FULL, NOT_AUTHORIZED }

    List<VillagerOfferSnapshot> inspectLoadedVillagers(
            UUID operator, VillageSearchArea village);

    ExecutionResult attemptApprovedTrade(UUID operator, UUID transactionId,
            VillageSearchArea village, VillagerTradePlanner.Quote quote);
}
