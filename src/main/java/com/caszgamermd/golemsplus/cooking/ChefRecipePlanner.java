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
    /** Explains the work tree and which terminal ingredients the kitchen lacks. */
    public record Preview(Map<Item,Integer> available,Map<Item,Integer> missing,List<String> steps){}
    public static Preview preview(ServerLevel level,Item target,int count,Map<Item,Integer> available){
        var result=solve(level,target,count,available,true);
        if(result==null)return null;
        return new Preview(Map.copyOf(result.used),Map.copyOf(result.missing),
                List.copyOf(result.steps));
    }
    private record Entry(Item result,int count,List<Ingredient> ingredients,String method){}
    private ChefRecipePlanner(){}
    public static Plan plan(ServerLevel level,Item desired,int amount,Map<Item,Integer> stock){
        Mutable m=solve(level,desired,amount,stock,false);
        if(m==null)return null;
        return new Plan(Map.copyOf(m.used),List.copyOf(m.steps),Map.copyOf(m.crafted));
    }
    private static Mutable solve(ServerLevel level,Item desired,int amount,
                                 Map<Item,Integer> stock,boolean inspection){
        if(amount<1||amount>16)return null;
        if(stock.getOrDefault(desired,0)>=amount){
            Mutable ready=new Mutable(stock,inspection);
            return new Search(Map.of()).resolve(ready,desired,amount,new HashSet<>(),0);
        }
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
        // Compare available alternatives and keep one selected recipe path.
        Search search=new Search(byResult);
        return search.resolve(new Mutable(stock,inspection),desired,amount,
                new HashSet<>(),0);
    }

    private static final class Mutable{
        final Map<Item,Integer> remaining,used,crafted,missing;
        final List<String> steps;
        final boolean inspection;
        int operations;
        Mutable(Map<Item,Integer> stock,boolean inspection){
            remaining=new HashMap<>(stock);used=new HashMap<>();
            crafted=new HashMap<>();missing=new HashMap<>();
            steps=new ArrayList<>();this.inspection=inspection;
        }
        Mutable(Mutable other){
            remaining=new HashMap<>(other.remaining);
            used=new HashMap<>(other.used);
            crafted=new HashMap<>(other.crafted);
            missing=new HashMap<>(other.missing);
            steps=new ArrayList<>(other.steps);
            inspection=other.inspection;operations=other.operations;
        }
        long score(){
            // Missing raw goods are hugely worse than a few additional
            // assembly steps. Within equally stocked choices, minimize
            // machine operations and materials taken from the pantry.
            long raw=missing.values().stream().mapToLong(Integer::longValue).sum();
            long drawn=used.values().stream().mapToLong(Integer::longValue).sum();
            return raw*1_000_000L+(long)operations*100+drawn*3+steps.size();
        }
    }

    private static final class Search{
        private static final int MAX_DEPTH=10, MAX_BRANCHES=14, MAX_CHOICES=20;
        private final Map<Item,List<Entry>> recipes;
        private int attempts;
        Search(Map<Item,List<Entry>> recipes){this.recipes=recipes;}

        Mutable resolve(Mutable before,Item desired,int quantity,
                        Set<Item> visiting,int depth){
            if(quantity<=0)return before;
            if(depth>MAX_DEPTH||before.steps.size()>140||attempts++>3500
                    ||visiting.contains(desired))return null;
            Mutable start=new Mutable(before);
            int made=Math.min(quantity,start.crafted.getOrDefault(desired,0));
            if(made>0)start.crafted.merge(desired,-made,Integer::sum);
            int unmet=quantity-made;
            int stocked=Math.min(unmet,start.remaining.getOrDefault(desired,0));
            if(stocked>0){
                start.remaining.merge(desired,-stocked,Integer::sum);
                start.used.merge(desired,stocked,Integer::sum);
            }
            unmet-=stocked;
            if(unmet==0)return start;
            if(depth==MAX_DEPTH){
                if(!start.inspection)return null;
                start.missing.merge(desired,unmet,Integer::sum);
                start.steps.add("RAW "+unmet+" x "+BuiltInRegistries.ITEM.getKey(desired));
                return start;
            }

            HashSet<Item> occupied=new HashSet<>(visiting);
            occupied.add(desired);
            Mutable best=null;
            // Deterministic ordering. There may be dozens of recipes for
            // common modded food; score the alternatives but don't execute
            // or consume all of them.
            List<Entry> alternatives=recipes.getOrDefault(desired,List.of());
            for(int ri=0;ri<Math.min(alternatives.size(),MAX_BRANCHES);ri++){
                Entry recipe=alternatives.get(ri);
                int times=(unmet+recipe.count-1)/recipe.count;
                if(times<1||times>64||recipe.ingredients.size()>40)continue;
                Mutable trial=new Mutable(start);
                boolean possible=true;
                for(Ingredient ingredient:recipe.ingredients){
                    List<Item> options=new ArrayList<>();
                    Set<Item> all=new HashSet<>(recipes.keySet());
                    all.addAll(trial.remaining.keySet());
                    all.addAll(trial.crafted.keySet());
                    for(Item item:all)
                        if(ingredient.test(new ItemStack(item)))options.add(item);
                    // Stocked options first; the remaining options are still
                    // scored against their recursive crafting requirements.
                    final Mutable optionStock=trial;
                    options.sort(Comparator
                            .<Item>comparingInt(item->optionStock.remaining.getOrDefault(item,0)
                                    +optionStock.crafted.getOrDefault(item,0)>0?0:1)
                            .thenComparing(item->BuiltInRegistries.ITEM.getKey(item).toString()));
                    Mutable selected=null;
                    int tried=0;
                    for(Item option:options){
                        if(tried++>=MAX_CHOICES)break;
                        Mutable variant=resolve(trial,option,times,occupied,depth+1);
                        if(variant!=null&&(selected==null||variant.score()<selected.score()))
                            selected=variant;
                        // A stocked ingredient is optimal for this
                        // ingredient if its use incurs no new recipe steps.
                        if(selected!=null && selected.steps.size()==trial.steps.size()
                                &&selected.missing.equals(trial.missing))break;
                    }
                    if(selected==null){
                        possible=false;break;
                    }
                    trial=selected;
                }
                if(!possible)continue;
                trial.steps.add(recipe.method+" "+times+" x "+
                        BuiltInRegistries.ITEM.getKey(desired));
                trial.operations+=times;
                int extra=times*recipe.count-unmet;
                if(extra>0)trial.crafted.merge(desired,extra,Integer::sum);
                if(best==null||trial.score()<best.score()
                        ||trial.score()==best.score()&&
                        trial.steps.toString().compareTo(best.steps.toString())<0)
                    best=trial;
            }
            // For the preview, show missing raw ingredients when no known
            // recipe can satisfy the shortage. Do not ever use this path
            // for an actual order.
            if(best==null&&before.inspection){
                Mutable missing=new Mutable(start);
                missing.missing.merge(desired,unmet,Integer::sum);
                missing.steps.add("RAW "+unmet+" x "+BuiltInRegistries.ITEM.getKey(desired));
                return missing;
            }
            return best;
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
