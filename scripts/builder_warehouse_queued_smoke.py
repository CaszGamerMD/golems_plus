#!/usr/bin/env python3
"""Builder never loses warehouse-owned order UUID or blocks clearance while waiting."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]/"src/main/java/com/caszgamermd/golemsplus"
port=(root/"warehouse/BuilderWarehousePort.java").read_text()
coordinator=(root/"builder/BuilderWarehouseCoordinator.java").read_text()
assert '"QUEUED_AT_WAREHOUSE".equals(state)' in port
assert "table.prepareSupplyRequest(ticket,needed);" in coordinator
assert 'if(reply.accepted()){\n            table.prepareSupplyRequest(ticket,needed);' in coordinator
assert "table.setSupplyPtp(reply.ptp());" in coordinator
assert "BuilderWarehousePort.request(level,computer,table.getBlockPos()," in coordinator
assert "if(reply.accepted()&&reply.ptp().equals(BlockPos.ZERO)){" in coordinator
assert "return false;" in coordinator[coordinator.index(
        "if(reply.accepted()&&reply.ptp().equals(BlockPos.ZERO)){"):
        coordinator.index("if(!table.claimSupplyRunner(worker.getUUID()))")]
assert coordinator.index("if(reply.accepted()&&reply.ptp().equals(BlockPos.ZERO)){") < coordinator.index(
        "if(!table.claimSupplyRunner(worker.getUUID()))")
assert "table.prepareSupplyRequest(ticket,requested);" in coordinator
print("PASS: Builder persists pending computer ticket and leaves worker available until OUTPUT assigned")
