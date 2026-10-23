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
            case "bee_output" -> {
                if (!table.isBeekeeper()) return;
                OutputChestSelection.begin(player, table, false);
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
                if(!table.isBuilder())return;
                done=table.startPlacedBuilder(player.getUUID());
                if(done){
                    reply(player,"Construction started: "+table.builderBlocks()
                            +" schematic blocks; "+table.builderWorkerIds().size()
                            +" Builder Golems assigned");
                    reply(player,com.caszgamermd.golemsplus.builder.BuilderBuildService
                            .primeWarehouse((net.minecraft.server.level.ServerLevel)player.level(),
                                    table));
                }else reply(player,"Cannot start: choose/import a schematic, resolve unsupported blocks, "
                            +"and ensure no other build is active");
                return;
            }
            case "builder_supplies" -> {
                if(!table.isBuilder())return;
                if(table.activeOrder()==null){
                    reply(player,"Start a loaded schematic first, then the warehouse can fulfill materials");
                }else if(table.warehouse()==null){
                    reply(player,"Use a warehouse-linked Caszual Additions scanner on the Planning Table");
                }else if(table.supplyRequest()!=null){
                    reply(player,"Supply ticket "+table.supplyRequest()+
                            "; "+table.supplyManifest().size()+" requested item types"+
                            (table.supplyPtp().equals(BlockPos.ZERO)?
                            "; waiting for an output PTP":
                            "; output PTP "+table.supplyPtp().toShortString()));
                    if(!table.problem().isBlank())reply(player,table.problem());
                }else{
                    reply(player,com.caszgamermd.golemsplus.builder.BuilderBuildService
                            .primeWarehouse((net.minecraft.server.level.ServerLevel)player.level(),
                                    table));
                }
                return;
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
            case "chef_link" -> {
                if(table.isCooking()){
                    done=com.caszgamermd.golemsplus.cooking.ChefWorkService
                            .linkKitchen(player,table);
                    if(done)reply(player,"Kitchen linked at "+table.kitchenLink());
                    else reply(player,table.problem().isBlank()?
                            "No compatible kitchen found":table.problem());
                    return;
                }
            }
            case "chef_preview" -> {
                if(table.isCooking()){
                    String item=validatedItem(change.item);
                    if(item!=null&&change.amount>0&&change.amount<=16)
                        com.caszgamermd.golemsplus.cooking.ChefWorkService
                                .preview(player,table,item,change.amount);
                    return;
                }
            }
            case "chef_order" -> {
                if(table.isCooking()){
                    String item=validatedItem(change.item);
                    if(item!=null){
                        done=com.caszgamermd.golemsplus.cooking.ChefWorkService.order(
                                player,table,item,change.amount);
                    }
                    if(done)reply(player,"Order accepted: "+change.amount+" x "+change.item);
                    else reply(player,table.problem().isBlank()?"Unable to start kitchen order":table.problem());
                    return;
                }
            }
            case "chef_pause" -> {
                if(table.isCooking()){table.setPhase(WorkerPhase.PAUSED);done=true;}
            }
            case "chef_resume" -> {
                if(table.isCooking()){table.setPhase(WorkerPhase.IDLE);done=true;}
            }
            case "fish_output" -> {
                if (!table.isFishing()) return;
                OutputChestSelection.begin(player, table, true);
                return;
            }
            case "fish_spot" -> {
                if(!table.isFishing())return;
                BlockPos location=new BlockPos(change.x,change.y,change.z);
                if(table.getBlockPos().distSqr(location)>128L*128L
                        ||!player.level().hasChunkAt(location)
                        ||!player.level().mayInteract(player,location)){
                    reply(player,"Fishing spot must be in reach (128 blocks)");return;
                }
                boolean water=player.level().getFluidState(location)
                        .is(net.minecraft.world.level.material.Fluids.WATER);
                if(!water&&!player.level().getBlockState(location).canBeReplaced()){
                    reply(player,"Place the marker in free space or on water");return;
                }
                if(water&&!player.level().getFluidState(location).isSource()){
                    reply(player,"Use a source water surface for buoy");return;
                }
                BlockPos previous=table.fishingSpot();
                if(!table.setFishingSpot(player.getUUID(),location,water))return;
                if(previous!=null&&!previous.equals(location)
                        &&player.level().getBlockState(previous)
                            .is(WorksiteContent.FISHING_SPOT))
                    player.level().destroyBlock(previous,false);
                player.level().setBlockAndUpdate(location,
                        WorksiteContent.FISHING_SPOT.defaultBlockState().setValue(
                                com.caszgamermd.golemsplus.fishing.FishingSpotBlock.WATERLOGGED,water));
                reply(player,"Fishing spot marked: "+location.toShortString());
                return;
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
