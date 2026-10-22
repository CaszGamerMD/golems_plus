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

/** A bounded view of actual Cooking for Blockheads connected containers.
 * Never includes unrelated chests, unloaded chunks or remote inventories.
 * No hard dependency: kitchen providers are queried only when installed. */
public final class KitchenSupplies {
    private final List<Container> containers;
    private final BlockPos workPoint;
    private KitchenSupplies(List<Container> containers,BlockPos workPoint){
        this.containers=containers;this.workPoint=workPoint;
    }
    public BlockPos workPoint(){return workPoint;}
    public boolean connected(){return !containers.isEmpty();}
    public static KitchenSupplies around(ServerLevel level,BlockPos kiosk) {
        Set<BlockPos> seen=new HashSet<>();
        ArrayDeque<BlockPos> open=new ArrayDeque<>();
        for(Direction d:Direction.values())open.add(kiosk.relative(d));
        List<Container> supplies=new ArrayList<>();
        BlockPos workPoint=null;
        int budget=0;
        while(!open.isEmpty()&&budget++<512){
            BlockPos pos=open.removeFirst();
            if(!seen.add(pos)||!level.hasChunkAt(pos)||pos.distSqr(kiosk)>256)continue;
            var state=level.getBlockState(pos);
            if(!BuiltInRegistries.BLOCK.getKey(state.getBlock())
                    .getNamespace().equals("cookingforblockheads"))continue;
            BlockEntity be=level.getBlockEntity(pos);
            if(workPoint==null||BuiltInRegistries.BLOCK.getKey(state.getBlock())
                    .getPath().contains("oven"))workPoint=pos;
            if(be!=null){
                Container c=container(be);
                if(c!=null&&!supplies.contains(c))supplies.add(c);
            }
            for(Direction d:Direction.values())open.add(pos.relative(d));
        }
        return new KitchenSupplies(supplies,workPoint);
    }
    private static Container container(BlockEntity be){
        if(be instanceof Container c)return c;
        // Cooking for Blockheads uses BalmContainerProvider, which exposes
        // getContainer(): Container. Reflect to keep the dependency optional.
        try {
            Method m=be.getClass().getMethod("getContainer");
            Object result=m.invoke(be);
            if(result instanceof Container c)return c;
        }catch(ReflectiveOperationException|LinkageError ignored){}
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
    /** All-or-nothing, server-thread-only, after checking quantities again. */
    public boolean consume(Map<Item,Integer> needed){
        Map<Item,Integer> inStock=snapshot();
        for(var e:needed.entrySet())if(inStock.getOrDefault(e.getKey(),0)<e.getValue())return false;
        for(var e:needed.entrySet()){
            int left=e.getValue();
            for(Container c:containers){
                for(int i=0;i<c.getContainerSize()&&left>0;i++){
                    ItemStack stack=c.getItem(i);
                    if(stack.is(e.getKey())){
                        int n=Math.min(left,stack.getCount());
                        c.removeItem(i,n);c.setChanged();left-=n;
                    }
                }
                if(left==0)break;
            }
        }
        return true;
    }
}
