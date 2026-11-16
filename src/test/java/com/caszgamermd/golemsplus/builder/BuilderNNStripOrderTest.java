package com.caszgamermd.golemsplus.builder;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class BuilderNNStripOrderTest {
    private BuilderConstructionPlan.Step s(int x,int y,int z,String state,String item){
        return new BuilderConstructionPlan.Step(x,y,z,state,item);
    }
    @Test void dryStripsBeforeWaterAndVerification(){
        var steps=List.of(s(0,0,0,"minecraft:tuff_bricks","minecraft:tuff_bricks"),
                s(1,0,1,"minecraft:tuff_bricks","minecraft:tuff_bricks"),
                s(0,1,0,"minecraft:tuff_bricks","minecraft:tuff_bricks"),
                s(0,0,1,"minecraft:water[level=0]",BuilderWaterPass.WATER_BUCKET),
                s(0,0,2,"golems_plus:flow_verify|minecraft:water[level=1]",null));
        assertEquals(List.of(0,2,1,3,4),BuilderNNStripOrder.compile(steps,"X",true));
        assertEquals(List.of(0,2,1,3,4),BuilderNNStripOrder.compile(steps,"Z",true));
        assertFalse(BuilderNNStripOrder.sameGroup(steps.get(0),steps.get(3),"X"));
        assertFalse(BuilderNNStripOrder.sameGroup(steps.get(3),steps.get(4),"X"));
        assertThrows(IllegalArgumentException.class,()->BuilderNNStripOrder.compile(steps,"X",false));
    }
    @Test void axesDifferAndUnsupportedBlocksFailClosed(){
        var steps=List.of(s(0,0,2,"minecraft:tuff_bricks","minecraft:tuff_bricks"),
                s(2,0,0,"minecraft:tuff_bricks","minecraft:tuff_bricks"));
        assertEquals(List.of(0,1),BuilderNNStripOrder.compile(steps,"X",true));
        assertEquals(List.of(1,0),BuilderNNStripOrder.compile(steps,"Z",true));
        assertThrows(IllegalArgumentException.class,()->BuilderNNStripOrder.compile(
                List.of(s(0,0,0,"minecraft:sand","minecraft:sand")),"X",true));
    }
}
