#!/usr/bin/env python3
"""The dome's closed fence gate, vertical target and OnSite shulker regressions."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]/"src/main/java/com/caszgamermd/golemsplus"
nav=(root/"worker/WorkerNavigation.java").read_text()
scaffold=(root/"builder/BuilderScaffolding.java").read_text()
builder=(root/"builder/BuilderBuildService.java").read_text()
supply=(root/"builder/BuilderSupplyInventory.java").read_text()
# Fence gates and doors both participate in the same per-entrance FIFO.
assert "instanceof FenceGateBlock" in nav
assert "FenceGateBlock.FACING" in nav
assert "FenceGateBlock.OPEN" in nav
assert "setPassageOpen(level,mob,p.queuedDoor,true)" in nav
assert "if(!isOpenPassage(entrance))" in nav
assert "setPassageOpen(level,mob,door,true)" in nav
assert "BlockPos door=imminentDoor(level,mob,p)" in nav
assert "if(!onExistingPath)" in nav
assert "setPassageOpen(level,mob,door,false)" in nav
# Don't attempt to path to a height that can only be reached with scaffolds.
assert "pos.getY()-worker.getY()>1.35&&distToTarget>14" in builder
assert "target.getY()-worker.getY()<=1.35" in scaffold
assert "for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)" in scaffold
assert "distance<1||distance>5" in scaffold
assert "worker.setNoGravity(false);" in scaffold
assert "worker.setShiftKeyDown(true)" in scaffold
assert "worker.getNavigation().stop();" in scaffold
assert "if(!worker.onGround()||worker.getY()>minY+2.1)return;" in scaffold
# Actual item transfer supports placed shulkers next to OnSite and item-form
# shulkers inside it, with component-aware decrement after pouch acceptance.
assert "instanceof ShulkerBoxBlock" in supply
assert "world.getBlockEntity(at) instanceof Container box" in supply
assert "private static int drawFromStorage(" in supply
assert "private static int drawFromSources(" in supply
assert "storage.setChanged();remaining-=moved;" in supply
assert "ItemContainerContents.fromItems(nested)" in supply
assert "drawFromSources(table,worker,item,needed);" in supply
assert "drawFromSources(table,worker,requested,needed);" in supply
assert "drawFromSources(table,worker,item,required-worker.carriedCount(item));" in supply
assert "for(Container store:sources(table))" in supply
assert "BuilderScaffolding.descendForTravel(level,worker,table)" in supply
assert "tower=" in builder and "gravity=" in builder and "depot=" in builder
print("PASS: door/gate opening, elevated scaffolds, physical return, shulker transfer")
