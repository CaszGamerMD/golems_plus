#!/usr/bin/env python3
"""Builder multi-worker clearing, future door states and warehouse full demand."""
from pathlib import Path
base=Path(__file__).resolve().parents[1]/"src/main/java/com/caszgamermd/golemsplus"
site=(base/"builder/BuilderSiteClearance.java").read_text()
build=(base/"builder/BuilderBuildService.java").read_text()
plan=(base/"builder/BuilderConstructionPlan.java").read_text()
resolver=(base/"builder/BuilderBlockResolver.java").read_text()
table=(base/"world/WorkerStationBlockEntity.java").read_text()
warehouse=(base/"builder/BuilderWarehouseCoordinator.java").read_text()
assert 'if(id.endsWith("_bed")' in resolver
assert 'id.endsWith("_door")' not in resolver
assert 'b.state().contains("half=upper")' in plan
assert "String item=resolved==null||upperDoor?null:" in plan
assert "if(step.requiredItem()!=null" in build
assert "boolean freeHalf=step.requiredItem()==null;" in build
assert "if(payment!=null)BuilderSupplyInventory.refund" in build
assert 'if(existing.isAir()){' in site
assert site.index('if(existing.isAir()){')<site.index('BuilderBlockResolver.resolve(step.state(),')
assert 'int index=table.claimBuilderClear(worker.getUUID(),plan.size());' in site
assert 'table.completeBuilderClear(worker.getUUID(),index,false)' in site
assert 'table.completeBuilderClear(worker.getUUID(),index,true)' in site
assert "boolean courier=worker.getUUID().equals(table.supplyRunner());" in build
assert 'if(!courier||table.builderWorkerIds().size()==1)' in build
assert 'for(UUID id:table.builderWorkerIds())' not in build[build.index("if(table.builderClearCursor()<plan.clearing().size()){"):build.index("// All workers finish the current bottommost layer")]
assert "builderClearClaims=new HashMap" in table
assert "builderClearVerified=new java.util.HashSet" in table
assert "builder_clear_verified_count" in table
assert "while(builderClearVerified.remove(builderClearCursor))" in table
assert 'BuilderMaterialDemand.outstanding(' in warehouse
assert 'warehouseStock==null?Map.of():warehouseStock' in warehouse
assert 'needed.replaceAll' not in warehouse[warehouse.index('public static boolean queueMaterials('):warehouse.index('public static boolean cancelShipment(')]
assert 'BuilderWarehousePort.request(level,computer,table.getBlockPos(),' in warehouse
print("PASS: 8 independent clearing claims, safe door halves, empty-first check, whole shortage order")
