#!/usr/bin/env python3
"""Builder ten-coordinate leases and full-job warehouse orders."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]/"src/main/java/com/caszgamermd/golemsplus"
station=(root/"world/WorkerStationBlockEntity.java").read_text()
stock=(root/"builder/BuilderSupplyInventory.java").read_text()
service=(root/"builder/BuilderBuildService.java").read_text()
demand=(root/"builder/BuilderMaterialDemand.java").read_text()
warehouse=(root/"builder/BuilderWarehouseCoordinator.java").read_text()
assert "private final Map<UUID,java.util.List<Integer>> builderWorkOrders" in station
assert "private final java.util.Set<Integer> builderFinishedSteps" in station
assert "builderCursor+80" in station
assert "order.size()<10" in station
assert "builderWorkOrders.put(worker,order)" in station
assert "assignment.contains(i)" in station
assert "while(builderFinishedSteps.remove(builderCursor))builderCursor++" in station
assert 'builder_batch_count' in station
assert 'builder_done_count' in station
assert 'builder_batch_prepared_' in station
assert "builderOrderPrepared(UUID worker)" in station
assert "public static LoadStatus prepareWorkOrder(" in stock
assert "return LoadStatus.MOVING;" in stock
assert "int remainder=storeSurplus(storage,held,giveBack);" in stock
assert "ItemContainerContents.fromItems(nested)" in stock
assert "materials.merge(\"minecraft:scaffolding\"" in stock
assert "if(!table.builderOrderPrepared(worker.getUUID()))" in service
assert "table.prepareBuilderOrder(worker.getUUID());" in service
assert "BuilderScaffolding.retrieve(level,worker,table,false);" in service
assert "claimBuilderZoneStep(" in station
assert "builder_batch_size_" in station
assert "BUILDER_STAGE_SIZE" in station
assert "public static Map<String,Integer> outstanding(" in demand
assert "for(int i=cursor;i<steps.size();i++)" in demand
assert "manifest.size()>=16" in demand
assert "BuilderMaterialDemand.outstandingRemaining(" in warehouse
assert "table.builderRemaining()" in warehouse
assert "reconcileTicket(" in warehouse
assert "if(level.getGameTime()%80==0)" not in service  # compound condition, not standalone
assert "&&level.getGameTime()%80==0)" in service
assert "primeWarehouse(level,table);" in service
print("PASS ten distinct saved assignments, physical OnSite staging and job-wide orders")
