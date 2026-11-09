package com.caszgamermd.golemsplus.builder;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuilderRailAssemblyAuditTest {
    private static LitematicFileReader.Block b(int x,int y,int z,String s){
        return new LitematicFileReader.Block(x,y,z,s);
    }
    private static final String SLOPE=
            "minecraft:powered_rail[powered=true,shape=ascending_west,waterlogged=true]";
    @Test void conflictingFinalFlatRailCannotSupportRaisedIncline(){
        var schematic=new LitematicFileReader.Schematic("matrix conflict",List.of(
            b(15,1,24,SLOPE),
            b(14,1,24,"minecraft:powered_rail[powered=true,shape=east_west]"),
            b(14,2,24,"minecraft:iron_trapdoor[facing=east,half=top,open=false]")),
            Map.of());
        var report=BuilderRailAssemblyAudit.inspect(schematic);
        assertEquals(1,report.ascending());
        assertEquals(1,report.conflictingRaisedEndpoints());
        assertEquals(1,report.upperNonRail());
        assertEquals(1,report.railFootings());
        assertTrue(report.hasConflicts());
        assertTrue(report.examples().getFirst().contains("raisedEnd="));
    }
    @Test void temporaryJigAbovePermanentStoneMayBeAValidSequence(){
        var schematic=new LitematicFileReader.Schematic("jig",List.of(
            b(15,1,24,SLOPE),
            b(14,1,24,"minecraft:stone")),Map.of());
        var result=BuilderRailAssemblyAudit.inspect(schematic);
        assertEquals(1,result.ascending());
        assertFalse(result.hasConflicts());
        assertEquals(0,result.railFootings());
    }
    @Test void permanentRaisedRailIsNotFlaggedAsMissing(){
        var schematic=new LitematicFileReader.Schematic("continuity",List.of(
            b(15,1,24,SLOPE),
            b(14,1,24,"minecraft:stone"),
            b(14,2,24,"minecraft:powered_rail[powered=true,shape=east_west]")),
            Map.of());
        var result=BuilderRailAssemblyAudit.inspect(schematic);
        assertEquals(1,result.ascending());
        assertEquals(0,result.conflictingRaisedEndpoints());
        assertEquals(0,result.upperNonRail());
    }
}
