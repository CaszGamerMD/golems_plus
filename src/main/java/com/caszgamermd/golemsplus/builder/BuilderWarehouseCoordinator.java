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
        BlockPos computer=new BlockPos(warehouse.x(),warehouse.y(),warehouse.z());
        Map<String,Integer> onsite=BuilderSupplyInventory.available(table);
        Map<String,Integer> warehouseStock=BuilderWarehousePort.stockSnapshot(
                level,computer,table.builderRemaining());
        Map<String,Integer> needed=new java.util.LinkedHashMap<>(
                BuilderMaterialDemand.outstandingRemaining(
                        table.builderRemaining(),onsite,
                        warehouseStock==null?Map.of():warehouseStock));
        if(needed.isEmpty())return false;
        // Keep the ENTIRE remaining order at the Warehouse Computer,
        // including temporarily out-of-stock items. The old stockSnapshot
        // clamp reduced every zero-stock item to 0 and could result in no
        // order ever appearing in the warehouse UI. Actual pickers still
        // require real rack stock before reserving or removing items.
        // The warehouse accepts the request FIRST, persisting its UUID at
        // the computer. An unavailable pallet is NOT locked; this same
        // ticket will later claim a prepared OUTPUT PTP without duplication.
        UUID ticket=UUID.randomUUID();
        var reply=BuilderWarehousePort.request(level,computer,table.getBlockPos(),
                table.owner(),ticket,needed);
        if(reply.accepted()){
            table.prepareSupplyRequest(ticket,needed);
            if(!reply.ptp().equals(BlockPos.ZERO)){
                table.setSupplyPtp(reply.ptp());
                table.setProblem("Warehouse output reserved at "
                        +reply.ptp().toShortString()
                        +"; Builder courier departing to collect real shulkers");
            }else table.setProblem("Builder request visible at Rack Computer; "
                    +"waiting for an empty OUTPUT PTP pallet and Shulker Station");
            return true;
        }
        table.setProblem("Warehouse request not reserved: "+reply.state()
                +(!level.hasChunkAt(computer)?" (Rack Computer chunk unloaded)":"")
                +"; will retry automatically");
        return false;
    }

    /** The Planner and courier must not disagree about staged stock.
     * Never delete a physical packed shulker or active picker reservation.
     * Safe warehouse cancellation will retry while cargo is in flight.
     */
    public static void reconcileTicket(ServerLevel level,
            WorkerStationBlockEntity table){
        if(table.supplyRequest()==null||table.builderCancelShipment())return;
        var staged=BuilderSupplyInventory.available(table);
        // Explicit scaffolding requests are not in the normal schematic
        // material ledger; don't cancel them on a false zero.
        if(table.supplyManifest().containsKey("minecraft:scaffolding")){
            if(table.supplyManifest().size()==1
                    &&staged.getOrDefault("minecraft:scaffolding",0)>=48)
                table.requestBuilderShipmentCancellation();
            return;
        }
        if(BuilderMaterialDemand.outstandingRemaining(
                table.builderRemaining(),staged,Map.of()).isEmpty())
            table.requestBuilderShipmentCancellation();
    }
    /** Table-side inbound notification, including while no golem is near. */
    /** Cancel an outstanding ticket only after the warehouse confirms
     * that no physical shipment, picker, or packing inventory can be lost.
     */
    public static boolean cancelShipment(ServerLevel level,
            WorkerStationBlockEntity table){
        if(table.supplyRequest()==null)return true;
        // Do not discard the reservation UUID while its courier holds a
        // real shulker in the saved entity. It must reach OnSite Storage
        // first so RE-CALCULATE counts those nested materials correctly.
        for(UUID id:table.builderWorkerIds())
            if(level.getEntity(id) instanceof BuilderGolem golem
                    &&golem.hasSupplyBox())return false;
        var warehouse=table.warehouse();
        if(warehouse==null||table.owner()==null
                ||!level.dimension().identifier().toString().equals(warehouse.dimension()))
            return false;
        BlockPos computer=new BlockPos(warehouse.x(),warehouse.y(),warehouse.z());
        if(!level.hasChunkAt(computer))return false;
        if(!BuilderWarehousePort.cancel(level,computer,table.getBlockPos(),
                table.owner(),table.supplyRequest(),table.supplyPtp()))
            return false;
        table.clearSupplyRequest();
        return true;
    }
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
        // A canceled construction never dispatches a NEW supply pickup.
        // A courier already holding an actual shulker was handled above
        // and must still physically return it to the on-site chest.
        if(table.builderCancelShipment())return false;
        UUID ticket=table.supplyRequest();
        var warehouse=table.warehouse();
        if(ticket==null)return false;
        // Dispatch the closest currently loaded Builder as courier, not
        // whichever worker happened to tick first.
        if(table.supplyRunner()==null){
            BlockPos pickup=table.supplyPtp().equals(BlockPos.ZERO)
                    ?new BlockPos(warehouse.x(),warehouse.y(),warehouse.z())
                    :table.supplyPtp();
            UUID nearestId=null;
            double nearest=Double.MAX_VALUE;
            for(UUID member:table.builderWorkerIds()){
                if(!(level.getEntity(member) instanceof BuilderGolem candidate))continue;
                double dist=candidate.distanceToSqr(
                        pickup.getX()+.5,pickup.getY()+1,pickup.getZ()+.5);
                if(dist<nearest){nearest=dist;nearestId=member;}
            }
            if(nearestId!=null&&!nearestId.equals(worker.getUUID()))return false;
        }
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
        if(reply.accepted()&&reply.ptp().equals(BlockPos.ZERO)){
            // This is a REAL order persisted at the Rack Computer, awaiting
            // a configured output pallet. Keep the Builder at the worksite
            // so it may clear/build with staged materials; do not claim its
            // UUID as the supply courier until an output is actually ready.
            table.setProblem("Warehouse order queued; configure OUTPUT PTP "
                    +"with pallet and Shulker Station to begin packing");
            return false;
        }
        if(!table.claimSupplyRunner(worker.getUUID()))return false;
        if(!reply.accepted()&&table.supplyPtp().equals(BlockPos.ZERO)){
            // Repair stuck reservations left by previous mod versions.
            // No PTP was ever reserved, so no physical shulker exists to
            // orphan. Release the invalid ticket and retry on a later tick.
            String failure=reply.state();
            table.clearSupplyRequest();
            table.setProblem("No warehouse output assigned ("+failure+
                    "); check output PTP / Shulker Station and retrying");
            return false;
        }
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
                table.setProblem("Free a slot in the selected on-site material storage for warehouse shulkers");
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
        // Leave for the warehouse as soon as the PTP is reserved.
        // Do not wait at the worksite until the shulker is already packed.
        if(reply.accepted()&&reply.boxes()==0
                &&!reply.state().equals("DELIVERED")
                &&!reply.ptp().equals(BlockPos.ZERO)){
            // A solo courier is also the only Builder. Until a REAL
            // packed box reaches the PTP, keep clearing/placing onsite
            // instead of marching to an empty PTP and deadlocking the job.
            // supplyRunner stays assigned, so ready boxes interrupt work.
            if(table.builderWorkerIds().size()==1)return false;
            BlockPos pickup=reply.ptp();
            // Once a real Builder has been assigned the delivery role,
            // do not send that worker back to construction simply because
            // another Builder can consume material already staged on-site.
            // Courier identity stays fixed until the warehouse confirms the
            // entire ticket delivered and the PTP has been released.
            if(level.hasChunkAt(pickup)
                    &&worker.distanceToSqr(pickup.getX()+.5,
                            pickup.getY()+1,pickup.getZ()+.5)>12
                    &&(level.getGameTime()%20==0||worker.getNavigation().isDone())){
                if(!com.caszgamermd.golemsplus.worker.WorkerNavigation
                        .routeTo(level,worker,pickup,.88))
                    table.setProblem("Courier cannot path to warehouse output "
                            +pickup.toShortString());
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
        // A solo Builder must still build with the physical material already
        // delivered or manually staged, even if a later warehouse request is
        // waiting on missing rack stock or empty shulkers.
        if(reply.boxes()==0 && (reply.state().equals("MISSING_STOCK")
                ||reply.state().equals("MISSING_EMPTY_SHULKERS")
                ||reply.state().equals("NO_EMPTY_OUTPUT_PTP")
                ||reply.state().equals("FULFILLING"))){
            int cursor=table.builderCursor();
            if(cursor>=0&&cursor<steps.size()){
                String next=steps.get(cursor).requiredItem();
                if(next!=null&&BuilderSupplyInventory.available(table)
                        .getOrDefault(next,0)>0)return false;
            }
        }
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
            // Scaffolding uses the same real reservation contract as blocks.
            if(level.getGameTime()%40!=0)return false;
            Map<String,Integer> requested=Map.of("minecraft:scaffolding",amount);
            var warehouse=table.warehouse();
            if(warehouse==null)return false;
            BlockPos computer=new BlockPos(warehouse.x(),warehouse.y(),warehouse.z());
            UUID ticket=UUID.randomUUID();
            var reply=BuilderWarehousePort.request(level,computer,table.getBlockPos(),
                    table.owner(),ticket,requested);
            if(!reply.accepted()){
                table.setProblem("Cannot queue warehouse scaffolding: "+reply.state());
                return false;
            }
            table.prepareSupplyRequest(ticket,requested);
            if(!reply.ptp().equals(BlockPos.ZERO))
                table.setSupplyPtp(reply.ptp());
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
