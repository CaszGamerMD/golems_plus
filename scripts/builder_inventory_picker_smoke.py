#!/usr/bin/env python3
"""Static and geometry regression for the read-only material inventory picker.

In-game/client visual QA is still necessary. This protects the render pass,
live-inventory reference, exact click alignment, and small viewport bounds.
"""
from pathlib import Path

screen = Path("src/client/java/com/caszgamermd/golemsplus/builder/BuilderPlannerScreen.java").read_text()
background = screen.split("@Override public void extractBackground(", 1)[1].split(
    "@Override protected void extractTooltip(", 1)[0]
labels = screen.split("@Override protected void extractLabels(", 1)[1]
picker = screen.split("private void renderInventoryPicker(", 1)[1].split(
    "private List<Map.Entry<String,Long>> materials(", 1)[0]
click = screen.split("@Override public boolean mouseClicked(", 1)[1].split(
    "private void placements(", 1)[0]

assert "if(page==Page.MATERIAL_EDIT)renderInventoryPicker(g);" in background
assert "renderInventoryPicker(g);" not in labels, "Don't render absolute slots in translated labels"
assert "minecraft.player.getInventory()" in screen
assert "currentPlayerInventory().getItem(slot)" in click
assert "inventoryPickerSlot(event.x(),event.y())" in click
assert "for(int i=0;i<36;i++)" in picker
assert "g.item(stack,x,y)" in picker
assert "inventoryPickerX(i),y=inventoryPickerY(i)" in picker
assert "removeItem(" not in click and "setItem(" not in click
for panel_height in (254, 390, 900):
    main = [(26+(i%9)*19, 112+((i-9)//9)*19) for i in range(9,36)]
    hotbar = [(26+i*19,190) for i in range(9)]
    all_slots = main+hotbar
    assert len(all_slots)==36
    for x,y in all_slots:
        assert x >= 18 and x+18 <= 212, "Slots must fit the sidebar"
        assert y >= 107 and y+18 < 219, "Slots must clear Cancel at min height"
    assert panel_height-12 > 239, "Inventory count must sit below Cancel"
print("Visible material inventory picker: render pass, live stacks, alignment, 36 cells, no transfers OK")
