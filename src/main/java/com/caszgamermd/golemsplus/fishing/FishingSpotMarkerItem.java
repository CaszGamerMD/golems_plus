package com.caszgamermd.golemsplus.fishing;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;

/** Shift-right-click a shoreline or water surface to bind a fisher cooler.
 * Stays in inventory so one flag tool can move fishing spots repeatedly.
 */
public final class FishingSpotMarkerItem extends Item {
    public FishingSpotMarkerItem(Properties props){super(props);}
    @Override public InteractionResult useOn(UseOnContext ctx){
        Player p=ctx.getPlayer();
        if(p==null||!p.isShiftKeyDown())return InteractionResult.PASS;
        BlockPos target=ctx.getClickedPos();
        if(!ctx.getLevel().getFluidState(target).is(Fluids.WATER))
            target=target.relative(ctx.getClickedFace());
        return mark(ctx.getLevel(),p,target);
    }
    @Override public InteractionResult use(Level world,Player player,InteractionHand hand){
        if(!player.isShiftKeyDown())return InteractionResult.PASS;
        BlockHitResult result=getPlayerPOVHitResult(world,player,ClipContext.Fluid.SOURCE_ONLY);
        return mark(world,player,result.getBlockPos());
    }
    private InteractionResult mark(Level world,Player player,BlockPos at){
        if(!(world instanceof ServerLevel level))return InteractionResult.SUCCESS;
        if(!level.hasChunkAt(at)||!level.mayInteract(player,at))return InteractionResult.FAIL;
        WorkerStationBlockEntity closest=null;double best=Double.MAX_VALUE;
        for(int dx=-64;dx<=64;dx++)for(int dy=-12;dy<=12;dy++)
            for(int dz=-64;dz<=64;dz++){
                BlockPos p=at.offset(dx,dy,dz);
                double ds=at.distSqr(p);
                if(ds>128*128||ds>=best||!level.hasChunkAt(p))continue;
                if(level.getBlockEntity(p) instanceof WorkerStationBlockEntity station
                        &&station.isFishing()&&station.canConfigure(player.getUUID())){
                    closest=station;best=ds;
                }
            }
        if(closest==null){
            player.sendSystemMessage(Component.literal("No claimed Fisher coolers found nearby"));
            return InteractionResult.FAIL;
        }
        boolean water=level.getFluidState(at).is(Fluids.WATER);
        if(water&&!level.getFluidState(at).isSource()){
            player.sendSystemMessage(Component.literal("Place buoy on a source-water surface"));
            return InteractionResult.FAIL;
        }
        if(!water&&!level.getBlockState(at).canBeReplaced()){
            player.sendSystemMessage(Component.literal("Flag position is occupied"));
            return InteractionResult.FAIL;
        }
        BlockPos old=closest.fishingSpot();
        if(!closest.setFishingSpot(player.getUUID(),at,water))return InteractionResult.FAIL;
        if(old!=null&&!old.equals(at)&&level.getBlockState(old).is(WorksiteContent.FISHING_SPOT))
            level.destroyBlock(old,false);
        level.setBlockAndUpdate(at,WorksiteContent.FISHING_SPOT.defaultBlockState()
                .setValue(FishingSpotBlock.WATERLOGGED,water));
        player.sendSystemMessage(Component.literal((water?"Fishing buoy":"Fishing flag")
                +" linked at "+at.toShortString()));
        return InteractionResult.SUCCESS;
    }
}
