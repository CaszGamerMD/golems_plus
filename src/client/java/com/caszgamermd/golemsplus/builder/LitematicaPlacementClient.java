package com.caszgamermd.golemsplus.builder;

import com.caszgamermd.golemsplus.world.WorksiteContent;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Optional;
import java.lang.reflect.InvocationTargetException;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * Optional reflection bridge: Golems Plus still launches without Litematica,
 * and the dedicated server never imports client-only Litematica classes.
 * The player's SELECTED loaded placement is the sole source of alignment.
 */
public final class LitematicaPlacementClient {
    private LitematicaPlacementClient(){}
    public static void register(){
        ClientCommandRegistrationCallback.EVENT.register((dispatcher,access)->
                dispatcher.register(ClientCommands.literal("golems_use_placement")
                        .executes(ctx->{
                            try{
                                sendSelected();
                                ctx.getSource().sendFeedback(Component.literal(
                                        "Selected Litematica placement sent. Check Builder chat preview."));
                                return 1;
                            }catch(Exception e){
                                ctx.getSource().sendFeedback(Component.literal(
                                        "[Builder] Cannot use selected placement: "+e.getMessage()));
                                return 0;
                            }
                        })));
    }
    private static Object invoke(Object source,String method)throws Exception{
        try{return source.getClass().getMethod(method).invoke(source);}
        catch(InvocationTargetException e){
            Throwable cause=e.getCause();
            throw new IllegalArgumentException(method+": "+
                    (cause==null?e.getMessage():cause.getMessage()),cause);
        }
    }
    private static Object firstAvailable(Object o,String... names)throws Exception{
        for(String name:names){
            try{
                Object v=invoke(o,name);
                if(v!=null)return v;
            }catch(NoSuchMethodException ignored){}
        }
        return null;
    }
    private static BlockPos asPos(Object object)throws Exception{
        if(object instanceof BlockPos p)return p;
        if(object==null)return null;
        try{
            int x=((Number)invoke(object,"getX")).intValue();
            int y=((Number)invoke(object,"getY")).intValue();
            int z=((Number)invoke(object,"getZ")).intValue();
            return new BlockPos(x,y,z);
        }catch(NoSuchMethodException ignored){return null;}
    }
    private static Object invokeEither(Object source,String a,String b)throws Exception{
        try{return invoke(source,a);}catch(NoSuchMethodException ex){return invoke(source,b);}
    }
    /** Client-side placement menu entries. Never send these objects across the network. */
    public record PlacementChoice(Object placement,String name,BlockPos origin,boolean enabled){}

    private static boolean optionalBoolean(Object object,String name,boolean fallback)
            throws Exception {
        try{return Boolean.TRUE.equals(invoke(object,name));}
        catch(NoSuchMethodException ignored){return fallback;}
    }
    private static Object optional(Object object,String name)throws Exception{
        try{return invoke(object,name);}
        catch(NoSuchMethodException ignored){return null;}
    }

