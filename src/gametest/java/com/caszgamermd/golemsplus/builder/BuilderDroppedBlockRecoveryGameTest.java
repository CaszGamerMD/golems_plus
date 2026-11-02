package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorksiteContent;
import com.caszgamermd.golemsplus.world.WorkerStationBlockEntity;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/** Real entity pickup, physically returning to OnSite, and no duplication. */
public final class BuilderDroppedBlockRecoveryGameTest {
    @GameTest(maxTicks=360,skyAccess=true)
    public void idleBuilderPicksUpDroppedBlocksAndDepositsAtOnsite(
            GameTestHelper h){
        ServerLevel world=h.getLevel();
        for(int x=0;x<9;x++)for(int z=0;z<9;z++)
            h.setBlock(x,1,z,Blocks.STONE);
        h.setBlock(0,2,0,WorksiteContent.BUILDER_TABLE);
        h.setBlock(1,2,0,Blocks.CHEST);
        BlockPos tablePos=h.absolutePos(new BlockPos(0,2,0));
        BlockPos onsite=h.absolutePos(new BlockPos(1,2,0));
        var table=(WorkerStationBlockEntity)world.getBlockEntity(tablePos);
        var owner=h.makeMockServerPlayerInLevel();
        if(table==null||!table.claimIfUnowned(owner.getUUID())
                ||!table.setPlacedBuilderPlan(owner.getUUID(),
                        "placed-drop-collection-test.litematic","Cleanup",
                        tablePos,0,0,8,0)
                ||!table.setBuilderMaterialStorage(owner.getUUID(),onsite)){
            h.fail("Could not prepare OnSite drop storage");return;
        }
        BuilderGolem worker=new BuilderGolem(WorksiteContent.BUILDER_GOLEM,world);
        BlockPos spawn=h.absolutePos(new BlockPos(2,2,2));
        worker.setPos(spawn.getX()+.5,spawn.getY(),spawn.getZ()+.5);
        world.addFreshEntity(worker);
        BlockPos dropped=h.absolutePos(new BlockPos(6,2,3));
        ItemEntity stack=new ItemEntity(world,
                dropped.getX()+.5,dropped.getY()+.2,dropped.getZ()+.5,
                new ItemStack(Items.STONE,8));
        world.addFreshEntity(stack);
        var footprint=new BuilderZones.Layout(tablePos.getX(),
                tablePos.getX()+8,tablePos.getZ(),tablePos.getZ()+8);
        h.onEachTick(()->{
            if(h.getTick()<8||h.getTick()%5!=0)return;
            BuilderDroppedBlockRecovery.tick(world,worker,table,footprint);
            if(world.getBlockEntity(onsite) instanceof ChestBlockEntity chest
                    &&chest.getItem(0).getCount()==8
                    &&chest.getItem(0).is(Items.STONE)
                    &&!stack.isAlive()&&!worker.hasRecoveredDrops()){
                System.out.println("[BUILDER DROP PICKUP] PASS 8 real blocks "
                        +"retrieved and deposited once at OnSite");
                h.succeed();return;
            }
            if(h.getTick()>=320)
                h.fail("Dropped block pickup stalled feet="+worker.position()
                        +" cargo="+worker.recoveredDrops()
                        +" itemAlive="+stack.isAlive());
        });
    }
}
