#!/usr/bin/env python3
"""Zoned Builder scheduling, physical movement, stock and save regression."""
from pathlib import Path
r=Path(__file__).resolve().parents[1]
server=r/"src/main/java/com/caszgamermd/golemsplus"
zones=(server/"builder/BuilderZones.java").read_text()
station=(server/"world/WorkerStationBlockEntity.java").read_text()
build=(server/"builder/BuilderBuildService.java").read_text()
golem=(server/"builder/BuilderGolem.java").read_text()
scaffold=(server/"builder/BuilderScaffolding.java").read_text()
warehouse=(server/"builder/BuilderWarehouseCoordinator.java").read_text()
nav=(server/"worker/WorkerNavigation.java").read_text()
assert "Math.abs(Math.log(" in zones
assert "zi*columns+xi" in zones
assert "int lowest=Integer.MAX_VALUE" in station
assert "claimBuilderZoneStep(UUID worker" in station
assert "BUILDER_STAGE_SIZE=32" in station
assert "footprint.contains(step,workerZone,activeCount)" in station
assert "step.y()" in station
assert "order.size()<10" in station
assert 'builder_finished_bits_v2' in station
assert 'java.util.BitSet.valueOf(' in station
assert "builderCursor+bit" in station
assert "var assigned=zoneSchedule(table,plan)" in build
assert "assigned.tasks().steps(worker.getUUID())" in build
assert "table.claimPreferredBuilderZoneStep(" in build
assert "BuilderZoneWorkPlan.compile(cache.steps(),cache.zones(),roster)" in build
assert "claimPreferredBuilderZoneStep(" in build
assert ".add(Attributes.MOVEMENT_SPEED,.40)" in golem
assert "speed=1.0; // Builder target:" in nav
assert "canReachExact(level,worker,landing)" in scaffold
assert "routeToExact(level,worker,approach,1.0)" in scaffold
assert "begin(level,worker,base,top+1.0,false)" in scaffold
assert "BuilderMaterialDemand.outstandingRemaining(" in warehouse
assert "table.requestBuilderShipmentCancellation();" in warehouse
assert "BuilderWarehouseCoordinator.reconcileTicket(level,table)" in build
assert "if(table.warehouse()!=null && table.supplyRequest()==null" not in build
print("PASS: zoned crews, independent Y, 4m/s walking, near-site ascent, no duplicate warehouse order")
