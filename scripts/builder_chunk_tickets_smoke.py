#!/usr/bin/env python3
"""Guard Builder cargo persistence and active-job-only chunk ticket lifecycle."""
from pathlib import Path
root = Path(__file__).resolve().parents[1]
main = root / "src/main/java/com/caszgamermd/golemsplus"
ticket = (main / "builder/BuilderChunkTickets.java").read_text()
builder = (main / "builder/BuilderBuildService.java").read_text()
worker = (main / "builder/BuilderGolem.java").read_text()
planner = (main / "world/WorkerStationBlockEntity.java").read_text()
nav = (main / "worker/WorkerNavigation.java").read_text()
entry = (main / "GolemsPlus.java").read_text()

assert "TicketType.FLAG_LOADING" in ticket
assert "TicketType.FLAG_SIMULATION" in ticket
assert "TicketType.FLAG_KEEP_DIMENSION_ACTIVE" in ticket
assert "TicketType.FLAG_PERSIST" not in ticket.replace("NOT FLAG_PERSIST", "")
assert "addTicketWithRadius(BUILDER" in ticket
assert "removeTicketWithRadius(BUILDER" in ticket
assert "table.phase()!=WorkerPhase.PAUSED" in ticket
assert "table.activeOrder()!=null" in ticket
assert "release(level,table.getBlockPos())" in ticket
assert "lease.held.clear()" in ticket
assert "counts.put(center,owners+1)" in ticket
assert "relinquish(level,key)" in ticket
assert "if(owners==1)" in ticket
assert "counts.put(center,owners-1)" in ticket
assert "table.builderLastPosition(id)" in ticket
assert "table.rememberBuilderPosition(id,previous)" in ticket
assert "lease.destinations.get(id)" in ticket
assert "add(required,ahead(previous,target),home)" in ticket
assert "BuilderChunkTickets.tick(level,table)" in builder
assert "BuilderChunkTickets.destination(level,worker,pos);" in builder
assert "BuilderChunkTickets.destination(world,worker,pos);" in (main / "builder/BuilderSiteClearance.java").read_text()
assert "BuilderChunkTickets.track(server,this)" in worker
assert "BuilderChunkTickets\n                    .destination(level,builder,target)" in nav
assert "BuilderChunkTickets.initialize()" in entry
assert "builder_last_position_" in planner
assert "builder_last_worker_" in planner
assert "BuilderChunkTickets\n                    .release(server,worldPosition)" in planner
assert "worker.dropCargo(level);\n            worker.discard()" not in builder
assert "setPersistenceRequired();" in worker
print("PASS: active Builder chunk tickets, rolling routes, saved crew locations and lossless worker recovery")
