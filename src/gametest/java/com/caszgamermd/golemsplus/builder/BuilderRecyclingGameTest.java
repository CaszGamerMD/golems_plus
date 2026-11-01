package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.UnemployedCenterBlockEntity;
import com.caszgamermd.golemsplus.world.WorksiteContent;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Recycling erases worker identity but not profession or item count. */
public final class BuilderRecyclingGameTest {
    @GameTest(maxTicks=60,skyAccess=true)
    public void namedWorkersBecomeStackableGenericItems(GameTestHelper h){
        h.setBlock(1,2,1,WorksiteContent.UNEMPLOYED_CENTER);
        var pos=h.absolutePos(new BlockPos(1,2,1));
        if(!(h.getLevel().getBlockEntity(pos)
                instanceof UnemployedCenterBlockEntity bin)){
            h.fail("No recycling bin");return;
        }
        var first=new ItemStack(WorksiteContent.BUILDER_WORKER_ITEM);
        first.set(DataComponents.CUSTOM_NAME,Component.literal("Sawyer"));
        var second=new ItemStack(WorksiteContent.BUILDER_WORKER_ITEM);
        second.set(DataComponents.CUSTOM_NAME,Component.literal("Mortar Mike"));
        if(!bin.acceptWorker(first)||!bin.acceptWorker(second)){
            h.fail("Bin rejected named Builder workers");return;
        }
        var stored=bin.getItem(0);
        if(stored.getCount()!=2
                ||!stored.is(WorksiteContent.BUILDER_WORKER_ITEM)
                ||stored.has(DataComponents.CUSTOM_NAME)
                ||!bin.getItem(1).isEmpty()){
            h.fail("Recycled Builder items retained names or failed to stack");
            return;
        }
        var chef=new ItemStack(WorksiteContent.CHEF_WORKER_ITEM);
        chef.set(DataComponents.CUSTOM_NAME,Component.literal("Chef"));
        if(!bin.acceptWorker(chef)
                ||!bin.getItem(1).is(WorksiteContent.CHEF_WORKER_ITEM)
                ||bin.getItem(1).has(DataComponents.CUSTOM_NAME)){
            h.fail("Recycling lost profession or retained name");return;
        }
        h.succeed();
    }
}
