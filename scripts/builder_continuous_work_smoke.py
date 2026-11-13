#!/usr/bin/env python3
"""Protect the low-allocation lookahead and next-tick same-layer work handoff."""
from pathlib import Path

build = Path("src/main/java/com/caszgamermd/golemsplus/builder/BuilderBuildService.java").read_text()
entity = Path("src/main/java/com/caszgamermd/golemsplus/builder/BuilderGolem.java").read_text()
assert "lookaheadEligible=List.of()" in build
assert "lookaheadEligibleLayer=Integer.MIN_VALUE" in build
assert "activity.lookaheadEligibleLayer!=layer" in build
assert "activity.lookaheadEligible=nearbyLookaheadCandidates(" in build
assert "List<Integer> eligible=activity.lookaheadEligible;" in build
assert "PriorityQueue<Integer> nearest" in build
assert "nearest.size()<32" in build
assert "return List.copyOf(result)" in build
assert "var choice=nearbyDirectFollowup(" in build
assert "if(choice==null&&activity.lookaheadFor==completed)choice=activity.lookahead;" in build
assert "boolean hovering=BuilderFlight.active(worker)&&!BuilderFlight.moving(worker);" in build
assert "BuilderFlight.safeWorkPose(level,worker,target,worker.position()," in build
assert "new BuilderConstructionPlanner.Choice(best,hovering?null:pose,false," in build
assert "BuilderConstructionPlanner.safeStand(" in build
assert "BuilderPlacementSpace.horizontalClear(target,worker)" in build
assert "table.claimPreferredBuilderZoneStep(worker.getUUID()," in build
assert "activity.fastFollowup=true;" in build
assert "action.fastFollowup=false;" in build
assert "BuilderBuildService.takeFastFollowup(this)" in entity
assert "((tickCount+getId())%5==0||followup)" in entity
assert "WorkerNavigation.routeToExact(" in build
assert "BuilderSupplyInventory.consume(worker,state.item())" in build
station = Path("src/main/java/com/caszgamermd/golemsplus/world/WorkerStationBlockEntity.java").read_text()
claim = station.split("public int claimPreferredBuilderZoneStep(",1)[1].split(
    "/** No route or anchor",1)[0]
assert "syncBuilderClaim();" in claim
assert "sync();" not in claim
assert "setChanged();" in claim
assert "now-builderProgressNotifyTick>=20" in claim
assert "level.sendBlockUpdated(worldPosition,state,state,3);" in claim
print("Builder bounded lookahead + safe direct handoff + next-tick followup OK")
