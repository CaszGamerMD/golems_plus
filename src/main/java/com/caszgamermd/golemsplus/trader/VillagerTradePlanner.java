package com.caszgamermd.golemsplus.trader;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only quote planner for real villager offers. This class never performs
 * a Minecraft trade, grants inventory, increments offer uses, or spawns loot.
 * It supports player -> villager payments and villager -> player goods, e.g.
 * crops into emeralds or emeralds into enchanted books.
 */
public final class VillagerTradePlanner {
    public static final int MAX_TRADES_PER_TRIP = 64;

    public record Quote(UUID order, UUID villager, int offerIndex,
                        int executions, int goodsCount, Map<String, Integer> payments) {
        public Quote {
            if (order == null || villager == null || offerIndex < 0
                    || executions <= 0 || executions > MAX_TRADES_PER_TRIP
                    || goodsCount <= 0)
                throw new IllegalArgumentException("Invalid quote");
            payments = Map.copyOf(payments);
        }
    }

    private VillagerTradePlanner() {}

    /**
     * Returns one feasible villager/offer combination. The supplied stock is
     * an inventory snapshot, NOT a reservation. Quotes MUST be revalidated
     * at the villager before any actual trade or inventory mutation.
     */
    public static Optional<Quote> choose(VillagerTradeRequest request,
            List<VillagerOfferSnapshot> nearbyOffers, TradeInventory carriedStock) {
        if (request == null || nearbyOffers == null || carriedStock == null)
            throw new IllegalArgumentException("Missing trade planning inputs");
        Quote best = null;
        for (VillagerOfferSnapshot offer : nearbyOffers) {
            if (offer == null || !request.permits(offer.villager())
                    || offer.remainingUses() == 0
                    || !offer.received().itemId().equals(request.wantedItem()))
                continue;
            int maxExecutions = Math.min(MAX_TRADES_PER_TRIP, offer.remainingUses());
            maxExecutions = Math.min(maxExecutions,
                    (int) Math.min(Integer.MAX_VALUE,
                            ((long) request.wantedCount() + offer.received().count() - 1)
                            / offer.received().count()));
            TradeInventory trial = carriedStock.copy();
            Map<String, Integer> spend = new HashMap<>();
            int completed = 0;
            for (int i = 0; i < maxExecutions; i++) {
                TradeInventory next = trial.copy();
                Map<String, Integer> nextSpend = new HashMap<>(spend);
                if (!pay(next, nextSpend, request.totalSpendLimits(), offer.primary()))
                    break;
                if (offer.secondary() != null && !pay(next, nextSpend,
                        request.totalSpendLimits(), offer.secondary()))
                    break;
                if (!next.change(offer.received().itemId(), offer.received().count()))
                    break; // cannot receive loot: do not spend either payment
                trial = next;
                spend = nextSpend;
                completed++;
            }
            if (completed == 0) continue;
            Quote quote = new Quote(request.orderId(), offer.villager(), offer.offerIndex(),
                    completed, Math.multiplyExact(completed, offer.received().count()), spend);
            if (best == null || quote.goodsCount() > best.goodsCount()
                    || (quote.goodsCount() == best.goodsCount()
                        && totalPayments(quote) < totalPayments(best)))
                best = quote;
        }
        return Optional.ofNullable(best);
    }

    private static long totalPayments(Quote quote) {
        return quote.payments().values().stream().mapToLong(Integer::longValue).sum();
    }

    private static boolean pay(TradeInventory inventory, Map<String, Integer> spent,
            Map<String, Integer> limits, VillagerOfferSnapshot.StackCost cost) {
        int max = limits.getOrDefault(cost.itemId(), 0);
        long newSpending = (long) spent.getOrDefault(cost.itemId(), 0) + cost.count();
        if (newSpending > max || newSpending > Integer.MAX_VALUE) return false;
        if (!inventory.change(cost.itemId(), -cost.count())) return false;
        spent.put(cost.itemId(), (int) newSpending);
        return true;
    }
}
