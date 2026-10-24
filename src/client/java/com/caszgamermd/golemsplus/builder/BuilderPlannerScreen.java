package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.StationSettingsNetworking;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import com.caszgamermd.golemsplus.world.WorkerStationMenu;
import com.caszgamermd.golemsplus.world.WorkerStationScreen;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Dedicated full-screen Builder planner, not the cramped old workstation
 * right pane. All actual job data is server-owned in the block entity; this
 * cache keeps only presentation state when the player closes/reopens.
 */
public final class BuilderPlannerScreen extends WorkerStationScreen {
    private enum Page { JOB, PLACEMENTS }
    private enum Sort { ALPHABETICAL, VOLUME }
    private record Memory(Page page,Sort sort,int scroll,int placementPage,
                          int jobPage,String search){}
    private static final Map<String,Memory> VIEW=new HashMap<>();
    private Page page=Page.JOB;
    private Sort sort=Sort.ALPHABETICAL;
    private int scroll,placementPage,jobPage;
    private String search="";
    private EditBox query;
    private String notice="";
    private int deleteConfirm=-1;
    private List<LitematicaPlacementClient.PlacementChoice> choices=List.of();
    private static final int TEXT=0xffdce9e6, MUTED=0xffa6c0c7, GREEN=0xff89f39f,
            YELLOW=0xffffd379, RED=0xffff7b81, WHITE=0xfff4f5f5;
    private static final int SIDE=220;
    private int panelWidth=600,panelHeight=390;
    public BuilderPlannerScreen(WorkerStationMenu menu,Inventory inventory,Component title){
        super(menu,inventory,title);
        titleLabelX=15;titleLabelY=10;inventoryLabelX=18;inventoryLabelY=188;
    }
    /** Full viewport JEI exclusion area. Container's default width is
     * small due to inventory slots, but the Builder planner fills the screen.
     */
    public net.minecraft.client.renderer.Rect2i plannerBounds(){
        return new net.minecraft.client.renderer.Rect2i(
                leftPos,topPos,panelWidth,panelHeight);
    }
    private String key(){
        return (minecraft!=null&&minecraft.level!=null?
                minecraft.level.dimension().identifier().toString():"world")
                +":"+menu.stationPos().asLong();
    }
    private WorkerStationBlockEntity station(){
        if(minecraft==null||minecraft.level==null)return null;
        return minecraft.level.getBlockEntity(menu.stationPos())
                instanceof WorkerStationBlockEntity t?t:null;
    }
    private void remember(){
        if(query!=null)search=query.getValue();
        VIEW.put(key(),new Memory(page,sort,scroll,placementPage,jobPage,search));
    }
    @Override protected void init(){
        // The old UI forced a minimum 520x420 box even when Minecraft was
        // scaled smaller. That moved the real inventory slots off-screen.
        // Use the available viewport while retaining the minimum underlying
        // menu size for the physical 9-column inventory.
        panelWidth=Math.max(imageWidth,Math.min(1200,width-16));
        panelHeight=Math.max(imageHeight,Math.min(900,height-16));
        super.init();
        // Container-screen image dimensions are final in Minecraft 26.2.
        // The planner draws across the actual viewport and repositions slots.
        leftPos=(width-panelWidth)/2;
        topPos=(height-panelHeight)/2;
        clearWidgets();
        Memory saved=VIEW.get(key());
        if(saved!=null){
            page=saved.page();sort=saved.sort();
            scroll=saved.scroll();placementPage=saved.placementPage();
            jobPage=saved.jobPage();search=saved.search();
        }
        buildWidgets();
    }
    @Override public void onClose(){
        remember();
        super.onClose();
    }
    // AbstractContainerScreen uses its original 375x254 image dimensions
    // to test clicks outside. This planner is much larger, so normal clicks
    // in the player's 36 slots (and the planner's 27 input slots) were
    // incorrectly interpreted as THROW/DROP carried items.
    //
    // No click inside the planner should ever count as a world drop; players
    // can still use Q in their inventory if they deliberately want to drop.
    @Override protected boolean hasClickedOutside(double mouseX,double mouseY,
            int left,int top){
        return false;
    }
    @Override public boolean keyPressed(KeyEvent key){
        if(key.isEscape()){onClose();return true;}
        if(getFocused() instanceof EditBox e&&e.keyPressed(key))return true;
        if(key.isCycleFocus())return super.keyPressed(key);
        return true;
    }
    @Override public boolean mouseScrolled(double x,double y,double dx,double dy){
        if(page==Page.PLACEMENTS){
            placementPage=Math.max(0,placementPage+(dy>0?-1:1));
            show(Page.PLACEMENTS);return true;
        }
        if(x>=leftPos+SIDE){
            scroll=Math.max(0,scroll+(dy>0?-1:1));
            remember();return true;
        }
        return super.mouseScrolled(x,y,dx,dy);
    }
    private int materialWidth(){return Math.max(200,panelWidth-SIDE-35);}
    private int columns(){return Math.max(3,materialWidth()/76);}
    private int rows(){return Math.max(2,(panelHeight-225)/62);}
    private int pageSize(){return columns()*rows();}
    private void show(Page next){
        remember();page=next;
        clearWidgets();buildWidgets();remember();
    }
    private void button(String caption,int x,int y,int w,Runnable run){
        addRenderableWidget(Button.builder(Component.literal(caption),
                event->run.run()).bounds(leftPos+x,topPos+y,w,20).build());
    }
    private void send(String action,int number){
        if(!ClientPlayNetworking.canSend(StationSettingsNetworking.Change.TYPE)){
            notice="Server is missing Builder settings support";
            return;
        }
        ClientPlayNetworking.send(new StationSettingsNetworking.Change(
                menu.stationPos(),action,"",number,0,0,0));
        notice="Requested: "+action.replace('_',' ');
    }
    private void placements(){
        try{
            choices=LitematicaPlacementClient.placements();
            placementPage=0;
            show(Page.PLACEMENTS);
        }catch(Exception ex){
            notice=ex.getMessage()==null?"Litematica placements unavailable":ex.getMessage();
        }
    }
    private void buildWidgets(){
        var table=station();
        int jobCount=table==null?0:table.builderJobs().size();
        int visibleTabs=Math.max(2,Math.min(6,(panelWidth-205)/125));
        jobPage=Math.clamp(jobPage,0,Math.max(0,(jobCount-1)/visibleTabs));
        button("<",12,39,22,()->{jobPage=Math.max(0,jobPage-1);show(page);});
        for(int i=jobPage*visibleTabs;i<Math.min(jobCount,(jobPage+1)*visibleTabs);i++){
            int n=i,at=i-jobPage*visibleTabs;
            var job=table.builderJobs().get(i);
            String symbol=job.phase().equals("WORKING")?"▶":
                    job.phase().equals("COMPLETE")?"✓":"•";
            String label=symbol+" "+job.name()+" "+
                    (job.blocks()==0?0:job.cursor()*100/job.blocks())+"%";
            if(label.length()>17)label=label.substring(0,16)+"…";
            button(label,40+at*125,39,121,()->{
                if(table.activeOrder()!=null&&table.selectedBuilderJob()!=n){
                    notice="Finish/cancel the running job before switching";
                    return;
                }
                send("builder_select",n);
                show(Page.JOB);
            });
        }
        button(">",Math.min(panelWidth-174,40+visibleTabs*125),39,22,()->{
            jobPage=Math.min(Math.max(0,(jobCount-1)/visibleTabs),jobPage+1);
            show(page);
        });
        button("+ NEW JOB",panelWidth-134,39,118,this::placements);

        if(page==Page.PLACEMENTS){
            button("REFRESH",SIDE+12,92,100,this::placements);
            button("BACK TO JOB",SIDE+119,92,126,()->show(Page.JOB));
            int maxPerPage=Math.max(4,(panelHeight-182)/30);
            int max=Math.max(0,(choices.size()-1)/maxPerPage);
            placementPage=Math.min(placementPage,max);
            for(int i=placementPage*maxPerPage;
                    i<Math.min(choices.size(),(placementPage+1)*maxPerPage);i++){
                int at=i-placementPage*maxPerPage;
                var placement=choices.get(i);
                String label=(placement.enabled()?"":"[DISABLED] ")+placement.name()
                        +" @ "+placement.origin().toShortString();
                if(label.length()>75)label=label.substring(0,74)+"…";
                button(label,SIDE+15,129+at*30,
                        Math.min(materialWidth()-10,panelWidth-SIDE-40),()->{
                    try{
                        LitematicaPlacementClient.sendPlacement(placement,menu.stationPos());
                        notice="Uploading selected placement. Server will save its block list.";
                        show(Page.JOB);
                    }catch(Exception e){
                        notice="Placement upload failed: "+e.getMessage();
                    }
                });
            }
            button("PREV",SIDE+16,panelHeight-43,85,()->{
                placementPage=Math.max(0,placementPage-1);show(Page.PLACEMENTS);
            });
            button("NEXT",SIDE+110,panelHeight-43,85,()->{
                placementPage=Math.min(max,placementPage+1);show(Page.PLACEMENTS);
            });
            return;
        }
        button(sort==Sort.ALPHABETICAL?"SORT: A-Z":"SORT: QTY",SIDE+14,94,116,()->{
            sort=sort==Sort.ALPHABETICAL?Sort.VOLUME:Sort.ALPHABETICAL;
            scroll=0;show(Page.JOB);
        });
        query=new EditBox(font,leftPos+SIDE+142,topPos+95,
                Math.max(140,Math.min(260,materialWidth()-160)),18,
                Component.literal("Search materials"));
        query.setMaxLength(80);query.setValue(search);
        addRenderableWidget(query);
        int y=panelHeight-106;
        button("START BUILD",SIDE+12,y,116,()->send("builder_start",0));
        button("PAUSE",SIDE+136,y,72,()->send("builder_pause",0));
        button("RESUME",SIDE+216,y,82,()->send("builder_resume",0));
        button("SUPPLIES",SIDE+306,y,95,()->send("builder_supplies",0));
        button("PICK STORAGE",SIDE+12,y+27,137,()->{
            send("builder_storage",0);
            notice="Close the planner, then right-click a staging chest";
        });
        button("SCAN STOCK",SIDE+158,y+27,116,()->send("builder_refresh",0));
        button("CANCEL",SIDE+282,y+27,117,()->send("builder_cancel",0));
        button(deleteConfirm>=0?"CONFIRM DELETE":"DELETE JOB",
                SIDE+12,y+54,137,()->{
            if(table==null||table.selectedBuilderJob()<0)return;
            if(table.activeOrder()!=null){
                notice="Cancel the running construction before deleting its job";
                return;
            }
            if(deleteConfirm!=table.selectedBuilderJob()){
                deleteConfirm=table.selectedBuilderJob();
                notice="Press CONFIRM DELETE to remove only this saved job";
                show(Page.JOB);
                return;
            }
            send("builder_delete",deleteConfirm);
            deleteConfirm=-1;
            notice="Job delete requested. Saved material inventory is not destroyed.";
            show(Page.JOB);
        });
        button("CREW -",18,285,90,()->{
            if(table!=null)send("crew",Math.max(1,table.crewTarget()-1));
        });
        button("CREW +",113,285,90,()->{
            if(table!=null)send("crew",Math.min(4,table.crewTarget()+1));
        });
    }
    private List<Map.Entry<String,Long>> materials(){
        WorkerStationBlockEntity table=station();
        if(table==null)return List.of();
        String filter=query!=null?query.getValue().toLowerCase():search.toLowerCase();
        List<Map.Entry<String,Long>> out=new ArrayList<>();
        for(var row:table.builderRemaining().entrySet())
            if(filter.isBlank()||row.getKey().toLowerCase().contains(filter))
                out.add(row);
        if(sort==Sort.ALPHABETICAL)
            out.sort(Map.Entry.comparingByKey());
        else out.sort(Comparator.<Map.Entry<String,Long>>comparingLong(
                Map.Entry::getValue).reversed().thenComparing(Map.Entry::getKey));
        return out;
    }
    private int color(WorkerStationBlockEntity table,String id,long remaining){
        long staged=table.builderStagedStock().getOrDefault(id,0);
        long shortage=Math.max(0,remaining-staged);
        if(shortage==0)return GREEN;
        if(!table.builderStockChecked())return WHITE;
        long warehouse=table.builderWarehouseStock().getOrDefault(id,0);
        if(warehouse==0)return RED;
        return warehouse>=shortage?GREEN:YELLOW;
    }
    private static String stacks(long count,int limit){
        int size=Math.max(1,limit);
        long full=count/size,partial=count%size;
        return count+" items ("+full+" stacks"+(partial==0?"":", "+partial+" extra")+")";
    }
    @Override public void extractBackground(GuiGraphicsExtractor g,
            int mouseX,int mouseY,float partialTick){
        int x=leftPos,y=topPos;
        g.fill(x,y,x+panelWidth,y+panelHeight,0xff111b24);
        g.fill(x+4,y+4,x+panelWidth-4,y+69,0xff263946);
        g.fill(x+9,y+76,x+SIDE-8,y+panelHeight-12,0xff283c40);
        g.fill(x+SIDE+4,y+76,x+panelWidth-9,y+panelHeight-12,0xff1e313c);
        g.fill(x+SIDE+5,y+114,x+panelWidth-10,y+panelHeight-119,0xff162932);
        // Job-block and player inventory remain available in the planner.
        // Draw the slot backgrounds from the *actual* slots owned by the
        // server-backed WorkerStationMenu, rather than guessing their Y.
        // In particular the last nine player slots are the hotbar, not
        // another row of storage.
        if(page==Page.JOB)
            for(int i=0;i<menu.slots.size();i++){
                var slot=menu.slots.get(i);
                int sx=x+slot.x,sy=y+slot.y;
                g.fill(sx-1,sy-1,sx+17,sy+17,
                        i<36?0xff637578:0xff627478);
            }
    }
    @Override protected void extractTooltip(GuiGraphicsExtractor g,int mouseX,int mouseY){
        super.extractTooltip(g,mouseX,mouseY);
        if(page!=Page.JOB)return;
        var list=materials();
        int columns=columns(),size=pageSize(),first=scroll*columns;
        for(int i=first;i<Math.min(list.size(),first+size);i++){
            int n=i-first,px=leftPos+SIDE+22+(n%columns)*76;
            int py=topPos+139+(n/columns)*62;
            if(mouseX<px||mouseX>=px+45||mouseY<py||mouseY>=py+45)continue;
            var entry=list.get(i);
            Identifier id=Identifier.tryParse(entry.getKey());
            if(id==null||!BuiltInRegistries.ITEM.containsKey(id))break;
            ItemStack stack=new ItemStack(BuiltInRegistries.ITEM.getValue(id));
            var table=station();
            long staged=table==null?0:table.builderStagedStock()
                    .getOrDefault(entry.getKey(),0);
            long need=Math.max(0,entry.getValue()-staged);
            String warehouse=table==null||!table.builderStockChecked()?"not checked":
                    ""+table.builderWarehouseStock().getOrDefault(entry.getKey(),0);
            String name=stack.getHoverName().getString();
            stack.set(DataComponents.CUSTOM_NAME,Component.literal(name+
                    " | left "+stacks(entry.getValue(),stack.getMaxStackSize())+
                    " | need delivery "+stacks(need,stack.getMaxStackSize())+
                    " | staged "+staged+" | warehouse "+warehouse));
            g.setTooltipForNextFrame(font,stack,mouseX,mouseY);
            return;
        }
    }
    @Override protected void extractLabels(GuiGraphicsExtractor g,int mouseX,int mouseY){
        var t=station();
        g.text(font,"CASZUAL BUILDERS  /  JOB PLANNER",16,14,0xffeff6eb,false);
        g.text(font,"ACTIVE / SAVED JOBS",16,73,MUTED,false);
        g.text(font,"PLANNER: TOOLS / BLOCKS / SCAFFOLD",18,85,TEXT,false);
        g.text(font,"PLAYER INVENTORY",18,188,TEXT,false);
        if(t!=null){
            g.text(font,"CREW "+t.builderWorkerIds().size()+"/"+t.crewTarget(),18,274,
                    TEXT,false);
            g.text(font,"JOB: "+(t.selectedBuilderJob()+1)+"/"+t.builderJobs().size(),
                    18,318,TEXT,false);
            g.text(font,"WAREHOUSE",18,339,MUTED,false);
            String warehouse=t.warehouse()==null?"NONE":
                    (t.builderWarehouseName().isBlank()?
                            t.warehouse().x()+", "+t.warehouse().y()+", "+t.warehouse().z():
                            t.builderWarehouseName());
            g.text(font,shorten(warehouse,32),18,354,
                    t.warehouse()==null?RED:GREEN,false);
            g.text(font,"SITE CONTAINER",18,374,MUTED,false);
            g.text(font,t.builderMaterialStorage()==null?"NOT SELECTED":
                    t.builderMaterialStorage().toShortString(),18,388,TEXT,false);
        }
        if(page==Page.PLACEMENTS){
            g.text(font,"SELECT AN ACTIVE LITEMATICA PLACEMENT",SIDE+15,80,TEXT,false);
            g.text(font,"Imported snapshot, origin and materials are saved to this job block.",
                    SIDE+15,118,MUTED,false);
            if(choices.isEmpty())g.text(font,"No Litematica placement loaded; refresh.",
                    SIDE+15,161,YELLOW,false);
            if(!notice.isBlank())g.text(font,shorten(notice,90),
                    SIDE+15,panelHeight-12,YELLOW,false);
            return;
        }
        if(t==null)return;
        String name=t.hasPlacedSchematic()?t.placedSchematicName():"NO PLACEMENT SELECTED";
        double percentage=t.builderBlocks()>0
                ?100.0*t.builderCursor()/t.builderBlocks():0;
        g.text(font,shorten("PLACEMENT: "+name,96),SIDE+15,80,TEXT,false);
        String info="Origin "+t.builderOrigin().toShortString()+
                " | "+t.builderWorkerIds().size()+" builders"+
                " | "+String.format(java.util.Locale.ROOT,"%.1f",percentage)+"%"+
                " | "+t.phase().name();
        g.text(font,shorten(info,100),SIDE+15,113,MUTED,false);
        var rows=materials();
        int column=columns(),size=pageSize();
        scroll=Math.min(scroll,Math.max(0,(rows.size()-1)/column));
        int first=scroll*column;
        for(int i=first;i<Math.min(rows.size(),first+size);i++){
            int n=i-first,px=SIDE+22+(n%column)*76,py=139+(n/column)*62;
            var entry=rows.get(i);
            Identifier id=Identifier.tryParse(entry.getKey());
            if(id==null||!BuiltInRegistries.ITEM.containsKey(id))continue;
            Item item=BuiltInRegistries.ITEM.getValue(id);
            int shade=color(t,entry.getKey(),entry.getValue());
            g.fill(px-3,py-3,px+42,py+43,0xff344851);
            g.fill(px-2,py+39,px+41,py+41,shade);
            g.item(new ItemStack(item),px+10,py+4);
            long pending=Math.max(0,entry.getValue()
                    -t.builderStagedStock().getOrDefault(entry.getKey(),0));
            String caption=pending>=10000?""+(pending/1000)+"k":""+pending;
            g.text(font,caption,px+8,py+27,shade,false);
        }
        if(rows.isEmpty())g.text(font,t.hasPlacedSchematic()?
                "No outstanding materials, or adjust your search.":
                "Click + NEW JOB to select and import a Litematica placement.",
                SIDE+19,156,YELLOW,false);
        g.text(font,"MATERIALS "+rows.size()+" | SCROLL "+(scroll+1)+"/"+
                Math.max(1,(rows.size()+column-1)/column),SIDE+18,panelHeight-119,MUTED,false);
        g.text(font,"WHITE unchecked   GREEN enough   YELLOW partial   RED none",
                SIDE+16,panelHeight-15,TEXT,false);
        if(!t.problem().isBlank())g.text(font,shorten("STATUS: "+t.problem(),93),
                SIDE+16,panelHeight-128,YELLOW,false);
        else if(!notice.isBlank())g.text(font,shorten(notice,93),
                SIDE+16,panelHeight-128,MUTED,false);
    }
    private static String shorten(String s,int max){
        return s.length()<=max?s:s.substring(0,Math.max(0,max-1))+"…";
    }
}
