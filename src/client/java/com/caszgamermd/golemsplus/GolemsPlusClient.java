package com.caszgamermd.golemsplus;

import com.caszgamermd.golemsplus.world.WorkerStationScreen;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import com.caszgamermd.golemsplus.beekeeper.BeeModel;
import com.caszgamermd.golemsplus.beekeeper.BeeRenderer;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.entity.IronGolemRenderer;

public final class GolemsPlusClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        MenuScreens.register(WorksiteContent.STATION_MENU,WorkerStationScreen::new);
        EntityRenderers.register(WorksiteContent.TRADER_GOLEM,IronGolemRenderer::new);
        EntityRenderers.register(WorksiteContent.BUILDER_GOLEM,IronGolemRenderer::new);
        EntityModelLayerRegistry.registerModelLayer(BeeRenderer.LAYER,BeeModel::layer);
        EntityRenderers.register(WorksiteContent.BEEKEEPER_GOLEM,BeeRenderer::new);
        com.caszgamermd.golemsplus.builder.LitematicaPlacementClient.register();
    }
}
