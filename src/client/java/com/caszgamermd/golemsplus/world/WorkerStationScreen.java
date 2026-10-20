package com.caszgamermd.golemsplus.world;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public final class WorkerStationScreen extends AbstractContainerScreen<WorkerStationMenu> {
    public WorkerStationScreen(WorkerStationMenu menu,Inventory inventory,Component title){
        super(menu,inventory,title,176,215);
        titleLabelX=8;titleLabelY=7;inventoryLabelX=8;inventoryLabelY=109;
    }
    private static void slot(GuiGraphicsExtractor g,int x,int y,int color){
        g.fill(x-1,y-1,x+17,y+17,0xff323b3e);
        g.fill(x,y,x+16,y+16,color);
    }
    @Override public void extractBackground(GuiGraphicsExtractor g,int mouseX,
            int mouseY,float partialTick){
        int x=leftPos,y=topPos;
        g.fill(x,y,x+176,y+215,0xff1b2529);
        g.fill(x+4,y+4,x+172,y+211,0xff586a70);
        g.fill(x+6,y+23,x+170,y+105,0xff384a50);
        g.fill(x+6,y+115,x+170,y+205,0xff384a50);
        for(int i=0;i<36;i++)slot(g,x+8+(i%9)*18,y+29+(i/9)*18,
                i<27?0xffd5dbd2:0xffc1dcc8);
        for(int i=0;i<36;i++)slot(g,x+8+(i%9)*18,y+121+(i/9)*18,0xffd5dbd2);
    }
    @Override protected void extractLabels(GuiGraphicsExtractor g,int mouseX,int mouseY){
        g.text(font,title,8,7,0xffe5f9ee,false);
        g.text(font,Component.literal("STOCK / MATERIALS"),65,19,0xffcbe1d2,false);
        g.text(font,Component.literal("RETURNS"),8,87,0xffcbe1d2,false);
        g.text(font,Component.literal("INVENTORY"),8,109,0xffcbe1d2,false);
    }
}
