package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.warehouse.BuilderWarehousePort;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.network.chat.Component;

/** Owner-driven supply trips; neither racks nor shulkers are teleported. */
public final class BuilderWarehouseCoordinator {
    private BuilderWarehouseCoordinator(){}
    /** Pre-order physical shulker deliveries against the next 256
     * construction steps. A UUID reservation prevents duplicate stock
     * allocations when multiple Builders are ticking the same job.
     * No player inventory or fabricated supplies are ever used.
     */
    public static boolean queueMaterials(ServerLevel level,
            WorkerStationBlockEntity table,List<BuilderConstructionPlan.Step> steps){
        if(!table.isBuilder()||table.activeOrder()==null
                ||table.warehouse()==null||table.supplyRequest()!=null
                ||table.builderCursor()>=steps.size())return false;
        var warehouse=table.warehouse();
        if(!level.dimension().identifier().toString().equals(warehouse.dimension())){
            table.setProblem("Builder warehouse must be in the same dimension");
            return false;
        }
        if(!BuilderWarehousePort.available()){
            table.setProblem("Install Caszual Additions with Builder warehouse support");
            return false;
        }
        Map<String,Integer> needed=BuilderMaterialDemand.batch(
                steps,table.builderCursor(),BuilderSupplyInventory.available(table));
        if(needed.isEmpty())return false;
        table.prepareSupplyRequest(UUID.randomUUID(),needed);
        BlockPos computer=new BlockPos(warehouse.x(),warehouse.y(),warehouse.z());
        var reply=BuilderWarehousePort.request(level,computer,table.getBlockPos(),
                table.owner(),table.supplyRequest(),needed);
        if(reply.accepted()&&!reply.ptp().equals(BlockPos.ZERO)){
            table.setSupplyPtp(reply.ptp());
            table.setProblem("Warehouse preparing "+needed.size()
                    +" material types; Builder courier will collect shulkers");
        }else{
            table.setProblem("Warehouse supply waiting: "+reply.state()
                    +(!level.hasChunkAt(computer)?" (load the Rack Computer chunk)":""));
        }
        return true;
    }

    /** Table-side inbound notification, including while no golem is near. */
    public static void pollTable(ServerLevel level,WorkerStationBlockEntity table){
        if(table.supplyRequest()==null||table.warehouse()==null
                ||table.supplyPtp().equals(BlockPos.ZERO)
                ||level.getGameTime()%60!=0)return;
        var w=table.warehouse();
        var report=BuilderWarehousePort.inspect(level,
                new BlockPos(w.x(),w.y(),w.z()),table.getBlockPos(),
                table.owner(),table.supplyRequest(),table.supplyPtp());
        String warning="";
        if(!report.missing().isEmpty())warning="Warehouse missing: "+report.missing();
        else if(report.state().equals("MISSING_EMPTY_SHULKERS"))
            warning="Warehouse needs empty shulker boxes at its Shulker Station";
        if(!warning.isEmpty()&&!warning.equals(table.supplyNotice())){
            table.setSupplyNotice(warning);
            table.setProblem(warning);
            ServerPlayer player=level.getServer().getPlayerList().getPlayer(table.owner());
            if(player!=null)
                player.sendSystemMessage(Component.literal("[Builder warehouse] "+warning));
        }else if(warning.isEmpty()&&!table.supplyNotice().isEmpty())
            table.setSupplyNotice("");
    }

