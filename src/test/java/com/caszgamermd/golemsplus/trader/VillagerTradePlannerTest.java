package com.caszgamermd.golemsplus.trader;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VillagerTradePlannerTest {
    private static VillagerOfferSnapshot.StackCost item(String id, int count) {
        return new VillagerOfferSnapshot.StackCost(id, count);
    }

    @Test void purchasesFromActualVillagerOfferSnapshotsWithoutMutatingStock() {
        UUID owner = UUID.randomUUID(), farmer = UUID.randomUUID(), librarian = UUID.randomUUID();
        var request = new VillagerTradeRequest(owner, UUID.randomUUID(),
                "minecraft:emerald", 4, Set.of(farmer),
                Map.of("minecraft:wheat", 40));
        var stock = new TradeInventory(100);
        stock.change("minecraft:wheat", 50);
        var excluded = new VillagerOfferSnapshot(librarian, 1,
                item("minecraft:wheat", 1), null, item("minecraft:emerald", 1), 0, 10, true);
        var farmerOffer = new VillagerOfferSnapshot(farmer, 0,
                item("minecraft:wheat", 20), null, item("minecraft:emerald", 1), 0, 10, true);
        var quote = VillagerTradePlanner.choose(request,
                List.of(excluded, farmerOffer), stock).orElseThrow();
        assertEquals(farmer, quote.villager());
        assertEquals(2, quote.executions());
        assertEquals(2, quote.goodsCount());
        assertEquals(40, quote.payments().get("minecraft:wheat"));
        assertEquals(50, stock.count("minecraft:wheat")); // planner never spends actual items
        assertEquals(0, stock.count("minecraft:emerald"));
    }

    @Test void twoInputVillagerOfferNeedsBothPaymentsAndOutputSpace() {
        UUID villager = UUID.randomUUID();
        var order = new VillagerTradeRequest(UUID.randomUUID(), UUID.randomUUID(),
                "minecraft:enchanted_book", 1, Set.of(),
                Map.of("minecraft:emerald", 20, "minecraft:book", 1));
        var offer = new VillagerOfferSnapshot(villager, 3,
                item("minecraft:emerald", 20), item("minecraft:book", 1),
                item("minecraft:enchanted_book", 1), 0, 5, true);
        var stock = new TradeInventory(21);
        stock.change("minecraft:emerald", 20);
        assertTrue(VillagerTradePlanner.choose(order, List.of(offer), stock).isEmpty());
        stock.change("minecraft:book", 1);
        assertEquals(1, VillagerTradePlanner.choose(order, List.of(offer), stock)
                .orElseThrow().executions());
        var limited = new VillagerTradeRequest(order.owner(), order.orderId(),
                order.wantedItem(), 1, Set.of(), Map.of("minecraft:emerald", 19,
                        "minecraft:book", 1));
        assertTrue(VillagerTradePlanner.choose(limited, List.of(offer), stock).isEmpty());
    }

    @Test void fullStorageAndExhaustedVillagerNeverQuote() {
        UUID v = UUID.randomUUID();
        var request = new VillagerTradeRequest(UUID.randomUUID(), UUID.randomUUID(),
                "minecraft:bread", 1, Set.of(), Map.of("minecraft:emerald", 1));
        var stock = new TradeInventory(1);
        stock.change("minecraft:emerald", 1);
        var expired = new VillagerOfferSnapshot(v, 0,
                item("minecraft:emerald", 1), null, item("minecraft:bread", 3), 2, 2, true);
        var live = new VillagerOfferSnapshot(v, 0,
                item("minecraft:emerald", 1), null, item("minecraft:bread", 3), 1, 2, true);
        assertTrue(VillagerTradePlanner.choose(request, List.of(expired), stock).isEmpty());
        assertTrue(VillagerTradePlanner.choose(request, List.of(live), stock).isEmpty());
        var roomy = new TradeInventory(5);
        roomy.change("minecraft:emerald", 1);
        assertEquals(3, VillagerTradePlanner.choose(request, List.of(live), roomy)
                .orElseThrow().goodsCount()); // offers yield bundles
    }

    @Test void alreadySpentOnPreviousTripsCountsAgainstOriginalLimits() {
        UUID villager = UUID.randomUUID();
        var request = new VillagerTradeRequest(UUID.randomUUID(), UUID.randomUUID(),
                "minecraft:emerald", 5, Set.of(), Map.of("minecraft:wheat", 40));
        var offer = new VillagerOfferSnapshot(villager, 0,
                item("minecraft:wheat", 20), null, item("minecraft:emerald", 1),
                0, 10, true);
        var stock = new TradeInventory(100);
        stock.change("minecraft:wheat", 100);
        assertEquals(2, VillagerTradePlanner.choose(request, List.of(offer), stock,
                Map.of()).orElseThrow().executions());
        assertEquals(1, VillagerTradePlanner.choose(request, List.of(offer), stock,
                Map.of("minecraft:wheat", 20)).orElseThrow().executions());
        assertTrue(VillagerTradePlanner.choose(request, List.of(offer), stock,
                Map.of("minecraft:wheat", 40)).isEmpty());
    }

    @Test void bothCostsUsingSameCurrencyRespectAggregateBudget() {
        UUID v = UUID.randomUUID();
        var request = new VillagerTradeRequest(UUID.randomUUID(), UUID.randomUUID(),
                "minecraft:diamond", 1, Set.of(), Map.of("minecraft:emerald", 3));
        var offer = new VillagerOfferSnapshot(v, 0,
                item("minecraft:emerald", 2), item("minecraft:emerald", 2),
                item("minecraft:diamond", 1), 0, 1, true);
        var stock = new TradeInventory(10);
        stock.change("minecraft:emerald", 10);
        assertTrue(VillagerTradePlanner.choose(request, List.of(offer), stock).isEmpty());
    }
}
