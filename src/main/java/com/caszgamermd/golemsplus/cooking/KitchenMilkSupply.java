package com.caszgamermd.golemsplus.cooking;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Optional Cooking for Blockheads Milk Jar / Cow in a Jar integration.
 * The jar provides fluid milk through CFB's actual kitchen item provider,
 * not an item inventory. A fluid unit is consumed for every milk ingredient.
 *
 * All interaction is reflective because Golems Plus does not require CFB
 * to load. The exact CFB "milk" item tag is used, so Croptopia milk bottles
 * are covered when CFB registers them.
 */
final class KitchenMilkSupply {
    static final TagKey<Item> MILK_ITEMS=TagKey.create(Registries.ITEM,
            Identifier.fromNamespaceAndPath("cookingforblockheads","milk"));
    private final List<BlockEntity> jars;
    KitchenMilkSupply(List<BlockEntity> jars){this.jars=List.copyOf(jars);}

    boolean hasJar(){return !jars.isEmpty();}

    boolean isMilk(Item item){return new ItemStack(item).is(MILK_ITEMS);}

    int availableUnits(){
        long available=0;
        for(BlockEntity jar:jars)available+=jarUnits(jar);
        return (int)Math.min(Integer.MAX_VALUE,available);
    }

    void addTo(Map<Item,Integer> available){
        int units=availableUnits();
        if(units==0)return;
        for(var holder:BuiltInRegistries.ITEM.getTagOrEmpty(MILK_ITEMS))
            available.merge(holder.value(),units,Integer::sum);
    }

    /**
     * A unit from the same jar may stand in for any tagged milk item, but it
     * cannot simultaneously pay for several different milk variants.
     */
    boolean canSupply(int units){return units<=availableUnits();}

    /**
     * Consume real milk via CFB's own item provider tokens. This matches the
     * behavior of its cooking table and prevents virtual milk duplication.
     *
     * Preflight all planned units before committing. The provided list is
     * deterministic so retries do not silently switch milk alternatives.
     */
    boolean consume(Map<Item,Integer> virtual){
        if(virtual.isEmpty())return true;
        int count=virtual.values().stream().mapToInt(Integer::intValue).sum();
        if(!canSupply(count))return false;
        List<MilkToken> tokens=new ArrayList<>();
        // Each jar's provider tracks already reserved units in the token
        // collection; reserve each request, regardless of the milk item ID.
        for(var request:virtual.entrySet().stream()
                .sorted(Comparator.comparing(e->
                        BuiltInRegistries.ITEM.getKey(e.getKey()).toString())).toList()){
            if(!isMilk(request.getKey()))return false;
            for(int i=0;i<request.getValue();i++){
                MilkToken found=null;
                for(BlockEntity jar:jars){
                    List<Object> held=new ArrayList<>();
                    for(MilkToken previous:tokens)
                        if(previous.jar()==jar)held.add(previous.token());
                    Object candidate=findMilkToken(jar,request.getKey(),held);
                    if(candidate!=null){found=new MilkToken(jar,candidate);break;}
                }
                if(found==null)return false;
                tokens.add(found);
            }
        }
        // The item providers implement peek()/consume() with the actual
        // drained milk amount, not synthetic inventory contents.
        for(MilkToken token:tokens)if(!peek(token.token()))return false;
        for(MilkToken token:tokens)if(!take(token.token()))return false;
        return true;
    }

    private record MilkToken(BlockEntity jar,Object token){}

    private static int jarUnits(BlockEntity jar){
        try{
            Object tank=invoke(jar,"getFluidTank");
            if(tank==null)return 0;
            Object amount=invoke(tank,"getAmount",0);
            return amount instanceof Number n?Math.max(0,n.intValue()/1000):0;
        }catch(ReflectiveOperationException|RuntimeException|LinkageError ex){
            return 0;
        }
    }
    private static Object findMilkToken(BlockEntity jar,Item item,
                                         Collection<Object> reserved){
        try{
            Object provider=invoke(jar,"getKitchenItemProvider");
            if(provider==null)return null;
            Class<?> hintClass=Class.forName(
                    "net.blay09.mods.cookingforblockheads.api.CacheHint");
            Object noHint=hintClass.getField("NONE").get(null);
            Method finder=provider.getClass().getMethod("findIngredient",
                    ItemStack.class,Collection.class,hintClass,boolean.class);
            finder.setAccessible(true);
            Object token=finder.invoke(provider,new ItemStack(item),reserved,
                    noHint,false);
            return token!=null&&peek(token)?token:null;
        }catch(ReflectiveOperationException|RuntimeException|LinkageError ex){
            return null;
        }
    }
    private static boolean peek(Object token){
        try{return invoke(token,"peek") instanceof ItemStack stack
                &&!stack.isEmpty();}
        catch(ReflectiveOperationException|RuntimeException|LinkageError ex){
            return false;
        }
    }
    private static boolean take(Object token){
        try{return invoke(token,"consume") instanceof ItemStack stack
                &&!stack.isEmpty();}
        catch(ReflectiveOperationException|RuntimeException|LinkageError ex){
            return false;
        }
    }
    private static Object invoke(Object source,String name,Object... arguments)
            throws ReflectiveOperationException{
        Method selected=null;
        for(Method method:source.getClass().getMethods()){
            if(method.getName().equals(name)
                    &&method.getParameterCount()==arguments.length){
                selected=method;break;
            }
        }
        if(selected==null)throw new NoSuchMethodException(name);
        selected.setAccessible(true);
        return selected.invoke(source,arguments);
    }
}
