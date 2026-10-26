#!/usr/bin/env python3
"""FIFO doors, 1.5-block worker spacing and no-gravity scaffold safety."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]/"src/main/java/com/caszgamermd/golemsplus"
queue=(root/"worker/WorkerDoorQueue.java").read_text()
traffic=(root/"worker/WorkerDoorTraffic.java").read_text()
nav=(root/"worker/WorkerNavigation.java").read_text()
scaffold=(root/"builder/BuilderScaffolding.java").read_text()
builder=(root/"builder/BuilderGolem.java").read_text()
assert "LinkedHashMap<UUID,Long> users" in queue
assert "users.put(worker,gameTime)" in queue
assert "users.keySet().iterator().next().equals(worker)" in queue
assert "STALE_TICKS=100" in queue
assert "Map<ServerLevel,Map<BlockPos,Lane>> doors" in traffic
assert "public static boolean crossed(" in traffic
assert "projected< -1.25" in traffic
assert "initial<.35" in traffic
assert "new HashMap<>()" in traffic
assert "queuedDoor,doorStandby" in nav
assert "private static BlockPos imminentDoor(" in nav
assert "private static BlockPos doorStandby(" in nav
assert "radius<2||radius>3.35" in nav
assert "if(doorTrafficTick(level,mob,p))" in nav
assert "if(p.waitingDoor||p.finishingDoor)return true" in nav
assert "WorkerDoorTraffic.request(level,local,mob)" in nav
assert "if(WorkerDoorTraffic.count(level,door)>0)return;" in nav
assert "1.5*1.5" in nav
assert "neighborOccupied(level,mob,p)?90:0" in nav
assert "private record ClimbSession(" in scaffold
assert "public static boolean climbingNow(" in scaffold
assert "level.getBlockState(columnLevel).is(Blocks.SCAFFOLDING)" in scaffold
assert "sessions.put(worker,new ClimbSession(" in scaffold
assert "public static boolean tickMotion(" in scaffold
assert "setNoGravity(false)" in builder
assert "!BuilderScaffolding.climbingNow(server,this)" in builder
print("PASS: independent FIFO doors, 2-3m safe queuing, spacing and gravity recovery")

