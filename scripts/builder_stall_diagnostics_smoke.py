#!/usr/bin/env python3
"""Builders should not launch warehouse scans from every worker tick."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]/"src/main/java/com/caszgamermd/golemsplus"
build=(root/"builder/BuilderBuildService.java").read_text()
golem=(root/"builder/BuilderGolem.java").read_text()
supplies=(root/"builder/BuilderSupplyInventory.java").read_text()
worker=build[build.index("public static void tick(ServerLevel level,BuilderGolem worker)"):
             build.index("public static String debugStatus(")]
dispatch=build[build.index("public static void dispatch("):
               build.index("public static void tick(ServerLevel level,BuilderGolem worker)")]
assert "BuilderWarehouseCoordinator.queueMaterials(level,table,plan.steps())" not in worker
assert "BuilderWarehouseCoordinator.reconcileTicket(level,table)" not in worker
assert "primeWarehouse(level,table);" in dispatch
assert "BuilderWarehouseCoordinator.reconcileTicket(level,table)" in dispatch
assert "Map.of(current.requiredItem()," in supplies
assert "BuilderMaterialDemand.batch(steps,index" not in supplies
assert "mobInteract(Player player," in golem
assert "BuilderBuildService.debugStatus(world,this)" in golem
assert "builderClearCursor()<plan.clearing().size()" in build
assert "builderClearClaim(worker.getUUID())" in build
assert "claim> =0" not in build
assert "BuilderZoneWorkPlan.compile(" in build
print("PASS per-builder scheduling without synchronized warehouse rescans and live status inspection")
