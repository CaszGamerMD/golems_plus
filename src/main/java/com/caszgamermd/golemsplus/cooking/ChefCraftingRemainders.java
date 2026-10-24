package com.caszgamermd.golemsplus.cooking;

import java.lang.reflect.Method;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Return genuine crafting remainders, including modded utensils. */
public final class ChefCraftingRemainders {
    private ChefCraftingRemainders(){}
    public static ItemStack forIngredient(Item item){
        for(String name:new String[]{"getCraftingRemainder","getRecipeRemainder"}){
            try{
                Method getter=item.getClass().getMethod(name);
                Object result=getter.invoke(item);
                if(result instanceof ItemStack stack&&!stack.isEmpty())
                    return stack.copy();
                if(result instanceof Item leftover&&leftover!=Items.AIR)
                    return new ItemStack(leftover);
            }catch(ReflectiveOperationException|RuntimeException ignored){}
        }
        if(item==Items.MILK_BUCKET||item==Items.WATER_BUCKET
                ||item==Items.LAVA_BUCKET)return new ItemStack(Items.BUCKET);
        if(item==Items.HONEY_BOTTLE)return new ItemStack(Items.GLASS_BOTTLE);
        return ItemStack.EMPTY;
    }
}
