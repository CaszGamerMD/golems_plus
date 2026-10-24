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
import java.util.Map;
import com.caszgamermd.golemsplus.warehouse.WarehouseAssignment;

/** File import is read-only and restricted to server's schematic folder. */
public final class BuilderCommands {
    private BuilderCommands(){}
    public static void register(){
        CommandRegistrationCallback.EVENT.register((dispatcher,registries,environment)->
            dispatcher.register(Commands.literal("golembuilder")
                .then(Commands.literal("inspect")
                    .then(Commands.argument("filename",StringArgumentType.word())
                        .executes(ctx->inspect(ctx.getSource().getPlayerOrException(),
                                StringArgumentType.getString(ctx,"filename")))))
                .then(Commands.literal("status").executes(ctx->status(ctx.getSource().getPlayerOrException())))
                .then(Commands.literal("preview").executes(ctx->preview(ctx.getSource().getPlayerOrException())))
                .then(Commands.literal("start").executes(ctx->start(ctx.getSource().getPlayerOrException())))
                .then(Commands.literal("pause").executes(ctx->pause(ctx.getSource().getPlayerOrException())))
                .then(Commands.literal("resume").executes(ctx->resume(ctx.getSource().getPlayerOrException())))
                .then(Commands.literal("cancel").executes(ctx->cancel(ctx.getSource().getPlayerOrException())))
                .then(Commands.literal("crew")
                    .then(Commands.argument("count",
                            com.mojang.brigadier.arguments.IntegerArgumentType.integer(1,4))
                        .executes(ctx->setCrew(ctx.getSource().getPlayerOrException(),
                                com.mojang.brigadier.arguments.IntegerArgumentType
                                        .getInteger(ctx,"count")))))
                .then(Commands.literal("warehouse").executes(ctx->warehouse(ctx.getSource().getPlayerOrException())))
                .then(Commands.literal("rotate")
                    .then(Commands.argument("degrees",com.mojang.brigadier.arguments.IntegerArgumentType.integer(0,270))
                        .executes(ctx->rotate(ctx.getSource().getPlayerOrException(),
                                com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx,"degrees")))))));
    }
    private static WorkerStationBlockEntity nearest(ServerPlayer player){
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
        return table;
    }
    private static int status(ServerPlayer player) {
        var table=nearest(player);
        if(table==null)return 0;
        String warehouse=table.warehouse()==null?"none":
                table.warehouse().dimension()+" "+table.warehouse().x()+","+
                table.warehouse().y()+","+table.warehouse().z();
        player.sendSystemMessage(Component.literal("Builder plan: "+
                (table.hasPlacedSchematic()?table.placedSchematicName():table.builderFile())+
                " | "+table.builderCursor()+"/"+table.builderBlocks()+" steps"+
                " | "+table.builderUnresolved()+" unresolved | origin "+table.builderOrigin()+
                " | rotation "+table.builderRotation()+" | warehouse "+warehouse+
                " | Crew "+table.builderWorkerIds().size()+"/"+table.crewTarget()+
                " | "+table.phase()+" | "+table.problem()));
        return 1;
    }
    private static int setCrew(ServerPlayer player,int count){
        var table=nearest(player);
        if(table==null||!table.setCrewTarget(player.getUUID(),count))return 0;
        player.sendSystemMessage(Component.literal("[Builder] Crew target set to "+
                count+" workers. Existing workers remain linked, including those carrying cargo."));
        return 1;
    }
    private static int preview(ServerPlayer player){
        return previewFromStation(player,nearest(player));
    }
    public static int previewFromStation(ServerPlayer player,WorkerStationBlockEntity table){
        if(table==null||!table.canConfigure(player.getUUID())
                ||!table.hasPlacedSchematic()){
            player.sendSystemMessage(Component.literal(
                    "First select a Litematica placement and run /golems_use_placement (client command)."));
            return 0;
        }
        try{
            Path folder=FabricLoader.getInstance().getConfigDir()
                    .resolve("golems_plus").resolve("schematics");
            var schematic=LitematicFileReader.read(folder.resolve(table.builderFile()));
            var origin=table.builderOrigin();
            var site=new BuildSite(origin.getX(),origin.getY(),origin.getZ(),
                    BuildSite.Rotation.values()[table.builderRotation()/90],
                    BuildSite.Mirror.values()[table.builderMirror()]);
            var plan=BuilderConstructionPlan.create(schematic,site);
            if(table.builderRequired().isEmpty()&&table.activeOrder()==null)
                table.setBuilderMaterialsForFile(table.builderFile(),plan.required());
            player.sendSystemMessage(Component.literal("[Builder preview] "+
                    plan.steps().size()+" blocks at "+origin+
                    "; unsupported types: "+plan.unsupported().size()));
            plan.required().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .limit(20).forEach(e->player.sendSystemMessage(Component.literal(
                            "  "+e.getValue()+" × "+e.getKey())));
            if(!plan.unsupported().isEmpty())
                player.sendSystemMessage(Component.literal(
                        "Unsupported states will block their individual steps: "+
                        plan.unsupported().stream().limit(8).toList()));
            else
                player.sendSystemMessage(Component.literal(table.warehouse()==null
                        ? "Ready. To auto-supply, link a Rack Computer by using a linked Warehouse Scanner on this Planning Table. Then press Start Build."
                        : "Ready. Warehouse linked. Press Start Build: one Builder will collect real shulker supplies from the warehouse output PTP."));
            return 1;
        }catch(Exception ex){
            player.sendSystemMessage(Component.literal("Preview failed: "+ex.getMessage()));
            return 0;
        }
    }
    private static int start(ServerPlayer player){
        var table=nearest(player);
        if(table==null||!table.startPlacedBuilder(player.getUUID())){
            player.sendSystemMessage(Component.literal(
                    "Cannot start: select an imported placement, linked warehouse and at least one registered Builder Golem."));
            return 0;
        }
        player.sendSystemMessage(Component.literal(
                "Builder started at imported Litematica position. Existing blocks will not be replaced."));
        player.sendSystemMessage(Component.literal("[Builder] "+
                BuilderBuildService.primeWarehouse((net.minecraft.server.level.ServerLevel)
                        player.level(),table)));
        return 1;
    }
    private static int pause(ServerPlayer player){
        var table=nearest(player);
        if(table==null||table.activeOrder()==null)return 0;
        table.setPhase(com.caszgamermd.golemsplus.worker.WorkerPhase.PAUSED);
        player.sendSystemMessage(Component.literal("Builder paused."));
        return 1;
    }
    private static int resume(ServerPlayer player){
        var table=nearest(player);
        if(table==null||table.activeOrder()==null)return 0;
        table.setPhase(com.caszgamermd.golemsplus.worker.WorkerPhase.WORKING);
        player.sendSystemMessage(Component.literal("Builder resumed from "+table.builderCursor()+"."));
        return 1;
    }
    private static int cancel(ServerPlayer player){
        var table=nearest(player);
        if(table==null||!table.cancelBuilder(player.getUUID()))return 0;
        player.sendSystemMessage(Component.literal(
                "Builder job stopped. Already placed blocks remain; stored schematic is unchanged."));
        return 1;
    }
    private static int rotate(ServerPlayer player,int degrees) {
        var table=nearest(player);
        if(table==null||!table.setBuilderRotation(player.getUUID(),degrees))return 0;
        player.sendSystemMessage(Component.literal("Builder orientation set to "+degrees));
        return 1;
    }
    private static int warehouse(ServerPlayer player) {
        var table=nearest(player);
        if(table==null)return 0;
        // Find an actual loaded Caszual rack computer close to the player;
        // this only saves a selection. Reservations still require auth.
        var center=player.blockPosition();
        for(int dx=-6;dx<=6;dx++)for(int dy=-4;dy<=4;dy++)
            for(int dz=-6;dz<=6;dz++){
                var pos=center.offset(dx,dy,dz);
                if(!player.level().hasChunkAt(pos))continue;
                String id=BuiltInRegistries.BLOCK.getKey(
                        player.level().getBlockState(pos).getBlock()).toString();
                if(!id.equals("caszual_additions:rack_computer"))continue;
                table.setWarehouse(player.getUUID(),new WarehouseAssignment(
                        player.level().dimension().identifier().toString(),
                        pos.getX(),pos.getY(),pos.getZ()));
                player.sendSystemMessage(Component.literal(
                        "Builder linked to warehouse computer at "+pos+
                        ". Materials are NOT reserved until warehouse integration is enabled."));
                return 1;
            }
        player.sendSystemMessage(Component.literal("No loaded Caszual rack computer within 6 blocks."));
        return 0;
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
            var rotations=BuildSite.Rotation.values();
            var site=new BuildSite(table.getBlockPos().getX(),table.getBlockPos().getY()+1,
                    table.getBlockPos().getZ(),rotations[table.builderRotation()/90],
                    BuildSite.Mirror.NONE);
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
            table.setBuilderFile(player.getUUID(),name,result.blockCount(),result.unsupported().size());
            player.sendSystemMessage(Component.literal("Schematic: "+schem.name()+
                    " | "+result.blockCount()+" blocks | "+result.materials().size()+
                    " item types | "+result.unsupported().size()+" unresolved states."));
            result.materials().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .limit(15).forEach(e->player.sendSystemMessage(Component.literal(
                        "  "+e.getValue()+" x "+e.getKey())));
            if(!result.unsupported().isEmpty())
                player.sendSystemMessage(Component.literal(
                        "Unresolved blocks will pause their step, not prevent the build from starting."));
            return 1;
        }catch(Exception e){
            player.sendSystemMessage(Component.literal("Cannot import: "+e.getMessage()));
            return 0;
        }
    }
}
