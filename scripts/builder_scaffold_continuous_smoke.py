#!/usr/bin/env python3
"""Regression for persistent physical Builder scaffold climb.
The previous 2-block-offset towers were forgotten after placement and
upward motion was only refreshed every fifth tick, causing deadlock.
"""
from pathlib import Path
root=Path(__file__).resolve().parents[1]/"src/main/java/com/caszgamermd/golemsplus"
sc=(root/"builder/BuilderScaffolding.java").read_text()
golem=(root/"builder/BuilderGolem.java").read_text()
build=(root/"builder/BuilderBuildService.java").read_text()
assert "private record ClimbSession(" in sc
assert "public static boolean tickMotion(ServerLevel level,BuilderGolem worker)" in sc
assert "worker.getJumpControl().jump();" in sc
assert "worker.getMoveControl().setWantedPosition(" in sc
assert "worker.setNoGravity(false);" in sc
assert "worker.setJumping(true);" in sc
assert "worker.setShiftKeyDown(true);" in sc
assert "worker.getNavigation().stop();" in sc
assert "sessions.put(worker,new ClimbSession(" in sc
assert "clearMotion(worker);" in sc
assert "if(Math.abs(worker.getX()-cx)>1.15" in sc
assert "target.getY()-worker.getY()<=1.35" in sc
assert "dx*dx+dz*dz<=5;" in sc
assert "if(base==null)for(Direction side:Direction.Plane.HORIZONTAL)" in sc
assert "if(base==null)base=findBase(level,worker,target,feet,table,plan)" in sc
assert "begin(level,worker,base,top+1.0,false)" in sc
assert "begin(level,worker,column,bottom,true)" in sc
assert "BuilderScaffolding.tickMotion(climbWorld,this)" in golem
assert "if(!climbing)" in golem
assert "WorkerNavigation.tick(server,this);" in golem
assert 'BuilderScaffolding.debugClimb(worker)' in build
assert "new ClimbPermit(" not in sc
print("PASS: stable owned tower, per-tick physical climb, descent and navigation isolation")
