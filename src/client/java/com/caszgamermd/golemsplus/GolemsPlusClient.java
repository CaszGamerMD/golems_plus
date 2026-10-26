package com.caszgamermd.golemsplus;

import com.caszgamermd.golemsplus.world.WorkerStationScreen;
import com.caszgamermd.golemsplus.builder.BuilderPlannerScreen;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.ModelLayerRegistry;
import com.caszgamermd.golemsplus.beekeeper.BeeModel;
import com.caszgamermd.golemsplus.beekeeper.BeeRenderer;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.renderer.entity.EntityRenderers;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import com.caszgamermd.golemsplus.cooking.MealCounterRenderer;
import com.caszgamermd.golemsplus.render.ProfessionGolemModel;
import com.caszgamermd.golemsplus.render.ProfessionGolemRenderer;

public final class GolemsPlusClient implements ClientModInitializer {
    @Override public void onInitializeClient() {
        EntityRenderers.register(com.caszgamermd.golemsplus.crane.CraneContent.CRANE,
                com.caszgamermd.golemsplus.crane.CraneRenderer::new);
        MenuScreens.<com.caszgamermd.golemsplus.world.WorkerStationMenu,WorkerStationScreen>register(
                WorksiteContent.STATION_MENU,(menu,inventory,title)->
                menu.isBuilderTable()?new BuilderPlannerScreen(menu,inventory,title):
                        new WorkerStationScreen(menu,inventory,title));
        BlockEntityRenderers.register(WorksiteContent.MEAL_COUNTER_ENTITY,MealCounterRenderer::new);
        ModelLayerRegistry.registerModelLayer(
                ProfessionGolemRenderer.BUILDER_LAYER,ProfessionGolemModel::builderLayer);
        ModelLayerRegistry.registerModelLayer(
                ProfessionGolemRenderer.TRADER_LAYER,ProfessionGolemModel::traderLayer);
        ModelLayerRegistry.registerModelLayer(
                ProfessionGolemRenderer.CHEF_LAYER,ProfessionGolemModel::chefLayer);
        ModelLayerRegistry.registerModelLayer(
                ProfessionGolemRenderer.FISHER_LAYER,ProfessionGolemModel::fisherLayer);
        EntityRenderers.register(WorksiteContent.BUILDER_GOLEM,
                ctx->new ProfessionGolemRenderer<>(ctx,
                        ProfessionGolemRenderer.BUILDER_LAYER,
                        ProfessionGolemModel.Profession.BUILDER));
        EntityRenderers.register(WorksiteContent.TRADER_GOLEM,
                ctx->new ProfessionGolemRenderer<>(ctx,
                        ProfessionGolemRenderer.TRADER_LAYER,
                        ProfessionGolemModel.Profession.TRADER));
        EntityRenderers.register(WorksiteContent.CHEF_GOLEM,
                ctx->new ProfessionGolemRenderer<>(ctx,
                        ProfessionGolemRenderer.CHEF_LAYER,
                        ProfessionGolemModel.Profession.CHEF));
        EntityRenderers.register(WorksiteContent.FISHER_GOLEM,
                ctx->new ProfessionGolemRenderer<>(ctx,
                        ProfessionGolemRenderer.FISHER_LAYER,
                        ProfessionGolemModel.Profession.FISHER));
        ModelLayerRegistry.registerModelLayer(BeeRenderer.LAYER,BeeModel::layer);
        EntityRenderers.register(WorksiteContent.BEEKEEPER_GOLEM,BeeRenderer::new);
        com.caszgamermd.golemsplus.builder.LitematicaPlacementClient.register();
    }
}

