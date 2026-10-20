package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.worker.WorkerSpecialty;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import java.nio.file.Files;
import java.nio.file.Path;

/** File import is read-only and restricted to server's schematic folder. */
public final class BuilderCommands {
    private BuilderCommands(){}
    public static void register(){
        CommandRegistrationCallback.EVENT.register((dispatcher,registries,environment)->
            dispatcher.register(Commands.literal("golembuilder")
                .then(Commands.literal("inspect")
                    .then(Commands.argument("filename",StringArgumentType.word())
                        .executes(ctx->inspect(ctx.getSource().getPlayerOrException(),
                                StringArgumentType.getString(ctx,"filename")))))));
    }
    private static int inspect(ServerPlayer player,String name){
        if(!name.matches("[a-zA-Z0-9_-]{1,80}\\.litematic")){
            player.sendSystemMessage(Component.literal("Use a simple .litematic filename."));
            return 0;
        }
        WorkerStationBlockEntity table=null;
        var center=player.blockPosition();
        for(int dx=-8;dx<=8&&table==null;dx++)
            for(int dy=-4;dy<=4&&table==null;dy++)
                for(int dz=-8;dz<=8&&table==null;dz++){
                    var p=center.offset(dx,dy,dz);
                    if(!player.level().hasChunkAt(p))continue;
                    BlockEntity be=player.level().getBlockEntity(p);
                    if(be instanceof WorkerStationBlockEntity station
                            &&station.getBlockState().getBlock()
                                    instanceof com.caszgamermd.golemsplus.world.WorkerStationBlock block
                            &&block.specialty()==WorkerSpecialty.BUILDER
                            &&station.canConfigure(player.getUUID()))
                        table=station;
                }
        if(table==null){
            player.sendSystemMessage(Component.literal("Claim a Builder Planning Table nearby first."));
            return 0;
        }
        Path folder=FabricLoader.getInstance().getConfigDir()
                .resolve("golems_plus").resolve("schematics");
        try{
            Files.createDirectories(folder);
            var schem=LitematicFileReader.read(folder.resolve(name));
            var site=new BuildSite(table.getBlockPos().getX(),table.getBlockPos().getY()+1,
                    table.getBlockPos().getZ(),BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
            var result=BuilderSchematicAnalysis.analyze(schem,site,id->{
                Identifier parsed=Identifier.tryParse(id);
                if(parsed==null||!BuiltInRegistries.BLOCK.containsKey(parsed))return null;
                var block=BuiltInRegistries.BLOCK.getValue(parsed);
                var item=block.asItem();
                // This is an ESTIMATE; plants, redstone, fluids, gravity and
                // multiblock items require placement-specific resolvers.
                if(item==Items.AIR || id.endsWith("_door")||id.endsWith("_bed")
                        ||id.endsWith("_crop")||id.contains("water")||id.contains("lava"))
                    return null;
                return BuiltInRegistries.ITEM.getKey(item).toString();
            });
            player.sendSystemMessage(Component.literal("Schematic: "+schem.name()+
                    " | "+result.blockCount()+" blocks | "+result.materials().size()+
                    " item types | "+result.unsupported().size()+" unresolved states."));
            result.materials().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .limit(15).forEach(e->player.sendSystemMessage(Component.literal(
                        "  "+e.getValue()+" x "+e.getKey())));
            if(!result.unsupported().isEmpty())
                player.sendSystemMessage(Component.literal(
                        "Construction locked: resolve unsupported blocks first."));
            return 1;
        }catch(Exception e){
            player.sendSystemMessage(Component.literal("Cannot import: "+e.getMessage()));
            return 0;
        }
    }
}
