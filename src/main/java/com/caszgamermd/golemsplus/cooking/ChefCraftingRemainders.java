package com.caszgamermd.golemsplus.cooking;

import java.lang.reflect.Method;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Return genuine crafting remainders, including modded utensils. */
public final class ChefCraftingRemainders {
    private ChefCraftingRemainders(){}
    public static boolean isVirtualMilkCandidate(Item item){
        return new ItemStack(item).is(KitchenMilkSupply.MILK_ITEMS);
    }
    public static ItemStack forIngredient(Item item){
        ItemStack ingredient=new ItemStack(item);
        // Modern component-driven recipes (including many modded pots,
        // pans and reusable kitchen vessels) declare the remainder on
        // ItemStack rather than directly on the Item class.
        for(String name:new String[]{"getCraftingRemainder","getRecipeRemainder"}){
            try{
                Method getter=ingredient.getClass().getMethod(name);
                Object result=getter.invoke(ingredient);
                if(result instanceof ItemStack stack&&!stack.isEmpty())
                    return stack.copy();
                if(result instanceof Item leftover&&leftover!=Items.AIR)
                    return new ItemStack(leftover);
            }catch(ReflectiveOperationException|RuntimeException ignored){}
        }
        try{
            var field=net.minecraft.core.component.DataComponents.class
                    .getField("CRAFTING_REMAINDER");
            Object key=field.get(null);
            if(key instanceof net.minecraft.core.component.DataComponentType<?> type){
                Object remainder=ingredient.get(type);
                if(remainder instanceof ItemStack stack&&!stack.isEmpty())
                    return stack.copy();
                if(remainder instanceof Item leftover&&leftover!=Items.AIR)
                    return new ItemStack(leftover);
            }
        }catch(ReflectiveOperationException|RuntimeException ignored){}
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
