package com.caszgamermd.golemsplus.builder;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;

/**
 * Defensive read-only Litematica .litematic import. Reads the standard gzipped
 * named NBT root, supports multiple palette regions, signed extents and modded
 * block-state properties. Never copies block-entity or entity NBT into worlds.
 */
public final class LitematicFileReader {
    private static final int MAX_FILE_BYTES=16*1024*1024;
    private static final int MAX_UNZIPPED=48*1024*1024;
    private static final int MAX_TAG_ELEMENTS=5_000_000;
    private static final int MAX_BLOCKS=16_777_216;
    public record Block(int x,int y,int z,String state) {}
    public record Schematic(String name,List<Block> blocks,Map<String,Long> stateCounts) {
        public Schematic {
            blocks=List.copyOf(blocks);
            stateCounts=Map.copyOf(stateCounts);
        }
    }
    private LitematicFileReader(){}

    public static Schematic read(Path path) throws IOException {
        if(!Files.isRegularFile(path)||Files.size(path)>MAX_FILE_BYTES)
            throw new IOException("Missing or oversized schematic");
        byte[] bytes;
        try(var raw=Files.newInputStream(path);
            var gzip=new GZIPInputStream(raw)){
            bytes=gzip.readNBytes(MAX_UNZIPPED+1);
        }
        if(bytes.length>MAX_UNZIPPED)throw new IOException("Schematic exceeds decoded size limit");
        try(var in=new DataInputStream(new ByteArrayInputStream(bytes))){
            int type=in.readUnsignedByte();
            if(type!=10)throw new IOException("Expected compound NBT root");
            in.readUTF();
            var root=compound(in,0);
            Map<String,Object> regions=asCompound(root.get("Regions"),"Regions");
            if(regions.isEmpty()||regions.size()>256)throw new IOException("Invalid region count");
            List<Block> blocks=new ArrayList<>();
            Map<String,Long> counts=new LinkedHashMap<>();
            for(var entry:regions.entrySet()){
                var region=asCompound(entry.getValue(),"Region");
                var position=asCompound(region.get("Position"),"Position");
                var size=asCompound(region.get("Size"),"Size");
                int px=integer(position,"x"),py=integer(position,"y"),pz=integer(position,"z");
                int sx=integer(size,"x"),sy=integer(size,"y"),sz=integer(size,"z");
                long total=(long)Math.abs((long)sx)*Math.abs((long)sy)*Math.abs((long)sz);
                if(total<1||total>MAX_BLOCKS||total+blocks.size()>MAX_BLOCKS)
                    throw new IOException("Invalid region volume");
                int width=(int)Math.abs((long)sx);
                int height=(int)Math.abs((long)sy);
                int depth=(int)Math.abs((long)sz);
                if(!(region.get("BlockStatePalette") instanceof List<?> paletteRaw)
                        ||paletteRaw.isEmpty()||paletteRaw.size()>65_536)
                    throw new IOException("Missing block palette");
                List<String> palette=new ArrayList<>();
                for(Object paletteEntry:paletteRaw){
                    Map<String,Object> tag=asCompound(paletteEntry,"BlockStatePalette entry");
                    String name=string(tag,"Name");
                    if(name.isBlank())throw new IOException("Blank block state ID");
                    Map<String,Object> properties=tag.containsKey("Properties")
                            ?asCompound(tag.get("Properties"),"Properties"):Map.of();
                    TreeMap<String,String> sorted=new TreeMap<>();
                    for(var prop:properties.entrySet()){
                        if(!(prop.getValue() instanceof String v))
                            throw new IOException("Invalid block property");
                        sorted.put(prop.getKey(),v);
                    }
                    String state=name;
                    if(!sorted.isEmpty()){
                        List<String> pieces=new ArrayList<>();
                        sorted.forEach((k,v)->pieces.add(k+"="+v));
                        state+="["+String.join(",",pieces)+"]";
                    }
                    palette.add(state);
                }
                if(!(region.get("BlockStates") instanceof long[] packed))
                    throw new IOException("Missing packed state array");
                int[] indexes;
                try{
                    indexes=LitematicPaletteDecoder.decode(packed,palette.size(),(int)total);
                }catch(IllegalArgumentException error){
                    throw new IOException("Invalid palette data for region "+entry.getKey(),error);
                }
                for(int i=0;i<indexes.length;i++){
                    String state=palette.get(indexes[i]);
                    String plain=state.split("\\[",2)[0];
                    if(Set.of("minecraft:air","minecraft:cave_air","minecraft:void_air").contains(plain))
                        continue;
                    int x=i%width,z=(i/width)%depth,y=i/(width*depth);
                    long bx=(long)px+(sx<0?-x:x);
                    long by=(long)py+(sy<0?-y:y);
                    long bz=(long)pz+(sz<0?-z:z);
                    if(bx<Integer.MIN_VALUE||bx>Integer.MAX_VALUE||
                            by<Integer.MIN_VALUE||by>Integer.MAX_VALUE||
                            bz<Integer.MIN_VALUE||bz>Integer.MAX_VALUE)
                        throw new IOException("Block coordinate overflow");
                    blocks.add(new Block((int)bx,(int)by,(int)bz,state));
                    counts.merge(state,1L,Long::sum);
                }
            }
            String title="Untitled";
            if(root.get("Metadata") instanceof Map<?,?> metadata
                    &&metadata.get("Name") instanceof String name &&!name.isBlank())
                title=name;
            return new Schematic(title,blocks,counts);
        }catch(IllegalArgumentException exception){
            throw new IOException("Malformed Litematica schematic",exception);
        }
    }

