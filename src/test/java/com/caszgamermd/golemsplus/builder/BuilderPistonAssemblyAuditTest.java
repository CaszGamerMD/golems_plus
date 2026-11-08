package com.caszgamermd.golemsplus.builder;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BuilderPistonAssemblyAuditTest {
    private static LitematicFileReader.Block block(int x,int y,int z,String state) {
        return new LitematicFileReader.Block(x,y,z,state);
    }
    @Test void distinguishesRealForwardHeadsFromImpossibleReverseSnapshots() {
        var schematic=new LitematicFileReader.Schematic("pistons",List.of(
            block(0,0,0,"minecraft:piston[extended=true,facing=east]"),
            block(1,0,0,"minecraft:piston_head[facing=east,short=false,type=normal]"),
            block(4,0,0,"minecraft:sticky_piston[extended=true,facing=north]"),
            block(4,0,1,"minecraft:piston_head[facing=north,short=false,type=sticky]"),
            block(7,0,0,"minecraft:piston[extended=true,facing=up]"),
            block(7,1,0,"minecraft:stone")),Map.of());
        var result=BuilderPistonAssemblyAudit.inspect(schematic);
        assertEquals(3,result.extended());
        assertEquals(1,result.validForward());
        assertEquals(1,result.reversed());
        assertEquals(1,result.unmatched());
        assertEquals(2,result.totalHeads());
        assertFalse(result.physicallyConsistent());
        assertTrue(result.examples().stream().anyMatch(e->e.contains("reverse head")));
    }
    @Test void verifiesMatchingStickyHeadInActualExtensionDirection() {
        var schematic=new LitematicFileReader.Schematic("safe",List.of(
            block(8,3,2,"minecraft:sticky_piston[extended=true,facing=down]"),
            block(8,2,2,"minecraft:piston_head[facing=down,short=false,type=sticky]")),
            Map.of());
        var result=BuilderPistonAssemblyAudit.inspect(schematic);
        assertEquals(1,result.extended());
        assertEquals(1,result.validForward());
        assertTrue(result.physicallyConsistent());
    }
}
