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
    // Marker remembers a preferred station during the play session, making
    // a long-distance mark fast. The waypoint itself is stored permanently
    // in the workstation block entity, not this session cache.
    private static final java.util.Map<java.util.UUID,BlockPos> bound=
            new java.util.HashMap<>();
    public FishingSpotMarkerItem(Properties props){super(props);}
    @Override public InteractionResult useOn(UseOnContext ctx){
        Player p=ctx.getPlayer();
        if(p==null)return InteractionResult.PASS;
        if(ctx.getLevel() instanceof ServerLevel level
                &&level.getBlockEntity(ctx.getClickedPos()) instanceof WorkerStationBlockEntity station
                &&station.isFishing()&&station.canConfigure(p.getUUID())){
            bound.put(p.getUUID(),ctx.getClickedPos().immutable());
            p.sendSystemMessage(Component.literal(
                    "Fishing marker linked to coolers "+ctx.getClickedPos().toShortString()+
                    ". Shift-use at water or shore to place flag/buoy."));
            return InteractionResult.SUCCESS;
        }
        if(!p.isShiftKeyDown())return InteractionResult.PASS;
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
        BlockPos preferred=bound.get(player.getUUID());
        if(preferred!=null&&level.hasChunkAt(preferred)
                &&level.getBlockEntity(preferred) instanceof WorkerStationBlockEntity station
                &&station.isFishing()&&station.canConfigure(player.getUUID())
                &&station.getBlockPos().distSqr(at)<=128L*128L)
            closest=station;
        // Legacy convenience: automatically discover a station near the
        // marker location if the player has not explicitly bound one.
        for(int dx=-48;closest==null&&dx<=48;dx++)
            for(int dy=-10;dy<=10;dy++)
            for(int dz=-48;dz<=48;dz++){
                BlockPos p=at.offset(dx,dy,dz);
                double ds=at.distSqr(p);
                if(ds>48*48||ds>=best||!level.hasChunkAt(p))continue;
                if(level.getBlockEntity(p) instanceof WorkerStationBlockEntity station
                        &&station.isFishing()&&station.canConfigure(player.getUUID())){
                    closest=station;best=ds;
                }
            }
        if(closest==null){
            player.sendSystemMessage(Component.literal(
                    "No claimed coolers near marker. Right-click your coolers with this marker first"));
            return InteractionResult.FAIL;
        }
        bound.put(player.getUUID(),closest.getBlockPos().immutable());
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
