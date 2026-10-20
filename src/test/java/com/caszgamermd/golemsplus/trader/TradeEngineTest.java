package com.caszgamermd.golemsplus.trader;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TradeEngineTest {
    @Test void atomicAndIdempotent() {
        var trader = new TradeEngine();
        var customer = new TradeInventory(20);
        var shop = new TradeInventory(20);
        assertTrue(customer.change("minecraft:emerald", 5));
        assertTrue(shop.change("minecraft:bread", 3));
        var offer = new TradeOffer("minecraft:emerald", 2, "minecraft:bread", 1);
        UUID tx = UUID.randomUUID();
        assertEquals(TradeEngine.Result.COMPLETED,
                trader.execute(tx, true, offer, customer, shop));
        assertEquals(3, customer.count("minecraft:emerald"));
        assertEquals(1, customer.count("minecraft:bread"));
        assertEquals(2, shop.count("minecraft:bread"));
        assertEquals(2, shop.count("minecraft:emerald"));
        assertEquals(TradeEngine.Result.DUPLICATE,
                trader.execute(tx, true, offer, customer, shop));
        assertEquals(1, customer.count("minecraft:bread"));
        assertEquals(TradeEngine.Result.NOT_AUTHORIZED,
                trader.execute(UUID.randomUUID(), false, offer, customer, shop));
    }
    @Test void refusesFullOrEmptyInventoriesWithoutPartialTransfer() {
        var engine = new TradeEngine();
        var customer = new TradeInventory(2);
        var shop = new TradeInventory(3);
        customer.change("minecraft:emerald", 2);
        shop.change("minecraft:bread", 3);
        var offer = new TradeOffer("minecraft:emerald", 1, "minecraft:bread", 2);
        assertEquals(TradeEngine.Result.BUYER_FULL,
                engine.execute(UUID.randomUUID(), true, offer, customer, shop));
        assertEquals(2, customer.count("minecraft:emerald"));
        assertEquals(3, shop.count("minecraft:bread"));
    }
}
