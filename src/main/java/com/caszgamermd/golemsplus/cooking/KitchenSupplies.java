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
import net.minecraft.world.level.block.Blocks;

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
    // Preserve original storage positions, including the exact slot from
    // which a reserved ingredient was physically withdrawn.
    private final Map<Container,BlockPos> locations;
    public record Origin(String itemId,BlockPos container,int slot) {}
    private final KitchenMilkSupply milkSupply;
    private final BlockPos workPoint;
    private final List<BlockPos> appliances;
    private KitchenSupplies(List<Container> containers,Map<Container,BlockPos> locations,
                            BlockPos workPoint,List<BlockEntity> milkJars,
                            List<BlockPos> appliances){
        this.containers=List.copyOf(containers);
        this.locations=new IdentityHashMap<>(locations);this.workPoint=workPoint;
        this.appliances=List.copyOf(appliances);
        this.milkSupply=new KitchenMilkSupply(milkJars);
    }
    // In-memory storage view used by the insertion regression tests.
    KitchenSupplies(List<Container> containers,Map<Container,BlockPos> locations){
        this(containers,locations,null,List.of(),List.of());
    }
    public BlockPos workPoint(){return workPoint;}
    public BlockPos stationFor(ServerLevel world,String task){
        boolean heated=!task.equals("Assemble")&&!task.equals("Plate");
        BlockPos chosen=null,cfbOven=null;
        for(BlockPos pos:appliances){
            if(!world.hasChunkAt(pos))continue;
            var blockState=world.getBlockState(pos);
            var id=BuiltInRegistries.BLOCK.getKey(blockState.getBlock());
            String block=id.getPath();
            if(heated){
                if(id.getNamespace().equals("cookingforblockheads")
                        &&block.contains("oven"))cfbOven=pos;
                if(task.equals("Cook")&&blockState.is(Blocks.FURNACE))return pos;
                if(task.equals("Smoke")&&blockState.is(Blocks.SMOKER))return pos;
                continue;
            }
            if(block.contains("cooking_table"))return pos;
            if(block.contains("counter")||block.contains("cabinet"))chosen=pos;
        }
        return heated?cfbOven:(chosen==null?workPoint:chosen);
    }
    public boolean hasCooker(ServerLevel world){
        return stationFor(world,"Cook")!=null
                ||stationFor(world,"Smoke")!=null
                ||stationFor(world,"Campfire")!=null;
    }
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
            return new KitchenSupplies(List.of(),Map.of(),null,List.of(),List.of());

        Set<BlockPos> visited=new HashSet<>();
        ArrayDeque<BlockPos> queue=new ArrayDeque<>();
        queue.add(anchor);
        List<Container> supplies=new ArrayList<>();
        Map<Container,BlockPos> locations=new IdentityHashMap<>();
        List<BlockEntity> milkJars=new ArrayList<>();
        List<BlockPos> appliances=new ArrayList<>();
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
                appliances.add(pos.immutable());
                if(key.getPath().equals("cow_jar")||key.getPath().equals("milk_jar"))
                    milkJars.add(be);
                Container inventory=container(be);
                if(inventory!=null&&!supplies.contains(inventory)){
                    supplies.add(inventory);
                    locations.put(inventory,pos.immutable());
                }
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
        // A vanilla furnace/smoker beside a connected CFB kitchen is a
        // valid work appliance, but NEVER a food-supply chest. Do not make
        // it part of the CFB multiblock or count its input/fuel/output as
        // loose ingredients available to any Chef recipe.
        Set<BlockPos> connected=new HashSet<>(visited);
        Set<BlockPos> seen=new HashSet<>(appliances);
        for(BlockPos kitchen:connected){
            if(!kitchenBlock(level,kitchen))continue;
            for(int dx=-2;dx<=2;dx++)for(int dy=-1;dy<=1;dy++)
                for(int dz=-2;dz<=2;dz++){
                    BlockPos candidate=kitchen.offset(dx,dy,dz);
                    if(kiosk.distSqr(candidate)>LINK_RADIUS*LINK_RADIUS
                            ||!level.hasChunkAt(candidate)||!seen.add(candidate))
                        continue;
                    var state=level.getBlockState(candidate);
                    if(state.is(Blocks.FURNACE)||state.is(Blocks.SMOKER))
                        appliances.add(candidate.immutable());
                }
        }
        return new KitchenSupplies(supplies,locations,appliance,milkJars,appliances);
    }

    private static Container container(BlockEntity be){
        // Oven internal inventory also holds fuel, half-cooked food and
        // ingredient staging slots. These are NOT free pantry ingredients.
        if(BuiltInRegistries.BLOCK.getKey(be.getBlockState().getBlock())
                .getPath().contains("oven"))return null;
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
                    if(value instanceof Container c && !(c instanceof net.minecraft.world.entity.player.Inventory))return c;
                }catch(NoSuchFieldException ignored){}
                catch(ReflectiveOperationException|LinkageError|SecurityException ignored){
                    break;
                }
            }
        }
        try{
            Method getter=be.getClass().getMethod("getContainer");
            Object result=getter.invoke(be);
            if(result instanceof Container c && !(c instanceof net.minecraft.world.entity.player.Inventory))return c;
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
        final BlockPos position;
        ItemStack after;
        SlotState(Container container,BlockPos position,int slot,ItemStack original){
            this.container=container;this.position=position;this.slot=slot;
            this.before=original.copy();this.after=original.copy();
        }
    }
    public boolean canInsertAll(List<ItemStack> items){
        return canInsertAll(items,List.of());
    }
    public boolean insertAll(List<ItemStack> items){
        return insertAll(items,List.of());
    }
    public boolean canInsertAll(List<ItemStack> items,List<Origin> originalSlots){
        return simulateInsertion(items,originalSlots,false);
    }
    public boolean insertAll(List<ItemStack> items,List<Origin> originalSlots){
        return simulateInsertion(items,originalSlots,true);
    }
    private static int capacity(SlotState slot,ItemStack stack){
        return Math.min(stack.getMaxStackSize(),slot.container.getMaxStackSize());
    }
    private static int put(SlotState slot,ItemStack incoming,int left){
        if(left<=0||!slot.container.canPlaceItem(slot.slot,incoming))return left;
        ItemStack current=slot.after;
        if(!current.isEmpty()&&!ItemStack.isSameItemSameComponents(current,incoming))
            return left;
        int cap=capacity(slot,incoming)-(current.isEmpty()?0:current.getCount());
        int used=Math.min(left,Math.max(0,cap));
        if(used>0){
            if(current.isEmpty())slot.after=incoming.copyWithCount(used);
            else current.grow(used);
        }
        return left-used;
    }
    private boolean simulateInsertion(List<ItemStack> additions,List<Origin> originalSlots,
                                      boolean commit){
        List<SlotState> slots=new ArrayList<>();
        for(Container c:containers){
            for(int n=0;n<c.getContainerSize();n++){
                ItemStack before=c.getItem(n);
                slots.add(new SlotState(c,locations.get(c),n,before));
            }
        }
        // Preferred order: exact former slot, then all matching stacks,
        // then any empty kitchen slot. An occupied former slot is never
        // overwritten. This entire operation is staged before mutation.
        for(ItemStack incoming:additions){
            if(incoming.isEmpty())continue;
            String id=BuiltInRegistries.ITEM.getKey(incoming.getItem()).toString();
            List<SlotState> ordered=KitchenReturnPriority.order(slots,
                    slot->originalSlots.stream().anyMatch(origin->
                            id.equals(origin.itemId())&&origin.slot()==slot.slot
                            &&origin.container().equals(slot.position)),
                    slot->!slot.after.isEmpty()
                            &&ItemStack.isSameItemSameComponents(slot.after,incoming),
                    slot->slot.after.isEmpty());
            int left=incoming.getCount();
            for(SlotState slot:ordered){
                if(left==0)break;
                left=put(slot,incoming,left);
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
        return withdraw(needed)!=null;
    }
    /** Reserve only physically existing stock. Return original slots on
     * success, or null on failure; never report virtual milk as a chest slot.
     */
    public List<Origin> withdraw(Map<Item,Integer> needed){
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
                if(!milkSupply.isMilk(entry.getKey()))return null;
                fromJars.put(entry.getKey(),virtual);
            }
        }
        if(!milkSupply.canSupply(fromJars.values().stream()
                .mapToInt(Integer::intValue).sum()))return null;
        // Verify real storage *before* draining any milk or chest slots.
        for(var entry:physicalToTake.entrySet())
            if(physical.getOrDefault(entry.getKey(),0)<entry.getValue())return null;
        // Fluid-backed milk is drained through CFB's real provider.
        // If it isn't available, no physical ingredients are removed.
        if(!milkSupply.consume(fromJars))return null;
        List<Origin> origins=new ArrayList<>();
        for(var entry:physicalToTake.entrySet()){
            int left=entry.getValue();
            for(Container c:containers){
                for(int i=0;i<c.getContainerSize()&&left>0;i++){
                    ItemStack stack=c.getItem(i);
                    if(stack.is(entry.getKey())){
                        int n=Math.min(left,stack.getCount());
                        ItemStack removed=c.removeItem(i,n);
                        if(removed.getCount()!=n)return null;
                        c.setChanged();
                        BlockPos source=locations.get(c);
                        if(source!=null)origins.add(new Origin(
                                BuiltInRegistries.ITEM.getKey(entry.getKey()).toString(),
                                source,i));
                        left-=n;
                    }
                }
                if(left==0)break;
            }
            if(left>0)return null;
        }
        return List.copyOf(origins);
    }
}
