package com.caszgamermd.golemsplus.trader;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class TraderCommands {
    private TraderCommands(){}
    private static WorkerStationBlockEntity nearest(ServerPlayer player){
        BlockPos center=player.blockPosition();
        WorkerStationBlockEntity chosen=null;double best=Double.MAX_VALUE;
        for(int dx=-8;dx<=8;dx++)for(int dy=-4;dy<=4;dy++)for(int dz=-8;dz<=8;dz++){
            BlockPos p=center.offset(dx,dy,dz);
            if(!player.level().hasChunkAt(p))continue;
            BlockEntity be=player.level().getBlockEntity(p);
            if(be instanceof WorkerStationBlockEntity depot && depot.isTrader()
                    &&depot.canConfigure(player.getUUID())){
                double dist=p.distSqr(center);
                if(dist<best){best=dist;chosen=depot;}
            }
        }
        return chosen;
    }
    private static void notice(ServerPlayer player,String message){
        player.sendSystemMessage(Component.literal("[Golems Plus] "+message));
    }
    public static void register(){
        CommandRegistrationCallback.EVENT.register((dispatcher,registries,environment)->{
            dispatcher.register(Commands.literal("golems")
                .then(Commands.literal("trader")
                    .then(Commands.literal("village")
                        .then(Commands.argument("radius",IntegerArgumentType.integer(4,128))
                            .executes(ctx->{
                                ServerPlayer player=ctx.getSource().getPlayerOrException();
                                var depot=nearest(player);
                                if(depot==null){notice(player,"No owned Trader Counter within 8 blocks.");return 0;}
                                int radius=IntegerArgumentType.getInteger(ctx,"radius");
                                BlockPos p=player.blockPosition();
                                depot.setVillage(player.getUUID(),new VillageSearchArea(
                                        player.level().dimension().identifier().toString(),
                                        p.getX(),p.getY(),p.getZ(),radius));
                                notice(player,"Village area linked at "+p+" radius "+radius);
                                return 1;
                            })))
                    .then(Commands.literal("order")
                        .then(Commands.argument("wanted",StringArgumentType.word())
                            .then(Commands.argument("count",IntegerArgumentType.integer(1,4096))
                                .executes(ctx->{
                                    ServerPlayer player=ctx.getSource().getPlayerOrException();
                                    var depot=nearest(player);
                                    if(depot==null){notice(player,"No owned Trader Counter nearby.");return 0;}
                                    String id=StringArgumentType.getString(ctx,"wanted");
                                    Identifier parsed=Identifier.tryParse(id);
                                    if(parsed==null||!BuiltInRegistries.ITEM.containsKey(parsed)){
                                        notice(player,"Unknown item: "+id);return 0;
                                    }
                                    if(!depot.setTradeOrder(player.getUUID(),id,
                                            IntegerArgumentType.getInteger(ctx,"count"))){
                                        notice(player,"Current order active: cancel first.");return 0;
                                    }
                                    notice(player,"Order set for "+depot.wantedCount()+" "+id+
                                            ". Authorize payment with /golems trader budget <item> <max>.");
                                    return 1;
                                }))))
                    .then(Commands.literal("budget")
                        .then(Commands.argument("payment",StringArgumentType.word())
                            .then(Commands.argument("maximum",IntegerArgumentType.integer(1,65536))
                                .executes(ctx->{
                                    ServerPlayer player=ctx.getSource().getPlayerOrException();
                                    var depot=nearest(player);
                                    if(depot==null){notice(player,"No owned Trader Counter nearby.");return 0;}
                                    String id=StringArgumentType.getString(ctx,"payment");
                                    Identifier parsed=Identifier.tryParse(id);
                                    if(parsed==null||!BuiltInRegistries.ITEM.containsKey(parsed)){
                                        notice(player,"Unknown payment item: "+id);return 0;
                                    }
                                    if(!depot.setBudget(player.getUUID(),id,
                                            IntegerArgumentType.getInteger(ctx,"maximum"))){
                                        notice(player,"Create an order first.");return 0;
                                    }
                                    notice(player,"Payment budget set for "+id);
                                    return 1;
                                }))))
                    .then(Commands.literal("crew")
                        .then(Commands.argument("count",IntegerArgumentType.integer(1,4))
                            .executes(ctx->{
                                ServerPlayer player=ctx.getSource().getPlayerOrException();
                                var counter=nearest(player);
                                if(counter==null||!counter.setCrewTarget(player.getUUID(),
                                        IntegerArgumentType.getInteger(ctx,"count")))return 0;
                                notice(player,"Trader crew target: "+counter.crewTarget()
                                        +" golems (linked "+counter.traderWorkerIds().size()+")");
                                return 1;
                            })))
                    .then(Commands.literal("cancel").executes(ctx->{
                        ServerPlayer player=ctx.getSource().getPlayerOrException();
                        var depot=nearest(player);
                        if(depot==null){notice(player,"No owned counter nearby.");return 0;}
                        depot.cancelOrder(player.getUUID());
                        notice(player,"Order canceled. Trader returns escrowed goods.");return 1;
                    }))
                    .then(Commands.literal("status").executes(ctx->{
                        ServerPlayer player=ctx.getSource().getPlayerOrException();
                        var depot=nearest(player);
                        if(depot==null){notice(player,"No owned counter nearby.");return 0;}
                        notice(player,"Trader: "+depot.phase()+" "+depot.deliveredCount()+"/"+
                                depot.wantedCount()+" "+depot.wantedItem()+" | crew "+
                                depot.traderWorkerIds().size()+"/"+depot.crewTarget()
                                +" | "+depot.problem());
                        return 1;
                    }))));
        });
    }
}
