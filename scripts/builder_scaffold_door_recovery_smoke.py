#!/usr/bin/env python3
"""Builders must leave scaffolds to restock; door queues must not deadlock."""
from pathlib import Path
r=Path(__file__).resolve().parents[1]/"src/main/java/com/caszgamermd/golemsplus"
nav=(r/"worker/WorkerNavigation.java").read_text()
traffic=(r/"worker/WorkerDoorTraffic.java").read_text()
scaff=(r/"builder/BuilderScaffolding.java").read_text()
builder=(r/"builder/BuilderGolem.java").read_text()
job=(r/"builder/BuilderBuildService.java").read_text()
supply=(r/"builder/BuilderSupplyInventory.java").read_text()
assert "public static boolean descendForTravel(" in scaff
assert "begin(level,worker,column,bottom,true);" in scaff
assert "worker.setShiftKeyDown(true)" in scaff
assert "worker.setDeltaMovement(0,-Math.min(.16" in scaff
assert "worker.setShiftKeyDown(false)" in scaff
assert "worker.getNavigation().stop()" in scaff
assert "BuilderScaffolding.descendForTravel(level,worker,table)" in job
assert "BuilderScaffolding.descendForTravel(level,worker,table)" in supply
assert "&&!BuilderScaffolding.climbingNow(server,this)" in builder
assert "setShiftKeyDown(false)" in builder
assert "private static boolean requiresDoorCrossing(" in nav
assert "return Math.abs(from)>.35&&Math.abs(to)>.35&&from*to<0;" in nav
assert "requiresDoorCrossing(world,mob,p,door)" in nav
assert "requiresDoorCrossing(level,mob,p,doorPos)" in nav
assert "if(progress.queuedDoor!=null&&target!=null" in nav
assert "if(p.queuedDoor!=null&&target!=null" in nav
assert "public static void yieldStalled(" in traffic
assert "lane.cooldown.put(id,level.getGameTime()+60)" in traffic
assert "mob.tickCount-p.doorLastProgressTick>100" in nav
assert "WorkerDoorTraffic.yieldStalled(level,p.queuedDoor,mob)" in nav
assert "doorLastDistance" in nav
assert '" q#"+rank' in nav
print("PASS scaffold descent / gravity reset, actual door crossing filter, stalled head rotation")
