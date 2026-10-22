package com.caszgamermd.golemsplus.render;

import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;

/** Model-only runtime data; no server networking or new data trackers. */
public final class ProfessionGolemRenderState extends LivingEntityRenderState {
    public boolean carrying;
    public boolean working;
    public boolean presenting;
    public boolean hasRod;
    public boolean carryingFish;
    public final net.minecraft.client.renderer.item.ItemStackRenderState tradeItem = new net.minecraft.client.renderer.item.ItemStackRenderState();
}
