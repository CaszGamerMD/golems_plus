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
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
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
    /** Resolve the ghost blocks from Litematica's own already-transformed
     * schematic world. Never infer positions from raw signed Region.Size,
     * or apply placement rotation/mirror on the server a second time.
     */
    private static BlockGetter hologramWorld()throws Exception{
        for(String name:new String[]{
                "fi.dy.masa.litematica.world.SchematicWorldHandler",
                "litematica.world.SchematicWorldHandler"}){
            try{
                Object world=Class.forName(name)
                        .getMethod("getSchematicWorld").invoke(null);
                if(world instanceof BlockGetter getter)return getter;
            }catch(ClassNotFoundException ignored){}
        }
        throw new IllegalArgumentException(
                "Litematica's schematic world is unavailable; load the hologram first");
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    private static String propertyValue(Property property,Comparable value){
        return property.getName(value);
    }
    private static String encodeState(BlockState state){
        String id=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        if(state.getValues().isEmpty())return id;
        var values=new java.util.TreeMap<String,String>();
        state.getValues().forEach((property,value)->
                values.put(property.getName(),propertyValue(property,value)));
        return id+"["+String.join(",",values.entrySet().stream()
                .map(e->e.getKey()+"="+e.getValue()).toList())+"]";
    }
    private static byte[] captureVisibleHologram(Object placement,Path original)
            throws Exception{
        Object box=optional(placement,"getEnclosingBox");
        if(box==null)throw new IllegalArgumentException(
                "No Litematica placement bounding box: enable all subregions");
        // Modern Litematica returns malilib IntBoundingBox with public
        // minX/minY/minZ/maxX/maxY/maxZ fields, not a SelectionBox.
        // Older implementations expose getPos1/getPos2 instead.
        BlockPos a,b;
        try{
            Class<?> type=box.getClass();
            a=new BlockPos(type.getField("minX").getInt(box),
                    type.getField("minY").getInt(box),
                    type.getField("minZ").getInt(box));
            b=new BlockPos(type.getField("maxX").getInt(box),
                    type.getField("maxY").getInt(box),
                    type.getField("maxZ").getInt(box));
        }catch(NoSuchFieldException exception){
            a=asPos(firstAvailable(box,"getPos1","getCorner1"));
            b=asPos(firstAvailable(box,"getPos2","getCorner2"));
        }
        if(a==null||b==null)
            throw new IllegalArgumentException("Cannot read hologram boundaries");
        int minX=Math.min(a.getX(),b.getX()),maxX=Math.max(a.getX(),b.getX());
        int minY=Math.min(a.getY(),b.getY()),maxY=Math.max(a.getY(),b.getY());
        int minZ=Math.min(a.getZ(),b.getZ()),maxZ=Math.max(a.getZ(),b.getZ());
        long volume=((long)maxX-minX+1)*((long)maxY-minY+1)
                *((long)maxZ-minZ+1);
        if(volume<1||volume>400_000)
            throw new IllegalArgumentException(
                    "Hologram bounding volume "+volume+" is too large for import");
        if(minY< -64||maxY>511)
            throw new IllegalArgumentException("Hologram outside build height");
        // Never read the actual game world. The selected ghost is the plan.
        BlockGetter ghost=hologramWorld();
        var solids=new ArrayList<LitematicFileReader.Block>();
        var air=new ArrayList<LitematicFileReader.Block>();
        for(int y=minY;y<=maxY;y++)
            for(int z=minZ;z<=maxZ;z++)
                for(int x=minX;x<=maxX;x++){
                    BlockState state=ghost.getBlockState(new BlockPos(x,y,z));
                    if(state.isAir())air.add(new LitematicFileReader.Block(
                            x,y,z,"minecraft:air"));
                    else solids.add(new LitematicFileReader.Block(
                            x,y,z,encodeState(state)));
                }
        // A missing/unpopulated ghost chunk must not silently become AIR.
        // Also catch overlapping other visible schematic placements.
        int expected=LitematicFileReader.read(original).blocks().size();
        if(solids.size()!=expected)
            throw new IllegalArgumentException(
                    "Hologram is not fully resolved ("+solids.size()+"/"+
                    expected+" solid blocks). Move near it, let Litematica "+
                    "load it, and ensure no other hologram overlaps");
        return BuilderPlacementSnapshot.encode(
                String.valueOf(firstAvailable(placement,"getName")),solids,air);
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
        // Subregion offsets and rotations are supported: Litematica has
        // already applied them when producing its visible schematic world.
        // Litematica 26.2 renamed both accessors. The old calls here
        // threw NoSuchMethodException when clicking *any* listed placement.
        // getAllSubRegionsPlacements() returns Collection<SubRegionPlacement>
        // and getEnabledRelativeSubRegionPlacements() returns Map<String,SubRegionPlacement>.
        Object all=firstAvailable(placement,
                "getAllSubRegionsPlacements","getAllSubRegions");
        Object enabled=firstAvailable(placement,
                "getEnabledRelativeSubRegionPlacements","getEnabledSubRegions");
        if(all==null||enabled==null)
            throw new IllegalArgumentException(
                    "Litematica's subregion API is unavailable; expected "+
                    "getAllSubRegionsPlacements() and "+
                    "getEnabledRelativeSubRegionPlacements()");
        int allCount=all instanceof java.util.Collection<?> values?values.size()
                :all instanceof java.util.Map<?,?> values?values.size():-1;
        int enabledCount=enabled instanceof java.util.Collection<?> values?values.size()
                :enabled instanceof java.util.Map<?,?> values?values.size():-1;
        if(allCount<0||enabledCount<0)
            throw new IllegalArgumentException("Unknown Litematica subregion collection type");
        if(allCount==0)
            throw new IllegalArgumentException("This placement contains no subregions");
        if(allCount!=enabledCount)
            throw new IllegalArgumentException("Enable all "+allCount+
                    " subregions in Litematica before importing ("+enabledCount+" enabled)");
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
        // Freeze the exact world-position hologram ONCE, at this click.
        // Subsequent movement of the Litematica placement cannot shift
        // the committed Builder job's target coordinates.
        byte[] data=captureVisibleHologram(placement,path);
        if(data.length>BuilderUploadNetworking.MAX_BYTES)
            throw new IllegalArgumentException(
                    "Placed hologram snapshot exceeds the 2 MiB upload limit");
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
                    label,true,chunk));
        }
    }
}
