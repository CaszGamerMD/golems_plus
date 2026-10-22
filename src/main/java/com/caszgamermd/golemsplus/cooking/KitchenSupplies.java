package com.caszgamermd.golemsplus.cooking;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * A bounded, server-side view of physical Cooking for Blockheads kitchen storage.
 * The kiosk can link to a kitchen within 16 blocks; it does not need to touch
 * the kitchen. Only the linked multiblock component is traversed, avoiding
 * unrelated chests and other nearby kitchens.
 *
 * No hard class dependency: storage is accessed through the real inventory
 * exposed by the optional mod's BalmContainerProvider.
 */
public final class KitchenSupplies {
    private static final int LINK_RADIUS=16;
    private static final int SCAN_RADIUS=24;
    private static final TagKey<Block> KITCHEN_ITEM_PROVIDERS=TagKey.create(
            Registries.BLOCK,Identifier.fromNamespaceAndPath(
                    "cookingforblockheads","kitchen_item_providers"));
    private static final TagKey<Block> KITCHEN_CONNECTORS=TagKey.create(
            Registries.BLOCK,Identifier.fromNamespaceAndPath(
                    "cookingforblockheads","kitchen_connectors"));
    private final List<Container> containers;
    private final KitchenMilkSupply milkSupply;
    private final BlockPos workPoint;
    private KitchenSupplies(List<Container> containers,BlockPos workPoint,
                            List<BlockEntity> milkJars){
        this.containers=containers;this.workPoint=workPoint;
        this.milkSupply=new KitchenMilkSupply(milkJars);
    }
    public BlockPos workPoint(){return workPoint;}
    public boolean connected(){return !containers.isEmpty()||milkSupply.hasJar();}
    public int milkPortions(){return milkSupply.availableUnits();}

    private static boolean kitchenBlock(ServerLevel level,BlockPos at){
        if(!level.hasChunkAt(at))return false;
        var state=level.getBlockState(at);
        return BuiltInRegistries.BLOCK.getKey(state.getBlock())
                    .getNamespace().equals("cookingforblockheads")
                ||state.is(KITCHEN_ITEM_PROVIDERS)
                ||state.is(KITCHEN_CONNECTORS);
    }

    /**
     * Pick a real kitchen storage block so an isolated table or decoration
     * cannot be mistaken for a connected stocked kitchen.
     */
    public static BlockPos findAnchor(ServerLevel level,BlockPos kiosk){
        BlockPos chosen=null;
        double best=Double.MAX_VALUE;
        for(int x=-LINK_RADIUS;x<=LINK_RADIUS;x++)
            for(int y=-6;y<=6;y++)
                for(int z=-LINK_RADIUS;z<=LINK_RADIUS;z++){
                    BlockPos p=kiosk.offset(x,y,z);
                    double distance=kiosk.distSqr(p);
                    if(distance>LINK_RADIUS*LINK_RADIUS||distance>=best
                            ||!kitchenBlock(level,p))continue;
                    BlockEntity be=level.getBlockEntity(p);
                    if(be==null||container(be)==null)continue;
                    chosen=p.immutable();best=distance;
                }
        return chosen;
    }

    /** Legacy proximity discovery; currently used only as a safe fallback. */
    public static KitchenSupplies around(ServerLevel level,BlockPos kiosk){
        return around(level,kiosk,findAnchor(level,kiosk));
    }

    /** Enumerate only the component reachable through CFB kitchen blocks. */
    public static KitchenSupplies around(ServerLevel level,BlockPos kiosk,BlockPos anchor){
        if(anchor==null||kiosk.distSqr(anchor)>LINK_RADIUS*LINK_RADIUS
                ||!kitchenBlock(level,anchor))
            return new KitchenSupplies(List.of(),null,List.of());

        Set<BlockPos> visited=new HashSet<>();
        ArrayDeque<BlockPos> queue=new ArrayDeque<>();
        queue.add(anchor);
        List<Container> supplies=new ArrayList<>();
        List<BlockEntity> milkJars=new ArrayList<>();
        BlockPos appliance=anchor;
        boolean selectedOven=false;

        while(!queue.isEmpty()&&visited.size()<4096){
            BlockPos pos=queue.removeFirst();
            if(!visited.add(pos)||pos.distSqr(anchor)>SCAN_RADIUS*SCAN_RADIUS
                    ||!kitchenBlock(level,pos))continue;
            var key=BuiltInRegistries.BLOCK.getKey(level.getBlockState(pos).getBlock());
            if(!selectedOven&&key.getPath().contains("oven")){
                appliance=pos;selectedOven=true;
            }
            BlockEntity be=level.getBlockEntity(pos);
            if(be!=null){
                if(key.getPath().equals("cow_jar")||key.getPath().equals("milk_jar"))
                    milkJars.add(be);
                Container inventory=container(be);
                if(inventory!=null&&!supplies.contains(inventory))
                    supplies.add(inventory);
            }
            for(Direction dir:Direction.values()){
                BlockPos neighbor=pos.relative(dir);
                if(!visited.contains(neighbor))queue.addLast(neighbor);
            }
            // CFB's own multiblock scanner also considers two blocks
            // above each kitchen appliance for jar/sink attachments.
            BlockPos raised=pos.above(2);
            if(!visited.contains(raised))queue.addLast(raised);
        }
        return new KitchenSupplies(supplies,appliance,milkJars);
    }

