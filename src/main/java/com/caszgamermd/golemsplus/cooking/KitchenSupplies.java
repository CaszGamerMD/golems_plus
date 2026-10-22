package com.caszgamermd.golemsplus.cooking;

import java.lang.reflect.Method;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
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
    private final List<Container> containers;
    private final BlockPos workPoint;
    private KitchenSupplies(List<Container> containers,BlockPos workPoint){
        this.containers=containers;this.workPoint=workPoint;
    }
    public BlockPos workPoint(){return workPoint;}
    public boolean connected(){return !containers.isEmpty();}

    private static boolean kitchenBlock(ServerLevel level,BlockPos at){
        return level.hasChunkAt(at)
                &&BuiltInRegistries.BLOCK.getKey(level.getBlockState(at).getBlock())
                    .getNamespace().equals("cookingforblockheads");
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
            return new KitchenSupplies(List.of(),null);

        Set<BlockPos> visited=new HashSet<>();
        ArrayDeque<BlockPos> queue=new ArrayDeque<>();
        queue.add(anchor);
        List<Container> supplies=new ArrayList<>();
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
                Container inventory=container(be);
                if(inventory!=null&&!supplies.contains(inventory))
                    supplies.add(inventory);
            }
            for(Direction dir:Direction.values()){
                BlockPos neighbor=pos.relative(dir);
                if(!visited.contains(neighbor))queue.addLast(neighbor);
            }
        }
        return new KitchenSupplies(supplies,appliance);
    }

    private static Container container(BlockEntity be){
        if(be instanceof Container c)return c;
        try{
            Method getter=be.getClass().getMethod("getContainer");
            Object result=getter.invoke(be);
            if(result instanceof Container c)return c;
        }catch(ReflectiveOperationException|LinkageError|SecurityException ignored){}
        return null;
    }

    public Map<Item,Integer> snapshot(){
        Map<Item,Integer> result=new HashMap<>();
        for(Container c:containers)for(int slot=0;slot<c.getContainerSize();slot++){
            ItemStack stack=c.getItem(slot);
            if(!stack.isEmpty())result.merge(stack.getItem(),stack.getCount(),Integer::sum);
        }
        return result;
    }

    /** Recheck every count before consuming real items on the server thread. */
    public boolean consume(Map<Item,Integer> needed){
        Map<Item,Integer> available=snapshot();
        for(var entry:needed.entrySet())
            if(available.getOrDefault(entry.getKey(),0)<entry.getValue())return false;
        for(var entry:needed.entrySet()){
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
        }
        return true;
    }
}
