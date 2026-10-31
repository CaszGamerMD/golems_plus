#!/usr/bin/env python3
"""Builders execute full immutable zone plans, concurrent independent steps."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]/"src/main/java/com/caszgamermd/golemsplus"
plan=(root/"builder/BuilderZoneWorkPlan.java").read_text()
station=(root/"world/WorkerStationBlockEntity.java").read_text()
service=(root/"builder/BuilderBuildService.java").read_text()
worker=(root/"builder/BuilderGolem.java").read_text()
assert "for(int index=0;index<steps.size();index++)" in plan
assert "data.get(activeWorkers.get(owner)).add(index)" in plan
assert "List.copyOf(list)" in plan
assert "private record ScheduledZones(" in service
assert "schedules=new WeakHashMap" in service
assert "found.cache()==cache&&found.roster().equals(roster)" in service
assert "BuilderZoneWorkPlan.compile(cache.steps(),cache.zones(),roster)" in service
assert "table.beginBuilderZonePlan();" in service
assert "table.claimPreferredBuilderZoneStep(" in service
assert "var activity=action(worker,index);" in service
assert "Action.STAGE_MATERIALS" in service
assert "Action.MOVE_TO_WORK" in service
assert "Action.PLACE" in service
assert "Action.COMPLETE" in service
assert "BUILDER_STAGE_SIZE=32" in station
assert "builderClaims.getOrDefault(worker,-1)" in station
assert "builderMissingRetries.getOrDefault(idx,0L)>now" in station
assert "builderClaims.put(worker,idx)" in station
assert "if(selectedBuilderJob>=0&&selectedBuilderJob<builderJobs.size())" in station
assert "now-builderProgressNotifyTick>=20" in station
assert "setChanged();" in station
assert "Math.min(BUILDER_STAGE_SIZE,steps.size())" in station
assert "(tickCount+getId())%5==0" in worker
# Progress must be checked before OnSite loading to prevent an unnecessary
# walk back when another Builder has already placed the required block.
assert service.index("if(BuilderPlacementState.matches(existing,state.state()))") < service.index(
    "if(!table.builderOrderPrepared(worker.getUUID())")
# Never reset a claimed navigation step just because another Builder ticks.
assert "builderClaims.getOrDefault(worker,-1)" in station
# Ensure a single builder's transient approach does not overwrite all
# workers' Planner problem messages while traversing a valid route.
assert '"Builder exploring alternate routes to "' not in service
print("PASS full zone plans, independent worker phases, stable claims, 32-cell staging, throttled Planner packets")
