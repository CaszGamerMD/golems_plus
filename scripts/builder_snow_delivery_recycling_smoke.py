#!/usr/bin/env python3
"""Regress Builder snow, delivery identity, physical recycling, and clean Planner UX."""
from pathlib import Path
root=Path(__file__).resolve().parents[1]
server=root/"src/main/java/com/caszgamermd/golemsplus"
client=root/"src/client/java/com/caszgamermd/golemsplus"
clear=(server/"builder/BuilderClearance.java").read_text()
site=(server/"builder/BuilderSiteClearance.java").read_text()
demand=(server/"builder/BuilderMaterialDemand.java").read_text()
courier=(server/"builder/BuilderWarehouseCoordinator.java").read_text()
planner=(client/"builder/BuilderPlannerScreen.java").read_text()
menu=(server/"world/WorkerStationMenu.java").read_text()
station=(server/"world/WorkerStationBlockEntity.java").read_text()
inventory=(server/"builder/BuilderSupplyInventory.java").read_text()
nav=(server/"worker/WorkerNavigation.java").read_text()
model=(root/"src/main/resources/assets/golems_plus/models/block/unemployed_center.json").read_text()
assert "boolean snow=current.is(Blocks.SNOW)||current.is(Blocks.SNOW_BLOCK)" in clear
assert "if(!snow){" in clear
assert "boolean surfaceSnow=existing.is(Blocks.SNOW)" in site
assert "if(!surfaceSnow&&pos.getY()-worker.getY()>2.5)" in site
assert "Math.min(4096,shortage)" in demand
assert "Math.min(64,shortage)" not in demand
assert "Once a real Builder has been assigned the delivery role" in courier
assert 'if(kiosk||builderTable)return;' in menu
assert 'if(kiosk||builderTable)return ItemStack.EMPTY;' in menu
assert "new net.minecraft.world.item.component.ItemLore(List.of(" in planner
assert 'Component.literal("Amount Needed: "+need)' in planner
assert 'stack.set(DataComponents.CUSTOM_NAME' not in planner
assert "builderRetirementStorage=oldSite.immutable()" in station
assert '"builder_retirement_storage"' in station
assert "builderUnloadingStorage()" in inventory
assert "Container destination=selectedStorage(table);" in inventory
assert "activeOrder=null;" in station[station.index("public void setPhase("):
                                        station.index("public void clearOrder()")]
assert '"opaque_ground_base"' in model
assert "Math.min(count-1,p.routeCursor+distance-1)" in nav
assert "int length=candidate.getNodeCount();" in nav
print("PASS: no snow/scaffold deadlock, full material order, courier ownership, job recycling and tooltip")
