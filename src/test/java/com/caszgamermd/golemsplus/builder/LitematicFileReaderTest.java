package com.caszgamermd.golemsplus.builder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class LitematicFileReaderTest {
    @TempDir Path directory;
    private static void intTag(DataOutputStream out,String name,int n)throws IOException{
        out.writeByte(3);out.writeUTF(name);out.writeInt(n);
    }
    private static void compound(DataOutputStream out,String name)throws IOException{
        out.writeByte(10);out.writeUTF(name);
    }
    private static void coords(DataOutputStream out,String name,int x,int y,int z)
            throws IOException{
        compound(out,name);intTag(out,"x",x);intTag(out,"y",y);intTag(out,"z",z);
        out.writeByte(0);
    }
    private static void region(DataOutputStream out,String name,int posX,int sizeX,
            String block)throws IOException{
        compound(out,name);
        coords(out,"Position",posX,0,0);
        coords(out,"Size",sizeX,1,1);
        out.writeByte(9);out.writeUTF("BlockStatePalette");
        out.writeByte(10);out.writeInt(1);
        out.writeByte(8);out.writeUTF("Name");out.writeUTF(block);
        out.writeByte(0); // one palette entry
        out.writeByte(12);out.writeUTF("BlockStates");
        out.writeInt(1);out.writeLong(0);
        out.writeByte(0); // region compound
    }
    private Path write(boolean invalid)throws IOException{
        Path file=directory.resolve("test.litematic");
        try(var gzip=new GZIPOutputStream(Files.newOutputStream(file));
                var out=new DataOutputStream(gzip)){
            out.writeByte(10);out.writeUTF("");
            compound(out,"Metadata");
            out.writeByte(8);out.writeUTF("Name");out.writeUTF("Sample");
            out.writeByte(0);
            compound(out,"Regions");
            region(out,"first",0,2,"minecraft:stone");
            if(!invalid)region(out,"negative",10,-2,"minecraft:oak_planks");
            out.writeByte(0);
            out.writeByte(0);
        }
        return file;
    }
    @Test void decodesMultiRegionAndNegativeDirections()throws IOException{
        var schem=LitematicFileReader.read(write(false));
        assertEquals("Sample",schem.name());
        assertEquals(4,schem.blocks().size());
        assertEquals(2,schem.stateCounts().get("minecraft:stone"));
        assertEquals(10,schem.blocks().get(2).x());
        assertEquals(9,schem.blocks().get(3).x());
        var site=new BuildSite(100,64,200,BuildSite.Rotation.NONE,BuildSite.Mirror.NONE);
        var plan=BuilderSchematicAnalysis.analyze(schem,site,
                id->id.equals("minecraft:stone")?"minecraft:stone":null);
        assertEquals(2,plan.materials().get("minecraft:stone"));
        assertFalse(plan.ready());
        assertEquals(110,plan.positions().get(2).x());
    }
    @Test void failsOnWrongHeader()throws IOException {
        var file=directory.resolve("wrong.litematic");
        Files.writeString(file,"this is not a schematic");
        assertThrows(IOException.class,()->LitematicFileReader.read(file));
    }
}
