package com.caszgamermd.golemsplus.beekeeper;

import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;

public final class BeeRenderState extends LivingEntityRenderState {
    public boolean carrying;
    public boolean breakRoomSeated;
    public boolean breakRoomEating;
    public boolean breakRoomPlayingArcade;
    public final ItemStackRenderState breakRoomSnack = new ItemStackRenderState();
}
