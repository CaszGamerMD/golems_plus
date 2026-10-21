package com.caszgamermd.golemsplus.cooking;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

/**
 * Places one persistent chef near a claimed Cooking Station as a visual
 * placeholder. No ingredients consumed, recipes executed or output created.
 * A saved UUID prevents duplicate chefs when chunks unload.
 */
public final class ChefWorkService {
    private ChefWorkService(){}

    public static void tick(ServerLevel level,WorkerStationBlockEntity station){
        // Chefs are now created only by placing a crafted Chef Worker item.
        // Keep the method as a compatibility hook for future kitchen tasks.
    }
}
