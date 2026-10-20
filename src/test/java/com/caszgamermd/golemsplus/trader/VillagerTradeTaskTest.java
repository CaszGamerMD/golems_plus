package com.caszgamermd.golemsplus.trader;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VillagerTradeTaskTest {
    @Test void onlyRealMerchantConfirmationCountsAsCargoThenDelivery() {
        UUID owner = UUID.randomUUID(), villager = UUID.randomUUID();
        var request = new VillagerTradeRequest(owner, UUID.randomUUID(),
                "minecraft:emerald", 3, Set.of(), Map.of("minecraft:carrot", 64));
        var task = new VillagerTradeTask(request);
        var quote = new VillagerTradePlanner.Quote(request.orderId(),
                villager, 1, 3, 3, Map.of("minecraft:carrot", 60));
        task.select(quote);
        assertThrows(IllegalStateException.class, () -> task.confirmTrade(3, Map.of("minecraft:carrot", 60)));
        task.arrivedAtVillager();
        task.offerRevalidated();
        task.confirmTrade(3, Map.of("minecraft:carrot", 60));
        assertEquals(0, task.delivered());
        assertEquals(3, task.inTransit());
        assertThrows(IllegalStateException.class, () -> task.confirmTrade(3, Map.of("minecraft:carrot", 60)));
        task.arrivedHome();
        task.deposited(2);
        assertEquals(2, task.delivered());
        assertEquals(1, task.inTransit());
        task.deposited(1);
        assertEquals(VillagerTradeTask.Stage.COMPLETE, task.stage());
        assertEquals(0, task.outstanding());
        assertThrows(IllegalStateException.class, () -> task.deposited(1));
    }

    @Test void changedOffersWaitForRestockWithoutCreatingGoods() {
        UUID villager = UUID.randomUUID();
        var request = new VillagerTradeRequest(UUID.randomUUID(), UUID.randomUUID(),
                "minecraft:emerald", 2, Set.of(), Map.of("minecraft:wheat", 64));
        var task = new VillagerTradeTask(request);
        task.select(new VillagerTradePlanner.Quote(request.orderId(), villager,
                0, 1, 1, Map.of("minecraft:wheat", 20)));
        task.arrivedAtVillager();
        task.quoteExpired();
        assertEquals(VillagerTradeTask.Stage.WAITING_FOR_RESTOCK, task.stage());
        assertEquals(0, task.delivered());
        task.retry();
        task.pause();
        assertEquals(VillagerTradeTask.Stage.PAUSED, task.stage());
        task.resume();
        assertEquals(VillagerTradeTask.Stage.SCANNING, task.stage());
    }

    @Test void realPaymentsAreValidatedAcrossMultipleTrips() {
        UUID villager = UUID.randomUUID();
        var request = new VillagerTradeRequest(UUID.randomUUID(), UUID.randomUUID(),
                "minecraft:emerald", 3, Set.of(), Map.of("minecraft:wheat", 40));
        var task = new VillagerTradeTask(request);
        task.select(new VillagerTradePlanner.Quote(request.orderId(), villager, 0,
                2, 2, Map.of("minecraft:wheat", 40)));
        task.arrivedAtVillager();
        task.offerRevalidated();
        assertThrows(IllegalArgumentException.class, () ->
                task.confirmTrade(1, Map.of("minecraft:wheat", 25)));
        task.confirmTrade(2, Map.of("minecraft:wheat", 40));
        task.arrivedHome();
        task.deposited(2);
        assertEquals(40, task.paidAcrossTrips().get("minecraft:wheat"));
        assertEquals(1, task.outstanding());
        var stock = new TradeInventory(100);
        stock.change("minecraft:wheat", 60);
        var live = new VillagerOfferSnapshot(villager, 0,
                new VillagerOfferSnapshot.StackCost("minecraft:wheat", 20),
                null, new VillagerOfferSnapshot.StackCost("minecraft:emerald", 1),
                0, 10, true);
        assertTrue(VillagerTradePlanner.choose(request, java.util.List.of(live),
                stock, task.paidAcrossTrips()).isEmpty());
    }

    @Test void villageAreaAndBudgetsRejectInvalidConfiguration() {
        assertThrows(IllegalArgumentException.class, () ->
                new VillageSearchArea("minecraft:overworld", 0, 64, 0, 1024));
        assertThrows(IllegalArgumentException.class, () ->
                new VillagerTradeRequest(UUID.randomUUID(), UUID.randomUUID(),
                        "minecraft:emerald", 10, Set.of(), Map.of()));
    }
}