    /** Returns every placement managed by Litematica, not only the selected one. */
    public static List<PlacementChoice> placements()throws Exception{
        Object mgr=manager();
        Object all=firstAvailable(mgr,"getAllSchematicsPlacements",
                "getAllSchematicPlacements","getAllPlacements",
                "getVisibleSchematicPlacements");
        if(!(all instanceof Collection<?> collection))
            throw new IllegalArgumentException(
                    "Litematica placement manager did not expose getAllSchematicsPlacements()");
        List<PlacementChoice> found=new ArrayList<>();
        for(Object p:collection){
            if(p==null)continue;
            try{
                BlockPos pos=asPos(firstAvailable(p,"getOrigin","getPosition"));
                if(pos==null)continue;
                Object label=optional(p,"getName");
                String name=label==null?"Unnamed placement":label.toString();
                found.add(new PlacementChoice(p,name,pos,
                        optionalBoolean(p,"isEnabled",true)));
            }catch(Exception ignored){
                // One broken placement cannot hide all other loaded ones.
            }
        }
        if(found.isEmpty()&&!collection.isEmpty())
            throw new IllegalArgumentException(
                    "Litematica has placements, but could not decode their positions");
        if(found.isEmpty()){
            Object selected=optional(mgr,"getSelectedSchematicPlacement");
            if(selected!=null){
                BlockPos pos=asPos(firstAvailable(selected,"getOrigin","getPosition"));
                if(pos!=null)found.add(new PlacementChoice(selected,
                        String.valueOf(firstAvailable(selected,"getName")),
                        pos,optionalBoolean(selected,"isEnabled",true)));
            }
        }
        return List.copyOf(found);
    }
    private static Object manager()throws Exception{
        for(String name:new String[]{
                "fi.dy.masa.litematica.data.DataManager",
                "litematica.data.DataManager"}){
            try{
                Class<?> cls=Class.forName(name);
                return cls.getMethod("getSchematicPlacementManager").invoke(null);
            }catch(ClassNotFoundException ignored){}
        }
        throw new IllegalArgumentException("Litematica client mod is not loaded");
    }
    private static BlockPos nearestTable(){
        var client=Minecraft.getInstance();
        if(client.player==null||client.level==null)return null;
        var center=client.player.blockPosition();
        for(int radius=0;radius<=8;radius++)
            for(int dy=-4;dy<=4;dy++)
                for(int dx=-radius;dx<=radius;dx++)
                    for(int dz=-radius;dz<=radius;dz++){
                        if(Math.max(Math.abs(dx),Math.abs(dz))!=radius)continue;
                        var pos=center.offset(dx,dy,dz);
                        if(client.level.getBlockState(pos).is(WorksiteContent.BUILDER_TABLE))
                            return pos;
                    }
        return null;
    }
    public static void sendSelected()throws Exception{
        sendSelected(null);
    }
    public static void sendSelected(BlockPos selectedTable)throws Exception{
        Object placement=invoke(manager(),"getSelectedSchematicPlacement");
        if(placement==null)throw new IllegalArgumentException(
                "Choose a placement from the Builder Planner's placement list");
        sendPlacementObject(placement,selectedTable);
    }
    public static void sendPlacement(PlacementChoice choice,BlockPos table)throws Exception{
        if(choice==null)throw new IllegalArgumentException("Choose a placement");
        sendPlacementObject(choice.placement(),table);
    }
    private static void sendPlacementObject(Object placement,BlockPos selectedTable)
            throws Exception{
        if(!optionalBoolean(placement,"isEnabled",true))
            throw new IllegalArgumentException("Enable this placement in Litematica first");
        // Litematica versions expose getSchematic(), but not isSchematicLoaded().
        // A null loaded schematic means the placement cannot be imported safely.
        Object schematic=optional(placement,"getSchematic");
        if(schematic==null)
            throw new IllegalArgumentException("Load this schematic in Litematica first");
        if(Boolean.TRUE.equals(invoke(placement,"isRegionPlacementModified")))
            throw new IllegalArgumentException(
                    "Modified subregion positions/rotations are not yet supported");
        Object all=invoke(placement,"getAllSubRegions");
        Object enabled=invoke(placement,"getEnabledSubRegions");
        if(all instanceof java.util.Collection<?> a && enabled instanceof java.util.Collection<?> e
                && a.size()!=e.size())
            throw new IllegalArgumentException("Enable every subregion before importing");
        Object pathObj=firstAvailable(placement,"getSchematicFile","getFile");
        if(pathObj instanceof Optional<?> opt)pathObj=opt.orElse(null);
        if(pathObj==null)throw new IllegalArgumentException(
                "Save this in-memory schematic as a .litematic file first");
        Path path=pathObj instanceof Path p?p:Path.of(pathObj.toString());
        if(!path.getFileName().toString().endsWith(".litematic"))
            throw new IllegalArgumentException("Only .litematic file-backed placements");
        long fileSize=Files.size(path);
        if(fileSize<1||fileSize>BuilderUploadNetworking.MAX_BYTES)
            throw new IllegalArgumentException("This placement exceeds the 2 MiB upload limit");
        BlockPos origin=asPos(firstAvailable(placement,"getOrigin","getPosition"));
        if(origin==null)
            throw new IllegalArgumentException("Could not read Litematica origin");
        String rotation=invoke(placement,"getRotation").toString().toUpperCase();
        String mirror=invoke(placement,"getMirror").toString().toUpperCase();
        int rotationCode=switch(rotation){
            case "NONE"->0;case "CLOCKWISE_90"->1;
            case "CLOCKWISE_180"->2;case "COUNTERCLOCKWISE_90"->3;
            default->throw new IllegalArgumentException("Unknown rotation "+rotation);
        };
        int mirrorCode=switch(mirror){
            case "NONE"->0;case "FRONT_BACK"->1;case "LEFT_RIGHT"->2;
            default->throw new IllegalArgumentException("Unknown mirror "+mirror);
        };
        BlockPos table=selectedTable==null?nearestTable():selectedTable;
        if(table==null)throw new IllegalArgumentException(
                "Place and claim a Builder's Planning Table within eight blocks");
        if(!ClientPlayNetworking.canSend(BuilderUploadNetworking.Piece.TYPE))
            throw new IllegalArgumentException("Golems Plus server does not support imports");
        byte[] data=Files.readAllBytes(path);
        int parts=(data.length+BuilderUploadNetworking.PART_SIZE-1)
                /BuilderUploadNetworking.PART_SIZE;
        UUID upload=UUID.randomUUID();
        String label=path.getFileName().toString();
        if(label.length()>80)label=label.substring(0,80);
        label=label.replaceAll("[^a-zA-Z0-9_.-]","_");
        for(int i=0;i<parts;i++){
            int start=i*BuilderUploadNetworking.PART_SIZE;
            int end=Math.min(start+BuilderUploadNetworking.PART_SIZE,data.length);
            byte[] chunk=java.util.Arrays.copyOfRange(data,start,end);
            ClientPlayNetworking.send(new BuilderUploadNetworking.Piece(
                    table,upload,i,parts,data.length,origin,rotationCode,mirrorCode,
                    label,chunk));
        }
    }
}
