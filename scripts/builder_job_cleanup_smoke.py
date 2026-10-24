#!/usr/bin/env python3
"""A Planner coordinates Builders; only physical OnSite Storage owns supplies."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]
s=root/"src/main/java/com/caszgamermd/golemsplus"
ui=root/"src/client/java/com/caszgamermd/golemsplus"
entity=(s/"world/WorkerStationBlockEntity.java").read_text()
item=(s/"worker/WorkerGolemItem.java").read_text()
inventory=(s/"builder/BuilderSupplyInventory.java").read_text()
build=(s/"builder/BuilderBuildService.java").read_text()
returner=(s/"builder/BuilderReturnService.java").read_text()
bin=(s/"world/UnemployedCenterBlockEntity.java").read_text()
reg=(s/"world/WorksiteContent.java").read_text()
screen=(ui/"builder/BuilderPlannerScreen.java").read_text()
net=(s/"world/StationSettingsNetworking.java").read_text()
assert "Math.min(8,table.crewTarget()+1)" in screen
assert "profession==WorkerSpecialty.BUILDER?8:4" in item
assert "if(lastIndex>=8)break;" in entity
assert 'builder_extra_workers",Math.min(7,additionalBuilderWorkers.size())' in entity
assert 'builder_claim_count",Math.min(8,builderClaims.size())' in entity
assert "enum Sort { ALPHABETICAL, VOLUME, LEFT }" in screen
assert 'SORT: LEFT' in screen
assert "public static Container selectedStorage(" in inventory
assert "selected==null?java.util.List.of():java.util.List.of(selected)" in inventory
assert "Container destination=selectedStorage(table);" in inventory
assert "Container target=selectedStorage(table);" in inventory
assert "BuilderSupplyInventory.ready(table)" in net
assert "new UnemployedCenterBlock(" in reg
assert "ChestMenu.threeRows" in bin
assert "ContainerHelper.saveAllItems(out,items)" in bin
assert "builder_recall_requested" in entity
assert "table.retireBuilderWorker(worker.getUUID())" in returner
assert "if(!closest.acceptWorker(stored))" in returner
assert "worker.discard();" in returner
assert "BuilderReturnService.tick(level,worker,table)" in build
print("PASS: eight builders, onsight-only supplies, material sort and safe returned worker items")
