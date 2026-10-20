package com.caszgamermd.golemsplus.trader;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * All-or-nothing, idempotent trade planner for two owned ledgers.
 * Production integration must verify item components, access rights and
 * commit with Minecraft inventory locks in the same server tick.
 */
public final class TradeEngine {
    public enum Result { COMPLETED, DUPLICATE, NOT_AUTHORIZED, INSUFFICIENT_FUNDS,
                         SOLD_OUT, BUYER_FULL, SHOP_FULL }
    private final Set<UUID> completed = new HashSet<>();

    public synchronized Result execute(UUID transaction, boolean authorized,
            TradeOffer offer, TradeInventory buyer, TradeInventory shop) {
        if (transaction == null || offer == null || buyer == null || shop == null
                || buyer == shop)
            throw new IllegalArgumentException("Invalid transaction");
        if (completed.contains(transaction)) return Result.DUPLICATE;
        if (!authorized) return Result.NOT_AUTHORIZED;
        TradeInventory buyerCopy = buyer.copy();
        TradeInventory shopCopy = shop.copy();
        if (!buyerCopy.change(offer.paymentId(), -offer.paymentCount()))
            return Result.INSUFFICIENT_FUNDS;
        if (!shopCopy.change(offer.goodsId(), -offer.goodsCount()))
            return Result.SOLD_OUT;
        if (!buyerCopy.change(offer.goodsId(), offer.goodsCount()))
            return Result.BUYER_FULL;
        if (!shopCopy.change(offer.paymentId(), offer.paymentCount()))
            return Result.SHOP_FULL;
        buyer.commitFrom(buyerCopy);
        shop.commitFrom(shopCopy);
        completed.add(transaction);
        return Result.COMPLETED;
    }
    public synchronized boolean wasCompleted(UUID transaction) {
        return completed.contains(transaction);
    }
}
