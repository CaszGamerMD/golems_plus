package com.caszgamermd.golemsplus.warehouse;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;

/**
 * Optional version-safe bridge to Caszual Additions. Golems Plus does not
 * link Caszual warehouse classes directly on dedicated servers that lack it.
 */
public final class BuilderWarehousePort {
    public record Reply(String state,BlockPos ptp,Map<String,Integer> missing,
                        int queued,int boxes){
        public Reply {
            missing=Map.copyOf(missing);
        }
        public boolean accepted(){return "QUEUED_AT_WAREHOUSE".equals(state)
                ||(ptp!=null&&!ptp.equals(BlockPos.ZERO)
                &&!state.equals("WAREHOUSE_OFFLINE")
                &&!state.equals("RESERVATION_MISSING"));}
    }
    private static final String IMPL=
            "com.caszgamermd.caszualadditions.racks.BuilderWarehouseFulfillment";
    private BuilderWarehousePort(){}
    @SuppressWarnings("unchecked")
    public static Map<String,Integer> stockSnapshot(ServerLevel level,
            BlockPos computer,Map<String,Long> needed){
        try{
            Class<?> cls=Class.forName(IMPL);
            Method method=cls.getMethod("available",ServerLevel.class,
                    BlockPos.class,Map.class);
            Object result=method.invoke(null,level,computer,needed);
            return result instanceof Map<?,?> ? (Map<String,Integer>)result:null;
        }catch(ReflectiveOperationException|LinkageError|RuntimeException ex){
            return null; // unchecked/unknown, never fabricate "zero available"
        }
    }
    public static String warehouseName(ServerLevel level,BlockPos computer){
        try{
            Class<?> cls=Class.forName(
                    "com.caszgamermd.caszualadditions.racks.WarehouseComputerBlockEntity");
            return (String)cls.getMethod("name",ServerLevel.class,BlockPos.class)
                    .invoke(null,level,computer);
        }catch(ReflectiveOperationException|LinkageError|RuntimeException ex){
            return "";
        }
    }
    public static boolean available(){
        try{Class.forName(IMPL);return true;}
        catch(ClassNotFoundException ex){return false;}
    }
    @SuppressWarnings("unchecked")
    private static Reply snapshot(Object raw)throws ReflectiveOperationException{
        Class<?> cls=raw.getClass();
        return new Reply((String)cls.getMethod("state").invoke(raw),
                (BlockPos)cls.getMethod("ptp").invoke(raw),
                (Map<String,Integer>)cls.getMethod("missing").invoke(raw),
                (int)cls.getMethod("pendingOrders").invoke(raw),
                (int)cls.getMethod("packedBoxes").invoke(raw));
    }
    public static Reply request(ServerLevel level,BlockPos computer,BlockPos table,
            UUID owner,UUID ticket,Map<String,Integer> manifest){
        try{
            Class<?> cls=Class.forName(IMPL);
            Method method=cls.getMethod("request",ServerLevel.class,BlockPos.class,
                    BlockPos.class,UUID.class,UUID.class,Map.class);
            return snapshot(method.invoke(null,level,computer,table,owner,ticket,manifest));
        }catch(ReflectiveOperationException|LinkageError ex){
            return new Reply("INTEGRATION_ERROR",BlockPos.ZERO,Map.of(),0,0);
        }
    }
    public static Reply inspect(ServerLevel level,BlockPos computer,BlockPos table,
            UUID owner,UUID ticket,BlockPos ptp){
        try{
            Class<?> cls=Class.forName(IMPL);
            Method method=cls.getMethod("inspect",ServerLevel.class,BlockPos.class,
                    BlockPos.class,UUID.class,UUID.class,BlockPos.class);
            return snapshot(method.invoke(null,level,computer,table,owner,ticket,ptp));
        }catch(ReflectiveOperationException|LinkageError ex){
            return new Reply("INTEGRATION_ERROR",ptp,Map.of(),0,0);
        }
    }
    public static ItemStack takeBox(ServerLevel level,BlockPos computer,BlockPos table,
            UUID owner,UUID ticket,BlockPos ptp){
        try{
            Class<?> cls=Class.forName(IMPL);
            Method method=cls.getMethod("takeBox",ServerLevel.class,BlockPos.class,
                    BlockPos.class,UUID.class,UUID.class,BlockPos.class);
            return (ItemStack)method.invoke(null,level,computer,table,owner,ticket,ptp);
        }catch(ReflectiveOperationException|LinkageError ex){return ItemStack.EMPTY;}
    }
    /** No cross-mod hard dependency: refuse to forget a ticket unless the
     * warehouse confirms there is no already shipped or in-flight cargo.
     */
    public static boolean cancel(ServerLevel level,BlockPos computer,BlockPos table,
            UUID owner,UUID ticket,BlockPos ptp){
        try{
            Class<?> cls=Class.forName(IMPL);
            Method method=cls.getMethod("cancel",ServerLevel.class,BlockPos.class,
                    BlockPos.class,UUID.class,UUID.class,BlockPos.class);
            return (boolean)method.invoke(null,level,computer,table,owner,ticket,ptp);
        }catch(ReflectiveOperationException|LinkageError ex){return false;}
    }
    public static boolean release(ServerLevel level,BlockPos computer,BlockPos table,
            UUID owner,UUID ticket,BlockPos ptp){
        try{
            Class<?> cls=Class.forName(IMPL);
            Method method=cls.getMethod("release",ServerLevel.class,BlockPos.class,
                    BlockPos.class,UUID.class,UUID.class,BlockPos.class);
            return (boolean)method.invoke(null,level,computer,table,owner,ticket,ptp);
        }catch(ReflectiveOperationException|LinkageError ex){return false;}
    }
}
