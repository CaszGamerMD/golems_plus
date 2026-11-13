package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorksiteContent;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;

public final class BuilderRailKitGameTest {
    @GameTest(structure="golems_plus_gametest:dome_arena",maxTicks=180,skyAccess=true)
    public void fullPouchAndFullChestStillStageRailKit(GameTestHelper h){
        var level=h.getLevel();
        for(int x=2;x<=12;x++)for(int z=2;z<=12;z++){
            h.setBlock(x,1,z,Blocks.STONE);
            for(int y=2;y<=5;y++)h.setBlock(x,y,z,Blocks.AIR);
        }
        h.setBlock(4,2,4,WorksiteContent.BUILDER_TABLE);
        h.setBlock(5,2,4,Blocks.CHEST);
        BlockPos home=h.absolutePos(new BlockPos(4,2,4));
        BlockPos depot=h.absolutePos(new BlockPos(5,2,4));
        BlockPos start=h.absolutePos(new BlockPos(5,2,10));
        BlockPos target=h.absolutePos(new BlockPos(9,2,7));
        var table=(WorkerStationBlockEntity)level.getBlockEntity(home);
        var chest=(Container)level.getBlockEntity(depot);
        var owner=h.makeMockServerPlayerInLevel();
        if(!table.claimIfUnowned(owner.getUUID())
                ||!table.setCrewTarget(owner.getUUID(),1)
                ||!table.setPlacedBuilderPlan(owner.getUUID(),"placed-rail-kit-test.gpb",
                        "Rail kit",home,0,0,1,0)
                ||!table.setBuilderMaterialStorage(owner.getUUID(),depot)){
            h.fail("Rail kit planner setup failed");return;
        }
        var worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,level);
        worker.setPos(start.getX()+.5,start.getY(),start.getZ()+.5);
        worker.setItem(0,new ItemStack(Items.ACTIVATOR_RAIL,2));
        for(int i=1;i<9;i++)worker.setItem(i,new ItemStack(Items.SMOOTH_STONE,64));
        for(int i=0;i<chest.getContainerSize();i++)chest.setItem(i,new ItemStack(Items.GLASS,64));
        chest.setItem(0,new ItemStack(Items.COBBLESTONE,64));
        level.addFreshEntity(worker);
        if(!table.registerBuilderWorker(worker.getUUID())){
            h.fail("Rail kit worker registration failed");return;
        }
        h.onEachTick(()->{
            if(h.getTick()<5||h.getTick()%5!=0)return;
            var result=BuilderSupplyInventory.loadRailKit(level,worker,table,
                    Items.ACTIVATOR_RAIL,2,true,null);
            if(result==BuilderSupplyInventory.LoadStatus.READY){
                if(!BuilderReturnService.withinStorageReach(worker,depot)
                        ||worker.carriedCount(Items.ACTIVATOR_RAIL)!=2
                        ||worker.carriedCount(Items.COBBLESTONE)<1){
                    h.fail("Kit was not staged at real OnSite Storage");return;
                }
                int stone=worker.carriedCount(Items.SMOOTH_STONE);
                int cobble=worker.carriedCount(Items.COBBLESTONE);
                int glass=worker.carriedCount(Items.GLASS);
                for(int i=0;i<chest.getContainerSize();i++){
                    var stack=chest.getItem(i);
                    if(stack.is(Items.SMOOTH_STONE))stone+=stack.getCount();
                    if(stack.is(Items.COBBLESTONE))cobble+=stack.getCount();
                    if(stack.is(Items.GLASS))glass+=stack.getCount();
                }
                if(stone!=512||cobble!=64||glass!=26*64){
                    h.fail("Full chest exchange lost or duplicated materials: "+stone+" "+cobble+" "+glass);return;
                }
                System.out.println("[RAIL KIT FULL PASS] real depot walk; two rails plus footing; conserved all stocks");
                h.succeed();return;
            }
            if(h.getTick()==5&&worker.carriedCount(Items.COBBLESTONE)!=0){
                h.fail("Remote rail kit draw");return;
            }
            if(h.getTick()>=170)h.fail("Full inventory rail kit stalled: "+result+" at "+worker.position());
        });
    }
    @GameTest(maxTicks=450,skyAccess=true)
    public void realBuilderPaysForSlopeAndRecoversConnector(GameTestHelper h) throws java.io.IOException{
        new BuilderRailTrafficGameTest().builderPaysForRampAndRecoversJig(h);
    }
}