    private static Container container(BlockEntity be){
        if(be instanceof Container c)return c;
        // CFB multi-block fridges and cabinets expose combined wrappers
        // from getContainer(). Scanning both halves through those wrappers
        // counts the SAME jars twice. Read the underlying container field
        // on each real block instead, exactly once.
        if(BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock())
                .getNamespace().equals("cookingforblockheads")){
            for(Class<?> type=be.getClass();type!=null;type=type.getSuperclass()){
                try{
                    Field field=type.getDeclaredField("container");
                    field.setAccessible(true);
                    Object value=field.get(be);
                    if(value instanceof Container c)return c;
                }catch(NoSuchFieldException ignored){}
                catch(ReflectiveOperationException|LinkageError|SecurityException ignored){
                    break;
                }
            }
        }
        try{
            Method getter=be.getClass().getMethod("getContainer");
            Object result=getter.invoke(be);
            if(result instanceof Container c)return c;
        }catch(ReflectiveOperationException|LinkageError|SecurityException ignored){}
        return null;
    }

    private Map<Item,Integer> physicalSnapshot(){
        Map<Item,Integer> result=new HashMap<>();
        for(Container c:containers)for(int slot=0;slot<c.getContainerSize();slot++){
            ItemStack stack=c.getItem(slot);
            if(!stack.isEmpty())result.merge(stack.getItem(),stack.getCount(),Integer::sum);
        }
        return result;
    }
    public Map<Item,Integer> snapshot(){
        Map<Item,Integer> result=physicalSnapshot();
        // Cooking for Blockheads' cow/milk jars provide tagged milk as
        // virtual items; unlike regular storage they need the provider token
        // to drain 1000 mB when a real order consumes one.
        milkSupply.addTo(result);
        return result;
    }

    /**
     * Put batch leftovers back into the real linked kitchen. We validate
     * every slot and every item before changing *any* kitchen inventory.
     * No workstation storage is used, and leftovers are never voided.
     */
    private static final class SlotState{
        final Container container;
        final int slot;
        final ItemStack before;
        ItemStack after;
        SlotState(Container container,int slot,ItemStack original){
            this.container=container;this.slot=slot;
            this.before=original.copy();this.after=original.copy();
        }
    }
    public boolean canInsertAll(List<ItemStack> items){
        return simulateInsertion(items,false);
    }
    public boolean insertAll(List<ItemStack> items){
        return simulateInsertion(items,true);
    }
    private boolean simulateInsertion(List<ItemStack> additions,boolean commit){
        List<SlotState> slots=new ArrayList<>();
        for(Container c:containers){
            for(int n=0;n<c.getContainerSize();n++){
                ItemStack before=c.getItem(n);
                slots.add(new SlotState(c,n,before));
            }
        }
        for(ItemStack incoming:additions){
            if(incoming.isEmpty())continue;
            int left=incoming.getCount();
            for(SlotState slot:slots){
                ItemStack staged=slot.after;
                if(left==0)break;
                if(!staged.isEmpty()&&ItemStack.isSameItemSameComponents(staged,incoming)
                        &&slot.container.canPlaceItem(slot.slot,incoming)){
                    int capacity=Math.min(staged.getMaxStackSize(),64)-staged.getCount();
                    int accepted=Math.min(left,Math.max(0,capacity));
                    staged.grow(accepted);
                    left-=accepted;
                }
            }
            for(SlotState slot:slots){
                if(left==0)break;
                if(slot.after.isEmpty()
                        &&slot.container.canPlaceItem(slot.slot,incoming)){
                    int accepted=Math.min(left,Math.min(incoming.getMaxStackSize(),64));
                    slot.after=incoming.copyWithCount(accepted);
                    left-=accepted;
                }
            }
            if(left>0)return false;
        }
        if(!commit)return true;
        // Write only changed slots. Slot after-state has already been
        // validated, so every item is accounted for.
        for(SlotState slot:slots){
            if(!ItemStack.matches(slot.before,slot.after)){
                slot.container.setItem(slot.slot,slot.after.copy());
                slot.container.setChanged();
            }
        }
        return true;
    }

    /**
     * Reserve real pantry items first, then any remainder from CFB milk jars.
     * Milk variants share ONE physical tank capacity; aliases in the item
     * tag must never create or consume the same milk multiple times.
     */
    public boolean consume(Map<Item,Integer> needed){
        Map<Item,Integer> physical=physicalSnapshot();
        Map<Item,Integer> physicalToTake=new HashMap<>();
        Map<Item,Integer> fromJars=new HashMap<>();
        for(var entry:needed.entrySet()){
            if(entry.getValue()<=0)continue;
            int inChest=Math.min(entry.getValue(),
                    physical.getOrDefault(entry.getKey(),0));
            if(inChest>0)physicalToTake.put(entry.getKey(),inChest);
            int virtual=entry.getValue()-inChest;
            if(virtual>0){
                if(!milkSupply.isMilk(entry.getKey()))return false;
                fromJars.put(entry.getKey(),virtual);
            }
        }
        if(!milkSupply.canSupply(fromJars.values().stream()
                .mapToInt(Integer::intValue).sum()))return false;
        // Fluid-backed milk is drained through CFB's real provider.
        // If it isn't available, no physical ingredients are removed.
        if(!milkSupply.consume(fromJars))return false;
        for(var entry:physicalToTake.entrySet()){
            int left=entry.getValue();
            for(Container c:containers){
                for(int i=0;i<c.getContainerSize()&&left>0;i++){
                    ItemStack stack=c.getItem(i);
                    if(stack.is(entry.getKey())){
                        int n=Math.min(left,stack.getCount());
                        c.removeItem(i,n);
                        c.setChanged();
                        left-=n;
                    }
                }
                if(left==0)break;
            }
            if(left>0)return false;
        }
        return true;
    }
}
