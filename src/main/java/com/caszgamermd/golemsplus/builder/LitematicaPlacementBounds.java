package com.caszgamermd.golemsplus.builder;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;

/** Litematica API-version bridge for already-transformed world-space boxes.
 * In Litematica 26.2 the optional enclosing box is called getEclosingBox()
 * and may be null merely because the user has hidden its outline. Neither
 * its name nor its render toggle determines whether subregions are enabled.
 */
public final class LitematicaPlacementBounds {
    public record Bounds(int minX,int minY,int minZ,int maxX,int maxY,int maxZ) {
        public long volume(){
            return ((long)maxX-minX+1)*((long)maxY-minY+1)
                    *((long)maxZ-minZ+1);
        }
    }
    private LitematicaPlacementBounds(){}

    public static Bounds resolve(Object placement)throws ReflectiveOperationException{
        // Modern Litematica has getSubRegionBoxes(RequiredEnabled), which
        // applies both placement and region transforms to each box. Using
        // PLACEMENT_ENABLED never depends on the enclosing-box draw toggle.
        for(Method method:placement.getClass().getMethods()){
            if(!method.getName().equals("getSubRegionBoxes")
                    ||method.getParameterCount()!=1
                    ||!method.getParameterTypes()[0].isEnum())continue;
            Object enabled=null;
            Object fallback=null;
            for(Object candidate:method.getParameterTypes()[0].getEnumConstants()){
                String name=((Enum<?>)candidate).name();
                if(name.equals("PLACEMENT_ENABLED"))enabled=candidate;
                if(name.equals("ENABLED"))fallback=candidate;
            }
            if(enabled==null)enabled=fallback;
            if(enabled==null)continue;
            Object boxes=call(method,placement,enabled);
            if(boxes instanceof Map<?,?> map){
                if(map.isEmpty())throw new IllegalArgumentException(
                        "Litematica returned no enabled subregion boxes for this placement");
                return enclose(map.values());
            }
            throw new IllegalArgumentException(
                    "Litematica returned an unexpected subregion-box collection");
        }
        // Backwards compatibility with older Litematica implementations.
        // 26.2's misspelled accessor is only an optional last resort.
        for(String name:List.of("getEnclosingBox","getEclosingBox")){
            try{
                Object box=call(placement.getClass().getMethod(name),placement);
                if(box!=null)return enclose(List.of(box));
            }catch(NoSuchMethodException ignored){}
        }
        throw new IllegalArgumentException(
                "Litematica did not provide placement bounds; reload the schematic hologram");
    }

    private static Bounds enclose(Collection<?> boxes)throws ReflectiveOperationException{
        int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE;
        int maxX=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
        int count=0;
        for(Object box:boxes){
            if(box==null)continue;
            BlockPos a,b;
            try{
                Class<?> type=box.getClass();
                a=new BlockPos(type.getField("minX").getInt(box),
                        type.getField("minY").getInt(box),type.getField("minZ").getInt(box));
                b=new BlockPos(type.getField("maxX").getInt(box),
                        type.getField("maxY").getInt(box),type.getField("maxZ").getInt(box));
            }catch(NoSuchFieldException ignored){
                a=corner(box,"getPos1","getCorner1");
                b=corner(box,"getPos2","getCorner2");
            }
            minX=Math.min(minX,Math.min(a.getX(),b.getX()));
            minY=Math.min(minY,Math.min(a.getY(),b.getY()));
            minZ=Math.min(minZ,Math.min(a.getZ(),b.getZ()));
            maxX=Math.max(maxX,Math.max(a.getX(),b.getX()));
            maxY=Math.max(maxY,Math.max(a.getY(),b.getY()));
            maxZ=Math.max(maxZ,Math.max(a.getZ(),b.getZ()));
            count++;
        }
        if(count==0)throw new IllegalArgumentException("No readable Litematica region boxes");
        return new Bounds(minX,minY,minZ,maxX,maxY,maxZ);
    }
    private static BlockPos corner(Object box,String name,String alternate)
            throws ReflectiveOperationException{
        Method method;
        try{method=box.getClass().getMethod(name);}
        catch(NoSuchMethodException ignored){method=box.getClass().getMethod(alternate);}
        Object pos=call(method,box);
        if(pos instanceof BlockPos p)return p;
        throw new IllegalArgumentException("Litematica region has no readable "+name);
    }
    private static Object call(Method method,Object receiver,Object... args)
            throws ReflectiveOperationException{
        try{return method.invoke(receiver,args);}
        catch(InvocationTargetException e){
            throw new IllegalArgumentException("Litematica "+method.getName()+" failed",e.getCause());
        }
    }
}