    private static Map<String,Object> compound(DataInputStream in,int depth)throws IOException{
        if(depth>32)throw new IOException("NBT depth limit exceeded");
        Map<String,Object> fields=new LinkedHashMap<>();
        for(int i=0;i<MAX_TAG_ELEMENTS;i++){
            int type=in.readUnsignedByte();
            if(type==0)return fields;
            String key=in.readUTF();
            fields.put(key,tag(in,type,depth+1));
        }
        throw new IOException("NBT compound too large");
    }
    private static Object tag(DataInputStream in,int type,int depth)throws IOException{
        if(depth>32)throw new IOException("NBT depth limit exceeded");
        return switch(type){
            case 1->in.readByte();
            case 2->in.readShort();
            case 3->in.readInt();
            case 4->in.readLong();
            case 5->in.readFloat();
            case 6->in.readDouble();
            case 7->in.readNBytes(length(in)); // length is already bounded
            case 8->in.readUTF();
            case 9->{
                int childType=in.readUnsignedByte(),n=length(in);
                if(childType==0&&n!=0)throw new IOException("Invalid list tag");
                List<Object> list=new ArrayList<>(n);
                for(int i=0;i<n;i++)list.add(tag(in,childType,depth+1));
                yield list;
            }
            case 10->compound(in,depth+1);
            case 11->{
                int n=length(in);
                int[] items=new int[n];
                for(int i=0;i<n;i++)items[i]=in.readInt();
                yield items;
            }
            case 12->{
                int n=length(in);
                long[] items=new long[n];
                for(int i=0;i<n;i++)items[i]=in.readLong();
                yield items;
            }
            default->throw new IOException("Unsupported NBT tag "+type);
        };
    }
    private static int length(DataInputStream in)throws IOException{
        int n=in.readInt();
        if(n<0||n>MAX_TAG_ELEMENTS)throw new IOException("NBT array exceeds safe length");
        return n;
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> asCompound(Object value,String description)throws IOException{
        if(!(value instanceof Map<?,?> map))
            throw new IOException("Missing or invalid "+description);
        for(var key:map.keySet())if(!(key instanceof String))
            throw new IOException("Non-string NBT key");
        return (Map<String,Object>)map;
    }
    private static int integer(Map<String,Object> map,String key)throws IOException{
        if(!(map.get(key) instanceof Number number))
            throw new IOException("Missing coordinate "+key);
        return number.intValue();
    }
    private static String string(Map<String,Object> map,String key)throws IOException{
        if(!(map.get(key) instanceof String s))throw new IOException("Missing "+key);
        return s;
    }
}
