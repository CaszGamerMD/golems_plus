package com.caszgamermd.golemsplus.crane;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.InteractionResult;
import net.minecraft.server.level.ServerPlayer;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;

public final class CraneControlItem extends Item {
    private final boolean chassis;
    public CraneControlItem(boolean chassis,Properties p){super(p);this.chassis=chassis;}
    @Override public InteractionResult useOn(UseOnContext context){
        if(!(context.getPlayer() instanceof ServerPlayer player))return InteractionResult.SUCCESS;
        if(chassis)return CraneService.place(player,context);
        var world=player.level();
        if(world.getBlockEntity(context.getClickedPos()) instanceof WorkerStationBlockEntity table&&table.isBuilder()){
            if(!table.canConfigure(player.getUUID()))return InteractionResult.FAIL;
            CraneService.selectJob(player,table);return InteractionResult.SUCCESS;
        }
        BuildingCrane crane=CraneService.selected(player);
        if(crane==null){CraneService.tell(player,"Select a crane with the controller first");return InteractionResult.FAIL;}
        if(!crane.supplyMode()){CraneService.tell(player,"Manual parking is available with the supply box attached");return InteractionResult.SUCCESS;}
        crane.requestSupplyStop(context.getClickedPos().relative(context.getClickedFace()));
        return InteractionResult.SUCCESS;
    }
}
