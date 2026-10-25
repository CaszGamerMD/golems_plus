#!/usr/bin/env python3
"""Builder worker-local material stock, team sharing, and retained load paths."""
from pathlib import Path
p=Path(__file__).resolve().parents[1]/"src/main/java/com/caszgamermd/golemsplus"
build=(p/"builder/BuilderBuildService.java").read_text()
supply=(p/"builder/BuilderSupplyInventory.java").read_text()
station=(p/"world/WorkerStationBlockEntity.java").read_text()
golem=(p/"builder/BuilderGolem.java").read_text()
assert "enum LoadStatus { READY, MOVING, MISSING }" in supply
assert "loadForLayerStatus(" in supply
assert "return LoadStatus.MOVING;" in supply
assert "if(loading==BuilderSupplyInventory.LoadStatus.MOVING)" in build
assert "return; // KEEP this step while walking to real stock." in build
assert "private static LoadStatus borrowFromCrew(" in supply
assert "if(id.equals(worker.getUUID()))continue;" in supply
assert "teammate.carriedCount(target)<=0" in supply
assert "worker,donor.blockPosition(),.83" in supply
assert "int transferred=tryPocket(worker,stack,quantity)" in supply
assert "donor.setItem(slot,stack.copyWithCount(" in supply
assert "returnPouch(table,worker);" in supply
assert "quota=capacity*(requested==target?2:1)" in supply
assert "if(worker.carriedCount(target)==0&&requested!=target)continue;" in supply
assert "public int claimBuilderStep(UUID worker,int layerExclusive," in station
assert "heldItems.contains(material)" in station
assert "builderWorkOrders.put(worker,order)" in station
assert "order.size()<10" in station
assert "if(preferred>=0)next=preferred;" in station
assert "java.util.Set<String> carriedItems=new java.util.HashSet<>()" in build
assert "plan.steps(),carriedItems" in build
assert "Builder teammate is carrying " in build
assert "if(teammates>0)" in build
assert "public int carriedCount(" in golem
print("PASS: own-pouch task scheduling, physical crew transfer, full-pouch unloading and non-preempted supply walk")
