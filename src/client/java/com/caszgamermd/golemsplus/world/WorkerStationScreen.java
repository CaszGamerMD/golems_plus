package com.caszgamermd.golemsplus.world;

import com.caszgamermd.golemsplus.builder.LitematicaPlacementClient;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
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
    private enum Page { BUILD, BUILDER_SETUP, BUILDER_PLACEMENTS, TRADE_ORDER, TRADE_BUDGET, TRADE_VILLAGE, BEEKEEPER, CHEF_MENU, CHEF_SUPPLIES }
    private Page page=Page.BUILD;
    private EditBox first,second;
    private String requestedItem="minecraft:emerald", requestedAmount="16";
    private String budgetItem="minecraft:wheat", budgetMaximum="320";
    private String villagePosition="",villageRadius="32";
    private int beeRadius=12,beeMode=0;
    private boolean loadedVillageDefaults,loadedOrderDefaults;
    private String feedback="";
    private int desiredCrew=1;
    private String chefSearch="";
    private int chefPage,chefStockPage,chefAmount=1;
    private java.util.List<Item> visibleMeals=java.util.List.of();
    private java.util.List<LitematicaPlacementClient.PlacementChoice> choices=java.util.List.of();
    private int placementPage;
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
    private boolean beekeeper(){
        var be=station();
        if(be!=null)return be.isBeekeeper();
        return minecraft!=null&&minecraft.level!=null
                &&minecraft.level.getBlockState(menu.stationPos()).getBlock()
                    instanceof WorkerStationBlock block
                &&block.specialty()==com.caszgamermd.golemsplus.worker.WorkerSpecialty.BEEKEEPER;
    }
    private boolean fisher(){
        var st=station();
        if(st!=null)return st.isFishing();
        return minecraft!=null&&minecraft.level!=null
                &&minecraft.level.getBlockState(menu.stationPos()).getBlock()
                instanceof WorkerStationBlock block
                &&block.specialty()==com.caszgamermd.golemsplus.worker.WorkerSpecialty.FISHING;
    }
    private boolean cooking(){
        var st=station();
        if(st!=null)return st.isCooking();
        return minecraft!=null&&minecraft.level!=null
                &&minecraft.level.getBlockState(menu.stationPos()).getBlock()
                instanceof WorkerStationBlock block
                &&block.specialty()==com.caszgamermd.golemsplus.worker.WorkerSpecialty.COOKING;
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
        page=trader()?Page.TRADE_ORDER:beekeeper()?Page.BEEKEEPER:cooking()?Page.CHEF_MENU:Page.BUILD;
        readDefaults();
        var station=station();
        if(station!=null){
            desiredCrew=station.crewTarget();
            beeMode=station.beeMode();beeRadius=station.beeRadius();
        }
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
            case CHEF_MENU -> {chefSearch=first.getValue();}
            default -> {}
        }
    }
    private void openPage(Page next){
        saveInputs();
        page=next;feedback="";
        clearWidgets();
        buildControls();
    }
    private void choosePlacementPage(){
        safe(()->{
            try{
                choices=LitematicaPlacementClient.placements();
                placementPage=0;
                openPage(Page.BUILDER_PLACEMENTS);
            }catch(Exception e){throw new IllegalArgumentException(e.getMessage());}
        });
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
        feedback="Requested: "+action.replace('_',' ');
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
    private void refreshChefMeals(){
        String needle=chefSearch.trim().toLowerCase(java.util.Locale.ROOT);
        visibleMeals=BuiltInRegistries.ITEM.stream()
                .filter(item->new ItemStack(item).get(DataComponents.FOOD)!=null)
                .filter(item->needle.isBlank()||
                        new ItemStack(item).getHoverName().getString()
                            .toLowerCase(java.util.Locale.ROOT).contains(needle)
                        ||BuiltInRegistries.ITEM.getKey(item).toString().contains(needle))
                .sorted(java.util.Comparator.comparing(item->new ItemStack(item)
                        .getHoverName().getString())).toList();
        chefPage=0;
    }
    private void crewControls(){
        button("−",PANE,206,32,()->{
            desiredCrew=Math.max(1,desiredCrew-1);
            send("crew",desiredCrew);
        });
        button("+",PANE+148,206,34,()->{
            desiredCrew=Math.min(4,desiredCrew+1);
            send("crew",desiredCrew);
        });
    }
    private void buildControls(){
        first=second=null;
        boolean trade=trader();
        if(cooking()){
            button("ORDER",PANE,28,59,()->openPage(Page.CHEF_MENU));
            button("PANTRY",PANE+61,28,59,()->openPage(Page.CHEF_SUPPLIES));
            button("LINK",PANE+122,28,60,()->send("chef_link",0));
            if(page==Page.CHEF_SUPPLIES){
                var stock=station()==null?java.util.Map.<String,Integer>of():station().chefStock();
                var rows=stock.entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).toList();
                int start=Math.min(chefStockPage*4,Math.max(0,rows.size()-1));
                for(int ix=start;ix<Math.min(start+4,rows.size());ix++){
                    var entry=rows.get(ix);
                    String name=entry.getKey();
                    var id=net.minecraft.resources.Identifier.tryParse(name);
                    if(id!=null&&BuiltInRegistries.ITEM.containsKey(id))
                        name=new ItemStack(BuiltInRegistries.ITEM.getValue(id)).getHoverName().getString();
                    final String display=truncate(name,21)+" ×"+entry.getValue();
                    button(display,PANE,57+(ix-start)*25,182,()->{});
                }
                button("Previous",PANE,164,89,()->{
                    chefStockPage=Math.max(0,chefStockPage-1);openPage(Page.CHEF_SUPPLIES);
                });
                button("Next",PANE+92,164,90,()->{
                    if((chefStockPage+1)*4<rows.size())chefStockPage++;
                    openPage(Page.CHEF_SUPPLIES);
                });
            }else{
                editor(54,"Search all food",chefSearch,true);
                button("Search",PANE+127,78,55,()->{
                    chefSearch=first.getValue();refreshChefMeals();openPage(Page.CHEF_MENU);
                });
                if(visibleMeals.isEmpty())refreshChefMeals();
                int from=chefPage*3;
                if(from>=visibleMeals.size()){chefPage=0;from=0;}
                for(int ix=from;ix<Math.min(from+3,visibleMeals.size());ix++){
                    Item item=visibleMeals.get(ix);
                    String name=new ItemStack(item).getHoverName().getString();
                    button(truncate(name,26),PANE,101+(ix-from)*22,182,()->{
                        send("chef_order",BuiltInRegistries.ITEM.getKey(item).toString(),
                                chefAmount,0,0,0);
                    });
                }
                button("−",PANE,169,28,()->{
                    chefAmount=Math.max(1,chefAmount-1);openPage(Page.CHEF_MENU);
                });
                button("+",PANE+33,169,28,()->{
                    chefAmount=Math.min(16,chefAmount+1);openPage(Page.CHEF_MENU);
                });
                button("Previous",PANE+67,169,55,()->{
                    chefPage=Math.max(0,chefPage-1);openPage(Page.CHEF_MENU);
                });
                button("Next",PANE+125,169,57,()->{
                    if((chefPage+1)*3<visibleMeals.size())chefPage++;
                    openPage(Page.CHEF_MENU);
                });
            }
            crewControls();
        }else if(trade){
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
        }else if(beekeeper()){
            button("Find TOOLBOX",PANE,36,182,()->send("bee_tools",0));
            button("Find OUTPUT",PANE,61,182,()->send("bee_output",0));
            button("Mode: "+(beeMode==0?"Both":beeMode==1?"Comb":"Honey"),
                    PANE,91,182,()->{
                        beeMode=(beeMode+1)%3;send("bee_mode",beeMode);
                        openPage(Page.BEEKEEPER);
                    });
            button("Range −",PANE,120,89,()->{
                beeRadius=Math.max(4,beeRadius-2);send("bee_radius",beeRadius);
                openPage(Page.BEEKEEPER);
            });
            button("Range +",PANE+92,120,90,()->{
                beeRadius=Math.min(24,beeRadius+2);send("bee_radius",beeRadius);
                openPage(Page.BEEKEEPER);
            });
            button("Pause",PANE,154,89,()->send("bee_pause",0));
            button("Resume",PANE+92,154,90,()->send("bee_resume",0));
            crewControls();
        }else if(fisher()){
            button("PAUSE",PANE,57,89,()->send("fish_pause",0));
            button("RESUME",PANE+92,57,90,()->send("fish_resume",0));
            crewControls();
        }else if(builder()){
            button("BUILD",PANE,28,89,()->openPage(Page.BUILD));
            button("SETUP",PANE+92,28,90,()->openPage(Page.BUILDER_SETUP));
            if(page==Page.BUILDER_PLACEMENTS){
                int from=placementPage*3;
                if(from>=choices.size()&&placementPage>0){placementPage=0;from=0;}
                for(int ix=from;ix<Math.min(from+3,choices.size());ix++){
                    var entry=choices.get(ix);
                    int y=58+(ix-from)*29;
                    String name=(entry.enabled()?"":"[OFF] ")+entry.name()+
                            " @ "+entry.origin().toShortString();
                    button(truncate(name,27),PANE,y,182,()->safe(()->{
                        try{
                            LitematicaPlacementClient.sendPlacement(entry,menu.stationPos());
                            feedback="Placement upload sent; use Preview to verify";
                            openPage(Page.BUILD);
                            feedback="Placement upload sent; use Preview to verify";
                        }catch(Exception e){throw new IllegalArgumentException(e.getMessage());}
                    }));
                }
                button("Previous",PANE,152,89,()->{
                    placementPage=Math.max(0,placementPage-1);openPage(Page.BUILDER_PLACEMENTS);
                });
                button("Next",PANE+92,152,90,()->{
                    if((placementPage+1)*3<choices.size())placementPage++;
                    openPage(Page.BUILDER_PLACEMENTS);
                });
                button("Refresh",PANE,180,89,this::choosePlacementPage);
                button("Back",PANE+92,180,90,()->openPage(Page.BUILD));
            }else if(page==Page.BUILDER_SETUP){
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
                button("Choose Litematica placement",PANE,59,182,this::choosePlacementPage);
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
        g.text(font,Component.literal(beekeeper()?"LINK EXTERNAL TOOLBOX":"STOCK / MATERIALS"),35,19,LABEL_COLOR,false);
        g.text(font,Component.literal(beekeeper()?"NO DIRECT STORAGE":"RETURNS"),8,87,LABEL_COLOR,false);
        g.text(font,Component.literal("INVENTORY"),8,109,LABEL_COLOR,false);
        g.text(font,Component.literal(trader()?"TRADER COUNTER":
                builder()?"BUILDER PLANNER":
                beekeeper()?"BEEKEEPER STATION":
                fisher()?"FISHING STATION":
                cooking()?"ORDER KIOSK":"WORKSTATION"),
                185,10,0xffffffff,false);
        var station=station();
        if(cooking()){
            if(page==Page.CHEF_MENU){
                g.text(font,"SEARCH / ALL FOODS",185,87,LABEL_COLOR,false);
                g.text(font,"Qty "+chefAmount+"  Page "+(chefPage+1)+"/"+
                        Math.max(1,(visibleMeals.size()+2)/3),185,195,LABEL_COLOR,false);
            }else{
                g.text(font,"STOCK FROM LINKED KITCHEN",185,197,LABEL_COLOR,false);
            }
            if(station!=null){
                String status=station.chefBusy()
                        ?station.chefCurrentStep()+" "+station.chefStepIndex()+
                                "/"+station.chefStepCount()
                        :"Kitchen idle";
                g.text(font,truncate(status,32),185,224,LABEL_COLOR,false);
            }
        }else if(trader()){
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
        }else if(beekeeper()){
            g.text(font,"Scan radius: "+beeRadius,185,143,LABEL_COLOR,false);
            if(station!=null){
                String tools=station.beeToolbox()==null?"Not linked":
                        station.beeToolbox().toShortString();
                String output=station.beeOutput()==null?"Not linked":
                        station.beeOutput().toShortString();
                g.text(font,truncate("Tools: "+tools,32),185,184,LABEL_COLOR,false);
                g.text(font,truncate("Output: "+output,32),185,195,LABEL_COLOR,false);
            }
        }else if(fisher()){
            g.text(font,"SUPPLIES: FISHING RODS",185,94,LABEL_COLOR,false);
            g.text(font,"FISH: OUTPUT INVENTORY",185,111,LABEL_COLOR,false);
            g.text(font,"Right-click Fisher to equip",185,128,LABEL_COLOR,false);
            g.text(font,"Rod over shoulder while idle",185,140,LABEL_COLOR,false);
        }else if(builder()&&page==Page.BUILDER_PLACEMENTS){
            g.text(font,"LITEMATICA PLACEMENTS  "+choices.size(),185,45,LABEL_COLOR,false);
            if(choices.isEmpty())
                g.text(font,"No placements loaded. Refresh.",185,103,0xffffc480,false);
            g.text(font,"Page "+(placementPage+1)+"/"+Math.max(1,(choices.size()+2)/3),
                    185,139,LABEL_COLOR,false);
        }else if(builder()&&page==Page.BUILDER_SETUP){
            g.text(font,"Warehouse: use a linked",185,163,LABEL_COLOR,false);
            g.text(font,"scanner on this Planner.",185,175,LABEL_COLOR,false);
            if(station!=null&&station.warehouse()!=null){
                var w=station.warehouse();
                g.text(font,truncate(w.x()+", "+w.y()+", "+w.z(),34),
                        185,188,0xffb8eec7,false);
            }else g.text(font,"No warehouse linked",185,188,0xffffc480,false);
        }
        if(trader()||builder()||beekeeper()||fisher()||cooking()){
            int count=station==null?1:station.crewTarget();
            int linked=station==null?0:trader()?station.traderWorkerIds().size():
                    builder()?station.builderWorkerIds().size():
                    beekeeper()?station.beekeeperWorkers().size():
                    fisher()?station.fisherWorkerIds().size():
                    station.chefWorkerIds().size();
            g.text(font,"CREW: "+linked+"/"+count,224,212,0xffffffff,false);
        }
        if(station!=null){
            String status=station.phase().name()+" • "+
                    (trader()?station.deliveredCount()+"/"+station.wantedCount():
                            builder()?station.builderCursor()+"/"+station.builderBlocks():
                            cooking()?(station.kitchenLink()==null?"Kitchen unlinked":
                                    "Kitchen "+station.kitchenLink().toShortString()):
                            "range "+station.beeRadius());
            g.text(font,truncate(status,34),185,232,LABEL_COLOR,false);
            if(!station.problem().isBlank())
                g.text(font,truncate(station.problem(),36),185,244,0xffffc480,false);
            else if(!feedback.isBlank())
                g.text(font,truncate(feedback,36),185,244,0xffa5e7ae,false);
        }else if(!feedback.isBlank())
            g.text(font,truncate(feedback,36),185,244,0xffa5e7ae,false);
    }
}
