package com.caszgamermd.golemsplus.cooking;

import java.lang.reflect.*;
import java.util.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.crafting.*;

/** Bottom-up recipes: consumes real leaf ingredients and records each step.
 * Unresolvable recipes are rejected, never synthesized from nothing. */
public final class ChefRecipePlanner {
    public record Plan(Map<Item,Integer> ingredients,List<String> steps,Map<Item,Integer> leftovers){}
    private record Entry(Item result,int count,List<Ingredient> ingredients,String method){}
    private ChefRecipePlanner(){}
    public static Plan plan(ServerLevel level,Item desired,int amount,Map<Item,Integer> stock){
        // An already finished dish may be served without requiring a recipe.
        if(stock.getOrDefault(desired,0)>=amount)
            return new Plan(Map.of(desired,amount),List.of(),Map.of());
        Map<Item,List<Entry>> byResult=new HashMap<>();
        try{
            Object manager=level.getServer().getRecipeManager();
            Field field=manager.getClass().getDeclaredField("recipes");
            field.setAccessible(true);
            RecipeMap recipes=(RecipeMap)field.get(manager);
            for(var type:List.of(RecipeType.CRAFTING,RecipeType.SMELTING,
                    RecipeType.SMOKING,RecipeType.CAMPFIRE_COOKING,RecipeType.BLASTING)){
                @SuppressWarnings({"rawtypes","unchecked"})
                Collection<RecipeHolder<?>> candidates=(Collection)recipes.byType((RecipeType)type);
                for(RecipeHolder<?> holder:candidates){
                    Object recipe=holder.value();
                    ItemStack output;
                    List<Ingredient> ingredients;
                    try{
                        output=output(recipe);
                        if(output.isEmpty())continue;
                        ingredients=ingredients(recipe);
                        if(ingredients.isEmpty())continue;
                    }catch(ReflectiveOperationException|RuntimeException ignored){
                        // A modded recipe can have custom internals; leave it
                        // unsupported without hiding all other food recipes.
                        continue;
                    }
                    String method=type==RecipeType.CRAFTING?"Assemble":
                            type==RecipeType.SMOKING?"Smoke":
                            type==RecipeType.CAMPFIRE_COOKING?"Campfire":
                            type==RecipeType.BLASTING?"Blast":"Cook";
                    byResult.computeIfAbsent(output.getItem(),k->new ArrayList<>())
                            .add(new Entry(output.getItem(),output.getCount(),ingredients,method));
                }
            }
        }catch(ReflectiveOperationException | RuntimeException ex){return null;}
        Mutable m=new Mutable(stock,byResult);
        if(!m.resolve(desired,amount,new HashSet<>(),0))return null;
        return new Plan(Map.copyOf(m.used),List.copyOf(m.steps),Map.copyOf(m.crafted));
    }
    private static final class Mutable{
        final Map<Item,Integer> remaining,used=new HashMap<>(),crafted=new HashMap<>();
        final Map<Item,List<Entry>> recipes;
        final List<String> steps=new ArrayList<>();
        Mutable(Map<Item,Integer> stock,Map<Item,List<Entry>> recipes){
            remaining=new HashMap<>(stock);this.recipes=recipes;
        }
        boolean resolve(Item item,int count,Set<Item> visiting,int depth){
            if(count<=0)return true;
            if(depth>12||steps.size()>160||visiting.contains(item))return false;
            // Prefer previously crafted surplus before taking a single item
            // from actual kitchen storage. Crafted intermediates are not
            // charged against the pantry reservation a second time.
            int fromCrafted=Math.min(count,crafted.getOrDefault(item,0));
            if(fromCrafted>0)crafted.put(item,crafted.get(item)-fromCrafted);
            count-=fromCrafted;
            int fromStorage=Math.min(count,remaining.getOrDefault(item,0));
            remaining.put(item,remaining.getOrDefault(item,0)-fromStorage);
            if(fromStorage>0)used.merge(item,fromStorage,Integer::sum);
            int shortfall=count-fromStorage;
            if(shortfall==0)return true;
            // A saved snapshot is required because alternative recipes can
            // consume different supplies before being rejected.
            var savedRemaining=new HashMap<>(remaining);
            var savedUsed=new HashMap<>(used);
            var savedCrafted=new HashMap<>(crafted);
            int stepSize=steps.size();
            visiting.add(item);
            for(Entry recipe:recipes.getOrDefault(item,List.of())){
                int crafts=(shortfall+recipe.count-1)/recipe.count;
                if(crafts>64)continue;
                boolean ok=true;
                for(Ingredient ingredient:recipe.ingredients){
                    // First consider a stocked option, then every recipe
                    // output. Prevent cycles for tagged alternatives.
                    Set<Item> options=new LinkedHashSet<>();
                    for(Item candidate:crafted.keySet()){
                        if(crafted.get(candidate)>0&&ingredient.test(new ItemStack(candidate)))options.add(candidate);
                    }
                    for(Item candidate:remaining.keySet()){
                        if(ingredient.test(new ItemStack(candidate)))options.add(candidate);
                    }
                    for(Item candidate:recipes.keySet()){
                        if(ingredient.test(new ItemStack(candidate)))options.add(candidate);
                    }
                    if(options.isEmpty()){ok=false;break;}
                    boolean ingredientFound=false;
                    var preRemaining=new HashMap<>(remaining);
                    var preUsed=new HashMap<>(used);
                    var preCrafted=new HashMap<>(crafted);
                    int preSteps=steps.size();
                    for(Item candidate:options){
                        if(resolve(candidate,crafts,visiting,depth+1)){
                            ingredientFound=true;break;
                        }
                        remaining.clear();remaining.putAll(preRemaining);
                        used.clear();used.putAll(preUsed);
                        crafted.clear();crafted.putAll(preCrafted);
                        while(steps.size()>preSteps)steps.removeLast();
                    }
                    if(!ingredientFound){ok=false;break;}
                }
                if(ok){
                    steps.add(recipe.method+" "+crafts+" × "+BuiltInRegistries.ITEM.getKey(item));
                    int surplus=crafts*recipe.count-shortfall;
                    if(surplus>0)crafted.merge(item,surplus,Integer::sum);
                    visiting.remove(item);
                    return true;
                }
                remaining.clear();remaining.putAll(savedRemaining);
                used.clear();used.putAll(savedUsed);
                crafted.clear();crafted.putAll(savedCrafted);
                while(steps.size()>stepSize)steps.removeLast();
            }
            visiting.remove(item);
            return false;
        }
    }
    private static ItemStack output(Object recipe)throws ReflectiveOperationException{
        Class<?> cls=recipe.getClass();
        while(cls!=null){
            try{
                Field f=cls.getDeclaredField("result");f.setAccessible(true);
                Object value=f.get(recipe);
                if(value instanceof ItemStackTemplate template)return template.create();
                if(value instanceof ItemStack stack)return stack.copy();
            }catch(NoSuchFieldException ignored){}
            cls=cls.getSuperclass();
        }
        return ItemStack.EMPTY;
    }
    @SuppressWarnings("unchecked")
    private static List<Ingredient> ingredients(Object recipe)throws ReflectiveOperationException{
        if(recipe instanceof AbstractCookingRecipe cooking)return List.of(cooking.input());
        if(recipe instanceof ShapedRecipe shaped)
            return shaped.getIngredients().stream().flatMap(java.util.Optional::stream).toList();
        Object value=null;Class<?> cls=recipe.getClass();
        while(cls!=null&&value==null){
            try{
                Field f=cls.getDeclaredField("ingredients");f.setAccessible(true);
                value=f.get(recipe);
            }catch(NoSuchFieldException ignored){}
            cls=cls.getSuperclass();
        }
        if(!(value instanceof List<?> list))return List.of();
        List<Ingredient> items=new ArrayList<>();
        for(Object item:list){
            if(item instanceof Ingredient ingredient)items.add(ingredient);
            else if(item instanceof Optional<?> optional
                    &&optional.orElse(null) instanceof Ingredient ingredient)
                items.add(ingredient);
        }
        return items;
    }
}
