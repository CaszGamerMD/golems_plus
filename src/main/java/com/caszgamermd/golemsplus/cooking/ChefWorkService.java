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
        if(level.getGameTime()%40!=0||!station.isCooking()
                ||station.owner()==null)return;
        if(station.chefWorkerId()!=null)return; // unloaded != lost
        BlockPos at=station.getBlockPos();
        ChefGolem chef=new ChefGolem(WorksiteContent.CHEF_GOLEM,level);
        chef.assign(at,station.owner());
        chef.setPos(at.getX()+.5,at.getY()+1,at.getZ()+.5);
        if(level.addFreshEntity(chef))
            station.setChefWorkerId(chef.getUUID());
    }
}
