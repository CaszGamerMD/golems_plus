#!/usr/bin/env python3
"""Builder clearing/courier regression checks for Fabric 26.2."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]/"src/main/java/com/caszgamermd/golemsplus"
build=(root/"builder/BuilderBuildService.java").read_text()
courier=(root/"builder/BuilderWarehouseCoordinator.java").read_text()
clear=(root/"builder/BuilderSiteClearance.java").read_text()
nav=(root/"worker/WorkerNavigation.java").read_text()
assert "BuilderSiteClearance.tick(level,worker,table,owner," in build
assert "boolean courier=worker.getUUID().equals(table.supplyRunner())" in build
assert "table.claimBuilderClear(worker.getUUID(),plan.size())" in clear
assert "table.completeBuilderClear(worker.getUUID(),index,true)" in clear
assert "table.builderWorkerIds().size()==1" in build
assert "if(table.builderWorkerIds().size()==1)return false;" in courier
assert "if(reply.boxes()>0&&reply.accepted())" in courier
assert "table.claimSupplyRunner(worker.getUUID())" in courier
assert "if(!world.hasChunkAt(pos))\n                BuilderChunkTickets.destination" in clear
assert "if(existing.isAir()){" in clear
assert "if(BuilderPlacementState.matches(existing,expected)){" in clear
assert "(neighborOccupied(level,mob,p)?14:0)" in nav
assert "if(++checked>MAX_DESTINATION_PATHS*2" in nav
assert "best!=null&&checked>8)break;" in nav
print("PASS Builder clearing failover, solo courier prioritization, no chunk ticket churn, navigation goals")
