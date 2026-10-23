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
    public record WorkStep(String method,Item result,int resultCount,Map<Item,Integer> inputs){
        public String encode(){
            StringBuilder s=new StringBuilder(method).append('|')
                    .append(BuiltInRegistries.ITEM.getKey(result))
                    .append('|').append(resultCount).append('|');
            inputs.entrySet().stream()
                    .sorted(Comparator.comparing(e->BuiltInRegistries.ITEM.getKey(e.getKey()).toString()))
                    .forEach(e->s.append(BuiltInRegistries.ITEM.getKey(e.getKey()))
                            .append('*').append(e.getValue()).append(','));
            return s.toString();
        }
        public static WorkStep decode(String serialized){
            String[] fields=serialized.split("\\|",4);
            if(fields.length!=4)throw new IllegalArgumentException("Invalid Chef work step");
            int count=Integer.parseInt(fields[2]);
            if(count<1||count>1024)throw new IllegalArgumentException("Invalid batch size");
            Item result=item(fields[1]);
            Map<Item,Integer> in=new LinkedHashMap<>();
            if(!fields[3].isBlank())
                for(String part:fields[3].split(",")){
                    if(part.isBlank())continue;
                    String[] entry=part.split("\\*",2);
                    if(entry.length!=2)throw new IllegalArgumentException("Invalid ingredient entry");
                    int qty=Integer.parseInt(entry[1]);
                    if(qty<1||qty>1024)throw new IllegalArgumentException("Invalid quantity");
                    in.merge(item(entry[0]),qty,Integer::sum);
                }
            return new WorkStep(fields[0],result,count,Map.copyOf(in));
        }
        private static Item item(String id){
            net.minecraft.resources.Identifier name=net.minecraft.resources.Identifier.tryParse(id);
            if(name==null||!BuiltInRegistries.ITEM.containsKey(name))
                throw new IllegalArgumentException("Unknown ingredient "+id);
            return BuiltInRegistries.ITEM.getValue(name);
        }
        public String label(){
            return method+" "+resultCount+" x "+BuiltInRegistries.ITEM.getKey(result);
        }
    }
    public record Plan(Map<Item,Integer> ingredients,List<String> steps,
                       Map<Item,Integer> leftovers,List<WorkStep> work){}
    /** Explains the work tree and which terminal ingredients the kitchen lacks. */
    public record Preview(Map<Item,Integer> available,Map<Item,Integer> missing,List<String> steps){}
    public static Preview preview(ServerLevel level,Item target,int count,Map<Item,Integer> available){
        return preview(level,target,count,available,true);
    }
    public static Preview preview(ServerLevel level,Item target,int count,
                                  Map<Item,Integer> available,boolean hasOven){
        var result=solve(level,target,count,available,true,hasOven);
        if(result==null)return null;
        return new Preview(Map.copyOf(result.used),Map.copyOf(result.missing),
                List.copyOf(result.steps));
    }
    private record Entry(Item result,int count,List<Ingredient> ingredients,String method){}
    /** A recipe can exist even when the pantry cannot currently fulfill it.
     * The kiosk never equates an unavailable ingredient with a missing recipe.
     */
    public static boolean hasRecipe(ServerLevel level,Item target,boolean hasOven){
        Map<Item,List<Entry>> index=indexRecipes(level,hasOven);
        return index.containsKey(target);
    }
    private ChefRecipePlanner(){}
    public static Plan plan(ServerLevel level,Item desired,int amount,Map<Item,Integer> stock){
        return plan(level,desired,amount,stock,true);
    }
    public static Plan plan(ServerLevel level,Item desired,int amount,
                            Map<Item,Integer> stock,boolean hasOven){
        Mutable m=solve(level,desired,amount,stock,false,hasOven);
        if(m==null)return null;
        return new Plan(Map.copyOf(m.used),List.copyOf(m.steps),
                Map.copyOf(m.crafted),List.copyOf(m.work));
    }
    private static Mutable solve(ServerLevel level,Item desired,int amount,
                                 Map<Item,Integer> stock,boolean inspection,boolean hasOven){
        if(amount<1||amount>16)return null;
        // A completed meal may be served directly ONLY when it physically
        // exists in the linked kitchen's inventory snapshot.
        if(stock.getOrDefault(desired,0)>=amount){
            Mutable ready=new Mutable(stock,inspection);
            return new Search(Map.of()).resolve(ready,desired,amount,new HashSet<>(),0);
        }
        Map<Item,List<Entry>> byResult=indexRecipes(level,hasOven);
        // Compare available alternatives and keep one selected recipe path.
        Search search=new Search(byResult);
        return search.resolve(new Mutable(stock,inspection),desired,amount,
                new HashSet<>(),0);
    }

    private static Map<Item,List<Entry>> indexRecipes(ServerLevel level,boolean hasOven){
        Map<Item,List<Entry>> byResult=new HashMap<>();
        try{
            Object manager=level.getServer().getRecipeManager();
            Field field=manager.getClass().getDeclaredField("recipes");
            field.setAccessible(true);
            RecipeMap recipes=(RecipeMap)field.get(manager);
            // The linked CFB oven supports normal smelting, smoking and
            // campfire recipes, but NOT the blast-furnace recipe category.
            // Do not rank recipe paths requiring absent equipment.
            for(var type:List.of(RecipeType.CRAFTING,RecipeType.SMELTING,
                    RecipeType.SMOKING,RecipeType.CAMPFIRE_COOKING)){
                if(type!=RecipeType.CRAFTING&&!hasOven)continue;
                @SuppressWarnings({"rawtypes","unchecked"})
                Collection<RecipeHolder<?>> candidates=(Collection)recipes.byType((RecipeType)type);
                for(RecipeHolder<?> holder:candidates){
                    Object recipe=holder.value();
                    ItemStack output;
                    List<Ingredient> ingredients;
                    try{
                        try{output=output(recipe);}
                        catch(ReflectiveOperationException|RuntimeException ignored){
                            output=ItemStack.EMPTY;
                        }
                        // Some modded crafting recipes use inaccessible
                        // result templates. Recipes sharing the exact item
                        // ID are still indexable for normal crafting inputs.
                        if(output.isEmpty())output=outputByRecipeId(holder);
                        if(output.isEmpty())continue;
                        ingredients=ingredients(recipe);
                        if(ingredients.isEmpty())continue;
                    }catch(ReflectiveOperationException|RuntimeException ignored){
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
        }catch(ReflectiveOperationException | RuntimeException ex){
            return Map.of();
        }
        return byResult;
    }

    private static final class Mutable{
        final Map<Item,Integer> remaining,used,crafted,missing;
        final List<String> steps;
        final List<WorkStep> work;
        final boolean inspection;
        int operations;
        Mutable(Map<Item,Integer> stock,boolean inspection){
            remaining=new HashMap<>(stock);used=new HashMap<>();
            crafted=new HashMap<>();missing=new HashMap<>();
            steps=new ArrayList<>();work=new ArrayList<>();this.inspection=inspection;
        }
        Mutable(Mutable other){
            remaining=new HashMap<>(other.remaining);
            used=new HashMap<>(other.used);
            crafted=new HashMap<>(other.crafted);
            missing=new HashMap<>(other.missing);
            steps=new ArrayList<>(other.steps);work=new ArrayList<>(other.work);
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
        private static final int MAX_DEPTH=14, MAX_BRANCHES=28, MAX_CHOICES=48;
        private final Map<Item,List<Entry>> recipes;
        private final Map<Ingredient,List<Item>> knownAlternatives=new IdentityHashMap<>();
        private int attempts;
        private List<Item> optionsFor(Ingredient ingredient){
            // The 26.2 Ingredient API has no public getValues() on Fabric.
            // Expand tag and exact-item entries from the authoritative
            // registry once per Ingredient, then memoize them for this
            // request. Unlike the old recipes.keySet() shortcut this
            // includes Croptopia raw beef, grains, and other leaf inputs.
            return knownAlternatives.computeIfAbsent(ingredient,key->
                    BuiltInRegistries.ITEM.stream()
                            .filter(item->key.test(new ItemStack(item)))
                            .sorted(Comparator.comparing(
                                    item->BuiltInRegistries.ITEM.getKey(item).toString()))
                            .toList());
        }
        Search(Map<Item,List<Entry>> recipes){this.recipes=recipes;}

        Mutable resolve(Mutable before,Item desired,int quantity,
                        Set<Item> visiting,int depth){
            if(quantity<=0)return before;
            if(depth>MAX_DEPTH||before.steps.size()>140||attempts++>80000
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
                Map<Item,Integer> chosenIngredients=new LinkedHashMap<>();
                boolean possible=true;
                for(Ingredient ingredient:recipe.ingredients){
                    List<Item> options=new ArrayList<>();
                    // Tag ingredients often contain raw items that have no
                    // recipe and are not yet in the kitchen. The old resolver
                    // only enumerated stocked/craftable items. For Croptopia's
                    // cheeseburger that made e.g. beef, salt, and crops
                    // invisible, rejected an otherwise valid cheeseburger
                    // recipe, and misleadingly displayed the ENTIRE burger
                    // as a missing RAW ingredient.
                    for(Item item:optionsFor(ingredient))
                        if(!options.contains(item))options.add(item);
                    // Some custom modded ingredients accept items outside
                    // their advertised vanilla holder set.
                    for(Item item:trial.remaining.keySet())
                        if(ingredient.test(new ItemStack(item))&&!options.contains(item))
                            options.add(item);
                    if(options.isEmpty())for(Item item:recipes.keySet())
                        if(ingredient.test(new ItemStack(item))&&!options.contains(item))
                            options.add(item);
                    // Stocked options first; the remaining options are still
                    // scored against their recursive crafting requirements.
                    final Mutable optionStock=trial;
                    options.sort(Comparator
                            .<Item>comparingInt(item->optionStock.remaining.getOrDefault(item,0)
                                    +optionStock.crafted.getOrDefault(item,0)>0?0:1)
                            // Favor a known craftable intermediary over an
                            // unavailable raw tag variant. Real stock still
                            // always comes first.
                            .thenComparingInt(item->recipes.containsKey(item)?0:1)
                            .thenComparing(item->BuiltInRegistries.ITEM.getKey(item).toString()));
                    Mutable selected=null;
                    Item selectedItem=null;
                    int tried=0;
                    for(Item option:options){
                        if(tried++>=MAX_CHOICES)break;
                        Mutable variant=resolve(trial,option,times,occupied,depth+1);
                        if(variant!=null&&(selected==null||variant.score()<selected.score())){
                            selected=variant;
                            selectedItem=option;
                        }
                        // A stocked ingredient is optimal for this
                        // ingredient if its use incurs no new recipe steps.
                        if(selected!=null&&selected.missing.isEmpty()
                                &&selected.steps.size()<=trial.steps.size()+2)break;
                    }
                    if(selected==null){
                        possible=false;break;
                    }
                    trial=selected;
                    chosenIngredients.merge(selectedItem,times,Integer::sum);
                }
                if(!possible)continue;
                trial.steps.add(recipe.method+" "+times+" x "+
                        BuiltInRegistries.ITEM.getKey(desired));
                trial.work.add(new WorkStep(recipe.method,desired,
                        times*recipe.count,Map.copyOf(chosenIngredients)));
                trial.operations+=times;
                int extra=times*recipe.count-unmet;
                if(extra>0)trial.crafted.merge(desired,extra,Integer::sum);
                if(best==null||trial.score()<best.score()
                        ||trial.score()==best.score()&&
                        trial.steps.toString().compareTo(best.steps.toString())<0)
                    best=trial;
                // Avoid exploring exponentially many alternative recipes
                // once a stock-backed, fully satisfied path exists.
                if(best!=null&&best.missing.isEmpty()&&best.score()<2000)break;
            }
            // For the preview, show missing raw ingredients when no known
            // recipe can satisfy the shortage. Do not ever use this path
            // for an actual order.
            if(best==null&&before.inspection&&depth>0){
                // For preview, this is a supply shortage/unsupported
                // intermediate, not proof that the top-level recipe is
                // missing. Keep showing its parent ingredient chain.
                Mutable missing=new Mutable(start);
                missing.missing.merge(desired,unmet,Integer::sum);
                missing.steps.add("RAW "+unmet+" x "+BuiltInRegistries.ITEM.getKey(desired));
                return missing;
            }
            return best;
        }
    }
    private static ItemStack outputByRecipeId(RecipeHolder<?> holder){
        try{
            Object id=holder.getClass().getMethod("id").invoke(holder);
            Object location=id;
            try{location=id.getClass().getMethod("location").invoke(id);}
            catch(NoSuchMethodException ignored){}
            String raw=location.toString();
            // ResourceKey[... / namespace:path] and ResourceLocation
            // both end in the actual recipe id.
            java.util.regex.Matcher found=java.util.regex.Pattern
                    .compile("([a-z0-9_.-]+:[a-z0-9_./-]+)").matcher(raw);
            String name=null;
            while(found.find())name=found.group(1);
            if(name==null)return ItemStack.EMPTY;
            var idKey=net.minecraft.resources.Identifier.tryParse(name);
            if(idKey!=null&&BuiltInRegistries.ITEM.containsKey(idKey))
                return new ItemStack(BuiltInRegistries.ITEM.getValue(idKey));
        }catch(ReflectiveOperationException|RuntimeException ignored){}
        return ItemStack.EMPTY;
    }
    private static ItemStack output(Object recipe)throws ReflectiveOperationException{
        for(Class<?> cls=recipe.getClass();cls!=null;cls=cls.getSuperclass()){
            for(Field field:cls.getDeclaredFields()){
                // Minecraft 26.2 modded recipe implementations needn't
                // call their result field exactly "result". Use the
                // result's type, not a list of guessed field names.
                if(!ItemStackTemplate.class.isAssignableFrom(field.getType())
                        &&!ItemStack.class.isAssignableFrom(field.getType()))continue;
                try{
                    field.setAccessible(true);
                    ItemStack item=stackFrom(field.get(recipe));
                    if(!item.isEmpty())return item;
                }catch(RuntimeException ignored){}
            }
        }
        for(String method:new String[]{"result","getResultItem","getResult","output"}){
            try{
                Method getter=recipe.getClass().getMethod(method);
                if(getter.getParameterCount()!=0)continue;
                ItemStack item=stackFrom(getter.invoke(recipe));
                if(!item.isEmpty())return item;
            }catch(NoSuchMethodException ignored){}
            catch(RuntimeException ignored){}
        }
        return ItemStack.EMPTY;
    }
    private static ItemStack stackFrom(Object value){
        if(value instanceof ItemStackTemplate template)return template.create();
        if(value instanceof ItemStack stack)return stack.copy();
        if(value instanceof Optional<?> opt)return stackFrom(opt.orElse(null));
        if(value!=null){
            try{
                Object created=value.getClass().getMethod("create").invoke(value);
                if(created instanceof ItemStack stack)return stack;
            }catch(ReflectiveOperationException|RuntimeException ignored){}
        }
        return ItemStack.EMPTY;
    }
    @SuppressWarnings("unchecked")
    private static List<Ingredient> ingredients(Object recipe)throws ReflectiveOperationException{
        if(recipe instanceof AbstractCookingRecipe cooking)return List.of(cooking.input());
        if(recipe instanceof ShapedRecipe shaped)
            return shaped.getIngredients().stream().flatMap(java.util.Optional::stream).toList();
        Object value=null;
        // On modern Minecraft versions the canonical crafting ingredient
        // list is frequently held inside PlacementInfo rather than directly
        // on ShapelessRecipe. Check it before legacy reflective fields.
        try{
            Object placement=recipe.getClass().getMethod("placementInfo").invoke(recipe);
            if(placement!=null)
                value=placement.getClass().getMethod("ingredients").invoke(placement);
        }catch(ReflectiveOperationException|RuntimeException ignored){}
        Class<?> cls=recipe.getClass();
        while(cls!=null&&value==null){
            try{
                Field f=cls.getDeclaredField("ingredients");f.setAccessible(true);
                value=f.get(recipe);
            }catch(NoSuchFieldException ignored){}
            cls=cls.getSuperclass();
        }
        if(!(value instanceof List<?>)){
            // In 26.2 some recipes expose ingredients only via a public
            // method, not a field called "ingredients".
            try{value=recipe.getClass().getMethod("getIngredients").invoke(recipe);}
            catch(ReflectiveOperationException|RuntimeException ignored){}
        }
        if(value instanceof java.util.Collection<?> c
                &&!(value instanceof List<?>))value=new ArrayList<>(c);
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
