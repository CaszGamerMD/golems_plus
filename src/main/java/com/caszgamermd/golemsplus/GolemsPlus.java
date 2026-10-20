package com.caszgamermd.golemsplus;

import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;

/** Bootstrap: stations are prototypes; worker AI is not enabled yet. */
public final class GolemsPlus implements ModInitializer {
    public static final String MOD_ID = "golems_plus";

    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override public void onInitialize() {
        WorksiteContent.initialize();
    }
}
