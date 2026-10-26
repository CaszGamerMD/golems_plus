package com.caszgamermd.golemsplus;

import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;

public final class GolemsPlus implements ModInitializer {
    public static final String MOD_ID = "golems_plus";
    public static Identifier id(String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID,path);
    }
    @Override public void onInitialize() {
        com.caszgamermd.golemsplus.builder.BuilderChunkTickets.initialize();
        WorksiteContent.initialize();
        com.caszgamermd.golemsplus.crane.CraneContent.initialize();
        com.caszgamermd.golemsplus.world.StationSettingsNetworking.initialize();
        com.caszgamermd.golemsplus.world.OutputChestSelection.initialize();
        com.caszgamermd.golemsplus.builder.BuilderUploadNetworking.initialize();
        com.caszgamermd.golemsplus.trader.TraderCommands.register();
        com.caszgamermd.golemsplus.builder.BuilderCommands.register();
    }
}

