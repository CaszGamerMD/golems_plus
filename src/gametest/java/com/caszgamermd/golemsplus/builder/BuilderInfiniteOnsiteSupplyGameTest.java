package com.caszgamermd.golemsplus.builder;

import java.util.List;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/** Verifies the test supply chest actually contains physical item stacks
 * and replenishes after an ordinary inventory withdrawal.
 */
public final class BuilderInfiniteOnsiteSupplyGameTest {
    @GameTest(maxTicks=100,skyAccess=true)
    public void refillsRealOnsiteChest(GameTestHelper helper){
        BlockPos local=new BlockPos(2,2,2);
        helper.setBlock(2,1,2,Blocks.STONE);
        helper.setBlock(2,2,2,Blocks.CHEST);
        BlockPos depot=helper.absolutePos(local);
        if(!(helper.getLevel().getBlockEntity(depot) instanceof Container chest)){
            helper.fail("Test OnSite chest has no real inventory");
            return;
        }
        var materials=List.of(Items.COBBLESTONE,Items.SPRUCE_FENCE,
                Items.SCAFFOLDING);
        if(BuilderInfiniteOnsiteSupply.restock(helper.getLevel(),depot,materials)!=3){
            helper.fail("Test chest did not stock all three requested materials");
            return;
        }
        int slot=-1;
        for(int i=0;i<chest.getContainerSize();i++)
            if(chest.getItem(i).is(Items.COBBLESTONE)){slot=i;break;}
        if(slot<0||chest.getItem(slot).getCount()!=64){
            helper.fail("Expected a REAL 64-item cobblestone stack");
            return;
        }
        chest.removeItem(slot,17); // Simulate a Builder withdrawing stock.
        if(chest.getItem(slot).getCount()!=47){
            helper.fail("Withdrawal failed; inventory isn't real");
            return;
        }
        BuilderInfiniteOnsiteSupply.restock(helper.getLevel(),depot,materials);
        if(chest.getItem(slot).getCount()!=64){
            helper.fail("Infinite OnSite chest did not replenish withdrawn items");
            return;
        }
        helper.succeed();
    }
}
