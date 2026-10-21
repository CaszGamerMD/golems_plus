package com.caszgamermd.golemsplus;

import com.caszgamermd.golemsplus.world.WorkerStationScreen;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.entity.IronGolemRenderer;

public final class GolemsPlusClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        MenuScreens.register(WorksiteContent.STATION_MENU,WorkerStationScreen::new);
        EntityRenderers.register(WorksiteContent.TRADER_GOLEM,IronGolemRenderer::new);
        EntityRenderers.register(WorksiteContent.BUILDER_GOLEM,IronGolemRenderer::new);
        com.caszgamermd.golemsplus.builder.LitematicaPlacementClient.register();
    }
}
