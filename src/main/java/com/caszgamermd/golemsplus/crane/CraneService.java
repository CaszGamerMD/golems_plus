package com.caszgamermd.golemsplus.crane;

import java.util.*;
import com.caszgamermd.golemsplus.builder.BuilderGolem;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.worker.WorkerNavigation;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.*;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.*;

/** Job-isolated dispatch and player setup. No global cross-job crane lock. */
public final class CraneService {
    private static final Map<ServerLevel,Map<UUID,BuildingCrane>> live=new WeakHashMap<>();
    private record Selection(ServerLevel world,UUID crane,BlockPos table,long expires){}
    private static final Map<UUID,Selection> selected=new HashMap<>();
    private static boolean walking;
    public static void track(ServerLevel level,BuildingCrane crane){
        var cranes=live.computeIfAbsent(level,l->new HashMap<>());
        cranes.values().removeIf(BuildingCrane::isRemoved);cranes.put(crane.getUUID(),crane);
    }
    private static List<BuildingCrane> cranes(ServerLevel level){return live.getOrDefault(level,Map.of()).values().stream().filter(c->!c.isRemoved()).toList();}
    public static List<BlockPos> simulationPositions(WorkerStationBlockEntity table){
        if(!(table.getLevel() instanceof ServerLevel level))return List.of();
        return cranes(level).stream().filter(c->c.serves(table))
                .flatMap(c->c.simulationPositions().stream()).distinct().limit(12).toList();
    }
    public static void tell(Player player,String text){player.sendSystemMessage(Component.literal("Crane: "+text));}
    /** A Planner connection is backed by the crane's persisted
     * exact job UUID, not proximity or the player's last selection.
     */
    public static BuildingCrane linked(WorkerStationBlockEntity table){
        if(!(table.getLevel() instanceof ServerLevel level))return null;
        return cranes(level).stream().filter(c->c.serves(table))
                .findFirst().orElse(null);
    }
    public static String connectionStatus(WorkerStationBlockEntity table){
        var crane=linked(table);
        if(crane==null)return "NOT LINKED";
        return crane.operator()==null?"LINKED / NEEDS OPERATOR":
                "LINKED / OPERATOR "+crane.operator().toString().substring(0,8);
    }
    public static void selectJob(ServerPlayer player,WorkerStationBlockEntity table){
        if(table.activeOrder()==null){tell(player,"Start/select the construction job before linking its crane");return;}
        selected.put(player.getUUID(),new Selection(player.level(),null,table.getBlockPos(),player.level().getGameTime()+1200));
        tell(player,"Now right-click the crane with the controller to link this job");
    }
    private static boolean autoAssignOperator(ServerLevel world,
            BuildingCrane crane,WorkerStationBlockEntity table){
        if(crane.operator()!=null)return true;
        // Prefer an already-registered idle worker with no scaffolding
        // or shulker cargo. Actual removal from the Builder roster is
        // performed atomically by BuildingCrane.assignOperator().
        for(UUID id:table.builderWorkerIds()){
            if(world.getEntity(id) instanceof BuilderGolem worker
                    &&!worker.isPassenger()&&!worker.hasSupplyBox()
                    &&table.scaffoldPositions(id).isEmpty()
                    &&crane.assignOperator(worker))return true;
        }
        return false;
    }
    public static void selectCrane(ServerPlayer player,BuildingCrane crane){
        Selection old=selected.get(player.getUUID());
        if(old!=null&&old.world()==player.level()&&old.expires()>=player.level().getGameTime()&&old.table()!=null
                &&player.level().getBlockEntity(old.table()) instanceof WorkerStationBlockEntity table){
            if(!crane.link(table)){tell(player,"Cannot link: job ownership or passenger check failed");return;}
            if(autoAssignOperator(player.level(),crane,table))
                tell(player,"Crane linked: one Builder permanently assigned as operator until the job ends or is cancelled.");
            else tell(player,"Crane linked, but no available Builder can operate it. Add a Builder with no cargo/scaffold or assign one using the controller.");
        }
        selected.put(player.getUUID(),new Selection(player.level(),crane.getUUID(),null,player.level().getGameTime()+1200));
        tell(player,crane.status()+". Right-click a builder with controller to assign operator; /buildingcrane for commands");
    }
    public static BuildingCrane selected(ServerPlayer player){
        Selection s=selected.get(player.getUUID());
        return s!=null&&s.world()==player.level()&&s.expires()>=player.level().getGameTime()
                &&s.crane()!=null&&player.level().getEntity(s.crane()) instanceof BuildingCrane c
                &&player.getUUID().equals(c.owner())?c:null;
    }
    public static boolean operatorClick(BuilderGolem worker,Player player,InteractionHand hand){
        if(!player.getItemInHand(hand).is(CraneContent.CONTROLLER))return false;
        if(player instanceof ServerPlayer server){BuildingCrane c=selected(server);
            tell(player,c!=null&&c.assignOperator(worker)?"Operator assigned":"Select a linked crane; operator must belong to the same planner");}
        return true;
    }
    public static InteractionResult place(ServerPlayer player,UseOnContext context){
        ServerLevel world=player.level();BlockPos anchor=context.getClickedPos();
        if(!world.getBlockState(anchor).is(CraneContent.RAIL))anchor=anchor.relative(context.getClickedFace());
        if(!world.mayInteract(player,anchor))return InteractionResult.FAIL;
        BuildingCrane crane=new BuildingCrane(CraneContent.CRANE,world);
        Vec3 a=CraneMovement.anchorPoint(world,anchor);
        for(Vec3 offset:List.of(new Vec3(3,0,0),new Vec3(-3,0,0),new Vec3(0,0,3),new Vec3(0,0,-3))){
            crane.place(player.getUUID(),anchor,a.add(offset));
            if(!CraneMovement.pose(world,crane,crane.position(),a))continue;
            if(!world.addFreshEntity(crane))return InteractionResult.FAIL;
            context.getItemInHand().shrink(1);track(world,crane);selectCrane(player,crane);return InteractionResult.SUCCESS;
        }
        tell(player,"Need clear space for chassis, boom and builder box beside the placement");return InteractionResult.FAIL;
    }
    public static boolean controls(BuilderGolem worker){
        if(!(worker.level() instanceof ServerLevel level))return false;
        return cranes(level).stream().anyMatch(c->c.ownsWorker(worker));
    }
    /** Inclusive boundary: a work elevation of minimumY+15 switches
     * from scaffold to crane, provided the move needs a vertical lift.
     * Descents from above the boundary also use the crane.
     */
    public static boolean shouldUseCrane(int lowestY,int workerY,int goalY){
        return Math.abs(goalY-workerY)>=2
                &&Math.max(goalY,workerY)>=lowestY+15;
    }
    public static boolean requestRide(ServerLevel level,BuilderGolem worker,BlockPos goal){
        if(walking||goal==null||Math.abs(goal.getY()-worker.getY())<2)return false;
        if(!(level.getBlockEntity(worker.home()) instanceof WorkerStationBlockEntity table)
                ||table.activeOrder()==null)return false;
        Integer bottom=com.caszgamermd.golemsplus.builder.BuilderBuildService
                .lowestBuildY(table);
        // Crane movement is for genuine tall construction elevations.
        // Below 15 blocks above the LOWEST planned block, Builders use
        // the improved walk/solid-step/scaffold controller, even if a
        // powered crane happens to be parked alongside them.
        if(bottom==null||!shouldUseCrane(bottom,worker.blockPosition().getY(),
                goal.getY()))return false;
        BuildingCrane crane=linked(table);
        return crane!=null&&crane.operator()!=null&&crane.request(worker,goal);
        return false;
    }
    static boolean walk(ServerLevel level,BuilderGolem worker,BlockPos at){
        walking=true;try{return WorkerNavigation.routeToExact(level,worker,at,1);}
        finally{walking=false;}
    }
    public static BuildingCrane supply(WorkerStationBlockEntity table){
        if(!(table.getLevel() instanceof ServerLevel level))return null;
        return cranes(level).stream().filter(c->c.serves(table)&&c.supplyMode()&&c.onsite()).findFirst().orElse(null);
    }
    public static boolean bringSupplies(WorkerStationBlockEntity table,BuilderGolem worker){
        BuildingCrane c=supply(table);if(c==null||c.operator()==null)return false;
        if(c.parked()&&worker.distanceToSqr(c)<12)return false;
        // Queue only one stop at a time. Other builders follow the newly parked OnSite location.
        if(c.parked()){
            BlockPos at=CraneMovement.landing((ServerLevel)table.getLevel(),c,worker,worker.blockPosition());
            if(at==null)return false;
            c.requestSupplyStop(at);
        }
        return true;
    }
    public static void initialize(){
        CommandRegistrationCallback.EVENT.register((dispatcher,registries,environment)->dispatcher.register(
            Commands.literal("buildingcrane")
                .executes(ctx->{tell(ctx.getSource().getPlayerOrException(),"Select with controller; commands: status, onsite, recall, supplies <page 1-8>");return 1;})
                .then(Commands.literal("status").executes(ctx->{var p=ctx.getSource().getPlayerOrException();var c=selected(p);tell(p,c==null?"No selected crane":c.status());return 1;}))
                .then(Commands.literal("recall").executes(ctx->{var c=selected(ctx.getSource().getPlayerOrException());if(c!=null)c.recall();return c==null?0:1;}))
                .then(Commands.literal("onsite").executes(ctx->{var p=ctx.getSource().getPlayerOrException();var c=selected(p);
                    if(c==null||!c.supplyMode()||!c.parked()){tell(p,"Select a parked supply crane");return 0;}
                    if(c.station()==null){tell(p,"Link a construction job first");return 0;}
                    for(BuildingCrane peer:cranes(p.level()))if(peer.serves(c.station()))peer.setOnsite(false);
                    c.setOnsite(true);tell(p,"Supply box is this job's moving OnSite storage");return 1;}))
                .then(Commands.literal("supplies").then(Commands.argument("page",com.mojang.brigadier.arguments.IntegerArgumentType.integer(1,8))
                    .executes(ctx->{var p=ctx.getSource().getPlayerOrException();var c=selected(p);if(c==null||!c.supplyMode()||!c.stillValid(p))return 0;
                        c.openPage(p,com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(ctx,"page")-1);return 1;})))));
    }
}
