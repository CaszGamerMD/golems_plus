package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;

/** One-use marker item cryptographically scoped by station position and active job UUID. */
public final class BuilderRallyFlagItem extends Item {
    public BuilderRallyFlagItem(Properties properties){super(properties);}

    public static ItemStack forJob(WorkerStationBlockEntity station){
        if(station==null||station.getLevel()==null||station.activeOrder()==null
                ||station.owner()==null)return ItemStack.EMPTY;
        CompoundTag data=new CompoundTag();
        BlockPos pos=station.getBlockPos();
        data.putInt("station_x",pos.getX());
        data.putInt("station_y",pos.getY());
        data.putInt("station_z",pos.getZ());
        data.putString("order",station.activeOrder().toString());
        data.putString("owner",station.owner().toString());
        data.putString("dimension",station.getLevel().dimension().identifier().toString());
        ItemStack stack=new ItemStack(WorksiteContent.BUILDER_RALLY_FLAG_ITEM);
        stack.set(DataComponents.CUSTOM_DATA,CustomData.of(data));
        return stack;
    }

    @Override public InteractionResult useOn(UseOnContext context){
        Player player=context.getPlayer();
        if(player==null)return InteractionResult.PASS;
        if(!(context.getLevel() instanceof ServerLevel level))
            return InteractionResult.SUCCESS;
        ItemStack held=context.getItemInHand();
        CustomData custom=held.get(DataComponents.CUSTOM_DATA);
        if(custom==null)return reject(player,"This rally flag is not linked to a Builder job.");
        CompoundTag data=custom.copyTag();
        BlockPos stationPos=new BlockPos(data.getIntOr("station_x",Integer.MIN_VALUE),
                data.getIntOr("station_y",Integer.MIN_VALUE),
                data.getIntOr("station_z",Integer.MIN_VALUE));
        if(stationPos.getX()==Integer.MIN_VALUE
                ||!level.dimension().identifier().toString().equals(
                        data.getStringOr("dimension","")))
            return reject(player,"This rally flag belongs to a different world or invalid station.");
        if(!level.hasChunkAt(stationPos)
                ||!(level.getBlockEntity(stationPos) instanceof WorkerStationBlockEntity station))
            return reject(player,"The Builder Planning Table for this flag is not loaded.");
        java.util.UUID order;
        java.util.UUID owner;
        try{
            order=java.util.UUID.fromString(data.getStringOr("order",""));
            owner=java.util.UUID.fromString(data.getStringOr("owner",""));
        }catch(IllegalArgumentException ex){
            return reject(player,"This rally flag has invalid job data.");
        }
        if(!station.isBuilder()||!owner.equals(player.getUUID())
                ||!station.canConfigure(player.getUUID())
                ||!owner.equals(station.owner())
                ||!order.equals(station.activeOrder()))
            return reject(player,"This rally flag is no longer linked to the active Builder job.");

        BlockPos target=context.getClickedPos().relative(context.getClickedFace());
        if(!level.hasChunkAt(target)||!level.mayInteract(player,target)
                ||(!level.getBlockState(target).canBeReplaced()
                    &&!level.getBlockState(target).is(WorksiteContent.BUILDER_RALLY_FLAG_BLOCK))
                ||!level.getFluidState(target).isEmpty())
            return reject(player,"Invalid rally location: choose an empty, dry floor tile.");

        var probe=WorksiteContent.BUILDER_GOLEM.create(level);
        if(probe==null)return reject(player,"Could not validate Builder clearance here.");
        boolean safe;
        try{
            safe=WorkerNavigation.canStandOn(level,probe,target);
        }finally{
            probe.discard();
        }
        if(!safe)return reject(player,
                "Invalid rally location: a Builder cannot safely stand on this tile. Try a full block or slab floor.");

        BlockPos previous=station.builderRallyPoint();
        if(!level.getBlockState(target).is(WorksiteContent.BUILDER_RALLY_FLAG_BLOCK)
                &&!level.setBlock(target,WorksiteContent.BUILDER_RALLY_FLAG_BLOCK
                        .defaultBlockState(),3))
            return reject(player,"Could not place the rally flag at that location.");
        if(!station.setBuilderRallyPoint(player.getUUID(),order,target)){
            if(level.getBlockState(target).is(WorksiteContent.BUILDER_RALLY_FLAG_BLOCK)
                    &&(previous==null||!previous.equals(target)))
                level.removeBlock(target,false);
            return reject(player,"The Builder job changed before the rally point could be saved.");
        }
        if(previous!=null&&!previous.equals(target)
                &&level.hasChunkAt(previous)
                &&level.getBlockState(previous).is(WorksiteContent.BUILDER_RALLY_FLAG_BLOCK))
            level.removeBlock(previous,false);
        if(!player.getAbilities().instabuild)held.shrink(1);
        player.sendSystemMessage(Component.literal("Builder rally flag placed for job "
                +station.placedSchematicName()+" at "+target.toShortString()+"."));
        return InteractionResult.SUCCESS;
    }

    private static InteractionResult reject(Player player,String message){
        player.displayClientMessage(Component.literal("[Builder] "+message),true);
        return InteractionResult.FAIL;
    }
}
