package com.caszgamermd.golemsplus.builder;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Test-world ONLY material dispenser.
 * This is deliberately not a creative/survival block in the playable mod.
 * A normal chest placed at the linked OnSite depot is replenished with
 * REAL stacks on the server. Builders must still physically walk to that
 * container, extract items into their nine-slot pouch, and spend them
 * during ordinary construction.
 *
 * The caller passes materials for the workers' current assigned steps,
 * not the entire build palette. This keeps even 100-material schematics
 * within a 27-slot chest while never fabricating items inside a golem.
 */
public final class BuilderInfiniteOnsiteSupply {
    private BuilderInfiniteOnsiteSupply(){}

    public static int restock(ServerLevel world,BlockPos depot,
                              Collection<Item> requested){
        if(!world.hasChunkAt(depot)
                ||!(world.getBlockEntity(depot) instanceof Container chest))
            return 0;
        Set<Item> wanted=new LinkedHashSet<>();
        for(Item item:requested)if(item!=null&&item!=Items.AIR)
            wanted.add(item);
        // Stale items in a TEST crate can be replaced as the build advances.
        // Live survival OnSite inventories never use this method.
        for(int slot=0;slot<chest.getContainerSize();slot++){
            ItemStack old=chest.getItem(slot);
            if(!old.isEmpty()&&!wanted.contains(old.getItem()))
                chest.setItem(slot,ItemStack.EMPTY);
        }
        int stocked=0;
        for(Item item:wanted){
            int slot=-1;
            for(int i=0;i<chest.getContainerSize();i++)
                if(chest.getItem(i).is(item)){slot=i;break;}
            if(slot<0)for(int i=0;i<chest.getContainerSize();i++)
                if(chest.getItem(i).isEmpty()){slot=i;break;}
            if(slot<0)break; // demand selection controls the 27 slots
            ItemStack full=new ItemStack(item);
            full.setCount(full.getMaxStackSize());
            chest.setItem(slot,full);
            stocked++;
        }
        chest.setChanged();
        return stocked;
    }
}
