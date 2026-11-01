package com.caszgamermd.golemsplus.builder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Immutable coordinates of the exact Litematica hologram at IMPORT time.
 * Not a raw schematic offset. Every Block is already in absolute world
 * coordinates, including fully transformed subregions and air cells.
 *
 * Only block states are captured; no block entities or entity NBT.
 * The format has strict bounds before any allocations to protect servers.
 */
public final class BuilderPlacementSnapshot {
    public static final String EXTENSION=".gpb";
    private static final int MAGIC=0x47504231; // GPB1
    private static final int MAX_NON_AIR=100_000;
    private static final int MAX_AIR=300_000;
    private static final int MAX_PALETTE=65_536;
    private BuilderPlacementSnapshot(){}

    public static boolean isSnapshot(Path file){
        return file.getFileName().toString().endsWith(EXTENSION);
    }

    public static byte[] encode(String name,
            List<LitematicFileReader.Block> solids,
            List<LitematicFileReader.Block> air)throws IOException {
        if(solids==null||air==null||solids.isEmpty()
                ||solids.size()>MAX_NON_AIR||air.size()>MAX_AIR)
            throw new IOException("Invalid hologram size");
        var palette=new LinkedHashMap<String,Integer>();
        for(var block:solids)
            palette.computeIfAbsent(block.state(),x->palette.size());
        if(palette.size()>MAX_PALETTE)throw new IOException("Too many block states");
        var bytes=new ByteArrayOutputStream();
        try(var zipped=new GZIPOutputStream(bytes);
            var out=new DataOutputStream(zipped)){
            out.writeInt(MAGIC);
            out.writeUTF(name==null?"Placed hologram":name.substring(0,
                    Math.min(name.length(),80)));
            out.writeInt(palette.size());
            for(var state:palette.keySet())out.writeUTF(state);
            out.writeInt(solids.size());
            for(var block:solids){
                out.writeInt(block.x());out.writeInt(block.y());
                out.writeInt(block.z());out.writeInt(palette.get(block.state()));
            }
            out.writeInt(air.size());
            for(var block:air){
                out.writeInt(block.x());out.writeInt(block.y());out.writeInt(block.z());
            }
        }
        return bytes.toByteArray();
    }

    public static LitematicFileReader.Schematic read(Path file)throws IOException{
        if(!Files.isRegularFile(file)||Files.size(file)>BuilderUploadNetworking.MAX_BYTES)
            throw new IOException("Invalid saved hologram snapshot");
        return decode(Files.readAllBytes(file));
    }
    public static LitematicFileReader.Schematic decode(byte[] bytes)throws IOException {
        if(bytes==null||bytes.length<8
                ||bytes.length>BuilderUploadNetworking.MAX_BYTES)
            throw new IOException("Invalid hologram snapshot size");
        try(var in=new DataInputStream(new GZIPInputStream(
                new ByteArrayInputStream(bytes)))){
            if(in.readInt()!=MAGIC)throw new IOException("Unknown hologram format");
            String name=in.readUTF();
            int count=in.readInt();
            if(count<1||count>MAX_PALETTE)throw new IOException("Invalid palette size");
            var palette=new ArrayList<String>(count);
            for(int i=0;i<count;i++){
                String id=in.readUTF();
                if(id.isBlank())throw new IOException("Empty block state");
                palette.add(id);
            }
            int solidCount=in.readInt();
            if(solidCount<1||solidCount>MAX_NON_AIR)
                throw new IOException("Invalid solid block count");
            var blocks=new ArrayList<LitematicFileReader.Block>(solidCount);
            var totals=new LinkedHashMap<String,Long>();
            var seen=new java.util.HashSet<Long>();
            for(int i=0;i<solidCount;i++){
                int x=in.readInt(),y=in.readInt(),z=in.readInt(),index=in.readInt();
                if(index<0||index>=palette.size()||y< -64||y>511)
                    throw new IOException("Invalid world position/state");
                // Mutable block coordinates need full 3D uniqueness.
                var p=new net.minecraft.core.BlockPos(x,y,z).asLong();
                if(!seen.add(p))throw new IOException("Overlapping hologram blocks");
                String state=palette.get(index);
                blocks.add(new LitematicFileReader.Block(x,y,z,state));
                totals.merge(state,1L,Long::sum);
            }
            int airCount=in.readInt();
            if(airCount<0||airCount>MAX_AIR)
                throw new IOException("Invalid air volume");
            var air=new ArrayList<LitematicFileReader.Block>(airCount);
            for(int i=0;i<airCount;i++){
                int x=in.readInt(),y=in.readInt(),z=in.readInt();
                if(y< -64||y>511)throw new IOException("Invalid air position");
                if(!seen.add(new net.minecraft.core.BlockPos(x,y,z).asLong()))
                    throw new IOException("Overlapping hologram air");
                air.add(new LitematicFileReader.Block(x,y,z,"minecraft:air"));
            }
            if(in.read()!=-1)throw new IOException("Unexpected snapshot trailing data");
            return new LitematicFileReader.Schematic(name,blocks,totals,air);
        }catch(IllegalArgumentException e){
            throw new IOException("Invalid hologram contents",e);
        }
    }

    /** World-based plans are already final. Never transform them twice. */
    public static BuildSite worldSpaceSite(){
        return new BuildSite(0,0,0,BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
    }
}
