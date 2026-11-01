package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.GolemsPlus;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Explicit client-to-server opt-in for selected Litematica placement. No
 * direct Litematica dependency is required on dedicated servers. Uploads
 * are bounded, sequential and owner-authenticated on every packet.
 */
public final class BuilderUploadNetworking {
    public static final int PART_SIZE=24*1024, MAX_BYTES=2*1024*1024;
    private static final Map<UUID,Incoming> pending=new HashMap<>();
    private BuilderUploadNetworking(){}
    public record Piece(BlockPos table,UUID token,int index,int total,
            int byteLength,BlockPos origin,int rotation,int mirror,
            String name,boolean worldSnapshot,byte[] bytes) implements CustomPacketPayload{
        public static final Type<Piece> TYPE=new Type<>(GolemsPlus.id("placed_schematic"));
        public static final StreamCodec<RegistryFriendlyByteBuf,Piece> CODEC=
                StreamCodec.of((buf,p)->{
                    buf.writeBlockPos(p.table());
                    buf.writeUUID(p.token());buf.writeVarInt(p.index());
                    buf.writeVarInt(p.total());buf.writeVarInt(p.byteLength());
                    buf.writeBlockPos(p.origin());buf.writeVarInt(p.rotation());
                    buf.writeVarInt(p.mirror());buf.writeUtf(p.name(),90);
                    buf.writeBoolean(p.worldSnapshot());
                    buf.writeByteArray(p.bytes());
                },buf->new Piece(buf.readBlockPos(),buf.readUUID(),buf.readVarInt(),
                        buf.readVarInt(),buf.readVarInt(),buf.readBlockPos(),
                        buf.readVarInt(),buf.readVarInt(),buf.readUtf(90),
                        buf.readBoolean(),buf.readByteArray(PART_SIZE)));
        @Override public Type<? extends CustomPacketPayload> type(){return TYPE;}
    }
    private static final class Incoming {
        final UUID session;
        final BlockPos table,origin;
        final int rotation,mirror,total,size;
        final String name;
        final boolean worldSnapshot;
        final ByteArrayOutputStream data=new ByteArrayOutputStream();
        final long started;
        int next;
        Incoming(Piece p,long time){
            session=p.token();table=p.table();origin=p.origin();
            rotation=p.rotation();mirror=p.mirror();
            total=p.total();size=p.byteLength();name=p.name();
            worldSnapshot=p.worldSnapshot();started=time;
        }
        boolean matches(Piece p){
            return session.equals(p.token())&&table.equals(p.table())
                    &&origin.equals(p.origin())&&rotation==p.rotation()
                    &&mirror==p.mirror()&&total==p.total()
                    &&size==p.byteLength()&&name.equals(p.name())
                    &&worldSnapshot==p.worldSnapshot();
        }
    }
    public static void initialize(){
        PayloadTypeRegistry.serverboundPlay().register(Piece.TYPE,Piece.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Piece.TYPE,(payload,ctx)->
                ctx.server().execute(()->accept(ctx.player(),payload)));
    }
    private static void notify(ServerPlayer player,String message){
        player.sendSystemMessage(Component.literal("[Builder] "+message));
    }
    private static void accept(ServerPlayer player,Piece p){
        UUID uuid=player.getUUID();
        if(!(player.level().getBlockEntity(p.table()) instanceof WorkerStationBlockEntity table)
                ||!table.canConfigure(uuid)||table.isTrader()
                ||p.table().distSqr(player.blockPosition())>144
                ||!player.getAbilities().mayBuild){
            pending.remove(uuid);return;
        }
        if(!p.worldSnapshot()
                ||p.byteLength()<1||p.byteLength()>MAX_BYTES||p.total()<1
                ||p.total()>(MAX_BYTES+PART_SIZE-1)/PART_SIZE
                ||p.index()<0||p.index()>=p.total()
                ||p.bytes().length<1||p.bytes().length>PART_SIZE
                ||p.rotation()<0||p.rotation()>3||p.mirror()<0||p.mirror()>2
                ||p.name().isBlank()||p.name().length()>90
                ||p.name().chars().anyMatch(c->Character.isISOControl(c))
                ||Math.abs((long)p.table().getX()-p.origin().getX())>256
                ||Math.abs((long)p.table().getZ()-p.origin().getZ())>256
                ||Math.abs((long)p.table().getY()-p.origin().getY())>128){
            pending.remove(uuid);notify(player,"Placement rejected (size, range or format).");return;
        }
        long time=player.level().getGameTime();
        Incoming current=pending.get(uuid);
        if(p.index()==0){
            current=new Incoming(p,time);pending.put(uuid,current);
        }
        if(current==null||time-current.started>1200||!current.matches(p)
                ||p.index()!=current.next
                ||current.data.size()+p.bytes().length>current.size){
            pending.remove(uuid);notify(player,"Out-of-order schematic upload.");return;
        }
        current.data.writeBytes(p.bytes());current.next++;
        if(current.next<current.total)return;
        pending.remove(uuid);
        if(current.data.size()!=current.size){
            notify(player,"Incomplete Litematica transfer.");return;
        }
        Path saved=null;
        try{
            Path directory=FabricLoader.getInstance().getConfigDir()
                    .resolve("golems_plus").resolve("schematics");
            Files.createDirectories(directory);
            saved=directory.resolve("placed-"+UUID.randomUUID()
                    +BuilderPlacementSnapshot.EXTENSION);
            Files.write(saved,current.data.toByteArray());
            // Coordinates and states come ONLY from the rendered ghost at
            // the instant the player clicked Import. Do not run BuildSite
            // rotation, mirror or signed-size transformation again.
            var schematic=BuilderPlacementSnapshot.read(saved);
            var site=BuilderPlacementSnapshot.worldSpaceSite();
            for(var block:schematic.blocks()){
                if(Math.abs((long)block.x()-p.origin().getX())>256
                        ||Math.abs((long)block.z()-p.origin().getZ())>256)
                    throw new java.io.IOException(
                            "Hologram outside 256-block Builder range");
            }
            var plan=BuilderConstructionPlan.create(schematic,site);
            // The uploaded snapshot is now immutable and remains server-side;
            // only explicit confirmation from the Planning Table can begin work.
            // This snapshot already contains Litematica's final rotated
            // block STATES, not just rotated coordinates. Store identity
            // rotation/mirror on the job as well: every runtime resolver
            // consults the station orientation when placing directional
            // blocks (pistons, signs, stairs), and must not rotate again.
            if(!table.setPlacedBuilderPlan(uuid,saved.getFileName().toString(),
                    p.name(),p.origin(),0,0,
                    plan.steps().size(),plan.unsupported().size())){
                Files.deleteIfExists(saved);
                notify(player,"The table could not accept this placement.");return;
            }
            table.setBuilderMaterialsForFile(saved.getFileName().toString(),plan.required());
            notify(player,"Captured EXACT placed hologram '"+p.name()+
                    "' at "+p.origin()+" ("+plan.steps().size()+
                    " blocks; "+plan.unsupported().size()+
                    " unsupported states). Coordinates are frozen.");
            int minY=plan.steps().stream().mapToInt(
                    BuilderConstructionPlan.Step::y).min().orElse(0);
            int maxY=plan.steps().stream().mapToInt(
                    BuilderConstructionPlan.Step::y).max().orElse(0);
            notify(player,"Exact imported world Y range: "+minY+".."+maxY+
                    ". Lowest non-air layer: Y="+minY+
                    ". Reimport after moving the hologram.");
            notify(player,"In the Planning Table, click Preview, then Start build to confirm.");
        }catch(Exception ex){
            if(saved!=null)try{Files.deleteIfExists(saved);}catch(Exception ignored){}
            notify(player,"Could not read selected placement: "+ex.getMessage());
        }
    }
}
