package com.caszgamermd.golemsplus.builder;

import java.util.List;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.resources.Identifier;

/** JEI should not draw its ingredient panel over the full-screen planner. */
public final class BuilderJeiPlugin implements IModPlugin{
    @Override public Identifier getPluginUid(){
        return Identifier.fromNamespaceAndPath("golems_plus","builder_screen");
    }
    @Override public void registerGuiHandlers(IGuiHandlerRegistration registration){
        registration.addGuiContainerHandler(BuilderPlannerScreen.class,
                new IGuiContainerHandler<BuilderPlannerScreen>(){
                    @Override public List<Rect2i> getGuiExtraAreas(BuilderPlannerScreen screen){
                        return List.of(screen.plannerBounds());
                    }
                });
    }
}