    public static boolean tick(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,ServerPlayer player,
            List<BuilderConstructionPlan.Step> steps){
        if(worker.hasSupplyBox()){
            BlockPos destination=table.builderMaterialStorage()==null
                    ?table.getBlockPos():table.builderMaterialStorage();
            if(!level.hasChunkAt(destination)){
                table.setProblem("Material storage chunk must be loaded");
                return true;
            }
            if(worker.distanceToSqr(destination.getX()+.5,
                    destination.getY()+.5,destination.getZ()+.5)>10){
                if(!com.caszgamermd.golemsplus.worker.WorkerNavigation.routeTo(
                        level,worker,destination,.86))
                    table.setProblem("Builder courier cannot reach designated material storage");
            }else if(BuilderSupplyInventory.depositBox(table,worker.supplyBox())){
                worker.clearSupplyBox();
                table.setProblem("");
            }else table.setProblem("Materials storage is full or blocked: "
                    +destination.toShortString());
            return true;
        }
        UUID ticket=table.supplyRequest();
        var warehouse=table.warehouse();
        if(ticket==null)return false;
        // Only one courier may take packed shulkers from a reservation.
        // Every other Builder is free to continue building using supplies
        // already inside the Planning Table.
        if(!table.claimSupplyRunner(worker.getUUID()))return false;
        if(warehouse==null){
            table.setProblem("Linked warehouse removed while delivery pending");
            return true;
        }
        BlockPos computer=new BlockPos(warehouse.x(),warehouse.y(),warehouse.z());
        var reply=table.supplyPtp().equals(BlockPos.ZERO)
                ?BuilderWarehousePort.request(level,computer,table.getBlockPos(),
                        table.owner(),ticket,table.supplyManifest())
                :BuilderWarehousePort.inspect(level,computer,table.getBlockPos(),
                        table.owner(),ticket,table.supplyPtp());
        if(reply.accepted()&&!reply.ptp().equals(table.supplyPtp()))
            table.setSupplyPtp(reply.ptp());
        if(!reply.missing().isEmpty()){
            String missing="Warehouse missing: "+reply.missing();
            if(!missing.equals(table.supplyNotice())){
                table.setSupplyNotice(missing);
                player.sendSystemMessage(Component.literal("[Builder warehouse] "+missing));
            }
            table.setProblem(missing);
        }else if(reply.state().equals("MISSING_EMPTY_SHULKERS")){
            String message="Warehouse needs empty shulker boxes at its Shulker Station";
            if(!message.equals(table.supplyNotice())){
                table.setSupplyNotice(message);
                player.sendSystemMessage(Component.literal("[Builder warehouse] "+message));
            }
            table.setProblem(message);
        }else if(!reply.state().equals("READY_FOR_PICKUP")
                &&!reply.state().equals("DELIVERED")){
            table.setProblem("Warehouse: "+reply.state());
        }
        if(reply.boxes()>0&&reply.accepted()){
            BlockPos destination=reply.ptp();
            if(!BuilderSupplyInventory.hasRoomForBox(table)){
                table.setProblem("Free an input slot in the Builder Table for supplies");
                return true;
            }
            if(!level.hasChunkAt(destination)){
                table.setProblem("Supply PTP chunk unloaded; approach warehouse");
                return true;
            }
            if(worker.distanceToSqr(destination.getX()+.5,
                    destination.getY()+1,destination.getZ()+.5)>12){
                if(!com.caszgamermd.golemsplus.worker.WorkerNavigation.routeTo(
                        level,worker,destination,.9))
                    table.setProblem("Builder cannot reach warehouse output PTP; clear a walkable route");
                return true;
            }
            ItemStack boxed=BuilderWarehousePort.takeBox(level,computer,
                    table.getBlockPos(),table.owner(),ticket,destination);
            if(!boxed.isEmpty()){
                worker.holdSupplyBox(boxed);
                table.setProblem("Picking up warehouse supply shulker");
            }
            return true;
        }
        if(reply.state().equals("DELIVERED")&&reply.accepted()
                &&BuilderWarehousePort.release(level,computer,
                        table.getBlockPos(),table.owner(),ticket,reply.ptp())){
            table.clearSupplyRequest();
            table.setProblem("");
            return false;
        }
        if(!reply.accepted()&&reply.state().equals("INTEGRATION_ERROR"))
            table.setProblem("Install current Caszual Additions with Builder warehouse support");
        else if(!reply.accepted())
            table.setProblem("Warehouse: "+reply.state());
        return true;
    }
    /** Scaffold ladders are real construction supplies, never free world blocks. */
    public static boolean requestScaffolding(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,ServerPlayer player,
            List<BuilderConstructionPlan.Step> steps){
        if(table.warehouse()==null){
            table.setProblem("Place minecraft:scaffolding in the Planner to climb higher");
            return false;
        }
        if(table.supplyRequest()==null){
            if(!BuilderWarehousePort.available()){
                table.setProblem("Warehouse adapter unavailable for scaffolding");
                return true;
            }
            int existing=BuilderSupplyInventory.available(table)
                    .getOrDefault("minecraft:scaffolding",0);
            int amount=Math.max(1,48-existing);
            table.prepareSupplyRequest(UUID.randomUUID(),
                    Map.of("minecraft:scaffolding",amount));
        }
        return tick(level,worker,table,player,steps);
    }
    public static boolean request(ServerLevel level,BuilderGolem worker,
            WorkerStationBlockEntity table,ServerPlayer player,
            List<BuilderConstructionPlan.Step> steps){
        if(table.warehouse()==null)return false;
        String currentDimension=level.dimension().identifier().toString();
        if(!currentDimension.equals(table.warehouse().dimension())){
            table.setProblem("Warehouse is in another dimension");
            return true;
        }
        if(!BuilderWarehousePort.available()){
            table.setProblem("Caszual Additions warehouse integration unavailable");
            return true;
        }
        if(table.supplyRequest()==null)
            queueMaterials(level,table,steps);
        return table.supplyRequest()!=null
                &&tick(level,worker,table,player,steps);
    }
}
