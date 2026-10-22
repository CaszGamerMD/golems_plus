package com.caszgamermd.golemsplus.world;

import com.caszgamermd.golemsplus.GolemsPlus;
import com.caszgamermd.golemsplus.builder.BuilderCommands;
import com.caszgamermd.golemsplus.beekeeper.BeeToolboxBlockEntity;
import net.minecraft.world.Container;
import com.caszgamermd.golemsplus.trader.VillageSearchArea;
import com.caszgamermd.golemsplus.worker.WorkerPhase;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;

/**
 * All interactive job-block actions run on the server. A client cannot edit
 * another player's station, target a station it has not opened, or send
 * arbitrary commands. The old slash commands remain compatibility fallbacks.
 */
public final class StationSettingsNetworking {
    private StationSettingsNetworking() {}

    public record Change(BlockPos table, String action, String item,
                         int amount, int x, int y, int z)
            implements CustomPacketPayload {
        public static final Type<Change> TYPE = new Type<>(GolemsPlus.id("station_setting"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Change> CODEC =
                StreamCodec.of((buf,packet) -> {
                    buf.writeBlockPos(packet.table);
                    buf.writeUtf(packet.action,24);
                    buf.writeUtf(packet.item,120);
                    buf.writeVarInt(packet.amount);
                    buf.writeInt(packet.x);buf.writeInt(packet.y);buf.writeInt(packet.z);
                }, buf -> new Change(buf.readBlockPos(),buf.readUtf(24),
                        buf.readUtf(120),buf.readVarInt(),
                        buf.readInt(),buf.readInt(),buf.readInt()));
        @Override public Type<? extends CustomPacketPayload> type() {return TYPE;}
    }

    public static void initialize() {
        PayloadTypeRegistry.serverboundPlay().register(Change.TYPE,Change.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Change.TYPE,(payload,context) ->
                context.server().execute(() -> apply(context.player(),payload)));
    }
    private static void reply(ServerPlayer p, String message) {
        p.sendSystemMessage(Component.literal("[Job Block] " + message));
    }
    private static String validatedItem(String name) {
        Identifier id=Identifier.tryParse(name.trim());
        return id!=null && BuiltInRegistries.ITEM.containsKey(id)
                ?id.toString():null;
    }

    private static void apply(ServerPlayer player,Change change) {
        // Critical: only the *currently opened* workstation can be edited.
        if(!(player.containerMenu instanceof WorkerStationMenu menu)
                ||!menu.stationPos().equals(change.table)
                ||!player.level().hasChunkAt(change.table)
                ||!(player.level().getBlockEntity(change.table)
                    instanceof WorkerStationBlockEntity table)
                ||!table.canConfigure(player.getUUID())
                ||!table.stillValid(player))return;

        boolean done=false;
        String action=change.action;
        switch(action) {
            case "bee_tools","bee_output" -> {
                if(!table.isBeekeeper())break;
                boolean tools=action.equals("bee_tools");
                BlockPos closest=null;double best=Double.MAX_VALUE;
                BlockPos center=table.getBlockPos();
                for(int dx=-8;dx<=8;dx++)for(int dy=-5;dy<=5;dy++)
                    for(int dz=-8;dz<=8;dz++){
                        BlockPos pos=center.offset(dx,dy,dz);
                        if(!player.level().hasChunkAt(pos)||!player.level().mayInteract(player,pos))
                            continue;
                        var target=player.level().getBlockEntity(pos);
                        boolean good=tools?target instanceof BeeToolboxBlockEntity:
                                target instanceof Container
                                    &&!(target instanceof BeeToolboxBlockEntity)
                                    &&!(target instanceof WorkerStationBlockEntity);
                        if(!good)continue;
                        double distance=pos.distSqr(center);
                        if(distance<best){closest=pos;best=distance;}
                    }
                if(closest!=null){
                    done=table.setBeeLink(player.getUUID(),tools,closest);
                    if(done)reply(player,(tools?"Toolbox":"Output")+" linked at "+closest);
                    return;
                }
                reply(player,tools?"Place a Beekeeper Toolbox within 8 blocks":
                        "Place an output chest/barrel within 8 blocks");
                return;
            }
            case "bee_mode" -> {
                if(table.isBeekeeper())done=table.setBeeMode(player.getUUID(),change.amount);
            }
            case "bee_radius" -> {
                if(table.isBeekeeper())done=table.setBeeRadius(player.getUUID(),change.amount);
            }
            case "bee_pause" -> {
                if(table.isBeekeeper()){table.setPhase(WorkerPhase.PAUSED);done=true;}
            }
            case "bee_resume" -> {
                if(table.isBeekeeper()){table.setPhase(WorkerPhase.WORKING);done=true;}
            }
            case "crew" -> {
                done=table.setCrewTarget(player.getUUID(),change.amount);
                if(done)reply(player,"Assigned crew size: "+change.amount);
            }
            case "builder_preview" -> {
                if(table.isBuilder()){
                    BuilderCommands.previewFromStation(player,table);
                    return;
                }
            }
            case "builder_start" -> {
                if(table.isBuilder())done=table.startPlacedBuilder(player.getUUID());
            }
            case "builder_pause" -> {
                if(table.isBuilder()&&table.activeOrder()!=null){
                    table.setPhase(WorkerPhase.PAUSED);done=true;
                }
            }
            case "builder_resume" -> {
                if(table.isBuilder()&&table.activeOrder()!=null){
                    table.setPhase(WorkerPhase.WORKING);done=true;
                }
            }
            case "builder_cancel" -> {
                if(table.isBuilder())done=table.cancelBuilder(player.getUUID());
            }
            case "builder_rotate" -> {
                if(table.isBuilder())done=table.setBuilderRotation(player.getUUID(),
                        change.amount);
            }
            case "fish_pause" -> {
                if(table.isFishing()){table.setPhase(WorkerPhase.PAUSED);done=true;}
            }
            case "fish_resume" -> {
                if(table.isFishing()){table.setPhase(WorkerPhase.IDLE);done=true;}
            }
            case "trader_order" -> {
                if(table.isTrader()){
                    String item=validatedItem(change.item);
                    if(item!=null)done=table.setTradeOrder(player.getUUID(),
                            item,change.amount);
                }
            }
            case "trader_budget" -> {
                if(table.isTrader()){
                    String item=validatedItem(change.item);
                    if(item!=null)done=table.setBudget(player.getUUID(),
                            item,change.amount);
                }
            }
            case "trader_village" -> {
                if(table.isTrader()&&change.amount>=4&&change.amount<=128
                        &&change.table.distSqr(new BlockPos(change.x,change.y,change.z))
                                <= 512L*512L){
                    try {
                        done=table.setVillage(player.getUUID(),new VillageSearchArea(
                                player.level().dimension().identifier().toString(),
                                change.x,change.y,change.z,change.amount));
                    }catch(IllegalArgumentException ignored){}
                }
            }
            case "trader_cancel" -> {
                if(table.isTrader())done=table.cancelOrder(player.getUUID());
            }
            case "trader_pause" -> {
                if(table.isTrader()&&table.activeOrder()!=null){
                    table.setPhase(WorkerPhase.PAUSED);done=true;
                }
            }
            case "trader_resume" -> {
                if(table.isTrader()&&table.activeOrder()!=null){
                    table.setPhase(WorkerPhase.WORKING);done=true;
                }
            }
            default -> {return;}
        }
        if(!done)reply(player,"Setting not applied. Check item IDs, permissions, "+
                "order state, or required schematic.");
        else if(!action.equals("crew"))
            reply(player,"Updated "+action.replace('_',' ')+" at "+change.table);
    }
}
