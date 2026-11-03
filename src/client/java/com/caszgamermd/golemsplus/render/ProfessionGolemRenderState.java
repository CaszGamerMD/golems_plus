package com.caszgamermd.golemsplus.render;

import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;

/** Model-only runtime data; no server networking or new data trackers. */
public final class ProfessionGolemRenderState extends LivingEntityRenderState {
    public boolean carrying;
    public boolean courier;
    public boolean working;
    public boolean presenting;
    public boolean hasRod;
    public boolean carryingFish;
    public boolean seated;
    public int chairColor;
    public boolean casting;
    public boolean reeling;
    public boolean breakRoomSeated;
    public boolean breakRoomEating;
    public boolean breakRoomPlayingArcade;
    public final ItemStackRenderState breakRoomSnack = new ItemStackRenderState();
    public final ItemStackRenderState chefIngredient = new ItemStackRenderState();
    public final ItemStackRenderState tradeItem = new ItemStackRenderState();
}
