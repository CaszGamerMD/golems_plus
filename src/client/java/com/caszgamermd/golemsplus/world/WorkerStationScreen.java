package com.caszgamermd.golemsplus.world;

import com.caszgamermd.golemsplus.builder.LitematicaPlacementClient;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * Every job-block setting is available from the block's own inventory UI.
 * The right pane controls the server-owned workstation. It never edits block
 * entity state directly on the client or dispatches slash commands.
 */
public final class WorkerStationScreen extends AbstractContainerScreen<WorkerStationMenu> {
    private enum Page { BUILD, BUILDER_SETUP, TRADE_ORDER, TRADE_BUDGET, TRADE_VILLAGE }
    private Page page=Page.BUILD;
    private EditBox first,second;
    private String requestedItem="minecraft:emerald", requestedAmount="16";
    private String budgetItem="minecraft:wheat", budgetMaximum="320";
    private String villagePosition="",villageRadius="32";
    private boolean loadedVillageDefaults,loadedOrderDefaults;
    private String feedback="";
    private static final int PANE=185, BG_WIDTH=375, BG_HEIGHT=254;
    private static final int PANEL_COLOR=0xff26383c;
    private static final int LABEL_COLOR=0xffcbe8d8;

    public WorkerStationScreen(WorkerStationMenu menu,Inventory inventory,Component title){
        super(menu,inventory,title,BG_WIDTH,BG_HEIGHT);
        titleLabelX=8;titleLabelY=7;inventoryLabelX=8;inventoryLabelY=109;
    }
    private WorkerStationBlockEntity station(){
        if(minecraft==null||minecraft.level==null)return null;
        return minecraft.level.getBlockEntity(menu.stationPos())
                instanceof WorkerStationBlockEntity station?station:null;
    }
    private boolean trader(){
        var station=station();
        if(station!=null)return station.isTrader();
        return minecraft!=null&&minecraft.level!=null
                &&minecraft.level.getBlockState(menu.stationPos()).getBlock()
                instanceof WorkerStationBlock block
                &&block.specialty()
                    ==com.caszgamermd.golemsplus.worker.WorkerSpecialty.TRADER;
    }
    private boolean builder(){
        var be=station();
        if(be!=null)return be.isBuilder();
        return minecraft!=null&&minecraft.level!=null
                &&minecraft.level.getBlockState(menu.stationPos()).getBlock()
                instanceof WorkerStationBlock block
                &&block.specialty()
                    ==com.caszgamermd.golemsplus.worker.WorkerSpecialty.BUILDER;
    }
    @Override protected void init(){
        super.init();
        page=trader()?Page.TRADE_ORDER:Page.BUILD;
        readDefaults();
        buildControls();
    }
    private void readDefaults(){
        var station=station();
        if(station==null)return;
        if(trader()&&!loadedOrderDefaults){
            if(!station.wantedItem().isBlank())requestedItem=station.wantedItem();
            if(station.wantedCount()>0)requestedAmount=""+station.wantedCount();
            loadedOrderDefaults=true;
        }
        if(trader()&&!loadedVillageDefaults){
            var area=station.village();
            if(area!=null){
                villagePosition=area.x()+" "+area.y()+" "+area.z();
                villageRadius=""+area.radius();
            }else if(minecraft.player!=null){
                var p=minecraft.player.blockPosition();
                villagePosition=p.getX()+" "+p.getY()+" "+p.getZ();
            }
            loadedVillageDefaults=true;
        }
    }
    private void saveInputs(){
        if(first==null)return;
        switch(page){
            case TRADE_ORDER -> {requestedItem=first.getValue();requestedAmount=second.getValue();}
            case TRADE_BUDGET -> {budgetItem=first.getValue();budgetMaximum=second.getValue();}
            case TRADE_VILLAGE -> {villagePosition=first.getValue();villageRadius=second.getValue();}
            default -> {}
        }
    }
    private void openPage(Page next){
        saveInputs();
        page=next;feedback="";
        clearWidgets();
        buildControls();
    }
    private void button(String text,int x,int y,int width,Runnable action){
        addRenderableWidget(Button.builder(Component.literal(text),b -> action.run())
                .bounds(leftPos+x,topPos+y,width,20).build());
    }
    private void editor(int y,String label,String value,boolean isFirst){
        EditBox box=new EditBox(font,leftPos+PANE,topPos+y,182,18,
                Component.literal(label));
        box.setMaxLength(120);
        box.setValue(value);
        addRenderableWidget(box);
        if(isFirst)first=box;else second=box;
    }
    private void send(String action,String item,int count,int x,int y,int z){
        if(!ClientPlayNetworking.canSend(StationSettingsNetworking.Change.TYPE)){
            feedback="Server does not support job settings";
            return;
        }
        ClientPlayNetworking.send(new StationSettingsNetworking.Change(
                menu.stationPos(),action,item,count,x,y,z));
        feedback="Sent: "+action.replace('_',' ');
    }
    private void send(String action,int count){
        send(action,"",count,0,0,0);
    }
    private int number(EditBox input,String field,int min,int max){
        if(input==null)throw new IllegalArgumentException("Missing "+field);
        int value=Integer.parseInt(input.getValue().trim());
        if(value<min||value>max)throw new IllegalArgumentException(
                field+" must be "+min+"–"+max);
        return value;
    }
    private void safe(Runnable action){
        try{action.run();}
        catch(NumberFormatException ex){feedback="Enter a valid number";}
        catch(IllegalArgumentException ex){feedback=ex.getMessage();}
        catch(Exception ex){feedback="Could not apply this setting";}
    }
    private void crewControls(){
        button("−",PANE,206,32,()->{
            var station=station();
            send("crew",Math.max(1,(station==null?1:station.crewTarget())-1));
        });
        button("+",PANE+148,206,34,()->{
            var station=station();
            send("crew",Math.min(4,(station==null?1:station.crewTarget())+1));
        });
    }
    private void buildControls(){
        first=second=null;
        boolean trade=trader();
        if(trade){
            button("ORDER",PANE,28,59,()->openPage(Page.TRADE_ORDER));
            button("BUDGET",PANE+61,28,59,()->openPage(Page.TRADE_BUDGET));
            button("VILLAGE",PANE+122,28,60,()->openPage(Page.TRADE_VILLAGE));
            switch(page){
                case TRADE_BUDGET -> {
                    editor(76,"Payment item",budgetItem,true);
                    editor(120,"Max spending",budgetMaximum,false);
                    button("Set payment limit",PANE,151,182,()->safe(()->
                            send("trader_budget",first.getValue(),
                                    number(second,"Spending",1,65536),0,0,0)));
                }
                case TRADE_VILLAGE -> {
                    editor(76,"Village center X Y Z",villagePosition,true);
                    editor(120,"Village radius",villageRadius,false);
                    button("Use my position",PANE,151,87,()->{
                        if(minecraft.player!=null){
                            BlockPos pos=minecraft.player.blockPosition();
                            first.setValue(pos.getX()+" "+pos.getY()+" "+pos.getZ());
                        }
                    });
                    button("Save village",PANE+89,151,93,()->safe(()->{
                        String[] coords=first.getValue().trim().split("[,\\s]+");
                        if(coords.length!=3)throw new IllegalArgumentException(
                                "Enter X Y Z");
                        int x=Integer.parseInt(coords[0]);
                        int y=Integer.parseInt(coords[1]);
                        int z=Integer.parseInt(coords[2]);
                        send("trader_village","",
                                number(second,"Radius",4,128),x,y,z);
                    }));
                }
                default -> {
                    editor(76,"Requested item",requestedItem,true);
                    editor(120,"Requested quantity",requestedAmount,false);
                    button("Set order",PANE,151,85,()->safe(()->
                            send("trader_order",first.getValue(),
                                    number(second,"Quantity",1,4096),0,0,0)));
                    button("Cancel order",PANE+87,151,95,()->
                            send("trader_cancel",0));
                }
            }
            button("Pause",PANE,178,90,()->send("trader_pause",0));
            button("Resume",PANE+92,178,90,()->send("trader_resume",0));
            crewControls();
        }else if(builder()){
            button("BUILD",PANE,28,89,()->openPage(Page.BUILD));
            button("SETUP",PANE+92,28,90,()->openPage(Page.BUILDER_SETUP));
            if(page==Page.BUILDER_SETUP){
                button("Rotate +90°",PANE,59,182,()->{
                    var station=station();
                    send("builder_rotate",station==null?90:
                            (station.builderRotation()+90)%360);
                });
                button("Preview materials",PANE,87,182,()->
                        send("builder_preview",0));
                button("Pause",PANE,119,89,()->send("builder_pause",0));
                button("Resume",PANE+92,119,90,()->send("builder_resume",0));
            }else{
                button("Import Litematica placement",PANE,59,182,()->safe(()->{
                    try{
                        LitematicaPlacementClient.sendSelected(menu.stationPos());
                        feedback="Placement upload sent";
                    }catch(Exception ex){
                        throw new IllegalArgumentException(ex.getMessage());
                    }
                }));
                button("Preview",PANE,87,89,()->send("builder_preview",0));
                button("Start build",PANE+92,87,90,()->send("builder_start",0));
                button("Pause",PANE,119,89,()->send("builder_pause",0));
                button("Resume",PANE+92,119,90,()->send("builder_resume",0));
                button("Cancel job",PANE,151,182,()->send("builder_cancel",0));
            }
            crewControls();
        }else{
            feedback="Workstation controls coming soon";
        }
    }
    private static void slot(GuiGraphicsExtractor g,int x,int y,int color){
        g.fill(x-1,y-1,x+17,y+17,0xff323b3e);
        g.fill(x,y,x+16,y+16,color);
    }
    @Override public void extractBackground(GuiGraphicsExtractor g,int mouseX,
            int mouseY,float partialTick){
        int x=leftPos,y=topPos;
        g.fill(x,y,x+BG_WIDTH,y+BG_HEIGHT,0xff1b2529);
        g.fill(x+4,y+4,x+BG_WIDTH-4,y+BG_HEIGHT-4,0xff586a70);
        g.fill(x+6,y+23,x+170,y+105,0xff384a50);
        g.fill(x+6,y+115,x+170,y+205,0xff384a50);
        g.fill(x+178,y+6,x+BG_WIDTH-6,y+BG_HEIGHT-6,PANEL_COLOR);
        for(int i=0;i<36;i++)slot(g,x+8+(i%9)*18,y+29+(i/9)*18,
                i<27?0xffd5dbd2:0xffc1dcc8);
        for(int i=0;i<36;i++)slot(g,x+8+(i%9)*18,y+121+(i/9)*18,0xffd5dbd2);
    }
    private String truncate(String value,int max){
        return value.length()>max?value.substring(0,max-1)+"…":value;
    }
    @Override protected void extractLabels(GuiGraphicsExtractor g,int mouseX,int mouseY){
        g.text(font,title,8,7,0xffe5f9ee,false);
        g.text(font,Component.literal("STOCK / MATERIALS"),65,19,LABEL_COLOR,false);
        g.text(font,Component.literal("RETURNS"),8,87,LABEL_COLOR,false);
        g.text(font,Component.literal("INVENTORY"),8,109,LABEL_COLOR,false);
        g.text(font,Component.literal(trader()?"TRADER COUNTER":
                builder()?"BUILDER PLANNER":"WORKSTATION"),
                185,10,0xffffffff,false);
        var station=station();
        if(trader()){
            switch(page){
                case TRADE_BUDGET -> {
                    g.text(font,"PAYMENT ITEM ID",185,61,LABEL_COLOR,false);
                    g.text(font,"MAXIMUM ITEMS",185,105,LABEL_COLOR,false);
                }
                case TRADE_VILLAGE -> {
                    g.text(font,"VILLAGE CENTER COORDINATES",185,61,LABEL_COLOR,false);
                    g.text(font,"SEARCH RADIUS (4–128)",185,105,LABEL_COLOR,false);
                }
                default -> {
                    g.text(font,"ITEM ID",185,61,LABEL_COLOR,false);
                    g.text(font,"QUANTITY",185,105,LABEL_COLOR,false);
                }
            }
        }else if(builder()&&page==Page.BUILDER_SETUP){
            g.text(font,"Warehouse: use a linked",185,163,LABEL_COLOR,false);
            g.text(font,"scanner on this Planner.",185,175,LABEL_COLOR,false);
            if(station!=null&&station.warehouse()!=null){
                var w=station.warehouse();
                g.text(font,truncate(w.x()+", "+w.y()+", "+w.z(),34),
                        185,188,0xffb8eec7,false);
            }else g.text(font,"No warehouse linked",185,188,0xffffc480,false);
        }
        if(trader()||builder()){
            int count=station==null?1:station.crewTarget();
            int linked=station==null?0:trader()?station.traderWorkerIds().size():
                    station.builderWorkerIds().size();
            g.text(font,"CREW: "+linked+"/"+count,224,212,0xffffffff,false);
        }
        if(station!=null){
            String status=station.phase().name()+" • "+
                    (trader()?station.deliveredCount()+"/"+station.wantedCount():
                            station.builderCursor()+"/"+station.builderBlocks());
            g.text(font,truncate(status,34),185,232,LABEL_COLOR,false);
            if(!station.problem().isBlank())
                g.text(font,truncate(station.problem(),36),185,244,0xffffc480,false);
            else if(!feedback.isBlank())
                g.text(font,truncate(feedback,36),185,244,0xffa5e7ae,false);
        }else if(!feedback.isBlank())
            g.text(font,truncate(feedback,36),185,244,0xffa5e7ae,false);
    }
}
