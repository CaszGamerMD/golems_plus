package com.caszgamermd.golemsplus.trader;

/** Component-aware item identity will be added by the Minecraft adapter. */
public record TradeOffer(String paymentId, int paymentCount, String goodsId, int goodsCount) {
    public TradeOffer {
        if (paymentId == null || paymentId.isBlank() || goodsId == null || goodsId.isBlank())
            throw new IllegalArgumentException("Missing item identifier");
        if (paymentCount <= 0 || goodsCount <= 0)
            throw new IllegalArgumentException("Trade amounts must be positive");
    }
}
